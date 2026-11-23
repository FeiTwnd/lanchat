package com.chat.test;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.Result;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.common.UserListCodec;
import com.chat.dao.JdbcMessageDao;
import com.chat.service.MessageService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 覆盖消息摘要、入库查询、关键字检索、导出与历史记录恢复。
 */
public class MessageServiceTest {
    private JdbcMessageDao messageDao;

    /** 重置消息表并构造消息服务。 */
    private MessageService createService() {
        messageDao = TestDatabase.messageDao();
        return new MessageService(messageDao);
    }

    /** 验证工厂创建各类消息，摘要按类型正确返回。 */
    @Test("消息模型：工厂创建各类型消息，摘要多态正确")
    public void testMessageFactoryAndPolymorphism() {
        TextMessage text = ChatMessageFactory.text("alice", "bob", "你好", MessageType.TEXT_PRIVATE);
        TestRunner.assertEquals(MessageType.TEXT_PRIVATE, text.getType(), "类型应为私聊");
        TestRunner.assertEquals("你好", text.getSummary(), "文本摘要应为正文");
        TestRunner.assertTrue(text.getId() > 0, "消息编号应被分配");

        TextMessage longText = ChatMessageFactory.text("alice", "bob",
                "0123456789012345678901234567890123456789", MessageType.TEXT_GROUP);
        TestRunner.assertTrue(longText.getSummary().endsWith("..."), "超长正文摘要应截断");

        SystemMessage system = ChatMessageFactory.system("", "alice 上线了");
        TestRunner.assertEquals(Constants.SYSTEM_SENDER, system.getSender(), "系统消息发送者应固定");
        TestRunner.assertEquals("alice 上线了", system.getSummary(), "系统消息摘要应为通知内容");
        TestRunner.assertTrue(system.isBroadcast(), "接收者为空应为广播消息");

        FileMessage file = ChatMessageFactory.file("alice", "bob", MessageType.FILE_REQUEST);
        file.setFileName("报告.pdf");
        file.setFileSize(1024L * 1024);
        TestRunner.assertTrue(file.getSummary().contains("报告.pdf"), "文件摘要应含文件名");
        TestRunner.assertTrue(file.getSummary().contains("MB"), "文件摘要应含可读大小");

        Message[] messages = {text, system, file};
        for (Message message : messages) {
            TestRunner.assertNotNull(message.getSummary(), "任意消息都应能给出摘要");
        }
    }

    /** 验证后创建的消息编号更大。 */
    @Test("消息模型：编号全局递增且不重复")
    public void testMessageIdSequence() {
        TextMessage first = ChatMessageFactory.text("a", "b", "1", MessageType.TEXT_PRIVATE);
        TextMessage second = ChatMessageFactory.text("a", "b", "2", MessageType.TEXT_PRIVATE);
        TestRunner.assertTrue(second.getId() > first.getId(), "后创建的消息编号应更大");
    }

    /** 验证消息保存后可按用户查询，私聊内容不对无关用户可见。 */
    @Test("消息服务：保存后可按用户查询到记录")
    public void testSaveAndQuery() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        MessageService service = createService();
        TestRunner.assertTrue(service.saveMessage(ChatMessageFactory.text("alice", "bob",
                "第一条私聊", MessageType.TEXT_PRIVATE)).isSuccess(), "保存应成功");
        TestRunner.assertTrue(service.saveMessage(ChatMessageFactory.text("carol", "",
                "群聊消息", MessageType.TEXT_GROUP)).isSuccess(), "保存群聊应成功");
        TestRunner.assertEquals(2L, service.count(), "测试库中应写入 2 条记录");

        Result<List<Message>> result = service.queryHistory("alice", (LocalDateTime) null, null);
        TestRunner.assertTrue(result.isSuccess(), "查询应成功");
        TestRunner.assertTrue(result.getData().size() >= 2, "alice 应能查询到私聊与群聊记录");

        Result<List<Message>> none = service.queryHistory("nobody", (LocalDateTime) null, null);
        TestRunner.assertEquals(1, none.getData().size(),
                "无关用户只能看到广播性质的群聊记录，看不到他人私聊");
        TestRunner.assertEquals(MessageType.TEXT_GROUP, none.getData().get(0).getType(),
                "无关用户可见的记录应为群聊消息");
    }

    /** 验证日期范围过滤生效，起止日期倒置时返回失败。 */
    @Test("消息服务：日期范围过滤正确，起止时间倒置时报错")
    public void testQueryByDateRange() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        MessageService service = createService();
        service.saveMessage(ChatMessageFactory.text("alice", "bob", "今天", MessageType.TEXT_PRIVATE));
        LocalDate today = LocalDate.now();
        TestRunner.assertEquals(1,
                service.queryHistory("alice", today, today).getData().size(),
                "当天范围应命中记录");
        TestRunner.assertEquals(0,
                service.queryHistory("alice", today.plusDays(1), today.plusDays(2)).getData().size(),
                "未来范围不应命中记录");
        TestRunner.assertFalse(service.queryHistory("alice", today, today.minusDays(3)).isSuccess(),
                "起始日期晚于结束日期应返回失败");
    }

    /** 验证关键字检索命中，空关键字与缺少请求者被拒绝。 */
    @Test("消息服务：关键字检索命中与空关键字校验")
    public void testSearch() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        MessageService service = createService();
        service.saveMessage(ChatMessageFactory.text("alice", "bob",
                "项目需要写测试说明", MessageType.TEXT_PRIVATE));
        service.saveMessage(ChatMessageFactory.text("alice", "bob",
                "今天天气不错", MessageType.TEXT_PRIVATE));
        TestRunner.assertEquals(1, service.search("alice", "测试说明").getData().size(),
                "应命中包含关键字的记录");
        TestRunner.assertEquals(0, service.search("alice", "不存在的关键字").getData().size(),
                "不应命中无关记录");
        TestRunner.assertFalse(service.search("alice", "").isSuccess(), "空关键字应返回失败");

        TestRunner.assertEquals(0, service.search("carol", "测试报告").getData().size(),
                "无关用户不应对他人私聊的命中结果可见");
        TestRunner.assertFalse(service.search("", "测试报告").isSuccess(),
                "缺少请求者时应拒绝而不是退化成全库检索");
    }

    /** 验证导出消息生成包含正文的文本文件。 */
    @Test("消息服务：导出记录生成文本文件且内容可读")
    public void testExport() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        MessageService service = createService();

        Path dir = TestRunner.createTempDir("msgsvc-export");
        try {
            service.saveMessage(ChatMessageFactory.text("alice", "bob", "导出测试内容",
                    MessageType.TEXT_PRIVATE));
            Result<List<Message>> query = service.queryHistory("alice", (LocalDateTime) null, null);
            File target = dir.resolve("export.txt").toFile();
            Result<File> exported = service.exportMessages(query.getData(), target);
            TestRunner.assertTrue(exported.isSuccess(), "导出应成功: " + exported.getMessage());
            TestRunner.assertTrue(target.isFile(), "导出文件应存在");
            String content = Files.readString(target.toPath(), Constants.CHARSET);
            TestRunner.assertTrue(content.contains("导出测试内容"), "导出内容应包含消息正文");
        } finally {
            TestRunner.deleteRecursively(dir);
        }
    }

    /** 验证无记录时导出返回失败。 */
    @Test("消息服务：无记录时导出返回失败")
    public void testExportEmpty() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        MessageService service = createService();
        Path dir = TestRunner.createTempDir("msgsvc-export-empty");
        try {
            Result<File> result = service.exportMessages(List.of(), dir.resolve("empty.txt").toFile());
            TestRunner.assertFalse(result.isSuccess(), "空记录导出应失败");
        } finally {
            TestRunner.deleteRecursively(dir);
        }
    }

    /** 验证用户列表编解码可还原用户名、昵称与角色，空文本返回空列表。 */
    @Test("用户列表编解码：编码后可完整还原")
    public void testUserListCodec() {
        User alice = new User("alice", "爱丽丝");
        User bob = new User("bob", "鲍勃");
        bob.setRole(Constants.ROLE_ADMIN);
        String encoded = UserListCodec.encode(List.of(alice, bob));
        List<User> decoded = UserListCodec.decode(encoded);
        TestRunner.assertEquals(2, decoded.size(), "应还原两条用户记录");
        TestRunner.assertEquals("alice", decoded.get(0).getUsername(), "用户名应一致");
        TestRunner.assertEquals("鲍勃", decoded.get(1).getNickname(), "昵称应一致");
        TestRunner.assertTrue(decoded.get(1).isAdmin(), "角色应被正确还原");
        TestRunner.assertEquals(0, UserListCodec.decode(null).size(), "空文本应返回空列表");
        TestRunner.assertEquals(0, UserListCodec.decode("  ").size(), "空白文本应返回空列表");
    }

    /** 验证文件消息入库后元信息可完整回读。 */
    @Test("消息持久化：文件消息记录可回读并保留元信息")
    public void testFileMessagePersistence() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        MessageService service = createService();
        FileMessage file = ChatMessageFactory.file("alice", "bob", MessageType.FILE_REQUEST);
        file.setTransferId("test-transfer");
        file.setFileName("说明.txt");
        file.setFileSize(2048L);
        file.setSha256("abc123");
        service.saveMessage(file);
        Result<List<Message>> result = service.queryHistory("alice", (LocalDateTime) null, null);
        TestRunner.assertEquals(1, result.getData().size(), "应查询到一条文件记录");
        Message restored = result.getData().get(0);
        TestRunner.assertTrue(restored instanceof FileMessage, "回读对象应为文件消息");
        FileMessage restoredFile = (FileMessage) restored;
        TestRunner.assertEquals("说明.txt", restoredFile.getFileName(), "文件名应保留");
        TestRunner.assertEquals(2048L, restoredFile.getFileSize(), "文件大小应保留");
        TestRunner.assertEquals("abc123", restoredFile.getSha256(), "校验和应保留");
    }

    /** 验证按编号删除后记录不再计入总数。 */
    @Test("消息持久化：按编号删除后不再被查询到")
    public void testDeleteMessage() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        MessageService service = createService();
        TextMessage message = ChatMessageFactory.text("alice", "bob", "待删除", MessageType.TEXT_PRIVATE);
        service.saveMessage(message);
        TestRunner.assertEquals(1L, service.count(), "应有 1 条记录");
        TestRunner.assertTrue(message.getId() > 0, "入库后应回填自增主键");
        TestRunner.assertTrue(messageDao.deleteById(message.getId()), "删除应成功");
        TestRunner.assertEquals(0L, messageDao.count(), "删除后应无记录");
    }

    /** 验证新服务实例仍能查询到已写入的历史记录。 */
    @Test("消息持久化：重启（新实例）后历史记录仍可查询")
    public void testHistoryReload() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        MessageService service = createService();
        service.saveMessage(ChatMessageFactory.text("alice", "bob", "重启前写入",
                MessageType.TEXT_PRIVATE));

        JdbcMessageDao reloadedDao = new JdbcMessageDao(TestDatabase.driver(), TestDatabase.url(),
                TestDatabase.user(), TestDatabase.password());
        MessageService reloadedService = new MessageService(reloadedDao);
        Result<List<Message>> result = reloadedService.queryHistory("alice", (LocalDateTime) null, null);
        TestRunner.assertTrue(result.isSuccess(), "重启后查询应成功");
        TestRunner.assertEquals(1, result.getData().size(), "重启后仍应查询到 1 条记录");
        Message restored = result.getData().get(0);
        TestRunner.assertTrue(restored instanceof TextMessage, "回读对象应为文本消息");
        TestRunner.assertEquals("重启前写入", ((TextMessage) restored).getContent(), "正文应完整保留");
    }

    /** 验证保存空消息返回失败且不产生记录。 */
    @Test("消息服务：保存空消息返回失败且不抛异常")
    public void testSaveNull() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        MessageService service = createService();
        TestRunner.assertFalse(service.saveMessage(null).isSuccess(), "保存空消息应返回失败");
        TestRunner.assertEquals(0L, service.count(), "空消息不应产生记录");
    }
}

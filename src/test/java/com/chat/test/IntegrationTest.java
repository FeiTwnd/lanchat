package com.chat.test;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.common.ChatMessageFactory;
import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.Result;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.common.UserListCodec;
import com.chat.dao.JdbcUserDao;
import com.chat.server.ChatServer;
import com.chat.service.MessageService;
import com.chat.service.UserService;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * 覆盖登录、在线列表、私聊、群聊、文件传输、撤回与资料修改的端到端流程。
 */
public class IntegrationTest {
    private Path root;

    private int port;

    private final List<ChatClient> clients = new CopyOnWriteArrayList<>();

    private final java.util.Map<String, BufferingListener> buffers = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 收集客户端回调的消息与连接状态，供用例按条件等待。
     */
    private static final class BufferingListener implements ChatListener {
        private final List<Message> received = new CopyOnWriteArrayList<>();

        private volatile String lastStatus = "";

        /** 记录收到的消息。 */
        @Override
        public void onMessage(Message message) {
            received.add(message);
        }

        /** 记录连接状态变化的原因。 */
        @Override
        public void onConnectionChanged(boolean connected, String reason) {
            lastStatus = reason;
        }

        /** 在超时前等待满足条件的消息，未等到返回 null。 */
        Message await(Predicate<Message> condition, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                for (Message message : received) {
                    if (condition.test(message)) {
                        return message;
                    }
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }

        int count(Predicate<Message> condition) {
            int hits = 0;
            for (Message message : received) {
                if (condition.test(message)) {
                    hits++;
                }
            }
            return hits;
        }

        int size() {
            return received.size();
        }
    }

    /**
     * 按顺序执行全部集成测试并汇总结果。
     *
     * @param args 未使用的命令行参数
     */
    public static void main(String[] args) {
        IntegrationTest test = new IntegrationTest();
        if (!test.useTestDatabase()) {
            System.out.println("========== 集成测试汇总 ==========");
            System.out.println("结论: 已跳过（" + TestDatabase.unavailableReason() + "）");
            return;
        }
        int failures = 0;
        failures += test.runSafely("集成测试 1：服务器启动与客户端登录", test::testLogin);
        failures += test.runSafely("集成测试 2：在线用户列表实时刷新", test::testUserListRefresh);
        failures += test.runSafely("集成测试 3：私聊消息端到端可达", test::testPrivateChat);
        failures += test.runSafely("集成测试 4：群聊消息广播到全部客户端", test::testGroupChat);
        failures += test.runSafely("集成测试 5：文件端到端传输并校验", test::testFileTransfer);
        failures += test.runSafely("集成测试 6：用户下线后列表刷新", test::testOfflineRefresh);
        failures += test.runSafely("集成测试 7：异常场景（未启动服务器/错误密码/离线用户/超限文件）",
                test::testExceptionCases);
        failures += test.runSafely("集成测试 8：消息撤回（本人/时限/幂等/越权/占位替换）",
                test::testRecall);
        failures += test.runSafely("集成测试 9：长会话稳定性（150 条不断线）",
                test::testLongSession);
        failures += test.runSafely("集成测试 10：群聊撤回广播与资料变更回执",
                test::testGroupRecallAndProfile);
        int total = 10;
        System.out.println("\n========== 集成测试汇总 ==========");
        System.out.println("用例总数: " + total);
        System.out.println("通过: " + (total - failures));
        System.out.println("失败: " + failures);
        System.out.println("结论: " + (failures == 0 ? "全部通过" : "存在失败用例"));
        test.shutdown();
        if (failures > 0) {
            System.exit(1);
        }
    }

    private int runSafely(String title, TestBody body) {
        System.out.println("\n--- " + title + " ---");
        try {
            body.run();
            System.out.println("  [通过] " + title);
            return 0;
        } catch (Throwable e) {
            System.out.println("  [失败] " + title);
            System.out.println("         原因: " + e.getMessage());
            return 1;
        } finally {
            shutdownClients();
        }
    }

    /** 集成测试用例的执行体。 */
    private interface TestBody {
        void run() throws Exception;
    }

    /** 验证已注册用户可连接并登录服务器。 */
    private void testLogin() throws Exception {
        prepareEnvironment();
        TestRunner.assertTrue(startServer(), "服务器应启动成功");
        TestRunner.assertTrue(ChatServer.getInstance().isRunning(), "服务器状态应为运行中");

        ChatClient client = newClient();
        TestRunner.assertTrue(connect(client, "alice01", "123456"),
                "已注册用户应登录成功: " + client.getLastLoginFailure());
        TestRunner.assertTrue(client.isConnected(), "登录后连接应保持");
    }

    /** 验证用户上线后客户端收到最新在线列表。 */
    private void testUserListRefresh() throws Exception {
        prepareEnvironment();
        startServer();
        connectWithBuffer("alice01");
        connectWithBuffer("bob01");
        Message list = bufferOf("bob01").await(message -> message.getType() == MessageType.USER_LIST
                && payloadOf(message).contains("alice01") && payloadOf(message).contains("bob01"), 5000);
        TestRunner.assertNotNull(list, "bob 应收到包含两名在线用户的列表");
        TestRunner.assertTrue(bufferOf("alice01").await(message -> message.getType() == MessageType.USER_LIST
                        && payloadOf(message).contains("bob01"), 5000) != null,
                "alice 也应收到包含 bob01 的最新列表");
    }

    /** 验证私聊消息送达接收方并回执发送方。 */
    private void testPrivateChat() throws Exception {
        prepareEnvironment();
        startServer();
        ChatClient alice = connectWithBuffer("alice01");
        connectWithBuffer("bob01");
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 2, 3000, "两名用户应在线上");

        TestRunner.assertTrue(alice.sendPrivateText("bob01", "你好，这是私聊测试"), "私聊发送应成功");
        Message received = bufferOf("bob01").await(message -> message.getType() == MessageType.TEXT_PRIVATE
                && message.getSummary().contains("私聊测试"), 5000);
        TestRunner.assertNotNull(received, "bob 应收到私聊消息");
        TestRunner.assertEquals("alice01", received.getSender(), "私聊消息发送者应为 alice01");
        TestRunner.assertEquals("bob01", received.getReceiver(), "私聊消息接收者应为 bob01");

        Message echo = bufferOf("alice01").await(message -> message.getType() == MessageType.TEXT_PRIVATE
                && "alice01".equals(message.getReceiver()), 5000);
        TestRunner.assertNotNull(echo, "发送方应收到服务器回执");
    }

    /** 验证群聊消息广播、消息标识与上线通知。 */
    private void testGroupChat() throws Exception {
        prepareEnvironment();
        startServer();
        ChatClient alice = connectWithBuffer("alice01");
        connectWithBuffer("bob01");
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 2, 3000, "两名用户应在线上");

        TestRunner.assertTrue(alice.sendGroupText("大家好，这是群聊测试"), "群聊发送应成功");
        for (String user : List.of("alice01", "bob01")) {
            Message group = bufferOf(user).await(message -> message.getType() == MessageType.TEXT_GROUP
                    && message.getSummary().contains("群聊测试"), 5000);
            TestRunner.assertNotNull(group, user + " 应收到群聊消息");
            TestRunner.assertEquals("alice01", group.getSender(), "群聊消息发送者应为 alice01");
            TestRunner.assertTrue(group.isBroadcast(), "群聊消息应为广播消息");
        }

        String groupMessageId = alice.sendGroupTextReturningId("带标识的群聊测试", null, null);
        TestRunner.assertNotNull(groupMessageId, "群聊发送应返回稳定消息标识");
        Message tagged = bufferOf("bob01").await(message -> message.getType() == MessageType.TEXT_GROUP
                && message.getSummary().contains("带标识的群聊测试"), 5000);
        TestRunner.assertNotNull(tagged, "bob 应收到带标识的群聊消息");
        TestRunner.assertEquals(groupMessageId, tagged.getMessageId(),
                "广播出去的群聊消息应携带发送方取回的那个标识");

        Message notification = bufferOf("alice01").await(message -> message.getType() == MessageType.SYSTEM
                && message.getSummary().contains("bob01") && message.getSummary().contains("上线"), 5000);
        TestRunner.assertNotNull(notification, "alice 应收到 bob01 上线的系统通知");
    }

    /** 验证文件端到端传输且内容与校验和一致。 */
    private void testFileTransfer() throws Exception {
        prepareEnvironment();
        startServer();
        ChatClient alice = connectWithBuffer("alice01");
        ChatClient bob = connectWithBuffer("bob01");
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 2, 3000, "两名用户应在线上");

        // 自动接受收到的文件请求
        bob.addListener(new ChatListener() {
            @Override
            public void onMessage(Message message) {
                if (message.getType() == MessageType.FILE_REQUEST) {
                    FileMessage request = message instanceof FileMessage
                            ? (FileMessage) message
                            : ChatClient.decodeRequest((TextMessage) message);
                    if (request != null) {
                        bob.respondFileRequest(request, true, "");
                    }
                }
            }

            @Override
            public void onConnectionChanged(boolean connected, String reason) {
            }
        });

        // 生成 300KB 随机文件并发送
        byte[] content = new byte[300 * 1024 + 17];
        new Random(20250101L).nextBytes(content);
        Path sourcePath = Files.write(root.resolve("send-me.bin"), content);
        File source = sourcePath.toFile();
        String transferId = alice.sendFile("bob01", source);
        TestRunner.assertNotNull(transferId, "应成功发起文件传输");

        Message result = bufferOf("bob01").await(message -> message.getType() == MessageType.FILE_RESULT, 15000);
        TestRunner.assertNotNull(result, "接收方应收到文件传输结果");
        TestRunner.assertTrue(((FileMessage) result).isAccepted(),
                "接收结果应为成功: " + ((FileMessage) result).getMessage());

        // 在接收目录中查找目标文件
        File received = null;
        try (java.util.stream.Stream<Path> files = Files.list(Path.of(bob.getReceiveDir()))) {
            received = files.filter(path -> path.getFileName().toString().equals("send-me.bin"))
                    .map(Path::toFile).findFirst().orElse(null);
        }
        TestRunner.assertNotNull(received, "接收目录中应存在目标文件");
        TestRunner.assertArrayEquals(content, Files.readAllBytes(received.toPath()),
                "接收文件内容应与发送内容逐字节一致");
        TestRunner.assertEquals(com.chat.util.FileUtil.sha256(source),
                com.chat.util.FileUtil.sha256(received), "两端 SHA-256 校验和应一致");

        Message senderResult = bufferOf("alice01").await(
                message -> message.getType() == MessageType.FILE_RESULT, 5000);
        TestRunner.assertNotNull(senderResult, "发送方应收到文件传输回执");
        TestRunner.assertTrue(((FileMessage) senderResult).isAccepted(), "发送方应收收到成功回执");
    }

    /** 验证用户下线后列表刷新并广播下线通知。 */
    private void testOfflineRefresh() throws Exception {
        prepareEnvironment();
        startServer();
        ChatClient alice = connectWithBuffer("alice01");
        ChatClient bob = connectWithBuffer("bob01");
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 2, 3000, "两名用户应在线上");

        bob.close();
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 1, 5000,
                "bob 断开后服务器在线人数应降为 1");

        Message list = bufferOf("alice01").await(message -> message.getType() == MessageType.USER_LIST
                && !payloadOf(message).contains("bob01") && payloadOf(message).contains("alice01"),
                5000);
        TestRunner.assertNotNull(list, "alice 应收到不含 bob01 的最新用户列表");

        Message offlineNotice = bufferOf("alice01").await(message -> message.getType() == MessageType.SYSTEM
                && message.getSummary().contains("bob01") && message.getSummary().contains("下线"), 5000);
        TestRunner.assertNotNull(offlineNotice, "alice 应收到 bob01 下线的系统通知");
    }

    /** 验证私聊撤回的成功、幂等、越权与超时限制。 */
    private void testRecall() throws Exception {
        prepareEnvironment();
        startServer();
        ChatClient alice = connectWithBuffer("alice01");
        ChatClient bob = connectWithBuffer("bob01");
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 2, 3000, "两名用户应在线上");

        String messageId = alice.sendPrivateTextReturningId("bob01", "这条消息稍后会被撤回", null, null);
        TestRunner.assertNotNull(messageId, "发送私聊应返回稳定消息标识");
        TestRunner.assertNotNull(bufferOf("bob01").await(message ->
                        message.getType() == MessageType.TEXT_PRIVATE
                                && message.getSummary().contains("稍后会被撤回"), 5000),
                "bob 应先收到原文");

        TestRunner.assertTrue(alice.requestRecall(messageId), "撤回请求应能发出");
        Message ok = bufferOf("alice01").await(message -> message.getType() == MessageType.MSG_RECALL
                && payloadOf(message).startsWith("OK|"), 5000);
        TestRunner.assertNotNull(ok, "alice 应收到撤回成功回执");
        TestRunner.assertEquals("OK|" + messageId, payloadOf(ok), "回执应带被撤回的消息标识");

        Message notice = bufferOf("bob01").await(message -> message.getType() == MessageType.MSG_RECALL
                && payloadOf(message).startsWith("PEER|"), 5000);
        TestRunner.assertNotNull(notice, "bob 应收到对端撤回通知");
        TestRunner.assertEquals("PEER|" + messageId + "|alice01", payloadOf(notice),
                "撤回通知应带消息标识与发起者");

        TestRunner.assertTrue(alice.requestRecall(messageId), "重复撤回请求应能发出");
        waitUntil(() -> countIn("alice01", message -> message.getType() == MessageType.MSG_RECALL
                        && ("OK|" + messageId).equals(payloadOf(message))) >= 2, 5000,
                "重复撤回应再收到一次 OK 回执");

        String bobId = bob.sendPrivateTextReturningId("alice01", "bob 自己发的消息", null, null);
        TestRunner.assertNotNull(bobId, "bob 发送应返回消息标识");
        TestRunner.assertNotNull(bufferOf("alice01").await(message ->
                        message.getType() == MessageType.TEXT_PRIVATE
                                && message.getSummary().contains("bob 自己发的消息"), 5000),
                "alice 应先收到 bob 的原文");
        TestRunner.assertTrue(alice.requestRecall(bobId), "撤回请求应能发出");
        Message denied = bufferOf("alice01").await(message -> message.getType() == MessageType.MSG_RECALL
                && payloadOf(message).startsWith("FAIL|" + bobId + "|"), 5000);
        TestRunner.assertNotNull(denied, "撤回他人消息应被拒绝");
        TestRunner.assertTrue(payloadOf(denied).contains("只能撤回自己发送的消息"),
                "拒绝原因应说明只能撤回自己的消息，实际为: " + payloadOf(denied));

        TestRunner.assertTrue(alice.requestRecall("no-such-message-id"), "撤回请求应能发出");
        Message missing = bufferOf("alice01").await(message -> message.getType() == MessageType.MSG_RECALL
                && payloadOf(message).startsWith("FAIL|no-such-message-id|"), 5000);
        TestRunner.assertNotNull(missing, "不存在的消息应被拒绝");

        // 构造超过撤回时限的消息
        TextMessage outdated = ChatMessageFactory.text("alice01", "bob01", "三分钟前发出的消息",
                MessageType.TEXT_PRIVATE);
        outdated.setTimestamp(java.time.LocalDateTime.now().minusMinutes(Constants.RECALL_WINDOW_MINUTES + 1L));
        TestRunner.assertTrue(ChatServer.getInstance().getMessageService().saveMessage(outdated).isSuccess(),
                "超时消息应能直接落库");
        String outdatedId = outdated.getMessageId();
        TestRunner.assertTrue(alice.requestRecall(outdatedId), "撤回请求应能发出");
        Message expired = bufferOf("alice01").await(message -> message.getType() == MessageType.MSG_RECALL
                && payloadOf(message).startsWith("FAIL|" + outdatedId + "|"), 5000);
        TestRunner.assertNotNull(expired, "超过时限的消息应被拒绝");
        TestRunner.assertTrue(payloadOf(expired).contains("超过撤回时限"),
                "拒绝原因应说明超过时限，实际为: " + payloadOf(expired));

        Message stored = ChatServer.getInstance().getMessageService().findByMessageId(messageId);
        TestRunner.assertNotNull(stored, "撤回后记录应当仍存在（只置标记位，不删行）");
        TestRunner.assertEquals(Constants.RECALLED_PLACEHOLDER, stored.getSummary(),
                "撤回后读出应为占位文本");

        MessageService service = ChatServer.getInstance().getMessageService();
        List<Message> control = service.search("alice01", "bob 自己发的消息").getData();
        TestRunner.assertTrue(control != null && !control.isEmpty(),
                "对照：未撤回的正文应能被关键字检索命中");
        List<Message> hits = service.search("alice01", "稍后会被撤回").getData();
        TestRunner.assertNotNull(hits, "检索应返回结果集");
        TestRunner.assertEquals(0, hits.size(), "已撤回的正文不应再被关键字检索命中");
    }

    /** 验证连续 150 条消息后连接保持可用。 */
    private void testLongSession() throws Exception {
        prepareEnvironment();
        startServer();
        ChatClient alice = connectWithBuffer("alice01");
        ChatClient bob = connectWithBuffer("bob01");
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 2, 3000, "两名用户应在线上");

        int total = 150;
        for (int i = 1; i <= total; i++) {
            TestRunner.assertTrue(alice.sendPrivateText("bob01", "第 " + i + " 条长会话测试"),
                    "第 " + i + " 条消息应能发出（连接可能已因累计上限被判 REJECTED 而关闭）");
        }
        Message last = bufferOf("bob01").await(message -> message.getType() == MessageType.TEXT_PRIVATE
                && message.getSummary().contains("第 " + total + " 条长会话测试"), 15000);
        TestRunner.assertNotNull(last, "第 " + total + " 条消息应送达："
                + "修复前连接会在收到第 66 条左右被判 REJECTED 后关闭");
        TestRunner.assertTrue(alice.isConnected(), "发完 " + total + " 条后发送方连接应仍然在线");
        TestRunner.assertTrue(bob.isConnected(), "发完 " + total + " 条后接收方连接应仍然在线");

        TestRunner.assertTrue(bob.sendPrivateText("alice01", "回你一条"), "接收方回发应成功");
        TestRunner.assertNotNull(bufferOf("alice01").await(message ->
                        message.getType() == MessageType.TEXT_PRIVATE
                                && message.getSummary().contains("回你一条"), 5000),
                "长会话之后反向消息仍应送达");
    }

    /** 验证群聊撤回广播与昵称、头像、密码变更回执。 */
    private void testGroupRecallAndProfile() throws Exception {
        prepareEnvironment();
        startServer();
        ChatClient alice = connectWithBuffer("alice01");
        connectWithBuffer("bob01");
        connectWithBuffer("carol01");
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 3, 3000, "三名用户应在线上");

        String groupId = alice.sendGroupTextReturningId("这条群聊消息稍后会被撤回", null, null);
        TestRunner.assertNotNull(groupId, "群聊发送应返回稳定消息标识");
        for (String user : List.of("bob01", "carol01")) {
            TestRunner.assertNotNull(bufferOf(user).await(message ->
                            message.getType() == MessageType.TEXT_GROUP
                                    && message.getSummary().contains("稍后会被撤回"), 5000),
                    user + " 应先收到群聊原文");
        }
        TestRunner.assertTrue(alice.requestRecall(groupId), "群聊撤回请求应能发出");
        TestRunner.assertNotNull(bufferOf("alice01").await(message ->
                        message.getType() == MessageType.MSG_RECALL
                                && ("OK|" + groupId).equals(payloadOf(message)), 5000),
                "发起者应收到撤回成功回执");
        for (String user : List.of("bob01", "carol01")) {
            Message notice = bufferOf(user).await(message -> message.getType() == MessageType.MSG_RECALL
                    && ("PEER|" + groupId + "|alice01").equals(payloadOf(message)), 5000);
            TestRunner.assertNotNull(notice, user + " 应收到群聊撤回通知（群聊撤回需广播给全体）");
        }

        TestRunner.assertTrue(alice.requestNicknameChange("新的昵称"), "改昵称请求应能发出");
        Message nick = bufferOf("alice01").await(message ->
                message.getType() == MessageType.PROFILE_RESULT, 5000);
        TestRunner.assertNotNull(nick, "改昵称应收到 PROFILE_RESULT 回执");
        TestRunner.assertTrue(payloadOf(nick).startsWith("1|" + Constants.PROFILE_SECTION_NICK + "|"),
                "改昵称回执应为 1|NICK|原因，实际: " + payloadOf(nick));

        String avatar = sampleAvatar();
        TestRunner.assertTrue(alice.requestAvatarChange(avatar), "改头像请求应能发出");
        Message avatarResult = bufferOf("alice01").await(message ->
                message.getType() == MessageType.PROFILE_RESULT
                        && payloadOf(message).startsWith("1|" + Constants.PROFILE_SECTION_AVATAR + "|"), 5000);
        TestRunner.assertNotNull(avatarResult, "改头像应收到成功回执，实际: "
                + (avatarResult == null ? "未收到" : payloadOf(avatarResult)));
        Message list = bufferOf("bob01").await(message -> message.getType() == MessageType.USER_LIST
                && payloadOf(message).contains(avatar), 5000);
        TestRunner.assertNotNull(list, "改头像后用户列表应携带新头像广播给其他在线用户");
        User refreshed = UserListCodec.decode(payloadOf(list)).stream()
                .filter(user -> "alice01".equals(user.getUsername())).findFirst().orElse(null);
        TestRunner.assertNotNull(refreshed, "用户列表里应包含 alice01");
        TestRunner.assertEquals(avatar, refreshed.getAvatar(), "用户列表里的头像应与上传的一致");

        TestRunner.assertTrue(alice.requestPasswordChange("wrong-password", "1234567"),
                "改密码请求应能发出");
        Message badPassword = bufferOf("alice01").await(message ->
                message.getType() == MessageType.PROFILE_RESULT
                        && payloadOf(message).startsWith("0|" + Constants.PROFILE_SECTION_PASSWORD + "|"), 5000);
        TestRunner.assertNotNull(badPassword, "原密码错误时改密码应失败并回执原因");
        TestRunner.assertTrue(payloadOf(badPassword).contains("原密码"),
                "失败原因应说明原密码不正确，实际: " + payloadOf(badPassword));

        TestRunner.assertTrue(alice.requestPasswordChange("123456", "1234567"), "改密码请求应能发出");
        TestRunner.assertNotNull(bufferOf("alice01").await(message ->
                        message.getType() == MessageType.PROFILE_RESULT
                                && payloadOf(message).startsWith("1|" + Constants.PROFILE_SECTION_PASSWORD + "|"), 5000),
                "原密码正确时改密码应成功");

        TestRunner.assertTrue(alice.requestAvatarChange("这不是一张 PNG"), "请求应能发出");
        Message badAvatar = bufferOf("alice01").await(message ->
                message.getType() == MessageType.PROFILE_RESULT
                        && payloadOf(message).startsWith("0|" + Constants.PROFILE_SECTION_AVATAR + "|"), 5000);
        TestRunner.assertNotNull(badAvatar, "非法头像应被服务端拒绝");

        int avatarReplies = countIn("alice01", message ->
                message.getType() == MessageType.PROFILE_RESULT
                        && payloadOf(message).startsWith("1|" + Constants.PROFILE_SECTION_AVATAR + "|"));
        TestRunner.assertTrue(alice.requestAvatarChange(""), "清空头像的请求应能发出");
        waitUntil(() -> countIn("alice01", message ->
                        message.getType() == MessageType.PROFILE_RESULT
                                && payloadOf(message).startsWith("1|" + Constants.PROFILE_SECTION_AVATAR + "|"))
                        > avatarReplies,
                5000, "清空头像应成功（空值表示恢复默认头像）");

        Message cleared = bufferOf("bob01").await(message -> message.getType() == MessageType.USER_LIST
                && UserListCodec.decode(payloadOf(message)).stream()
                .anyMatch(user -> "alice01".equals(user.getUsername()) && user.getAvatar() == null), 5000);
        TestRunner.assertNotNull(cleared, "清空头像后用户列表里的该用户应不再带头像");
    }

    private String sampleAvatar() {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                Constants.AVATAR_SIDE, Constants.AVATAR_SIDE,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", encoded);
        } catch (IOException e) {
            throw new IllegalStateException("测试头像编码失败", e);
        }
        return java.util.Base64.getEncoder().encodeToString(encoded.toByteArray());
    }

    /** 验证未启动服务器、错误密码、离线用户与超限文件的处理。 */
    private void testExceptionCases() throws Exception {
        prepareEnvironment();
        int closedPort = findFreePort();
        ChatClient lonely = new ChatClient();
        TestRunner.assertFalse(lonely.connect("127.0.0.1", closedPort, "alice01", "123456"),
                "服务器未启动时连接应返回失败");
        TestRunner.assertFalse(lonely.isConnected(), "连接失败后不应处于连接状态");
        lonely.close();

        startServer();
        ChatClient wrongPassword = newClient();
        TestRunner.assertFalse(connect(wrongPassword, "alice01", "wrong-password"),
                "错误密码应登录失败");
        TestRunner.assertTrue(wrongPassword.getLastLoginFailure().contains("密码"),
                "失败原因应提示密码错误，实际: " + wrongPassword.getLastLoginFailure());
        wrongPassword.close();

        ChatClient alice = connectWithBuffer("alice01");
        alice.sendPrivateText("nobody99", "这条消息应该失败");
        Message error = bufferOf("alice01").await(message -> message.getType() == MessageType.ERROR
                && message.getSummary().contains("不在线"), 5000);
        TestRunner.assertNotNull(error, "向不在线用户发消息应收到错误提示");

        File huge = root.resolve("huge.bin").toFile();
        try (java.io.RandomAccessFile accessor = new java.io.RandomAccessFile(huge, "rw")) {
            accessor.setLength(Constants.MAX_FILE_SIZE + 1);
        }
        TestRunner.assertNull(alice.sendFile("bob01", huge), "超限文件发送应返回 null（被拒绝）");

        String limitText = com.chat.util.FileUtil.humanSize(Config.maxFileSize());
        Message sizeError = bufferOf("alice01").await(message -> message.getType() == MessageType.ERROR
                && message.getSummary().contains("大小上限")
                && message.getSummary().contains(limitText), 5000);
        TestRunner.assertNotNull(sizeError, "应收到文件超限的错误提示（上限 " + limitText + "）");
    }

    /** 重置临时目录、端口、测试库与测试账号。 */
    private void prepareEnvironment() throws Exception {
        shutdownServer();
        shutdownClients();
        buffers.clear();
        // 切换到临时数据目录
        root = TestRunner.createTempDir("integration");

        System.setProperty("lanchat.data.dir", root.toString());
        port = findFreePort();

        TestDatabase.reset();
        seedUsers();
    }

    /** 把数据库配置指向测试库并返回是否可用。 */
    private boolean useTestDatabase() {
        System.setProperty("lanchat.db.url", TestDatabase.url());
        System.setProperty("lanchat.db.user", TestDatabase.user());
        System.setProperty("lanchat.db.password", TestDatabase.password());
        System.setProperty("lanchat.db.driver", TestDatabase.driver());
        return TestDatabase.isAvailable();
    }

    /** 写入三个用于登录的测试账号。 */
    private void seedUsers() {
        try {
            JdbcUserDao dao = new JdbcUserDao(TestDatabase.driver(), TestDatabase.url(),
                    TestDatabase.user(), TestDatabase.password());

            for (String username : new String[]{"alice01", "bob01", "carol01"}) {
                User user = new User(username, username + "的昵称");
                user.setSalt(com.chat.util.SecurityUtil.generateSalt());
                user.setPasswordHash(com.chat.util.SecurityUtil.hashPassword("123456", user.getSalt()));
                user.setCreateTime(java.time.LocalDateTime.now());
                dao.save(user);
            }
        } catch (Exception e) {
            throw new IllegalStateException("测试用户初始化失败(" + TestDatabase.url() + "): " + e, e);
        }
    }

    private boolean startServer() {
        ChatServer server = ChatServer.getInstance();
        server.setPort(port);
        return server.start();
    }

    private void shutdownServer() {
        ChatServer server = ChatServer.getInstance();
        if (server.isRunning()) {
            server.stop();
        }
    }

    private ChatClient newClient() {
        ChatClient client = new ChatClient();

        client.setReceiveDir(root.resolve("received").toString());
        clients.add(client);
        return client;
    }

    private ChatClient connectWithBuffer(String username) {
        ChatClient client = newClient();
        BufferingListener buffer = new BufferingListener();
        buffers.put(username, buffer);
        client.addListener(buffer);
        TestRunner.assertTrue(connect(client, username, "123456"), username + " 应登录成功");
        return client;
    }

    private BufferingListener bufferOf(String username) {
        BufferingListener buffer = buffers.get(username);
        TestRunner.assertNotNull(buffer, "应存在 " + username + " 的消息缓冲器");
        return buffer;
    }

    private int countIn(String username, Predicate<Message> condition) {
        return bufferOf(username).count(condition);
    }

    /** 取文本消息的正文，非文本消息返回空串。 */
    private static String payloadOf(Message message) {
        if (message instanceof TextMessage) {
            String content = ((TextMessage) message).getContent();
            return content == null ? "" : content;
        }
        return "";
    }

    private boolean connect(ChatClient client, String username, String password) {
        if (!client.connect("127.0.0.1", port, username, password)) {
            return false;
        }
        return client.waitForLoginResult(5000);
    }

    private void shutdownClients() {
        for (ChatClient client : clients) {
            client.close();
        }
        clients.clear();
    }

    private void shutdown() {
        try {
            if (TestDatabase.isAvailable()) {
                TestDatabase.reset();
            }
        } catch (RuntimeException e) {
            System.out.println("[警告] 测试数据清理失败: " + e.getMessage());
        }
        shutdownClients();
        shutdownServer();
        TestRunner.deleteRecursively(root);
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 轮询直到条件成立，超时抛出断言错误。 */
    private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs, String message)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(message + "（等待超时 " + timeoutMs + "ms）");
    }
}

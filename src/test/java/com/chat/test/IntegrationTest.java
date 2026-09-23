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
 * 集成测试：真实启动服务器 + 多个客户端，验证端到端功能。
 *
 * <p>覆盖点：</p>
 * <ol>
 *   <li>用户上线后在线用户列表实时刷新；</li>
 *   <li>两个客户端之间的私聊消息可达；</li>
 *   <li>群聊消息广播到所有在线客户端；</li>
 *   <li>文件从发送方完整传输到接收方并通过 SHA-256 校验；</li>
 *   <li>异常场景：服务器未启动、登录密码错误、向不在线用户发私聊、超限文件发送；</li>
 *   <li>消息撤回：本人撤回成功、对端收到撤回通知、重复撤回幂等、撤回他人消息与超时消息被拒绝、
 *       撤回后正文以占位文本替代且不再被关键字检索命中。</li>
 * </ol>
 *
 * <p>实现方式：不使用界面，直接驱动 {@link ChatClient}（与 GUI 共用同一网络层），
 * 因此本测试验证的是真实协议与真实服务器逻辑，而不是被 mock 掉的假实现。</p>
 *
 * <p>端口选取：通过 {@code ServerSocket(0)} 让操作系统分配空闲端口，
 * 避免与开发者本机正在运行的服务器冲突。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class IntegrationTest {

    /** 测试期间使用的临时根目录 */
    private Path root;

    /** 服务器监听端口 */
    private int port;

    /** 已创建的客户端，用于统一清理 */
    private final List<ChatClient> clients = new CopyOnWriteArrayList<>();

    /** 已创建的客户端对应的消息缓冲器（用户名 -> 缓冲器） */
    private final java.util.Map<String, BufferingListener> buffers = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 消息缓冲监听器。
     *
     * <p>集成测试的关键难点是“事件先于断言到达”：服务器推送的上线通知、
     * 用户列表刷新往往在测试开始等待之前就已经送达。若等到需要时才注册监听器，
     * 就会稳定地错过这些消息。本类在客户端连接的第一时间注册并缓存全部消息，
     * 后续断言只在缓存中检索，从根本上消除竞态。</p>
     */
    private static final class BufferingListener implements ChatListener {

        /** 收到的全部消息 */
        private final List<Message> received = new CopyOnWriteArrayList<>();

        /** 连接状态描述 */
        private volatile String lastStatus = "";

        /**
         * 缓存收到的消息。
         *
         * @param message 消息
         */
        @Override
        public void onMessage(Message message) {
            received.add(message);
        }

        /**
         * 记录连接状态。
         *
         * @param connected 是否连接
         * @param reason    原因
         */
        @Override
        public void onConnectionChanged(boolean connected, String reason) {
            lastStatus = reason;
        }

        /**
         * 等待满足条件的消息。
         *
         * @param condition 条件
         * @param timeoutMs 超时时间
         * @return 命中消息；超时返回 null
         */
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

        /**
         * 统计满足条件的消息条数。
         *
         * <p>{@link #await} 每次都从缓冲区头部扫描，只要存在一条旧命中就会立即返回，
         * 因此"又收到一条同样的消息"这类断言不能靠 {@code await}，只能数条数。</p>
         *
         * @param condition 条件
         * @return 命中条数
         */
        int count(Predicate<Message> condition) {
            int hits = 0;
            for (Message message : received) {
                if (condition.test(message)) {
                    hits++;
                }
            }
            return hits;
        }

        /**
         * 获取已缓存的消息数量。
         *
         * @return 消息数量
         */
        int size() {
            return received.size();
        }
    }

    /**
     * 集成测试主入口。
     *
     * @param args 未使用
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
        failures += test.runSafely("集成测试 9：长会话稳定性（150 条不断线，D-23 验证）",
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

    /**
     * 执行单个测试步骤并捕获异常，避免一步失败导致后续无法执行。
     *
     * @param title 用例标题
     * @param body  用例逻辑
     * @return 失败返回 1，成功返回 0
     */
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

    /**
     * 测试步骤函数式接口。
     */
    private interface TestBody {

        /**
         * 执行测试逻辑。
         *
         * @throws Exception 测试失败
         */
        void run() throws Exception;
    }

    /**
     * 用例 1：服务器启动与客户端登录。
     *
     * @throws Exception 测试异常
     */
    private void testLogin() throws Exception {
        prepareEnvironment();
        TestRunner.assertTrue(startServer(), "服务器应启动成功");
        TestRunner.assertTrue(ChatServer.getInstance().isRunning(), "服务器状态应为运行中");

        ChatClient client = newClient();
        TestRunner.assertTrue(connect(client, "alice01", "123456"),
                "已注册用户应登录成功: " + client.getLastLoginFailure());
        TestRunner.assertTrue(client.isConnected(), "登录后连接应保持");
    }

    /**
     * 用例 2：在线用户列表随用户上线实时刷新。
     *
     * @throws Exception 测试异常
     */
    private void testUserListRefresh() throws Exception {
        prepareEnvironment();
        startServer();
        connectWithBuffer("alice01");
        connectWithBuffer("bob01");
        Message list = bufferOf("bob01").await(message -> message.getType() == MessageType.USER_LIST
                && message.getSummary().contains("alice01") && message.getSummary().contains("bob01"), 5000);
        TestRunner.assertNotNull(list, "bob 应收到包含两名在线用户的列表");
        TestRunner.assertTrue(bufferOf("alice01").await(message -> message.getType() == MessageType.USER_LIST
                        && message.getSummary().contains("bob01"), 5000) != null,
                "alice 也应收到包含 bob01 的最新列表");
    }

    /**
     * 用例 3：私聊消息端到端可达。
     *
     * @throws Exception 测试异常
     */
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

    /**
     * 用例 4：群聊消息广播。
     *
     * @throws Exception 测试异常
     */
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

        // 群聊没有 ACK，稳定标识只能由本端在发送前分配：界面靠它给"自己发的那一条"装右键菜单
        // （此前缺的正是这一环），对端靠它引用同一条消息。这里把"取回的标识 == 广播出去的标识"
        // 这条闭环固定下来，避免以后又把标识只留在发送通道里。
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

    /**
     * 用例 5：文件端到端传输并通过校验。
     *
     * @throws Exception 测试异常
     */
    private void testFileTransfer() throws Exception {
        prepareEnvironment();
        startServer();
        ChatClient alice = connectWithBuffer("alice01");
        ChatClient bob = connectWithBuffer("bob01");
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 2, 3000, "两名用户应在线上");

        // 接收方自动同意文件请求，模拟界面上用户点击“同意”
        bob.addListener(new ChatListener() {
            /**
             * 收到文件请求时自动同意。
             *
             * @param message 消息
             */
            @Override
            public void onMessage(Message message) {
                if (message.getType() == MessageType.FILE_REQUEST) {
                    // FILE_REQUEST 既可能是文本承载的请求，也可能是已解析的文件消息，
                    // 两种形态都要兼容（由 ChatClient.decodeRequest 统一处理）
                    FileMessage request = message instanceof FileMessage
                            ? (FileMessage) message
                            : ChatClient.decodeRequest((TextMessage) message);
                    if (request != null) {
                        bob.respondFileRequest(request, true, "");
                    }
                }
            }

            /**
             * 连接状态变化无需处理。
             *
             * @param connected 是否连接
             * @param reason    原因
             */
            @Override
            public void onConnectionChanged(boolean connected, String reason) {
                // 集成测试无需处理连接事件
            }
        });

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

    /**
     * 用例 6：用户下线后在线列表刷新。
     *
     * @throws Exception 测试异常
     */
    private void testOfflineRefresh() throws Exception {
        prepareEnvironment();
        startServer();
        ChatClient alice = connectWithBuffer("alice01");
        ChatClient bob = connectWithBuffer("bob01");
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 2, 3000, "两名用户应在线上");

        bob.close();
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 1, 5000,
                "bob 断开后服务器在线人数应降为 1");
        // 必须从连接时就注册的缓冲监听器里检索：下线广播与系统通知往往在断言开始等待前就已到达，
        // 若在此处才注册监听器会稳定地错过消息（这是本用例此前偶发失败的根因）
        Message list = bufferOf("alice01").await(message -> message.getType() == MessageType.USER_LIST
                && !message.getSummary().contains("bob01") && message.getSummary().contains("alice01"),
                5000);
        TestRunner.assertNotNull(list, "alice 应收到不含 bob01 的最新用户列表");

        Message offlineNotice = bufferOf("alice01").await(message -> message.getType() == MessageType.SYSTEM
                && message.getSummary().contains("bob01") && message.getSummary().contains("下线"), 5000);
        TestRunner.assertNotNull(offlineNotice, "alice 应收到 bob01 下线的系统通知");
    }

    /**
     * 用例 8：消息撤回的完整链路。
     *
     * <p>覆盖服务端裁决的全部规则：本人撤回成功后回执形如 {@code OK|messageId} 并通知会话对端；
     * 重复撤回按幂等处理；撤回他人消息、撤回超时消息、撤回不存在的标识均被拒绝；
     * 撤回后记录保留但读出为占位文本，且不再被关键字检索命中。</p>
     *
     * <p>撤回链路此前只有界面侧的手工验证，仓库内没有自动化用例：界面点不出菜单时，
     * 服务端这一半是完全正确的，缺陷只能靠人发现。本用例把"协议这一半"固定下来。</p>
     *
     * @throws Exception 测试异常
     */
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

        // 1. 本人撤回成功
        TestRunner.assertTrue(alice.requestRecall(messageId), "撤回请求应能发出");
        Message ok = bufferOf("alice01").await(message -> message.getType() == MessageType.MSG_RECALL
                && payloadOf(message).startsWith("OK|"), 5000);
        TestRunner.assertNotNull(ok, "alice 应收到撤回成功回执");
        TestRunner.assertEquals("OK|" + messageId, payloadOf(ok), "回执应带被撤回的消息标识");

        // 2. 会话对端收到通知，界面据此把气泡改为"对方撤回了一条消息"
        Message notice = bufferOf("bob01").await(message -> message.getType() == MessageType.MSG_RECALL
                && payloadOf(message).startsWith("PEER|"), 5000);
        TestRunner.assertNotNull(notice, "bob 应收到对端撤回通知");
        TestRunner.assertEquals("PEER|" + messageId + "|alice01", payloadOf(notice),
                "撤回通知应带消息标识与发起者");

        // 3. 重复撤回幂等：仍返回 OK，而不是报"已撤回"错误
        //    await 每次都从缓冲区头部扫描，会命中第 1 步的旧回执，因此这里数条数
        TestRunner.assertTrue(alice.requestRecall(messageId), "重复撤回请求应能发出");
        waitUntil(() -> countIn("alice01", message -> message.getType() == MessageType.MSG_RECALL
                        && ("OK|" + messageId).equals(payloadOf(message))) >= 2, 5000,
                "重复撤回应再收到一次 OK 回执");

        // 4. 撤回他人消息被服务端拒绝：客户端提交的标识绕不过身份校验
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

        // 5. 不存在的标识被拒绝，且拒绝回执带上该标识便于界面定位
        TestRunner.assertTrue(alice.requestRecall("no-such-message-id"), "撤回请求应能发出");
        Message missing = bufferOf("alice01").await(message -> message.getType() == MessageType.MSG_RECALL
                && payloadOf(message).startsWith("FAIL|no-such-message-id|"), 5000);
        TestRunner.assertNotNull(missing, "不存在的消息应被拒绝");

        // 5.1 超时消息被拒绝：时限判的是"发出时刻"，不能靠界面上的菜单状态来保证
        //     直接落一条 3 分钟前的记录，避免用例真的等待 2 分钟
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

        // 6. 撤回只标记不删除，读出时以占位文本替换正文
        Message stored = ChatServer.getInstance().getMessageService().findByMessageId(messageId);
        TestRunner.assertNotNull(stored, "撤回后记录应当仍存在（只置标记位，不删行）");
        TestRunner.assertEquals(Constants.RECALLED_PLACEHOLDER, stored.getSummary(),
                "撤回后读出应为占位文本");

        // 7. 关键字检索同样看不到原文：这是"撤回"对使用者真正有意义的语义
        //    先做一次正向对照——同一次运行里检索未撤回的正文应当命中，
        //    否则"命中 0 条"可能只是检索本身坏了，断言会变成空转
        MessageService service = ChatServer.getInstance().getMessageService();
        List<Message> control = service.search("alice01", "bob 自己发的消息").getData();
        TestRunner.assertTrue(control != null && !control.isEmpty(),
                "对照：未撤回的正文应能被关键字检索命中");
        List<Message> hits = service.search("alice01", "稍后会被撤回").getData();
        TestRunner.assertNotNull(hits, "检索应返回结果集");
        TestRunner.assertEquals(0, hits.size(), "已撤回的正文不应再被关键字检索命中");
    }

    /**
     * 用例 9：长会话稳定性（缺陷 D-23 的端到端验证）。
     *
     * <p>反序列化过滤器的 {@code maxrefs} 是连接级累计量，而本项目每发一条消息都会
     * {@code reset()}，重置会让类描述符重发、引用消耗翻倍。修复前该值取 1000，
     * 据此实测"发到第 66 条就再也读不进任何消息"，服务端随后关闭连接——
     * 使用者看到的是"聊着聊着突然掉线"，且日志把容量问题记成了安全问题。</p>
     *
     * <p>本用例发送 150 条（约为修复前上限的两倍），断言最后一条仍然送达且两条连接都还在：
     * 它是这条链路唯一的自动化证据，此前只能靠人手点到第 66 条才会碰上。</p>
     *
     * @throws Exception 测试异常
     */
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

        // 反向也要通：接收方回一条，证明这条连接的两个方向都还活着
        TestRunner.assertTrue(bob.sendPrivateText("alice01", "回你一条"), "接收方回发应成功");
        TestRunner.assertNotNull(bufferOf("alice01").await(message ->
                        message.getType() == MessageType.TEXT_PRIVATE
                                && message.getSummary().contains("回你一条"), 5000),
                "长会话之后反向消息仍应送达");
    }

    /**
     * 用例 10：群聊撤回广播与资料变更回执。
     *
     * <p>本轮两处服务端改动合在一起验证：</p>
     * <ul>
     *   <li>群聊撤回从"只回发起者"改为"广播给除发起者外的全体在线用户"——
     *       只回发起者的话，群里其他人会一直看着一条服务端已经不存在的消息；</li>
     *   <li>改昵称/改头像/改密码从 {@code SYSTEM} 改为专用回执 {@code PROFILE_RESULT}——
     *       {@code SYSTEM} 同时承载"某人上线了"这类广播，客户端无法区分二者。</li>
     * </ul>
     *
     * @throws Exception 测试异常
     */
    private void testGroupRecallAndProfile() throws Exception {
        prepareEnvironment();
        startServer();
        ChatClient alice = connectWithBuffer("alice01");
        connectWithBuffer("bob01");
        connectWithBuffer("carol01");
        waitUntil(() -> ChatServer.getInstance().getUserManager().size() == 3, 3000, "三名用户应在线上");

        // 1. 群聊撤回：发起者拿到 OK 回执，另外两名在线用户都应收到对端撤回通知
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

        // 2. 改昵称：回执类型必须是 PROFILE_RESULT，且带分区标识
        TestRunner.assertTrue(alice.requestNicknameChange("新的昵称"), "改昵称请求应能发出");
        Message nick = bufferOf("alice01").await(message ->
                message.getType() == MessageType.PROFILE_RESULT, 5000);
        TestRunner.assertNotNull(nick, "改昵称应收到 PROFILE_RESULT 回执");
        TestRunner.assertTrue(payloadOf(nick).startsWith("1|" + Constants.PROFILE_SECTION_NICK + "|"),
                "改昵称回执应为 1|NICK|原因，实际: " + payloadOf(nick));

        // 3. 改头像：回执成功，且随后的用户列表里能取到该头像
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

        // 4. 改密码：原密码错误必须失败，正确时成功——两条都要有明显不同的回执
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

        // 5. 非法头像被服务端拒绝（客户端闸门可以绕过，服务端必须自己复核）
        TestRunner.assertTrue(alice.requestAvatarChange("这不是一张 PNG"), "请求应能发出");
        Message badAvatar = bufferOf("alice01").await(message ->
                message.getType() == MessageType.PROFILE_RESULT
                        && payloadOf(message).startsWith("0|" + Constants.PROFILE_SECTION_AVATAR + "|"), 5000);
        TestRunner.assertNotNull(badAvatar, "非法头像应被服务端拒绝");

        // 6. 清空头像：空值代表"恢复默认头像"，也必须有明确回执而不是静默失败。
        //    注意不能再用 await——它每次都从缓冲区头部扫描，会命中第 3 步那条旧回执，
        //    断言就变成了空转；只能数条数。
        int avatarReplies = countIn("alice01", message ->
                message.getType() == MessageType.PROFILE_RESULT
                        && payloadOf(message).startsWith("1|" + Constants.PROFILE_SECTION_AVATAR + "|"));
        TestRunner.assertTrue(alice.requestAvatarChange(""), "清空头像的请求应能发出");
        waitUntil(() -> countIn("alice01", message ->
                        message.getType() == MessageType.PROFILE_RESULT
                                && payloadOf(message).startsWith("1|" + Constants.PROFILE_SECTION_AVATAR + "|"))
                        > avatarReplies,
                5000, "清空头像应成功（空值表示恢复默认头像）");

        // 7. 清空后广播出去的列表里该用户不再带头像
        Message cleared = bufferOf("bob01").await(message -> message.getType() == MessageType.USER_LIST
                && UserListCodec.decode(payloadOf(message)).stream()
                .anyMatch(user -> "alice01".equals(user.getUsername()) && user.getAvatar() == null), 5000);
        TestRunner.assertNotNull(cleared, "清空头像后用户列表里的该用户应不再带头像");
    }

    /**
     * 造一段结构合法的最小 PNG 数据（魔数 + IHDR），用于头像链路测试。
     *
     * @return Base64 文本
     */
    private String sampleAvatar() {
        byte[] data = new byte[32];
        byte[] magic = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, data, 0, magic.length);
        data[11] = 13;
        System.arraycopy("IHDR".getBytes(Constants.CHARSET), 0, data, 12, 4);
        // 96×96，大端序写入
        data[19] = (byte) Constants.AVATAR_SIDE;
        data[23] = (byte) Constants.AVATAR_SIDE;
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    /**
     * 用例 7：异常场景。
     *
     * @throws Exception 测试异常
     */
    private void testExceptionCases() throws Exception {
        // 场景 A：服务器未启动时应能连接失败而不是抛异常
        prepareEnvironment();
        int closedPort = findFreePort();
        ChatClient lonely = new ChatClient();
        TestRunner.assertFalse(lonely.connect("127.0.0.1", closedPort, "alice01", "123456"),
                "服务器未启动时连接应返回失败");
        TestRunner.assertFalse(lonely.isConnected(), "连接失败后不应处于连接状态");
        lonely.close();

        // 场景 B：密码错误登录失败
        startServer();
        ChatClient wrongPassword = newClient();
        TestRunner.assertFalse(connect(wrongPassword, "alice01", "wrong-password"),
                "错误密码应登录失败");
        TestRunner.assertTrue(wrongPassword.getLastLoginFailure().contains("密码"),
                "失败原因应提示密码错误，实际: " + wrongPassword.getLastLoginFailure());
        wrongPassword.close();

        // 场景 C：向不在线用户发送私聊，应收到错误提示
        ChatClient alice = connectWithBuffer("alice01");
        alice.sendPrivateText("nobody99", "这条消息应该失败");
        Message error = bufferOf("alice01").await(message -> message.getType() == MessageType.ERROR
                && message.getSummary().contains("不在线"), 5000);
        TestRunner.assertNotNull(error, "向不在线用户发消息应收到错误提示");

        // 场景 D：超过大小上限的文件应被本地拒绝
        File huge = root.resolve("huge.bin").toFile();
        try (java.io.RandomAccessFile accessor = new java.io.RandomAccessFile(huge, "rw")) {
            accessor.setLength(Constants.MAX_FILE_SIZE + 1);
        }
        TestRunner.assertNull(alice.sendFile("bob01", huge), "超限文件发送应返回 null（被拒绝）");
        // 用实际生效的上限做断言，避免与配置文件中的自定义值或提示文本格式脱钩
        String limitText = com.chat.util.FileUtil.humanSize(Config.maxFileSize());
        Message sizeError = bufferOf("alice01").await(message -> message.getType() == MessageType.ERROR
                && message.getSummary().contains("大小上限")
                && message.getSummary().contains(limitText), 5000);
        TestRunner.assertNotNull(sizeError, "应收到文件超限的错误提示（上限 " + limitText + "）");
    }

    /**
     * 准备测试环境：临时目录、测试用户、独立端口。
     *
     * @throws Exception 准备失败
     */
    private void prepareEnvironment() throws Exception {
        shutdownServer();
        shutdownClients();
        buffers.clear();
        root = TestRunner.createTempDir("integration");
        // 接收文件与导出记录仍落磁盘，因此仍用临时根目录隔离
        System.setProperty("lanchat.data.dir", root.toString());
        port = findFreePort();
        // 用户与聊天记录在数据库里，每个用例前清库再重新造数据
        TestDatabase.reset();
        seedUsers();
    }

    /**
     * 让本次运行只使用独立的测试库，避免污染开发者真实使用的库。
     *
     * @return 测试库可用返回 true；不可用时返回 false 并给出原因
     */
    private boolean useTestDatabase() {
        System.setProperty("lanchat.db.url", TestDatabase.url());
        System.setProperty("lanchat.db.user", TestDatabase.user());
        System.setProperty("lanchat.db.password", TestDatabase.password());
        System.setProperty("lanchat.db.driver", TestDatabase.driver());
        return TestDatabase.isAvailable();
    }

    /**
     * 写入测试用户数据。
     *
     * <p>通过直接操作 DAO 造数据，避免集成测试依赖注册流程本身的正确性。</p>
     */
    private void seedUsers() {
        try {
            JdbcUserDao dao = new JdbcUserDao(TestDatabase.driver(), TestDatabase.url(),
                    TestDatabase.user(), TestDatabase.password());
            // carol01 只用于"群聊撤回广播给除发起者外的全体"这一条用例：
            // 只有两个人在线时，"广播"与"发给对端"在观感上是一样的，断言没有区分力。
            // 它只写库、不上线，因此不会进入在线用户列表，不影响其它用例的人数断言。
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

    /**
     * 启动服务器并设置端口。
     *
     * @return 启动成功返回 true
     */
    private boolean startServer() {
        ChatServer server = ChatServer.getInstance();
        server.setPort(port);
        return server.start();
    }

    /**
     * 停止服务器。
     */
    private void shutdownServer() {
        ChatServer server = ChatServer.getInstance();
        if (server.isRunning()) {
            server.stop();
        }
    }

    /**
     * 创建并登记一个客户端。
     *
     * @return 客户端实例
     */
    private ChatClient newClient() {
        ChatClient client = new ChatClient();
        // 接收目录放在临时根目录下，避免测试产物污染项目 data 目录
        client.setReceiveDir(root.resolve("received").toString());
        clients.add(client);
        return client;
    }

    /**
     * 创建客户端并在连接前注册缓冲监听器，确保不漏掉任何一条服务器消息。
     *
     * @param username 用户名
     * @return 已连接并登录成功的客户端
     */
    private ChatClient connectWithBuffer(String username) {
        ChatClient client = newClient();
        BufferingListener buffer = new BufferingListener();
        buffers.put(username, buffer);
        client.addListener(buffer);
        TestRunner.assertTrue(connect(client, username, "123456"), username + " 应登录成功");
        return client;
    }

    /**
     * 获取指定用户的消息缓冲器。
     *
     * @param username 用户名
     * @return 缓冲器
     */
    private BufferingListener bufferOf(String username) {
        BufferingListener buffer = buffers.get(username);
        TestRunner.assertNotNull(buffer, "应存在 " + username + " 的消息缓冲器");
        return buffer;
    }

    /**
     * 统计某客户端已收到的、满足条件的消息条数。
     *
     * @param username  用户名
     * @param condition 条件
     * @return 命中条数
     */
    private int countIn(String username, Predicate<Message> condition) {
        return bufferOf(username).count(condition);
    }

    /**
     * 读取一条文本消息的协议正文。
     *
     * <p>协议回执不能用 {@link Message#getSummary()} 断言：那是给界面展示用的摘要，
     * 正文超过 30 个字符就会被截断成"前 30 字 + ..."，而一条撤回回执
     * {@code OK|messageId} 本身就有 39 个字符，用摘要比对永远等不到相等。
     * 界面侧之所以不受影响，是因为它读的也是 {@code getContent()}。</p>
     *
     * @param message 消息
     * @return 文本正文；非文本消息返回空串
     */
    private static String payloadOf(Message message) {
        if (message instanceof TextMessage) {
            String content = ((TextMessage) message).getContent();
            return content == null ? "" : content;
        }
        return "";
    }

    /**
     * 连接并等待登录结果。
     *
     * @param client   客户端
     * @param username 用户名
     * @param password 密码
     * @return 登录成功返回 true
     */
    private boolean connect(ChatClient client, String username, String password) {
        if (!client.connect("127.0.0.1", port, username, password)) {
            return false;
        }
        return client.waitForLoginResult(5000);
    }

    /**
     * 关闭全部客户端。
     */
    private void shutdownClients() {
        for (ChatClient client : clients) {
            client.close();
        }
        clients.clear();
    }

    /**
     * 关闭服务器与客户端，清理临时目录。
     */
    private void shutdown() {
        // 清理测试库中的残留数据，避免影响下一次运行
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

    /**
     * 查找空闲端口。
     *
     * @return 空闲端口号
     * @throws IOException 获取失败
     */
    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 轮询等待条件成立。
     *
     * @param condition 条件
     * @param timeoutMs 超时时间
     * @param message   失败提示
     * @throws Exception 条件未在超时内成立
     */
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

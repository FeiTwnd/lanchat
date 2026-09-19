package com.chat.test;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.Result;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.dao.MessageDaoImpl;
import com.chat.dao.UserDaoImpl;
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
 *   <li>异常场景：服务器未启动、登录密码错误、向不在线用户发私聊、超限文件发送。</li>
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
        int failures = 0;
        failures += test.runSafely("集成测试 1：服务器启动与客户端登录", test::testLogin);
        failures += test.runSafely("集成测试 2：在线用户列表实时刷新", test::testUserListRefresh);
        failures += test.runSafely("集成测试 3：私聊消息端到端可达", test::testPrivateChat);
        failures += test.runSafely("集成测试 4：群聊消息广播到全部客户端", test::testGroupChat);
        failures += test.runSafely("集成测试 5：文件端到端传输并校验", test::testFileTransfer);
        failures += test.runSafely("集成测试 6：用户下线后列表刷新", test::testOfflineRefresh);
        failures += test.runSafely("集成测试 7：异常场景（未启动服务器/错误密码/离线用户/超限文件）",
                test::testExceptionCases);
        System.out.println("\n========== 集成测试汇总 ==========");
        System.out.println("用例总数: 7");
        System.out.println("通过: " + (7 - failures));
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
        Message list = alice.waitForMessage(message -> message.getType() == MessageType.USER_LIST
                && !message.getSummary().contains("bob01") && message.getSummary().contains("alice01"),
                5000);
        TestRunner.assertNotNull(list, "alice 应收到不含 bob01 的最新用户列表");
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
        Message sizeError = bufferOf("alice01").await(message -> message.getType() == MessageType.ERROR
                && message.getSummary().contains(Constants.MAX_FILE_SIZE_TEXT), 5000);
        TestRunner.assertNotNull(sizeError, "应收到文件超限的错误提示");
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
        System.setProperty("lanchat.data.dir", root.toString());
        port = findFreePort();
        seedUsers();
    }

    /**
     * 写入测试用户数据。
     *
     * <p>通过直接操作 DAO 造数据，避免集成测试依赖注册流程本身的正确性。</p>
     */
    private void seedUsers() {
        try {
            UserDaoImpl dao = new UserDaoImpl(com.chat.common.Config.userFile());
            for (String username : new String[]{"alice01", "bob01"}) {
                User user = new User(username, username + "的昵称");
                user.setSalt(com.chat.util.SecurityUtil.generateSalt());
                user.setPasswordHash(com.chat.util.SecurityUtil.hashPassword("123456", user.getSalt()));
                user.setCreateTime(java.time.LocalDateTime.now());
                dao.save(user);
            }
        } catch (Exception e) {
            throw new IllegalStateException("测试用户初始化失败(" + com.chat.common.Config.userFile()
                    + "): " + e, e);
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

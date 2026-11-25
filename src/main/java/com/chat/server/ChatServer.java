package com.chat.server;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.common.MessageType;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.service.AiAssistantService;
import com.chat.service.FileLogService;
import com.chat.service.MessageService;
import com.chat.service.UserService;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 聊天服务器主类，单例管理监听套接字、连接线程池、在线用户、业务服务与服务器观察者。
 */
public final class ChatServer {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".ChatServer");

    /** 会话令牌有效期，单位毫秒。 */
    private static final long SESSION_TOKEN_TTL_MILLIS = 30 * 60 * 1000L;

    /** 会话令牌记录中用户名与过期时间的分隔符。 */
    private static final String TOKEN_SEPARATOR = "|";

    /** 默认路由探测使用的目标地址。 */
    private static final String ROUTE_PROBE_HOST = "8.8.8.8";

    /** 默认路由探测使用的目标端口。 */
    private static final int ROUTE_PROBE_PORT = 53;

    /** 单例持有类。 */
    private static final class Holder {
        private static final ChatServer INSTANCE = new ChatServer();
    }

    private volatile boolean running;

    private ServerSocket serverSocket;

    private ExecutorService connectionPool;

    private ScheduledExecutorService heartbeatScanner;

    private Thread acceptThread;

    private DiscoveryResponder discoveryResponder;

    private final UserManager userManager = new UserManager();

    /** 会话令牌表，键为令牌，值为用户名与过期时间的拼接串。 */
    private final Map<String, String> sessionTokens = new ConcurrentHashMap<>();

    private UserService userService;

    private MessageService messageService;

    private FileLogService fileLogService;

    private AiAssistantService aiAssistant;

    private final List<ClientHandler> handlers = new ArrayList<>();

    private final List<ServerObserver> observers = new java.util.concurrent.CopyOnWriteArrayList<>();

    private int port = Config.serverPort();

    private ChatServer() {
    }

    /**
     * 返回服务器单例。
     *
     * @return 服务器实例
     */
    public static ChatServer getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 启动服务器。
     *
     * @return 启动成功返回 true，配置或端口异常时返回 false
     */
    public synchronized boolean start() {
        if (running) {
            LOGGER.info("服务器已在运行，忽略重复启动");
            return true;
        }
        try {
            // 校验消息加密口令
            if (Config.messageSecret().trim().isEmpty()) {
                String reason = "未配置 security.message.secret，消息加密口令缺失，服务器拒绝启动；"
                        + "请在 config/chat.properties 中配置后重启（口令一旦变更，历史密文将无法解密）";
                LOGGER.severe(reason);
                notify(ServerObserver.EventType.ERROR, reason);
                rollback();
                return false;
            }
            // 初始化业务服务
            initServices();

            if (!userService.isStorageAvailable() || !messageService.isStorageAvailable()) {
                String reason = !userService.isStorageAvailable()
                        ? userService.storageFailureReason() : messageService.storageFailureReason();
                LOGGER.severe("数据库不可用，服务器拒绝启动: " + reason);
                notify(ServerObserver.EventType.ERROR, "数据库不可用，服务器拒绝启动: " + reason);
                rollback();
                return false;
            }
            if (!fileLogService.isStorageAvailable()) {
                LOGGER.warning("文件传输日志表不可用，文件传输将不会写入 chat_file_log: "
                        + fileLogService.storageFailureReason());
            }
            userService.initAdminIfAbsent();
            registerAiAssistant();
            // 创建监听套接字与线程池
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);

            connectionPool = new ThreadPoolExecutor(0, Config.maxConnections(),
                    Constants.WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), namedFactory("chat-worker-"));
            heartbeatScanner = Executors.newSingleThreadScheduledExecutor(namedFactory("chat-heartbeat-"));
            // 启动接受连接、发现应答与心跳扫描
            running = true;
            acceptThread = new Thread(this::acceptLoop, "chat-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            discoveryResponder = new DiscoveryResponder(this, Config.discoveryPort());
            discoveryResponder.start();
            heartbeatScanner.scheduleWithFixedDelay(this::scanIdleConnections,
                    Constants.SCAN_INTERVAL_MS, Constants.SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
            String localAddress = localAddress();
            LOGGER.info(() -> "聊天服务器已启动，监听 " + localAddress + ":" + port);
            notify(ServerObserver.EventType.SERVER_START, "服务器已启动，监听端口 " + port);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "服务器启动失败: " + e.getMessage(), e);
            notify(ServerObserver.EventType.ERROR, "服务器启动失败: " + e.getMessage());
            rollback();
            return false;
        }
    }

    /**
     * 停止服务器并释放资源。
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        LOGGER.info("正在关闭服务器……");
        // 停止接受新连接并关闭发现应答
        closeQuietly(serverSocket);
        if (discoveryResponder != null) {
            discoveryResponder.shutdown();
            discoveryResponder = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        // 断开全部客户端并清空在线用户
        for (ClientHandler handler : new ArrayList<>(handlers)) {
            handler.close();
        }
        handlers.clear();
        userManager.clear();

        // 会话令牌与监听套接字无关，停止时不能清空：控制台的停止与启动只中断连接，
        // 客户端在令牌有效期内重连后仍可恢复会话，令牌失效统一由 TTL 与清理逻辑控制
        // 关闭线程池与智能体服务
        shutdownPool(connectionPool);
        connectionPool = null;
        shutdownPool(heartbeatScanner);
        heartbeatScanner = null;
        shutdownAiAssistant();
        notify(ServerObserver.EventType.SERVER_STOP, "服务器已停止");
        LOGGER.info("服务器已停止");
    }

    /** 回收启动过程中已分配的资源。 */
    private void rollback() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        userManager.clear();
        shutdownPool(connectionPool);
        connectionPool = null;
        shutdownPool(heartbeatScanner);
        heartbeatScanner = null;
        if (discoveryResponder != null) {
            discoveryResponder.shutdown();
            discoveryResponder = null;
        }
        shutdownAiAssistant();
    }

    /** 关闭线程池，超时后强制终止。 */
    private void shutdownPool(ExecutorService pool) {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    private void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "服务套接字关闭异常（忽略）", e);
        }
    }

    /** 循环接受客户端连接并提交到连接线程池。 */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                // 连接数达到上限时拒绝新连接
                if (handlers.size() >= Config.maxConnections()) {
                    LOGGER.warning("连接数达到上限，拒绝新连接: " + socket.getRemoteSocketAddress());
                    socket.close();
                    continue;
                }
                ClientHandler handler = new ClientHandler(socket, this,
                        Config.maxFileSize(), Config.heartbeatTimeoutMs());
                registerHandler(handler);
                connectionPool.execute(handler);
                LOGGER.info(() -> "接受新连接: " + socket.getRemoteSocketAddress()
                        + "，当前连接数 " + handlers.size());
            } catch (SocketException e) {
                if (running) {
                    LOGGER.log(Level.WARNING, "接受连接异常: " + e.getMessage(), e);
                }

                break;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "接受连接失败: " + e.getMessage(), e);
            }
        }
        LOGGER.info("接受连接线程已退出");
    }

    private void registerHandler(ClientHandler handler) {
        synchronized (handlers) {
            handlers.add(handler);
        }
    }

    /** 扫描并断开心跳超时的连接。 */
    private void scanIdleConnections() {
        long now = System.currentTimeMillis();
        List<ClientHandler> snapshot;
        synchronized (handlers) {
            snapshot = new ArrayList<>(handlers);
        }
        for (ClientHandler handler : snapshot) {
            if (handler.isTimeout(now)) {
                LOGGER.warning(() -> "连接心跳超时，强制断开: " + handler);
                handler.close();
            }
        }
    }

    void removeHandler(ClientHandler handler) {
        synchronized (handlers) {
            handlers.remove(handler);
        }
    }

    /** 创建带统一前缀的守护线程工厂。 */
    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 返回本机可供客户端访问的 IPv4 地址。
     *
     * @return 本机地址，无法获取时返回 127.0.0.1
     */
    public String localAddress() {
        // 优先按默认路由取地址，其次枚举网卡
        String routed = routedAddress();
        if (routed != null) {
            return routed;
        }
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || !networkInterface.supportsMulticast()
                        || isVirtualInterface(networkInterface)) {
                    continue;
                }
                for (java.net.InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    InetAddress candidate = address.getAddress();
                    if (candidate instanceof java.net.Inet4Address && !candidate.isLoopbackAddress()
                            && !candidate.isLinkLocalAddress()) {
                        return candidate.getHostAddress();
                    }
                }
            }
        } catch (java.net.SocketException e) {
            LOGGER.log(Level.FINE, "网卡枚举失败，回退 getLocalHost()", e);
        }
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            if (localHost instanceof java.net.Inet4Address && !localHost.isLoopbackAddress()
                    && !localHost.isLinkLocalAddress()) {
                return localHost.getHostAddress();
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "获取本机地址失败，回退 127.0.0.1", e);
        }
        return "127.0.0.1";
    }

    /**
     * 通过默认路由探测本机出口地址。
     *
     * @return 探测到的地址，失败时返回 null
     */
    private String routedAddress() {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.connect(InetAddress.getByName(ROUTE_PROBE_HOST), ROUTE_PROBE_PORT);
            InetAddress local = socket.getLocalAddress();
            if (local instanceof java.net.Inet4Address && !local.isLoopbackAddress()
                    && !local.isLinkLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "按默认路由获取本机地址失败，回退网卡枚举: " + e.getMessage(), e);
        }
        return null;
    }

    /** 根据网卡标记判断是否为虚拟网卡。 */
    private boolean isVirtualInterface(java.net.NetworkInterface networkInterface) {
        if (networkInterface.isVirtual()) {
            return true;
        }
        String name = (networkInterface.getDisplayName() + " " + networkInterface.getName())
                .toLowerCase(java.util.Locale.ROOT);
        String[] markers = {"hyper-v", "vethernet", "virtual", "vmware", "virtualbox", "wsl",
                "docker", "npcap", "wfp", "wan miniport", "wi-fi direct", "bluetooth",
                "kernel debug", "tap-", "tun"};
        for (String marker : markers) {
            if (name.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    /**
     * 设置监听端口。
     *
     * @param port 端口号
     * @throws IllegalStateException 服务器运行中时抛出
     */
    public void setPort(int port) {
        if (running) {
            throw new IllegalStateException("服务器运行中不允许修改端口");
        }
        this.port = port;
    }

    public UserManager getUserManager() {
        return userManager;
    }

    public AiAssistantService getAiAssistant() {
        return aiAssistant;
    }

    /**
     * 把用户提问转交智能体回答。
     *
     * @param asker 提问者用户名
     * @param question 问题内容
     */
    public void askAi(String asker, String question) {
        AiAssistantService service = aiAssistant;
        if (service == null || !service.isEnabled()) {
            return;
        }
        service.ask(asker, question, this::deliverAiReply);
    }

    private void deliverAiReply(String asker, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        TextMessage reply = ChatMessageFactory.text(Constants.AI_USERNAME, asker, text,
                MessageType.TEXT_PRIVATE);
        if (userManager.sendTo(asker, reply)) {
            LOGGER.info(() -> "智能体已回答: " + asker);
        } else {
            LOGGER.info(() -> "智能体答复未能投递，提问者已不在线: " + asker);
        }
    }

    /**
     * 签发会话令牌。
     *
     * @param username 用户名
     * @return 新令牌，用户名为空时返回空串
     */
    public String issueSessionToken(String username) {
        if (username == null || username.trim().isEmpty()) {
            return "";
        }

        purgeExpiredSessionTokens();
        String token = UUID.randomUUID().toString();
        String name = username.trim();
        sessionTokens.put(token, name + TOKEN_SEPARATOR + (System.currentTimeMillis() + SESSION_TOKEN_TTL_MILLIS));
        LOGGER.info(() -> "已签发会话令牌: " + name);
        return token;
    }

    /**
     * 校验会话令牌并顺延有效期。
     *
     * @param token 令牌
     * @return 令牌对应的用户名，无效或过期时返回 null
     */
    public String touchSessionToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        String key = token.trim();
        String value = sessionTokens.get(key);
        if (value == null) {
            return null;
        }
        int separator = value.lastIndexOf(TOKEN_SEPARATOR);
        long expiresAt;
        if (separator <= 0) {
            sessionTokens.remove(key);
            LOGGER.warning("会话令牌记录格式非法，已丢弃");
            return null;
        }
        try {
            expiresAt = Long.parseLong(value.substring(separator + 1));
        } catch (NumberFormatException e) {
            sessionTokens.remove(key);
            LOGGER.warning("会话令牌记录格式非法，已丢弃");
            return null;
        }
        if (System.currentTimeMillis() > expiresAt) {
            sessionTokens.remove(key);
            return null;
        }
        String name = value.substring(0, separator);
        sessionTokens.put(key, name + TOKEN_SEPARATOR + (System.currentTimeMillis() + SESSION_TOKEN_TTL_MILLIS));
        return name;
    }

    /**
     * 清理已过期的会话令牌。
     *
     * @return 清理的令牌数量
     */
    public int purgeExpiredSessionTokens() {
        long now = System.currentTimeMillis();
        int before = sessionTokens.size();
        sessionTokens.values().removeIf(value -> {
            int separator = value.lastIndexOf(TOKEN_SEPARATOR);
            if (separator <= 0) {
                return true;
            }
            try {
                return Long.parseLong(value.substring(separator + 1)) < now;
            } catch (NumberFormatException e) {
                return true;
            }
        });
        int removed = before - sessionTokens.size();
        if (removed > 0) {
            int count = removed;
            LOGGER.info(() -> "已清理过期会话令牌 " + count + " 条");
        }
        return removed;
    }

    private void initServices() {
        this.userService = new UserService();
        this.messageService = new MessageService();
        this.fileLogService = new FileLogService();
        this.aiAssistant = new AiAssistantService();
    }

    private void registerAiAssistant() {
        if (aiAssistant == null || !aiAssistant.isEnabled()) {
            LOGGER.info("智能体小助手未启用：未配置 ai.api.token 或 ai.workflow.id，跳过注册");
            return;
        }
        User assistant = new User(Constants.AI_USERNAME, Constants.AI_DISPLAY_NAME);
        assistant.setRole(Constants.ROLE_USER);
        assistant.setCreateTime(LocalDateTime.now());
        userManager.registerVirtualUser(assistant);
        LOGGER.info(() -> "智能体小助手已上线: " + assistant.getDisplayName()
                + "，接口 " + aiAssistant.getApiUrl());
    }

    private void shutdownAiAssistant() {
        if (aiAssistant != null) {
            aiAssistant.shutdown();
            aiAssistant = null;
        }
    }

    public UserService getUserService() {
        return userService;
    }

    public MessageService getMessageService() {
        return messageService;
    }

    public FileLogService getFileLogService() {
        return fileLogService;
    }

    /**
     * 注册服务器观察者。
     *
     * @param observer 观察者
     */
    public void addObserver(ServerObserver observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    /**
     * 移除服务器观察者。
     *
     * @param observer 观察者
     */
    public void removeObserver(ServerObserver observer) {
        observers.remove(observer);
    }

    /**
     * 通知全部观察者。
     *
     * @param type 事件类型
     * @param content 事件描述
     */
    public void notify(ServerObserver.EventType type, String content) {
        for (ServerObserver observer : observers) {
            try {
                observer.onEvent(type, content);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "观察者回调异常，已忽略", e);
            }
        }
    }

    /**
     * 启动服务器控制台界面。
     *
     * @param args 命令行参数，未使用
     */
    public static void main(String[] args) {
        ChatServer server = ChatServer.getInstance();
        javax.swing.SwingUtilities.invokeLater(() -> {
            ServerUI ui = new ServerUI(server);
            ui.setVisible(true);
        });
    }
}

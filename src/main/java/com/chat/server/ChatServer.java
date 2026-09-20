package com.chat.server;

import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.service.FileLogService;
import com.chat.service.MessageService;
import com.chat.service.UserService;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
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
 * 聊天服务器主类（单例）。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>绑定 TCP 端口，循环接受客户端连接，并把每个连接交给线程池中的
 *       {@link ClientHandler} 处理；</li>
 *   <li>启动 UDP 发现应答器 {@link DiscoveryResponder}，让局域网内客户端能自动找到本服务器；</li>
 *   <li>启动心跳扫描任务，清理已掉线的连接；</li>
 *   <li>持有业务服务与在线用户管理器，作为服务器端唯一的组合根。</li>
 * </ol>
 *
 * <p>设计模式：单例（全局唯一服务器实例），并通过 {@link ServerObserver} 列表
 * 把事件推送给界面层，服务器核心不依赖任何 Swing 类型。</p>
 *
 * <p>线程模型：主线程负责 {@code start()} 与 {@code stop()}；
 * 接受连接由单独的守护线程执行；连接处理使用固定大小线程池；
 * 心跳扫描使用单线程调度池。所有线程均为守护线程，保证 JVM 可正常退出。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class ChatServer {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".ChatServer");

    /** 单例持有者：利用类加载机制保证线程安全的懒加载 */
    private static final class Holder {
        /** 唯一实例 */
        private static final ChatServer INSTANCE = new ChatServer();
    }

    /** 服务器运行状态 */
    private volatile boolean running;

    /** TCP 服务套接字 */
    private ServerSocket serverSocket;

    /** 连接处理线程池 */
    private ExecutorService connectionPool;

    /** 心跳扫描调度器 */
    private ScheduledExecutorService heartbeatScanner;

    /** 接受连接的线程 */
    private Thread acceptThread;

    /** UDP 发现应答器 */
    private DiscoveryResponder discoveryResponder;

    /** 在线用户管理器 */
    private final UserManager userManager = new UserManager();

    /**
     * 用户业务服务（延迟初始化）。
     *
     * <p>刻意不在字段处直接 new：业务服务在构造时会读取数据目录配置，
     * 若单例在配置就绪前被获取（例如仅查询运行状态），就会把数据目录固化到错误的位置。
     * 改为 {@code start()} 时再创建，确保读到的永远是启动时刻的最新配置。</p>
     */
    private UserService userService;

    /** 消息业务服务（延迟初始化，原因同上） */
    private MessageService messageService;

    /** 文件传输日志服务（延迟初始化，原因同上） */
    private FileLogService fileLogService;

    /** 已建立的连接处理器，用于停止服务器时统一关闭 */
    private final List<ClientHandler> handlers = new ArrayList<>();

    /** 服务器事件观察者 */
    private final List<ServerObserver> observers = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 实际监听的端口 */
    private int port = Config.serverPort();

    /** 私有构造，禁止外部实例化 */
    private ChatServer() {
    }

    /**
     * 获取服务器单例。
     *
     * @return 服务器实例
     */
    public static ChatServer getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 启动服务器。
     *
     * <p>启动流程：初始化管理员账号 -> 绑定 TCP 端口 -> 启动接受线程 -> 启动 UDP 应答器 ->
     * 启动心跳扫描。任一步骤失败都会回滚已占用的资源，避免“半启动”状态。</p>
     *
     * @return 启动成功返回 true；端口被占用等失败情况返回 false
     */
    public synchronized boolean start() {
        if (running) {
            LOGGER.info("服务器已在运行，忽略重复启动");
            return true;
        }
        try {
            initServices();
            // 存储只保留数据库，因此数据库不可用时不存在"降级运行"这一选项：
            // 与其让服务器起来却无法注册/登录/查历史，不如明确拒绝启动并说明原因
            if (!userService.isStorageAvailable() || !messageService.isStorageAvailable()) {
                String reason = !userService.isStorageAvailable()
                        ? userService.storageFailureReason() : messageService.storageFailureReason();
                LOGGER.severe("数据库不可用，服务器拒绝启动: " + reason);
                notify(ServerObserver.EventType.ERROR, "数据库不可用，服务器拒绝启动: " + reason);
                rollback();
                return false;
            }
            if (!fileLogService.isStorageAvailable()) {
                // 传输日志属于旁路审计：不可用时给出提示即可，不应阻止服务器启动
                LOGGER.warning("文件传输日志表不可用，文件传输将不会写入 chat_file_log: "
                        + fileLogService.storageFailureReason());
            }
            userService.initAdminIfAbsent();
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            // 采用「按需创建、空闲回收」的线程池：每个连接的处理线程会一直阻塞在读取消息上，
            // 固定大小线程池会让超出线程数的连接永久排队（表现为服务器已接受连接却毫无响应）。
            connectionPool = new ThreadPoolExecutor(0, Config.maxConnections(),
                    Constants.WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), namedFactory("chat-worker-"));
            heartbeatScanner = Executors.newSingleThreadScheduledExecutor(namedFactory("chat-heartbeat-"));
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
     * 停止服务器并释放全部资源。
     *
     * <p>顺序很关键：先置 {@code running=false} 阻止新连接，再关闭监听套接字解除
     * {@code accept()} 阻塞，最后关闭线程池与所有连接。若顺序颠倒，
     * 接受线程可能阻塞在 accept 上无法退出。</p>
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        LOGGER.info("正在关闭服务器……");
        closeQuietly(serverSocket);
        if (discoveryResponder != null) {
            discoveryResponder.shutdown();
            discoveryResponder = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        for (ClientHandler handler : new ArrayList<>(handlers)) {
            handler.close();
        }
        handlers.clear();
        userManager.clear();
        shutdownPool(connectionPool);
        connectionPool = null;
        shutdownPool(heartbeatScanner);
        heartbeatScanner = null;
        notify(ServerObserver.EventType.SERVER_STOP, "服务器已停止");
        LOGGER.info("服务器已停止");
    }

    /**
     * 回滚启动过程中已占用的资源。
     */
    private void rollback() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        shutdownPool(connectionPool);
        connectionPool = null;
        shutdownPool(heartbeatScanner);
        heartbeatScanner = null;
        if (discoveryResponder != null) {
            discoveryResponder.shutdown();
            discoveryResponder = null;
        }
    }

    /**
     * 关闭线程池。
     *
     * @param pool 线程池，可为 null
     */
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
            // 恢复中断标志，交由上层决定是否继续等待
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /**
     * 关闭套接字，忽略异常。
     *
     * @param socket 套接字，可为 null
     */
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

    /**
     * 接受连接的主循环。
     *
     * <p>循环直到 {@code running} 为 false 或套接字被关闭；单次接受失败不退出循环，
     * 避免瞬时异常导致服务器停止服务。</p>
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
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
                // 服务器关闭时 accept 会抛 SocketException，此时正常退出循环
                break;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "接受连接失败: " + e.getMessage(), e);
            }
        }
        LOGGER.info("接受连接线程已退出");
    }

    /**
     * 登记连接处理器。
     *
     * @param handler 连接处理器
     */
    private void registerHandler(ClientHandler handler) {
        synchronized (handlers) {
            handlers.add(handler);
        }
    }

    /**
     * 扫描空闲连接，关闭心跳超时的连接。
     */
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

    /**
     * 从连接列表中移除已关闭的处理器。
     *
     * <p>由 {@link ClientHandler#onConnectionClosed()} 回调，保证长跑服务器不会
     * 因为连接列表持续增长而内存泄漏。</p>
     *
     * @param handler 已关闭的连接处理器
     */
    void removeHandler(ClientHandler handler) {
        synchronized (handlers) {
            handlers.remove(handler);
        }
    }

    /**
     * 创建带自定义名称的线程工厂，便于用 jstack 定位线程。
     *
     * @param prefix 线程名前缀
     * @return 线程工厂
     */
    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 获取本机用于对外通告的局域网地址。
     *
     * <p>取值顺序：优先返回第一个非回环网卡上的 IPv4 地址，全部取不到时再回退
     * {@code InetAddress.getLocalHost()}，最后兜底 127.0.0.1。</p>
     *
     * <p>为什么不直接用 {@code getLocalHost()}：在不少 Linux 发行版上该名称解析到
     * {@code /etc/hosts} 中指向回环地址的记录（例如 127.0.1.1），导致 UDP 自动发现
     * 应答里通告的是回环地址，同机与局域网内的客户端都会连接失败或误判。</p>
     *
     * @return 本机 IP 字符串
     */
    public String localAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || !networkInterface.supportsMulticast()) {
                    continue;
                }
                for (java.net.InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    InetAddress candidate = address.getAddress();
                    if (candidate instanceof java.net.Inet4Address && !candidate.isLoopbackAddress()) {
                        return candidate.getHostAddress();
                    }
                }
            }
        } catch (java.net.SocketException e) {
            LOGGER.log(Level.FINE, "网卡枚举失败，回退 getLocalHost()", e);
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "获取本机地址失败，回退 127.0.0.1", e);
            return "127.0.0.1";
        }
    }

    /**
     * 服务器是否运行中。
     *
     * @return 运行中返回 true
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取监听端口。
     *
     * @return TCP 端口
     */
    public int getPort() {
        return port;
    }

    /**
     * 设置监听端口（仅允许在启动前调用）。
     *
     * @param port 端口号
     */
    public void setPort(int port) {
        if (running) {
            throw new IllegalStateException("服务器运行中不允许修改端口");
        }
        this.port = port;
    }

    /**
     * 获取在线用户管理器。
     *
     * @return 用户管理器
     */
    public UserManager getUserManager() {
        return userManager;
    }

    /**
     * 初始化业务服务。
     *
     * <p>每次 {@code start()} 都重新创建服务实例，使数据目录等配置的变更
     * （特别是测试中通过系统属性切换数据目录）能够被正确感知。</p>
     */
    private void initServices() {
        this.userService = new UserService();
        this.messageService = new MessageService();
        this.fileLogService = new FileLogService();
    }

    /**
     * 获取用户业务服务。
     *
     * @return 用户服务；服务器尚未启动时返回 null
     */
    public UserService getUserService() {
        return userService;
    }

    /**
     * 获取消息业务服务。
     *
     * @return 消息服务；服务器尚未启动时返回 null
     */
    public MessageService getMessageService() {
        return messageService;
    }

    /**
     * 获取文件传输日志服务。
     *
     * @return 文件传输日志服务；服务器尚未启动时返回 null
     */
    public FileLogService getFileLogService() {
        return fileLogService;
    }

    /**
     * 注册服务器事件观察者。
     *
     * @param observer 观察者
     */
    public void addObserver(ServerObserver observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    /**
     * 注销服务器事件观察者。
     *
     * @param observer 观察者
     */
    public void removeObserver(ServerObserver observer) {
        observers.remove(observer);
    }

    /**
     * 向所有观察者推送事件。
     *
     * @param type    事件类型
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
     * 服务器入口：启动带图形控制台的服务器。
     *
     * <p>服务器只保留图形控制台一种启动方式：控制台里能看到在线用户、运行日志与启停按钮，
     * 比纯命令行输出更直观，也不必为同一套功能维护两条启动路径。</p>
     *
     * @param args 命令行参数（当前未使用，保留标准入口签名）
     */
    public static void main(String[] args) {
        ChatServer server = ChatServer.getInstance();
        javax.swing.SwingUtilities.invokeLater(() -> {
            ServerUI ui = new ServerUI(server);
            ui.setVisible(true);
        });
    }
}

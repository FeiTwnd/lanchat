package com.chat.client;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.Config;
import com.chat.common.FileMessage;
import com.chat.common.FileTransferCodec;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;
import com.chat.exception.FileTransferException;
import com.chat.service.FileService;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 聊天客户端（网络层）。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>连接服务器并完成登录；</li>
 *   <li>在独立线程中持续接收消息，通过 {@link ChatListener} 回调界面；</li>
 *   <li>提供发送私聊、群聊、文件、心跳等高层方法，界面层无需接触 Socket 与流；</li>
 *   <li>管理文件发送线程与会话状态（被 {@link FileService} 复用，避免重复实现分块逻辑）。</li>
 * </ol>
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>接收线程 1 个（{@code chat-receiver}），独占 {@link ObjectInputStream}；</li>
 *   <li>发送线程池 1 个（固定 2 线程），串行化 {@link ObjectOutputStream} 的写入；</li>
 *   <li>心跳调度器 1 个，定期发送心跳包并检测心跳应答是否超时；</li>
 *   <li>待确认消息扫描器 1 个（{@code chat-ack-scanner}），按固定周期重发超时未确认的私聊消息；</li>
 *   <li>重连线程按需创建 1 个（{@code chat-reconnect}），连接失效时按退避序列重连并在成功后恢复会话；</li>
 *   <li>文件发送线程按需创建，避免大文件阻塞聊天消息。</li>
 * </ul>
 *
 * <p>可靠性说明：私聊消息带稳定 {@code messageId}，未在 5 秒内收到 {@code MSG_ACK} 会复用同一标识重发，
 * 因此服务端可以幂等去重；接收侧用有界的已渲染标识集合去重，实现"至少一次投递 + 本地去重"。
 * 断线后自动指数退避重连，并用登录时服务端下发的会话令牌恢复登录态（令牌只保存在内存中）。</p>
 *
 * <p>设计说明：本类同时被 GUI 客户端与集成测试使用，
 * 因此不包含任何 Swing 依赖，测试可直接以“无界面客户端”方式驱动。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class ChatClient {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".ChatClient");

    /**
     * 私聊消息等待确认的超时时间（毫秒）。
     *
     * <p>取 5 秒：局域网内一次往返通常在毫秒级，5 秒既能容忍偶发卡顿，
     * 又不会让"消息发不出去"这件事长时间没有反馈。该值刻意不放进 {@code Constants}：
     * 本轮协议契约要求两侧各自定义私有常量，避免并行开发时改同一文件产生冲突。</p>
     */
    private static final long ACK_TIMEOUT_MS = 5000L;

    /** 私聊消息在首次发送之后允许的最大重发次数；超过仍未确认即标记失败 */
    private static final int MAX_RETRY_COUNT = 3;

    /** 待确认表扫描周期（毫秒），决定状态反馈的实时程度 */
    private static final long ACK_SCAN_INTERVAL_MS = 1000L;

    /** 已渲染消息标识的容量上限 */
    private static final int RENDERED_ID_LIMIT = 2000;

    /** 连续多少个心跳周期收不到应答即判定连接失效 */
    private static final int HEARTBEAT_MISS_LIMIT = 3;

    /** 重连退避序列（毫秒），超出序列长度后按最后一个值（30 秒）持续重试 */
    private static final long[] RECONNECT_BACKOFF_MS = {1000L, 2000L, 4000L, 8000L, 16000L, 30000L};

    /** 会话恢复应答的等待时间（毫秒） */
    private static final long RESUME_TIMEOUT_MS = 5000L;

    /** ACK 状态：接收方在线且已转发 */
    private static final String ACK_STATE_DELIVERED = "DELIVERED";

    /** ACK 状态：接收方离线，已入库待补投 */
    private static final String ACK_STATE_OFFLINE = "OFFLINE";

    /** ACK 状态：该 messageId 已处理过（幂等，按已送达处理） */
    private static final String ACK_STATE_DUPLICATE = "DUPLICATE";

    /** ACK 状态：既未送达也未入库 */
    private static final String ACK_STATE_FAILED = "FAILED";

    /** 会话恢复成功的应答正文 */
    private static final String RESUME_OK = "OK";

    /** 会话恢复失败（令牌过期）的应答前缀 */
    private static final String RESUME_EXPIRED_PREFIX = "EXPIRED";

    /** 历史记录分页请求使用的默认每页条数；与设计文档保持一致 */
    private static final String DEFAULT_PAGE_SIZE = "50";

    /** 消息监听器列表 */
    private final List<ChatListener> listeners = new CopyOnWriteArrayList<>();

    /** 文件业务服务，复用于分块与校验逻辑 */
    private final FileService fileService = new FileService();

    /** 待发送文件会话：传输编号 -> 请求消息 */
    private final Map<String, FileMessage> pendingSends = new ConcurrentHashMap<>();

    /** 已完成/进行中的文件接收会话：传输编号 -> 请求消息（提供文件名与大小用于进度展示） */
    private final Map<String, FileMessage> receivingSessions = new ConcurrentHashMap<>();

    /** 接收文件保存目录 */
    private String receiveDir = Config.receivedDir();

    /** Socket 连接 */
    private Socket socket;

    /** 对象输出流 */
    private ObjectOutputStream out;

    /** 对象输入流 */
    private ObjectInputStream in;

    /** 接收线程 */
    private Thread receiverThread;

    /** 发送线程池 */
    private ExecutorService senderPool;

    /** 心跳调度器 */
    private ScheduledExecutorService heartbeatScheduler;

    /** 待确认消息扫描器（单线程即可：扫描只做重发判断，不需要并发） */
    private ScheduledExecutorService ackScheduler;

    /** 私聊待确认表：messageId -> 待确认项，重发与状态标记都以此为准 */
    private final Map<String, PendingMessage> pendingMessages = new ConcurrentHashMap<>();

    /**
     * 已渲染消息标识集合（上限 {@value #RENDERED_ID_LIMIT}，按插入顺序淘汰最旧）。
     *
     * <p>为什么要用带淘汰顺序的 {@link LinkedHashMap}：重连与离线补投可能让同一条消息到达两次，
     * 必须去重；而集合若永不清理，长时间运行的客户端会持续增长直至内存耗尽。
     * 淘汰最旧而非最新，是因为最旧的消息再次到达的概率最低。</p>
     */
    private final Set<String> renderedMessageIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(64, 0.75f, false) {

                /** 序列化版本号（本集合不参与序列化，仅为消除编译告警） */
                private static final long serialVersionUID = 20260920L;

                /**
                 * 超过上限时淘汰最早插入的标识。
                 *
                 * @param eldest 最早插入的条目
                 * @return 需要淘汰返回 true
                 */
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > RENDERED_ID_LIMIT;
                }
            }));

    /** 已发送私聊消息的接收者：messageId -> 对端用户名，供界面把发送状态路由到对应会话窗口 */
    private final Map<String, String> sentMessagePeers = new ConcurrentHashMap<>();

    /** 发送状态监听器列表 */
    private final List<MessageStateListener> stateListeners = new CopyOnWriteArrayList<>();

    /** 是否处于重连流程中；界面据此区分"正在重连"与"已彻底断开" */
    private volatile boolean reconnecting;

    /** 触发重连的原因，重连期间反复展示 */
    private volatile String reconnectReason = "与服务器的连接已断开";

    /** 重连线程；用户主动退出时会被中断，保证不留残余线程 */
    private Thread reconnectThread;

    /** 重连状态的保护锁，保证同一时刻只有一个重连线程 */
    private final Object reconnectLock = new Object();

    /** 用户是否已主动退出；置位后重连循环必须立即终止 */
    private volatile boolean userClosed;

    /** 会话恢复应答闩，非空表示当前正在等待 SESSION_RESUME 应答 */
    private volatile CountDownLatch resumeLatch;

    /** 会话恢复应答正文，取值为 {@code OK} 或 {@code EXPIRED|<原因>} */
    private volatile String resumeResult;

    /** 会话令牌，只保存在内存，禁止落盘与写入日志 */
    private volatile String sessionToken;

    /** 最近一次收到心跳应答的时刻（毫秒），用于判断连接是否已经失效 */
    private volatile long lastHeartbeatAckAt;

    /** 是否处于已登录的会话中；控制心跳的发送时机，未登录时不发心跳 */
    private volatile boolean sessionActive;

    /**
     * 是否曾经登录成功（或恢复成功）。
     *
     * <p>重连的前提是"有一个可以恢复的会话"：登录界面在真正登录之前也可能连上服务器，
     * 此时若断线就自动重连，会在登录窗口背后制造一个用户既看不见也无法停止的重连循环。</p>
     */
    private volatile boolean sessionEstablished;

    /** 连接状态 */
    private volatile boolean connected;

    /** 当前登录用户名 */
    private volatile String username;

    /** 当前登录昵称，登录成功后由服务器下发 */
    private volatile String nickname;

    /** 最近一次登录失败原因，供界面与自动化测试读取 */
    private volatile String lastLoginFailure = "";

    /** 登录结果等待锁，便于命令行与测试同步等待登录完成（volatile：由接收线程计数、工作线程等待） */
    private transient volatile CountDownLatch loginLatch;

    /** 最近一次连接的服务器地址，供退出登录后回填登录窗口 */
    private volatile String serverHost = "";

    /** 最近一次连接的服务器端口，供退出登录后回填登录窗口 */
    private volatile int serverPort;

    /**
     * 消息发送状态。
     *
     * <p>刻意定义为 {@link ChatClient} 的公开嵌套枚举，而不是新建模型类：
     * 状态只在"网络层通知界面"这一处流转，单独建类会多一层没有实际价值的抽象。</p>
     */
    public enum SendState {

        /** 已发出，等待服务端确认 */
        SENDING("发送中"),
        /** 服务端确认接收方在线且已转发 */
        DELIVERED("已送达"),
        /** 接收方离线，消息已入库，待其上线后补投 */
        OFFLINE("对方离线"),
        /** 重发次数耗尽或服务端明确回执失败 */
        FAILED("发送失败");

        /** 状态的中文描述，用于界面展示 */
        private final String description;

        SendState(String description) {
            this.description = description;
        }

        /**
         * 获取状态的中文描述。
         *
         * @return 中文描述，例如“已送达”
         */
        public String getDescription() {
            return description;
        }
    }

    /**
     * 消息发送状态监听器。
     *
     * <p>由界面层实现：网络层只负责判定状态变化，具体如何展示交给界面决定，
     * 这样 {@link ChatClient} 可以继续保持零 Swing 依赖，能被集成测试直接驱动。</p>
     */
    public interface MessageStateListener {

        /**
         * 某条私聊消息的发送状态发生变化。
         *
         * <p>回调发生在网络接收线程或扫描线程中，实现方更新界面前必须自行切换到事件分发线程。
         * 本方法可能对同一个 {@code messageId} 被多次调用（发送中 -> 已送达/对方离线/发送失败）。</p>
         *
         * @param messageId 稳定消息标识
         * @param state     新状态
         * @param detail    补充说明，无补充时为空字符串
         */
        void onMessageState(String messageId, SendState state, String detail);
    }

    /**
     * 构造客户端。
     */
    public ChatClient() {
        // 无状态初始化，连接时再创建线程与流
    }

    /**
     * 建立与服务器的连接（不发送任何业务请求）。
     *
     * <p>把"连接"与"登录"拆成两步，是因为登录界面还需要在未登录状态下做两件事：
     * 注册新账号、修改密码（需先登录校验原密码）。若连接时隐式带上登录请求，
     * 注册会把一个尚不存在的账号拿去登录，修改密码则会让用户凭一次改密动作"顺便上线"，
     * 在其它客户端的在线列表里留下一个看不见的幽灵用户。</p>
     *
     * @param host 服务器地址
     * @param port 服务器端口
     * @return 连接成功返回 true；失败时通过监听器回调原因
     */
    public boolean openConnection(String host, int port) {
        if (connected) {
            LOGGER.warning("客户端已连接，忽略重复连接请求");
            return true;
        }
        String failure = openSocket(host, port);
        if (failure != null) {
            notifyConnection(false, failure);
            return false;
        }
        startWorkers();
        notifyConnection(true, "已连接服务器 " + host + ":" + port);
        LOGGER.info(() -> "已连接服务器: " + host + ":" + port);
        return true;
    }

    /**
     * 建立底层连接并启动接收线程（不创建任何线程池）。
     *
     * <p>从 {@link #openConnection(String, int)} 中抽出来，是为了让重连流程复用同一段建连逻辑：
     * 重连只需要重新建连与会话恢复，不需要重建已经存在的发送池与调度器，
     * 否则每重连一次就多出一组常驻线程。</p>
     *
     * @param host 服务器地址
     * @param port 服务器端口
     * @return 成功返回 null；失败返回可直接展示给用户的中文原因
     */
    private String openSocket(String host, int port) {
        try {
            Socket newSocket = new Socket();
            newSocket.connect(new InetSocketAddress(host, port), Constants.CONNECT_TIMEOUT_MS);
            newSocket.setTcpNoDelay(true);
            newSocket.setKeepAlive(true);
            // 先构造输出流并 flush，再构造输入流，避免两端同时等待对象流头部
            ObjectOutputStream newOut = new ObjectOutputStream(newSocket.getOutputStream());
            newOut.flush();
            ObjectInputStream newIn = new ObjectInputStream(newSocket.getInputStream());
            // 绑定反序列化白名单必须紧跟在创建之后：服务器返回的第一帧就可能携带恶意类型，
            // 任意一次 readObject 之前都必须完成绑定，否则过滤器形同虚设
            newIn.setObjectInputFilter(ChatMessageFactory.serializationFilter());
            socket = newSocket;
            out = newOut;
            in = newIn;
            serverHost = host;
            serverPort = port;
            lastHeartbeatAckAt = System.currentTimeMillis();
            connected = true;
            receiverThread = new Thread(this::receiveLoop, "chat-receiver");
            receiverThread.setDaemon(true);
            receiverThread.start();
            return null;
        } catch (IOException e) {
            closeQuietly();
            LOGGER.log(Level.WARNING, "连接服务器失败: " + host + ":" + port + "（" + e.getMessage() + "）", e);
            return "无法连接服务器 " + host + ":" + port + "（" + e.getMessage() + "）";
        }
    }

    /**
     * 创建发送线程池、心跳调度器与待确认消息扫描器。
     *
     * <p>只在首次连接时调用一次：重连复用这些线程，避免线程数量随重连次数增长。</p>
     */
    private void startWorkers() {
        senderPool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "chat-sender");
            thread.setDaemon(true);
            return thread;
        });
        ackScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-ack-scanner");
            thread.setDaemon(true);
            return thread;
        });
        ackScheduler.scheduleWithFixedDelay(this::scanPendingMessages,
                ACK_SCAN_INTERVAL_MS, ACK_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        startHeartbeat();
    }

    /**
     * 在已建立的连接上发送登录请求。
     *
     * @param name     用户名
     * @param password 明文密码
     * @return 请求已提交返回 true；尚未连接时返回 false
     */
    public boolean login(String name, String password) {
        if (!connected || out == null) {
            notifyMessage(ChatMessageFactory.error(username, "尚未连接服务器，无法登录"));
            return false;
        }
        this.username = name;
        // 先重置等待锁再发送登录请求，避免服务器响应比锁初始化更快导致永久等待
        loginLatch = new CountDownLatch(1);
        lastLoginFailure = "";
        return send(loginMessage(name, password));
    }

    /**
     * 连接服务器并发送登录请求。
     *
     * @param host     服务器地址
     * @param port     服务器端口
     * @param name     用户名
     * @param password 明文密码
     * @return 连接成功返回 true；失败时通过监听器回调原因
     */
    public boolean connect(String host, int port, String name, String password) {
        return openConnection(host, port) && login(name, password);
    }

    /**
     * 构造登录请求消息。
     *
     * @param name     用户名
     * @param password 密码
     * @return 登录消息，正文格式为 {@code 用户名|密码}
     */
    private TextMessage loginMessage(String name, String password) {
        TextMessage message = ChatMessageFactory.text(name, Constants.SYSTEM_SENDER,
                name + "|" + password, MessageType.TEXT_PRIVATE);
        message.setType(MessageType.LOGIN);
        return message;
    }

    /**
     * 发送注册请求。
     *
     * <p>注册与登录共用同一条连接前的请求通道，通过独立的消息类型区分，
     * 这样登录界面的“注册”按钮无需先建立会话即可使用。</p>
     *
     * @param name     用户名
     * @param password 密码
     * @param nickname 昵称
     * @return 发送成功返回 true
     */
    public boolean sendRegister(String name, String password, String nickname) {
        TextMessage message = ChatMessageFactory.text(name, Constants.SYSTEM_SENDER,
                name + "|" + password + "|" + (nickname == null ? "" : nickname), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.REGISTER);
        return send(message);
    }

    /**
     * 接收循环：持续读取服务器消息并回调监听器。
     *
     * <p>读取失败即视为连接失效，交由 {@link #handleConnectionLost(String)} 决定是重连还是彻底断开；
     * 本方法自身的 {@code finally} 只负责关闭本次连接的流，关闭后重连线程会新建一组流。</p>
     */
    private void receiveLoop() {
        try {
            while (connected) {
                Object received = in.readObject();
                if (received instanceof Message) {
                    handleIncoming((Message) received);
                }
            }
        } catch (java.io.EOFException | java.net.SocketException e) {
            handleConnectionLost("与服务器的连接已断开");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "接收消息异常: " + e.getMessage(), e);
            handleConnectionLost("接收消息失败: " + e.getMessage());
        } catch (ClassNotFoundException e) {
            LOGGER.warning("无法识别的消息类型，可能客户端版本不一致: " + e.getMessage());
        } finally {
            connected = false;
            closeQuietly();
        }
    }

    /**
     * 处理收到的消息。
     *
     * <p>处理顺序说明：心跳应答、消息确认、会话恢复应答都属于协议控制信息，
     * 必须先于"是否重复"的判断处理并提前返回，否则它们会流到界面层，
     * 在状态栏里刷出"收到消息: 心跳应答"之类的噪音。</p>
     *
     * <p>文件数据块先交给本地接收会话落盘，再继续回调界面，
     * 保证界面读到的进度与磁盘状态一致。</p>
     *
     * @param message 消息
     */
    private void handleIncoming(Message message) {
        MessageType type = message.getType();
        if (type == MessageType.HEARTBEAT_ACK) {
            lastHeartbeatAckAt = System.currentTimeMillis();
            return;
        }
        if (type == MessageType.MSG_ACK) {
            handleMessageAck(message);
            return;
        }
        if (type == MessageType.SESSION_RESUME) {
            handleSessionResume(message);
            return;
        }
        if (type == MessageType.OFFLINE_MESSAGE) {
            // 必须先回确认再判断是否重复：若因重复而跳过确认，
            // 服务端会一直认为该消息未投递，从而在每次上线时无限重发
            acknowledgeOfflineMessage(message);
        }
        if (!acceptIncoming(message)) {
            LOGGER.fine(() -> "忽略重复消息，不再渲染: " + message.getMessageId());
            return;
        }
        if (type == MessageType.LOGIN_RESULT) {
            handleLoginResult(message);
        }
        if (type == MessageType.FILE_CHUNK) {
            handleIncomingChunk((FileMessage) message);
            return;
        }
        if (type == MessageType.FILE_END) {
            handleIncomingEnd((FileMessage) message);
            return;
        }
        if (type == MessageType.FILE_ACCEPT) {
            handleIncomingAccept((FileMessage) message);
        }
        notifyMessage(message);
    }

    /**
     * 判断入站消息是否应当渲染（按 {@code messageId} 去重）。
     *
     * <p>只对承载聊天内容的消息去重：控制类报文数量少且每次语义独立，
     * 若一并去重，一旦服务端复用标识就可能丢掉登录结果或用户列表这类关键响应。</p>
     *
     * @param message 消息
     * @return 首次到达返回 true；重复返回 false
     */
    private boolean acceptIncoming(Message message) {
        MessageType type = message.getType();
        if (type != MessageType.TEXT_PRIVATE && type != MessageType.TEXT_GROUP
                && type != MessageType.OFFLINE_MESSAGE) {
            return true;
        }
        String messageId = message.getMessageId();
        if (messageId == null || messageId.trim().isEmpty()) {
            // 旧版服务端不下发稳定标识，无法去重；宁可重复渲染也不要丢消息
            return true;
        }
        synchronized (renderedMessageIds) {
            return renderedMessageIds.add(messageId);
        }
    }

    /**
     * 处理服务端下发的消息确认。
     *
     * <p>正文格式 {@code messageId|state|detail}，其中 {@code detail} 可为空。
     * 收到确认即从待确认表移除，扫描线程不会再重发；{@code DUPLICATE} 表示服务端此前已处理过，
     * 与 {@code DELIVERED} 同样停止重试。</p>
     *
     * @param message 确认消息
     */
    private void handleMessageAck(Message message) {
        if (!(message instanceof TextMessage)) {
            LOGGER.warning("消息确认的实际类型不是文本消息，已忽略");
            return;
        }
        String content = ((TextMessage) message).getContent();
        // 限制切分次数，保证 detail 中若含竖线也能完整保留在最后一个字段
        String[] fields = content == null ? new String[0] : content.split("\\|", 3);
        if (fields.length < 2 || fields[0].trim().isEmpty()) {
            LOGGER.warning("消息确认格式非法，已忽略");
            return;
        }
        String messageId = fields[0].trim();
        String state = fields[1].trim();
        String detail = fields.length > 2 ? fields[2] : "";
        PendingMessage pending = pendingMessages.remove(messageId);
        if (pending != null) {
            sentMessagePeers.remove(messageId);
        }
        if (ACK_STATE_DELIVERED.equals(state) || ACK_STATE_DUPLICATE.equals(state)) {
            notifyMessageState(messageId, SendState.DELIVERED, detail);
        } else if (ACK_STATE_OFFLINE.equals(state)) {
            notifyMessageState(messageId, SendState.OFFLINE, detail);
        } else if (ACK_STATE_FAILED.equals(state)) {
            notifyMessageState(messageId, SendState.FAILED, detail);
        } else {
            LOGGER.warning(() -> "未知的消息确认状态，已按发送失败处理: " + state);
            notifyMessageState(messageId, SendState.FAILED, detail);
        }
    }

    /**
     * 处理会话恢复相关消息。
     *
     * <p>同一个消息类型承载两种语义：登录成功后服务端下发的会话令牌（正文即令牌），
     * 以及重连时服务端对恢复请求的应答（正文为 {@code OK} 或 {@code EXPIRED|<原因>}）。
     * 用"当前是否正在等待应答"来区分，可以把协议的消息类型数量控制在最小。</p>
     *
     * @param message 会话恢复消息
     */
    private void handleSessionResume(Message message) {
        if (!(message instanceof TextMessage)) {
            return;
        }
        String content = ((TextMessage) message).getContent();
        String value = content == null ? "" : content.trim();
        CountDownLatch latch = resumeLatch;
        if (latch != null) {
            resumeResult = value;
            latch.countDown();
            return;
        }
        if (value.isEmpty() || RESUME_OK.equals(value) || value.startsWith(RESUME_EXPIRED_PREFIX)) {
            LOGGER.warning("收到没有对应请求的会话恢复应答，已忽略");
            return;
        }
        // 令牌只保存在内存字段中，不写日志、不落盘
        sessionToken = value;
        LOGGER.info("已接收会话令牌，断线后可用其恢复会话");
    }

    /**
     * 为收到的离线消息回确认，服务端据此把消息标记为已投递。
     *
     * @param message 离线消息
     */
    private void acknowledgeOfflineMessage(Message message) {
        String messageId = message.getMessageId();
        if (messageId == null || messageId.trim().isEmpty()) {
            return;
        }
        TextMessage ack = ChatMessageFactory.text(username, Constants.SYSTEM_SENDER,
                messageId + "|" + ACK_STATE_DELIVERED + "|", MessageType.TEXT_PRIVATE);
        ack.setType(MessageType.MSG_ACK);
        send(ack);
    }

    /**
     * 处理收到的文件数据块。
     *
     * @param chunk 数据块消息
     */
    private void handleIncomingChunk(FileMessage chunk) {
        if (!receivingSessions.containsKey(chunk.getTransferId())) {
            LOGGER.warning(() -> "收到未知传输会话的数据块，已忽略: " + chunk.getTransferId());
            return;
        }
        try {
            double before = fileService.progressOf(chunk.getTransferId());
            fileService.appendChunk(chunk);
            double after = fileService.progressOf(chunk.getTransferId());
            // 进度变化超过 1% 才回调，避免每个数据块都触发一次界面重绘
            if (after - before >= 0.01 || after >= 1.0) {
                notifyMessage(progressMessage(chunk, after));
            }
        } catch (FileTransferException e) {
            LOGGER.log(Level.WARNING, "接收数据块失败: " + e.getMessage(), e);
            finishFailedTransfer(chunk, e.getMessage());
        }
    }

    /**
     * 处理文件传输结束消息：校验并存盘。
     *
     * @param end 结束消息
     */
    private void handleIncomingEnd(FileMessage end) {
        FileMessage request = receivingSessions.remove(end.getTransferId());
        if (request == null) {
            return;
        }
        try {
            File saved = fileService.finishReceive(end);
            String text = "文件已保存到 " + saved.getAbsolutePath()
                    + "（" + com.chat.util.FileUtil.humanSize(saved.length()) + "）";
            FileMessage result = resultMessage(request, end.getSender(), true, text);
            sendSync(result);
            notifyMessage(result);
        } catch (FileTransferException e) {
            // 接收失败同样必须回执，否则发送方会一直等待确认
            FileMessage failure = resultMessage(request, end.getSender(), false,
                    "文件接收失败: " + e.getMessage());
            try {
                sendSync(failure);
            } catch (IOException ioException) {
                LOGGER.log(Level.FINE, "失败回执发送失败（连接可能已断开）", ioException);
            }
            notifyMessage(failure);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "文件结果回执发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造文件传输结果消息。
     *
     * <p>方向约定：结果消息永远由“本客户端”发出、发给对端，
     * 因此发送者固定为当前登录用户，接收者是对端用户名。
     * 不能从源消息“借”sender/receiver 字段：源消息在本端与对端眼中的方向恰好相反，
     * 借字段会导致结果被投递回自己（本项目中确实踩过这个坑，故显式传参）。</p>
     *
     * @param source  原始文件消息（提供传输编号与文件元信息）
     * @param peer    对端用户名
     * @param success 是否成功
     * @param text    结果描述
     * @return 结果消息
     */
    private FileMessage resultMessage(FileMessage source, String peer, boolean success, String text) {
        FileMessage message = ChatMessageFactory.file(username, peer, MessageType.FILE_RESULT);
        message.setTransferId(source.getTransferId());
        message.setFileName(source.getFileName());
        message.setFileSize(source.getFileSize());
        message.setSha256(source.getSha256());
        message.setAccepted(success);
        message.setMessage(text);
        return message;
    }

    /**
     * 处理接收方的同意应答：启动文件发送线程。
     *
     * @param accept 同意消息
     */
    private void handleIncomingAccept(FileMessage accept) {
        FileMessage request = pendingSends.remove(accept.getTransferId());
        if (request == null) {
            LOGGER.warning(() -> "收到未知传输的同意应答: " + accept.getTransferId());
            return;
        }
        Thread sender = new Thread(() -> sendFileChunks(request, accept.getSender()),
                "chat-file-sender-" + request.getTransferId().substring(0, 8));
        sender.setDaemon(true);
        sender.start();
    }

    /**
     * 依次发送文件全部分块并发送结束帧。
     *
     * @param request   文件请求消息
     * @param receiver  接收者用户名
     */
    private void sendFileChunks(FileMessage request, String receiver) {
        try {
            for (int index = 0; index < request.getTotalChunks(); index++) {
                if (!connected) {
                    throw new FileTransferException("连接已断开，文件发送中止", request.getTransferId());
                }
                FileMessage chunk = fileService.createChunkMessage(username, receiver, request, index);
                if (chunk == null) {
                    break;
                }
                sendSync(chunk);
                notifyMessage(progressMessage(chunk, (double) (index + 1) / request.getTotalChunks()));
            }
            sendSync(fileService.createEndMessage(username, receiver, request));
            LOGGER.info(() -> "文件发送完成: " + request.getFileName());
        } catch (FileTransferException e) {
            LOGGER.log(Level.WARNING, "文件发送失败: " + e.getMessage(), e);
            notifyMessage(resultMessage(request, receiver, false, "文件发送失败: " + e.getMessage()));
        } catch (IOException e) {
            // 分块写出失败意味着本次传输无法继续，按业务失败处理并回执界面
            LOGGER.log(Level.WARNING, "文件分块发送失败: " + e.getMessage(), e);
            notifyMessage(resultMessage(request, receiver, false, "文件发送失败: " + e.getMessage()));
        } finally {
            fileService.closeSend(request.getTransferId());
        }
    }

    /**
     * 构造一条仅用于本地界面刷新的进度消息。
     *
     * @param source   源文件消息
     * @param progress 进度比例
     * @return 携带进度的文件消息
     */
    private FileMessage progressMessage(FileMessage source, double progress) {
        FileMessage message = ChatMessageFactory.file(source.getSender(), source.getReceiver(),
                MessageType.FILE_CHUNK);
        message.setTransferId(source.getTransferId());
        message.setFileName(source.getFileName());
        message.setFileSize(source.getFileSize());
        message.setTotalChunks(source.getTotalChunks());
        message.setChunkIndex(source.getChunkIndex());
        // 进度通过 message 字段传递：data 字段承载二进制块，不适合再复用，
        // 而新增一个 double 字段会让每帧都多传 8 字节且污染协议，故采用文本形式
        message.setMessage(String.valueOf(progress));
        return message;
    }

    /**
     * 接收失败时清理并通知界面。
     *
     * @param source 源消息
     * @param reason 失败原因
     */
    private void finishFailedTransfer(FileMessage source, String reason) {
        receivingSessions.remove(source.getTransferId());
        fileService.cancelReceive(source.getTransferId());
        FileMessage failure = resultMessage(source, source.getSender(), false, "文件接收失败: " + reason);
        notifyMessage(failure);
        // 回执同样要发给发送方：与结束帧失败的处理保持一致。
        // 只通知本端界面会让发送方一直停在"传输中"，服务器也无从记录这次失败的传输
        send(failure);
    }

    /**
     * 异步发送一条消息。
     *
     * @param message 消息
     * @return 已提交发送任务返回 true
     */
    public boolean send(Message message) {
        if (!connected || out == null) {
            notifyMessage(ChatMessageFactory.error(username, "尚未连接服务器，消息未发送"));
            return false;
        }
        if (senderPool == null || senderPool.isShutdown()) {
            return false;
        }
        // 每条出站消息都带稳定标识：服务端据此去重，确认与离线补投也依赖它
        message.ensureMessageId();
        senderPool.execute(() -> {
            try {
                writeObject(message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "消息发送失败: " + e.getMessage(), e);
                notifyMessage(ChatMessageFactory.error(username, "消息发送失败: " + e.getMessage()));
            }
        });
        return true;
    }

    /**
     * 同步发送一条消息，用于必须保证顺序的文件分块。
     *
     * @param message 消息
     * @return 发送成功返回 true
     * @throws IOException 写入失败时抛出
     */
    public boolean sendSync(Message message) throws IOException {
        if (!connected || out == null) {
            throw new IOException("尚未连接服务器");
        }
        message.ensureMessageId();
        writeObject(message);
        return true;
    }

    /**
     * 写入对象并刷新缓冲。
     *
     * <p>对象流不是线程安全的，因此用 {@code out} 自身作为监视器串行化写入；
     * 同时周期性 {@code reset()}，避免长时间连接持有已发送对象的强引用。</p>
     *
     * @param message 消息
     * @throws IOException 写入失败时抛出
     */
    private void writeObject(Message message) throws IOException {
        synchronized (out) {
            out.writeObject(message);
            out.flush();
            out.reset();
        }
    }

    /**
     * 发送私聊文本消息。
     *
     * <p>私聊消息会登记到待确认表：服务端对每条私聊回 {@code MSG_ACK}，
     * 客户端据此实现超时重发与状态展示。群聊是广播语义，没有单一接收方，因此不做等待确认。</p>
     *
     * @param receiver 接收者用户名
     * @param content  正文
     * @return 发送成功返回 true
     */
    public boolean sendPrivateText(String receiver, String content) {
        return sendPrivateText(receiver, content, null, null);
    }

    /**
     * 发送带引用信息的私聊文本消息。
     *
     * <p>仍然走 {@link #sendTracked} 这条唯一通道：引用只是随消息一起发送的附加信息，
     * 不能因为它另开一条发送路径，否则这条消息会丢掉确认登记、超时重发与发送状态展示。</p>
     *
     * @param receiver     接收者用户名
     * @param content      正文
     * @param quoteId      被引用消息的稳定标识，可为 null
     * @param quoteSummary 被引用消息的摘要，可为 null
     * @return 发送成功返回 true
     */
    public boolean sendPrivateText(String receiver, String content, String quoteId, String quoteSummary) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        if (content.length() > Constants.MESSAGE_MAX_LENGTH) {
            notifyMessage(ChatMessageFactory.error(username, "消息过长，最多 "
                    + Constants.MESSAGE_MAX_LENGTH + " 个字符"));
            return false;
        }
        return sendTracked(buildText(username, receiver, content, MessageType.TEXT_PRIVATE,
                quoteId, quoteSummary));
    }

    /**
     * 构造一条出站文本消息。
     *
     * <p>不带引用时仍走 {@link ChatMessageFactory}：那里统一分配本地消息编号，
     * 让日志与调试信息能看出先后顺序；带引用时必须用五参构造器，因为引用字段是 final，
     * 只能在构造时确定。两条路径最终都交给同一个发送通道。</p>
     *
     * @param sender       发送者用户名
     * @param receiver     接收者用户名，群聊传空字符串
     * @param content      正文
     * @param type         消息类型
     * @param quoteId      被引用消息的稳定标识，可为 null
     * @param quoteSummary 被引用消息的摘要，可为 null
     * @return 文本消息
     */
    private TextMessage buildText(String sender, String receiver, String content, MessageType type,
                                  String quoteId, String quoteSummary) {
        if (quoteId == null && quoteSummary == null) {
            return ChatMessageFactory.text(sender, receiver, content, type);
        }
        TextMessage message = new TextMessage(sender, receiver, content, quoteId, quoteSummary);
        message.setType(type);
        return message;
    }

    /**
     * 发送需要确认的私聊消息：先登记待确认项，再写入连接。
     *
     * <p>登记必须先于写入：若先写入再登记，服务端的确认可能在登记之前就回到接收线程，
     * 那条确认会因为找不到待确认项而被当成"未知确认"丢弃，消息随后被误判为超时。</p>
     *
     * @param message 待发送的私聊消息
     * @return 发送成功返回 true
     */
    private boolean sendTracked(TextMessage message) {
        String messageId = message.ensureMessageId();
        pendingMessages.put(messageId, new PendingMessage(message));
        sentMessagePeers.put(messageId, message.getReceiver());
        notifyMessageState(messageId, SendState.SENDING, "");
        if (send(message)) {
            return true;
        }
        // 未连接时立即给出失败结论，避免界面一直停留在"发送中"
        failPending(messageId, "尚未连接服务器");
        return false;
    }

    /**
     * 发送群聊消息。
     *
     * @param content 正文
     * @return 发送成功返回 true
     */
    public boolean sendGroupText(String content) {
        return sendGroupText(content, null, null);
    }

    /**
     * 发送带引用信息的群聊文本消息。
     *
     * <p>群聊是广播语义，没有单一接收方，因此服务端不会回 {@code MSG_ACK}，
     * 这里也不做待确认登记；引用信息随消息一起广播给全部在线成员。</p>
     *
     * @param content      正文
     * @param quoteId      被引用消息的稳定标识，可为 null
     * @param quoteSummary 被引用消息的摘要，可为 null
     * @return 发送成功返回 true
     */
    public boolean sendGroupText(String content, String quoteId, String quoteSummary) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        if (content.length() > Constants.MESSAGE_MAX_LENGTH) {
            notifyMessage(ChatMessageFactory.error(username, "消息过长，最多 "
                    + Constants.MESSAGE_MAX_LENGTH + " 个字符"));
            return false;
        }
        return send(buildText(username, "", content, MessageType.TEXT_GROUP, quoteId, quoteSummary));
    }

    /**
     * 发起文件传输请求。
     *
     * @param receiver 接收者用户名
     * @param file     待发送文件
     * @return 成功时返回传输编号；失败返回 null
     */
    public String sendFile(String receiver, File file) {
        try {
            FileMessage request = fileService.prepareSend(username, receiver, file);
            pendingSends.put(request.getTransferId(), request);
            TextMessage wrapper = ChatMessageFactory.text(username, receiver,
                    FileTransferCodec.encode(request), MessageType.TEXT_PRIVATE);
            wrapper.setType(MessageType.FILE_REQUEST);
            send(wrapper);
            return request.getTransferId();
        } catch (FileTransferException e) {
            notifyMessage(ChatMessageFactory.error(username, "文件发送失败: " + e.getMessage()));
            return null;
        }
    }

    /**
     * 应答收到的文件请求。
     *
     * @param request  文件请求（来自服务器转发的文本消息，需先经 {@link #decodeRequest} 还原）
     * @param accepted 是否同意接收
     * @param reason   拒绝原因，同意时传空字符串
     * @return 发送成功返回 true
     */
    public boolean respondFileRequest(FileMessage request, boolean accepted, String reason) {
        if (request == null) {
            return false;
        }
        FileMessage response = ChatMessageFactory.file(username, request.getSender(),
                accepted ? MessageType.FILE_ACCEPT : MessageType.FILE_REJECT);
        response.setTransferId(request.getTransferId());
        response.setFileName(request.getFileName());
        response.setFileSize(request.getFileSize());
        response.setTotalChunks(request.getTotalChunks());
        response.setSha256(request.getSha256());
        response.setAccepted(accepted);
        response.setMessage(reason == null ? "" : reason);
        if (accepted) {
            try {
                fileService.openReceive(receiveDir, response);
                receivingSessions.put(request.getTransferId(), response);
            } catch (FileTransferException e) {
                notifyMessage(ChatMessageFactory.error(username, "无法接收文件: " + e.getMessage()));
                return false;
            }
        }
        return send(response);
    }

    /**
     * 从 FILE_REQUEST 文本消息还原文件请求对象。
     *
     * @param message 服务器转发来的文本消息
     * @return 文件请求对象；格式非法时返回 null
     */
    public static FileMessage decodeRequest(TextMessage message) {
        if (message == null) {
            return null;
        }
        return FileTransferCodec.decode(message.getContent(), message.getSender(), message.getReceiver());
    }

    /**
     * 请求刷新在线用户列表。
     *
     * @return 发送成功返回 true
     */
    public boolean requestUserList() {
        SystemMessage message = ChatMessageFactory.control(username, Constants.SYSTEM_SENDER,
                MessageType.USER_LIST_REQUEST);
        return send(message);
    }

    /**
     * 请求历史聊天记录。
     *
     * @param target   目标用户名，为空表示查询全部
     * @param fromDate 起始日期，格式 yyyy-MM-dd，可为空
     * @param toDate   结束日期，格式 yyyy-MM-dd，可为空
     * @return 发送成功返回 true
     */
    public boolean requestHistory(String target, String fromDate, String toDate) {
        return requestHistory(target, fromDate, toDate, "");
    }

    /**
     * 请求历史聊天记录（可限定会话对象）。
     *
     * @param target   目标用户名，为空表示查询全部
     * @param fromDate 起始日期，格式 yyyy-MM-dd，可为空
     * @param toDate   结束日期，格式 yyyy-MM-dd，可为空
     * @param peer     会话对象用户名；非空时只返回 target 与该用户之间的私聊记录，
     *                 为空时保持"本人全部收发 + 广播"的旧语义
     * @return 发送成功返回 true
     */
    public boolean requestHistory(String target, String fromDate, String toDate, String peer) {
        return requestHistory(target, fromDate, toDate, peer, "", DEFAULT_PAGE_SIZE);
    }

    /**
     * 请求历史聊天记录（键集分页）。
     *
     * <p>为什么用"上一页最小主键"而不是页码偏移：本项目的聊天记录会持续增长，
     * 用 OFFSET 翻页时数据库必须先扫过前面所有行，越翻越慢；按主键向前取一页
     * 每次都能走索引，代价恒定。首次加载传空游标，服务端返回最新一页。</p>
     *
     * @param target   目标用户名，为空表示查询全部
     * @param fromDate 起始日期，格式 yyyy-MM-dd，可为空
     * @param toDate   结束日期，格式 yyyy-MM-dd，可为空
     * @param peer     会话对象用户名；非空时只返回 target 与该用户之间的私聊记录
     * @param beforeId 上一页最小主键，空串表示取最新一页
     * @param pageSize 每页条数，空串表示由服务端取默认值
     * @return 发送成功返回 true
     */
    public boolean requestHistory(String target, String fromDate, String toDate, String peer,
                                  String beforeId, String pageSize) {
        // 追加两个字段而不是新增消息类型：旧版服务端只读取前四个字段，
        // 多出来的内容会被忽略，因此新旧两端可以各自升级而不会互相打断
        String body = (target == null ? "" : target) + "|" + (fromDate == null ? "" : fromDate)
                + "|" + (toDate == null ? "" : toDate) + "|" + (peer == null ? "" : peer)
                + "|" + (beforeId == null ? "" : beforeId)
                + "|" + (pageSize == null || pageSize.isEmpty() ? DEFAULT_PAGE_SIZE : pageSize);
        TextMessage message = ChatMessageFactory.text(username, Constants.SYSTEM_SENDER, body,
                MessageType.TEXT_PRIVATE);
        message.setType(MessageType.HISTORY_REQUEST);
        return send(message);
    }

    /**
     * 请求按关键字搜索本人的聊天记录。
     *
     * <p>搜索在服务端完成：正文以密文入库，只有服务端能在解密后做匹配，
     * 客户端不持有任何数据库连接。</p>
     *
     * @param keyword 关键字，空白串直接拒绝
     * @return 发送成功返回 true
     */
    public boolean requestSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return false;
        }
        TextMessage message = ChatMessageFactory.text(username, Constants.SYSTEM_SENDER,
                keyword.trim(), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.SEARCH_REQUEST);
        return send(message);
    }

    /**
     * 请求撤回自己发出的某条私聊消息。
     *
     * <p>是否允许撤回（例如是否在时限内）由服务端判定，
     * 客户端只负责提交请求并展示服务端回执，避免两端各写一套规则后互相矛盾。</p>
     *
     * @param messageId 稳定消息标识
     * @return 发送成功返回 true
     */
    public boolean requestRecall(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) {
            return false;
        }
        TextMessage message = ChatMessageFactory.text(username, Constants.SYSTEM_SENDER,
                messageId.trim(), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.MSG_RECALL);
        return send(message);
    }

    /**
     * 请求导出本人的全部聊天记录。
     *
     * <p>导出内容由服务端查库并渲染后回传，客户端只负责把文本写到自己的磁盘上。
     * 客户端刻意不持有数据库连接：换一台机器登录，同样能导出自己的记录。</p>
     *
     * @return 发送成功返回 true
     */
    public boolean requestExport() {
        return send(ChatMessageFactory.control(username, Constants.SYSTEM_SENDER,
                MessageType.EXPORT_REQUEST));
    }

    /**
     * 发送退出登录请求。
     *
     * <p>刻意使用同步发送：退出后马上就要关闭连接，若走异步发送队列，
     * 报文可能还没写出连接就断了，服务器只能等探测到 socket 关闭才清理在线状态。
     * 虽然最终结果一样，但其它客户端要多等一个心跳周期才能看到下线通知。</p>
     *
     * @return 发送成功返回 true；未连接或写入失败返回 false
     */
    public boolean sendLogout() {
        // 退出登录意味着本次会话就此结束：先关掉重连开关，
        // 否则服务端处理完 LOGOUT 关闭连接后，客户端会立刻开始重连并再次占用该账号
        userClosed = true;
        if (!connected || out == null) {
            return false;
        }
        try {
            return sendSync(ChatMessageFactory.control(username, Constants.SYSTEM_SENDER,
                    MessageType.LOGOUT));
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "退出登录请求发送失败（连接可能已断开）", e);
            return false;
        }
    }

    /**
     * 获取最近一次连接的服务器地址。
     *
     * @return 服务器地址；从未连接过时为空字符串
     */
    public String getServerHost() {
        return serverHost;
    }

    /**
     * 获取最近一次连接的服务器端口。
     *
     * @return 服务器端口；从未连接过时为 0
     */
    public int getServerPort() {
        return serverPort;
    }

    /**
     * 启动心跳任务：定期发送心跳，并在连续多个周期收不到应答时判定连接失效。
     *
     * <p>为什么用"收不到应答"而不是"写失败"来判定断线：TCP 连接在对端主机掉电、
     * 网线拔出等情况下不会立刻报错，写入仍会成功进入内核缓冲；只有应答的缺失
     * 才能在有限时间内暴露连接已经不可用。</p>
     */
    private void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Config.heartbeatIntervalMs();
        heartbeatScheduler.scheduleWithFixedDelay(() -> {
            if (!sessionActive || !connected) {
                return;
            }
            if (System.currentTimeMillis() - lastHeartbeatAckAt > interval * HEARTBEAT_MISS_LIMIT) {
                handleConnectionLost("连续 " + HEARTBEAT_MISS_LIMIT + " 个心跳周期未收到应答");
                return;
            }
            send(ChatMessageFactory.control(username, Constants.SYSTEM_SENDER, MessageType.HEARTBEAT));
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * 扫描待确认消息：超时未确认的消息重发，重发次数耗尽的标记失败。
     *
     * <p>由单线程调度器周期执行，因此不需要额外加锁；对共享表使用弱一致的遍历拷贝，
     * 避免在遍历过程中因接收线程移除条目而抛异常。</p>
     */
    private void scanPendingMessages() {
        long now = System.currentTimeMillis();
        for (String messageId : new ArrayList<>(pendingMessages.keySet())) {
            PendingMessage pending = pendingMessages.get(messageId);
            if (pending == null || now - pending.lastSentAt < ACK_TIMEOUT_MS) {
                continue;
            }
            if (pending.retryCount >= MAX_RETRY_COUNT) {
                failPending(messageId, "对方长时间未确认，已重发 " + MAX_RETRY_COUNT + " 次");
                continue;
            }
            if (!connected || out == null) {
                // 断线期间不重发：既写不出去，也会白白消耗重发次数；重连成功后计时自然到期即会重发
                continue;
            }
            pending.retryCount++;
            pending.lastSentAt = now;
            LOGGER.info(() -> "消息超过 " + (ACK_TIMEOUT_MS / 1000) + " 秒未确认，第 "
                    + pending.retryCount + " 次重发同一 messageId");
            try {
                writeObject(pending.message);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "待确认消息重发失败（连接可能已断开）", e);
            }
        }
    }

    /**
     * 把一条待确认消息标记为发送失败并通知界面。
     *
     * @param messageId 稳定消息标识
     * @param detail    失败原因
     */
    private void failPending(String messageId, String detail) {
        pendingMessages.remove(messageId);
        sentMessagePeers.remove(messageId);
        notifyMessageState(messageId, SendState.FAILED, detail);
    }

    /**
     * 处理连接失效：进入重连流程，或在用户已退出时静默结束。
     *
     * <p>关键顺序：先把 {@code reconnecting} 置位并关闭旧连接，再启动重连线程。
     * 先置位可以让接收线程因旧连接被关闭而再次触发本方法时直接返回，避免拉起第二个重连线程；
     * 先关旧连接则能让阻塞在 {@code readObject} 上的接收线程立即退出，
     * 否则重连线程无法安全地复用同一组流字段。</p>
     *
     * @param reason 失效原因，会展示在界面上
     */
    private void handleConnectionLost(String reason) {
        if (userClosed) {
            return;
        }
        if (!sessionEstablished) {
            // 尚未建立可恢复的会话（例如仍停留在登录窗口），只上报断开，不擅自重连
            connected = false;
            sessionActive = false;
            closeQuietly();
            notifyConnection(false, reason);
            return;
        }
        synchronized (reconnectLock) {
            if (reconnecting) {
                return;
            }
            reconnecting = true;
        }
        connected = false;
        sessionActive = false;
        reconnectReason = reason;
        LOGGER.warning(() -> "连接已失效: " + reason + "，进入重连流程");
        closeQuietly();
        Thread thread = new Thread(this::reconnectLoop, "chat-reconnect");
        thread.setDaemon(true);
        synchronized (reconnectLock) {
            if (userClosed) {
                reconnecting = false;
                return;
            }
            reconnectThread = thread;
        }
        thread.start();
    }

    /**
     * 重连循环：按退避序列反复尝试建连并恢复会话。
     *
     * <p>循环终止条件只有三个：会话恢复成功、会话已过期、用户主动退出。
     * 其余失败一律继续重试，符合"持续重试直到成功或用户主动退出"的契约。</p>
     */
    private void reconnectLoop() {
        int attempt = 0;
        try {
            while (!userClosed) {
                joinReceiverThread();
                attempt++;
                notifyConnection(false, reconnectReason + "，正在重连（第 " + attempt + " 次）");
                if (!sleepQuietly(backoffOf(attempt))) {
                    return;
                }
                if (userClosed) {
                    return;
                }
                String failure = openSocket(serverHost, serverPort);
                if (failure != null) {
                    continue;
                }
                if (resumeSession()) {
                    return;
                }
                // 本次建连成功但恢复失败：关闭它，下一轮重新建连，避免在半可用连接上继续等待
                connected = false;
                closeQuietly();
            }
        } finally {
            synchronized (reconnectLock) {
                reconnecting = false;
                reconnectThread = null;
            }
        }
    }

    /**
     * 用会话令牌请求服务端恢复登录态。
     *
     * <p>令牌只从内存字段读取，全过程不写日志、不落盘：它等价于一次登录凭据，
     * 泄露即可被他人冒用。</p>
     *
     * @return 重连流程应当结束（已恢复或已确定会话过期）返回 true；仅本次尝试失败返回 false
     */
    private boolean resumeSession() {
        String token = sessionToken;
        if (token == null || token.isEmpty()) {
            // 没有令牌就无法恢复：继续重试也没有意义，按会话过期处理并让用户重新登录
            LOGGER.warning("未持有会话令牌，无法恢复会话");
            finishSessionExpired();
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        resumeResult = null;
        resumeLatch = latch;
        try {
            TextMessage request = ChatMessageFactory.text(username, Constants.SYSTEM_SENDER, token,
                    MessageType.TEXT_PRIVATE);
            request.setType(MessageType.SESSION_RESUME);
            sendSync(request);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "会话恢复请求发送失败: " + e.getMessage(), e);
            resumeLatch = null;
            return false;
        }
        boolean answered;
        try {
            answered = latch.await(RESUME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            resumeLatch = null;
        }
        if (!answered) {
            LOGGER.warning("等待会话恢复应答超时，稍后重试");
            return false;
        }
        String result = resumeResult == null ? "" : resumeResult;
        if (RESUME_OK.equals(result)) {
            sessionActive = true;
            sessionEstablished = true;
            lastHeartbeatAckAt = System.currentTimeMillis();
            LOGGER.info("会话已恢复，重新拉取在线用户列表");
            // 断线期间的用户上下线变化已错过，恢复后必须重新取一份最新快照
            requestUserList();
            notifyConnection(true, "已重新连接，会话已恢复");
            return true;
        }
        if (result.startsWith(RESUME_EXPIRED_PREFIX)) {
            // 只记录原因，不记录应答里可能携带的其它内容
            LOGGER.warning("会话恢复被拒绝，停止重连");
            finishSessionExpired();
            return true;
        }
        // 刻意不打印应答正文：万一服务端把新令牌当作应答正文返回，正文入日志就会泄露凭据
        LOGGER.warning("会话恢复应答无法识别，稍后重试");
        return false;
    }

    /**
     * 结束重连并提示用户重新登录。
     *
     * <p>复用既有的断开通知路径：界面收到"未连接"事件后会走原有提示与退出登录界面的处理，
     * 不额外新增弹窗层级。先把 {@code reconnecting} 清掉，界面才能把它当成终态而非重连中间态。</p>
     */
    private void finishSessionExpired() {
        synchronized (reconnectLock) {
            reconnecting = false;
        }
        connected = false;
        sessionActive = false;
        closeQuietly();
        notifyConnection(false, "会话已过期，请重新登录");
    }

    /**
     * 等待上一轮的接收线程退出。
     *
     * <p>接收线程的 {@code finally} 会关闭"当前字段上的流"。若它在新连接建立之后才执行，
     * 就会把刚建好的新连接关掉；因此在每次建连前显式等待它结束。
     * 旧连接已在 {@link #handleConnectionLost(String)} 中关闭，这里通常只会等待极短时间。</p>
     */
    private void joinReceiverThread() {
        Thread thread = receiverThread;
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(Constants.CONNECT_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 计算第 n 次重连前的退避时间。
     *
     * @param attempt 重连次数，从 1 开始
     * @return 退避毫秒数；超出序列长度后返回序列最后一个值
     */
    private long backoffOf(int attempt) {
        int index = Math.min(attempt, RECONNECT_BACKOFF_MS.length) - 1;
        return RECONNECT_BACKOFF_MS[Math.max(index, 0)];
    }

    /**
     * 可中断的休眠。
     *
     * @param millis 休眠毫秒数
     * @return 正常结束返回 true；被中断返回 false（调用方应立即结束重连）
     */
    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 注册发送状态监听器。
     *
     * @param listener 监听器
     */
    public void addMessageStateListener(MessageStateListener listener) {
        if (listener != null && !stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }

    /**
     * 注销发送状态监听器。
     *
     * @param listener 监听器
     */
    public void removeMessageStateListener(MessageStateListener listener) {
        stateListeners.remove(listener);
    }

    /**
     * 回调所有监听器的发送状态事件。
     *
     * @param messageId 稳定消息标识
     * @param state     状态
     * @param detail    补充说明
     */
    private void notifyMessageState(String messageId, SendState state, String detail) {
        String text = detail == null ? "" : detail;
        for (MessageStateListener listener : stateListeners) {
            try {
                listener.onMessageState(messageId, state, text);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "监听器处理发送状态异常，已忽略", e);
            }
        }
    }

    /**
     * 查询某个已发送消息的接收者，供界面把发送状态路由到对应会话窗口。
     *
     * @param messageId 稳定消息标识
     * @return 接收者用户名；消息已完成（或不是本端发送的私聊消息）时返回 null
     */
    public String getMessageReceiver(String messageId) {
        return messageId == null ? null : sentMessagePeers.get(messageId);
    }

    /**
     * 查询某条"本端刚发出"消息的正文。
     *
     * <p>供界面在撤回时把气泡与消息标识对应起来：界面上渲染的本地回显只带正文，
     * 不带标识，需要借助待确认表把两者关联。消息确认到达后待确认项即被移除，
     * 因此本方法只对刚发送、尚未确认的消息有效——正好覆盖"2 分钟内可撤回"的场景。</p>
     *
     * @param messageId 稳定消息标识
     * @return 正文；消息不存在、已确认或不是文本消息时返回 null
     */
    public String getMessageContent(String messageId) {
        PendingMessage pending = messageId == null ? null : pendingMessages.get(messageId);
        if (pending == null || !(pending.message instanceof TextMessage)) {
            return null;
        }
        return ((TextMessage) pending.message).getContent();
    }

    /**
     * 是否正在重连。
     *
     * @return 重连流程进行中返回 true
     */
    public boolean isReconnecting() {
        return reconnecting;
    }

    /**
     * 待确认的私聊消息。
     *
     * <p>只保存消息对象、重发次数与上次发送时刻三项——重发必须复用同一个消息对象，
     * 这样 {@code messageId} 与时间戳都不会变化，服务端才能据此识别为同一条消息。</p>
     */
    private static final class PendingMessage {

        /** 待确认的消息对象 */
        private final Message message;

        /** 已重发次数 */
        private int retryCount;

        /** 最近一次发送时刻（毫秒） */
        private long lastSentAt;

        /**
         * 构造待确认项。
         *
         * @param message 消息对象
         */
        private PendingMessage(Message message) {
            this.message = message;
            this.lastSentAt = System.currentTimeMillis();
        }
    }

    /**
     * 注册消息监听器。
     *
     * @param listener 监听器
     */
    public void addListener(ChatListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 注销消息监听器。
     *
     * @param listener 监听器
     */
    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }

    /**
     * 回调所有监听器的消息事件。
     *
     * @param message 消息
     */
    private void notifyMessage(Message message) {
        for (ChatListener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "监听器处理消息异常，已忽略", e);
            }
        }
    }

    /**
     * 回调所有监听器的连接状态事件。
     *
     * @param connected 是否连接
     * @param reason    原因
     */
    private void notifyConnection(boolean connected, String reason) {
        for (ChatListener listener : listeners) {
            try {
                listener.onConnectionChanged(connected, reason);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "监听器处理连接事件异常，已忽略", e);
            }
        }
    }

    /**
     * 关闭连接并释放全部线程资源。
     *
     * <p>先把 {@link #userClosed} 置位再动手清理：重连循环与接收线程都以该标志为终止条件，
     * 这样"用户主动退出"不会被随后到来的连接断开事件误判为需要重连，
     * 重连线程也会在退避休眠中被立刻中断，不留残余线程。</p>
     */
    public void close() {
        userClosed = true;
        if (!connected && senderPool == null && !reconnecting) {
            return;
        }
        connected = false;
        sessionActive = false;
        synchronized (reconnectLock) {
            if (reconnectThread != null) {
                reconnectThread.interrupt();
                reconnectThread = null;
            }
        }
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
        if (ackScheduler != null) {
            ackScheduler.shutdownNow();
            ackScheduler = null;
        }
        if (senderPool != null) {
            senderPool.shutdownNow();
            senderPool = null;
        }
        pendingMessages.clear();
        sentMessagePeers.clear();
        closeQuietly();
        notifyConnection(false, "已断开与服务器的连接");
        LOGGER.info("客户端已关闭");
    }

    /**
     * 静默关闭网络资源。
     */
    private void closeQuietly() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "输出流关闭异常（忽略）", e);
        }
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "输入流关闭异常（忽略）", e);
        }
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "socket 关闭异常（忽略）", e);
        }
        out = null;
        in = null;
        socket = null;
    }

    /**
     * 是否已连接。
     *
     * @return 连接中返回 true
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 获取当前登录用户名。
     *
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * 获取当前登录昵称。
     *
     * @return 昵称；登录结果尚未到达时返回用户名
     */
    public String getNickname() {
        return nickname == null ? username : nickname;
    }

    /**
     * 设置当前登录昵称，由登录结果解析处调用。
     *
     * @param nickname 昵称
     */
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * 获取接收文件保存目录。
     *
     * @return 目录路径
     */
    public String getReceiveDir() {
        return receiveDir;
    }

    /**
     * 设置接收文件保存目录。
     *
     * @param receiveDir 目录路径
     */
    public void setReceiveDir(String receiveDir) {
        if (receiveDir != null && !receiveDir.trim().isEmpty()) {
            this.receiveDir = receiveDir.trim();
        }
    }

    /**
     * 获取文件服务实例（供界面查询进度）。
     *
     * @return 文件服务
     */
    public FileService getFileService() {
        return fileService;
    }

    /**
     * 获取待发送会话编号列表，用于测试断言。
     *
     * @return 传输编号列表
     */
    public List<String> pendingTransferIds() {
        return new ArrayList<>(pendingSends.keySet());
    }

    /**
     * 解析服务器下发的登录结果正文。
     *
     * @param content 登录结果正文，格式 {@code 成功标志|说明|用户名|昵称|角色}
     * @return 长度为 2 的数组 {@code [是否成功, 说明]}；格式非法时返回 {@code ["0", "登录响应格式错误"]}
     */
    public static String[] parseLoginResult(String content) {
        if (content == null || content.isEmpty()) {
            return new String[]{"0", "登录响应为空"};
        }
        String[] parts = content.split("\\|", -1);
        String success = parts.length > 0 ? parts[0] : "0";
        String reason = parts.length > 1 ? parts[1] : "";
        return new String[]{success, reason};
    }

    /**
     * 从登录结果正文中解析用户资料。
     *
     * @param content 登录结果正文，格式 {@code 成功标志|说明|用户名|昵称|角色}
     * @return 长度为 3 的数组 {@code [用户名, 昵称, 角色]}；解析失败返回 null
     */
    public static String[] parseLoginUser(String content) {
        if (content == null) {
            return null;
        }
        String[] parts = content.split("\\|", -1);
        if (parts.length < 5) {
            return null;
        }
        return new String[]{parts[2], parts[3], parts[4]};
    }

    /**
     * 解析服务器下发的注册结果正文，语义与登录结果一致。
     *
     * @param content 注册结果正文
     * @return 长度为 2 的数组 {@code [是否成功, 说明]}
     */
    public static String[] parseRegisterResult(String content) {
        return parseLoginResult(content);
    }

    /**
     * 处理登录结果：记录失败原因并唤醒等待中的线程。
     *
     * @param message 登录结果消息
     */
    private void handleLoginResult(Message message) {
        if (!(message instanceof TextMessage)) {
            return;
        }
        String[] result = parseLoginResult(((TextMessage) message).getContent());
        String[] user = parseLoginUser(((TextMessage) message).getContent());
        if ("1".equals(result[0])) {
            if (user != null) {
                this.username = user[0];
                this.nickname = user[1];
            }
            lastLoginFailure = "";
            // 登录成功后心跳才有意义：服务端的掉线判定只针对已登录会话
            sessionActive = true;
            sessionEstablished = true;
            lastHeartbeatAckAt = System.currentTimeMillis();
        } else {
            lastLoginFailure = result[1];
        }
        if (loginLatch != null) {
            loginLatch.countDown();
        }
    }

    /**
     * 等待登录结果返回。
     *
     * <p>主要服务于命令行模式与自动化测试；图形界面通过监听器异步处理，
     * 不需要阻塞等待。</p>
     *
     * @param timeoutMillis 超时时间
     * @return 登录成功返回 true；超时或登录失败返回 false
     */
    public boolean waitForLoginResult(long timeoutMillis) {
        CountDownLatch latch = loginLatch;
        if (latch == null) {
            return false;
        }
        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                lastLoginFailure = "等待登录结果超时";
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastLoginFailure = "等待登录结果被中断";
            return false;
        }
        return lastLoginFailure.isEmpty() && connected;
    }

    /**
     * 等待满足条件的消息到达。
     *
     * <p>实现方式：临时注册一个监听器，用 {@link java.util.concurrent.LinkedBlockingQueue}
     * 把回调转换为阻塞取用；条件命中或超时后自动注销监听器，不会造成监听器泄漏。</p>
     *
     * @param condition     消息匹配条件
     * @param timeoutMillis 超时时间
     * @return 命中的消息；超时返回 null
     */
    public Message waitForMessage(Predicate<Message> condition, long timeoutMillis) {
        java.util.concurrent.LinkedBlockingQueue<Message> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        ChatListener waiter = new ChatListener() {
            /**
             * 命中条件时把消息放入队列。
             *
             * @param message 消息
             */
            @Override
            public void onMessage(Message message) {
                if (condition.test(message)) {
                    queue.offer(message);
                }
            }

            /**
             * 连接状态回调，本等待器不需要处理。
             *
             * @param connected 是否连接
             * @param reason    原因
             */
            @Override
            public void onConnectionChanged(boolean connected, String reason) {
                // 等待器只关心消息
            }
        };
        addListener(waiter);
        try {
            return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            removeListener(waiter);
        }
    }

    /**
     * 获取最近一次登录失败原因。
     *
     * @return 失败原因；登录成功或尚未登录时为空字符串
     */
    public String getLastLoginFailure() {
        return lastLoginFailure;
    }

    /**
     * 输出客户端状态摘要。
     *
     * @return 形如 {@code ChatClient{user=alice, connected=true}} 的字符串
     */
    @Override
    public String toString() {
        return "ChatClient{user=" + username + ", connected=" + connected + "}";
    }
}

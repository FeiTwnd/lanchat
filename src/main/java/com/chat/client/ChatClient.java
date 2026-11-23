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
 * 客户端网络层，负责连接服务器、收发消息、处理文件传输以及断线重连与会话恢复。
 */
public class ChatClient {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".ChatClient");

    /** 等待消息确认的超时时间，单位毫秒。 */
    private static final long ACK_TIMEOUT_MS = 5000L;

    /** 消息未确认时的最大重发次数。 */
    private static final int MAX_RETRY_COUNT = 3;

    /** 待确认消息的扫描间隔，单位毫秒。 */
    private static final long ACK_SCAN_INTERVAL_MS = 1000L;

    /** 已渲染消息 ID 的保留上限，用于去重。 */
    private static final int RENDERED_ID_LIMIT = 2000;

    /** 连续多少个心跳周期未收到应答即判定连接失效。 */
    private static final int HEARTBEAT_MISS_LIMIT = 3;

    /** 每次重连前的退避等待时间，单位毫秒。 */
    private static final long[] RECONNECT_BACKOFF_MS = {1000L, 2000L, 4000L, 8000L, 16000L, 30000L};

    /** 等待会话恢复应答的超时时间，单位毫秒。 */
    private static final long RESUME_TIMEOUT_MS = 5000L;

    private static final String ACK_STATE_DELIVERED = "DELIVERED";

    private static final String ACK_STATE_OFFLINE = "OFFLINE";

    private static final String ACK_STATE_DUPLICATE = "DUPLICATE";

    private static final String ACK_STATE_FAILED = "FAILED";

    private static final String RESUME_OK = "OK";

    private static final String RESUME_EXPIRED_PREFIX = "EXPIRED";

    /** 历史消息的默认分页条数。 */
    private static final String DEFAULT_PAGE_SIZE = "50";

    private final List<ChatListener> listeners = new CopyOnWriteArrayList<>();

    private final FileService fileService = new FileService();

    /** 已发出、等待对方同意的文件传输请求，键为传输 ID。 */
    private final Map<String, FileMessage> pendingSends = new ConcurrentHashMap<>();

    /** 正在接收的文件传输会话，键为传输 ID。 */
    private final Map<String, FileMessage> receivingSessions = new ConcurrentHashMap<>();

    private String receiveDir = Config.receivedDir();

    private Socket socket;

    private ObjectOutputStream out;

    private ObjectInputStream in;

    private com.chat.common.MessageFilter inputFilter;

    private Thread receiverThread;

    private ExecutorService senderPool;

    private ScheduledExecutorService heartbeatScheduler;

    private ScheduledExecutorService ackScheduler;

    private final Map<String, PendingMessage> pendingMessages = new ConcurrentHashMap<>();

    /** 已渲染的文本消息 ID 集合，超出上限时淘汰最旧的记录。 */
    private final Set<String> renderedMessageIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
                private static final long serialVersionUID = 20260920L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > RENDERED_ID_LIMIT;
                }
            }));

    /** 消息 ID 到接收者的映射，供撤回等操作使用。 */
    private final Map<String, String> sentMessagePeers = new ConcurrentHashMap<>();

    private final List<MessageStateListener> stateListeners = new CopyOnWriteArrayList<>();

    private volatile boolean reconnecting;

    private volatile String reconnectReason = "与服务器的连接已断开";

    private Thread reconnectThread;

    private final Object reconnectLock = new Object();

    private volatile boolean userClosed;

    private volatile CountDownLatch resumeLatch;

    private volatile String resumeResult;

    private volatile String sessionToken;

    private volatile long lastHeartbeatAckAt;

    private volatile boolean sessionActive;

    private volatile boolean sessionEstablished;

    private volatile boolean connected;

    private volatile String username;

    private volatile String nickname;

    private volatile String lastLoginFailure = "";

    private transient volatile CountDownLatch loginLatch;

    private volatile String serverHost = "";

    private volatile int serverPort;

    /**
     * 单条消息的发送状态。
     */
    public enum SendState {
        SENDING("发送中"),

        DELIVERED("已送达"),

        OFFLINE("对方离线"),

        FAILED("发送失败");

        private final String description;

        SendState(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 发送状态变化回调。
     */
    public interface MessageStateListener {
        /**
         * 消息状态变化时回调。
         *
         * @param messageId 消息 ID
         * @param state 新的发送状态
         * @param detail 状态说明，没有说明时为空字符串
         */
        void onMessageState(String messageId, SendState state, String detail);
    }

    public ChatClient() {
    }

    /**
     * 建立到服务器的连接并启动收发线程。
     *
     * @param host 服务器主机
     * @param port 服务器端口
     * @return 连接成功返回 true
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

    /** 创建套接字与对象流并启动接收线程，失败时返回错误说明。 */
    private String openSocket(String host, int port) {
        try {
            // 建立连接，关闭 Nagle 并开启保活
            Socket newSocket = new Socket();
            newSocket.connect(new InetSocketAddress(host, port), Constants.CONNECT_TIMEOUT_MS);
            newSocket.setTcpNoDelay(true);
            newSocket.setKeepAlive(true);

            ObjectOutputStream newOut = new ObjectOutputStream(newSocket.getOutputStream());
            newOut.flush();
            ObjectInputStream newIn = new ObjectInputStream(newSocket.getInputStream());

            // 安装反序列化过滤器，限制可接收的类
            inputFilter = com.chat.common.MessageFilter.forClientInput();
            newIn.setObjectInputFilter(inputFilter);
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

    /** 启动发送线程池、确认扫描任务与心跳任务。 */
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
        // 心跳与确认扫描都由守护线程执行
        startHeartbeat();
    }

    /**
     * 发送登录请求。
     *
     * @param name 用户名
     * @param password 密码
     * @return 请求已进入发送队列返回 true
     */
    public boolean login(String name, String password) {
        if (!connected || out == null) {
            notifyMessage(ChatMessageFactory.error(username, "尚未连接服务器，无法登录"));
            return false;
        }
        this.username = name;

        loginLatch = new CountDownLatch(1);
        lastLoginFailure = "";
        return send(loginMessage(name, password));
    }

    /**
     * 先建立连接再发送登录请求。
     *
     * @param host 服务器主机
     * @param port 服务器端口
     * @param name 用户名
     * @param password 密码
     * @return 两步都成功返回 true
     */
    public boolean connect(String host, int port, String name, String password) {
        return openConnection(host, port) && login(name, password);
    }

    private TextMessage loginMessage(String name, String password) {
        TextMessage message = ChatMessageFactory.text(name, Constants.SYSTEM_SENDER,
                name + "|" + password, MessageType.TEXT_PRIVATE);
        message.setType(MessageType.LOGIN);
        return message;
    }

    /**
     * 发送注册请求。
     *
     * @param name 用户名
     * @param password 密码
     * @param nickname 昵称，可以为空
     * @return 请求已进入发送队列返回 true
     */
    public boolean sendRegister(String name, String password, String nickname) {
        TextMessage message = ChatMessageFactory.text(name, Constants.SYSTEM_SENDER,
                name + "|" + password + "|" + (nickname == null ? "" : nickname), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.REGISTER);
        return send(message);
    }

    /** 循环读取服务器消息，连接异常时转入断线处理。 */
    private void receiveLoop() {
        try {
            while (connected) {
                // 逐条读取并交给分发逻辑
                inputFilter.beginMessage();
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

    /** 按消息类型分发处理，其余消息交给监听器。 */
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
            // 离线消息先回送确认，再按普通消息继续处理
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

    /** 对文本与离线消息按消息 ID 去重，返回是否继续处理。 */
    private boolean acceptIncoming(Message message) {
        MessageType type = message.getType();
        if (type != MessageType.TEXT_PRIVATE && type != MessageType.TEXT_GROUP
                && type != MessageType.OFFLINE_MESSAGE) {
            return true;
        }
        String messageId = message.getMessageId();
        if (messageId == null || messageId.trim().isEmpty()) {
            // 没有消息 ID 时无法去重，直接放行
            return true;
        }
        synchronized (renderedMessageIds) {
            return renderedMessageIds.add(messageId);
        }
    }

    /** 解析消息确认并把结果转换为发送状态。 */
    private void handleMessageAck(Message message) {
        if (!(message instanceof TextMessage)) {
            LOGGER.warning("消息确认的实际类型不是文本消息，已忽略");
            return;
        }
        String content = ((TextMessage) message).getContent();

        // 确认内容格式为 消息ID|状态|说明
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

    /** 处理会话恢复应答，或在没有对应请求时保存服务器下发的会话令牌。 */
    private void handleSessionResume(Message message) {
        if (!(message instanceof TextMessage)) {
            return;
        }
        String content = ((TextMessage) message).getContent();
        String value = content == null ? "" : content.trim();
        CountDownLatch latch = resumeLatch;
        if (latch != null) {
            // 存在等待中的恢复请求，记录结果并唤醒等待线程
            resumeResult = value;
            latch.countDown();
            return;
        }
        if (value.isEmpty() || RESUME_OK.equals(value) || value.startsWith(RESUME_EXPIRED_PREFIX)) {
            LOGGER.warning("收到没有对应请求的会话恢复应答，已忽略");
            return;
        }

        // 没有对应请求时，把内容当作服务器下发的会话令牌
        sessionToken = value;
        LOGGER.info("已接收会话令牌，断线后可用其恢复会话");
    }

    /** 为离线消息回送已送达确认。 */
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

    /** 把文件数据块写入接收缓存，进度每变化百分之一就通知一次。 */
    private void handleIncomingChunk(FileMessage chunk) {
        if (!receivingSessions.containsKey(chunk.getTransferId())) {
            LOGGER.warning(() -> "收到未知传输会话的数据块，已忽略: " + chunk.getTransferId());
            return;
        }
        try {
            double before = fileService.progressOf(chunk.getTransferId());
            fileService.appendChunk(chunk);
            double after = fileService.progressOf(chunk.getTransferId());

            if (after - before >= 0.01 || after >= 1.0) {
                notifyMessage(progressMessage(chunk, after));
            }
        } catch (FileTransferException e) {
            LOGGER.log(Level.WARNING, "接收数据块失败: " + e.getMessage(), e);
            finishFailedTransfer(chunk, e.getMessage());
        }
    }

    /** 结束接收并保存文件，随后回送传输结果。 */
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

    /** 按源消息构造一条文件传输结果消息。 */
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

    /** 对方同意接收后启动独立线程发送文件。 */
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

    /** 逐块发送文件并回报进度，结束时发送结束帧。 */
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
            LOGGER.log(Level.WARNING, "文件分块发送失败: " + e.getMessage(), e);
            notifyMessage(resultMessage(request, receiver, false, "文件发送失败: " + e.getMessage()));
        } finally {
            fileService.closeSend(request.getTransferId());
        }
    }

    /** 复制文件信息并附上进度值，供界面刷新进度条。 */
    private FileMessage progressMessage(FileMessage source, double progress) {
        FileMessage message = ChatMessageFactory.file(source.getSender(), source.getReceiver(),
                MessageType.FILE_CHUNK);
        message.setTransferId(source.getTransferId());
        message.setFileName(source.getFileName());
        message.setFileSize(source.getFileSize());
        message.setTotalChunks(source.getTotalChunks());
        message.setChunkIndex(source.getChunkIndex());

        message.setMessage(String.valueOf(progress));
        return message;
    }

    /** 中止接收，回送失败结果并通知界面。 */
    private void finishFailedTransfer(FileMessage source, String reason) {
        receivingSessions.remove(source.getTransferId());
        fileService.cancelReceive(source.getTransferId());
        FileMessage failure = resultMessage(source, source.getSender(), false, "文件接收失败: " + reason);
        notifyMessage(failure);

        send(failure);
    }

    /**
     * 把消息交给发送线程池异步写出。
     *
     * @param message 待发送的消息
     * @return 已提交发送返回 true，未连接或线程池已关闭返回 false
     */
    public boolean send(Message message) {
        if (!connected || out == null) {
            notifyMessage(ChatMessageFactory.error(username, "尚未连接服务器，消息未发送"));
            return false;
        }
        if (senderPool == null || senderPool.isShutdown()) {
            return false;
        }

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
     * 在当前线程同步写出消息。
     *
     * @param message 待发送的消息
     * @return 写出成功返回 true
     * @throws IOException 未连接或写出失败时抛出
     */
    public boolean sendSync(Message message) throws IOException {
        if (!connected || out == null) {
            throw new IOException("尚未连接服务器");
        }
        message.ensureMessageId();
        writeObject(message);
        return true;
    }

    /** 串行写入对象流并重置流状态。 */
    private void writeObject(Message message) throws IOException {
        synchronized (out) {
            out.writeObject(message);
            out.flush();
            out.reset();
        }
    }

    /**
     * 发送私聊文本。
     *
     * @param receiver 接收者用户名
     * @param content 文本内容
     * @return 已提交发送返回 true
     */
    public boolean sendPrivateText(String receiver, String content) {
        return sendPrivateText(receiver, content, null, null);
    }

    /**
     * 发送带引用信息的私聊文本。
     *
     * @param receiver 接收者用户名
     * @param content 文本内容
     * @param quoteId 被引用消息的 ID，可以为 null
     * @param quoteSummary 被引用消息的摘要，可以为 null
     * @return 已提交发送返回 true
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
     * 发送带引用信息的私聊文本并返回消息 ID。
     *
     * @param receiver 接收者用户名
     * @param content 文本内容
     * @param quoteId 被引用消息的 ID，可以为 null
     * @param quoteSummary 被引用消息的摘要，可以为 null
     * @return 消息 ID，内容为空或发送失败时返回 null
     */
    public String sendPrivateTextReturningId(String receiver, String content, String quoteId,
                                             String quoteSummary) {
        return sendPrivateTextReturningId(receiver, content, quoteId, quoteSummary, null);
    }

    /**
     * 发送带引用信息的私聊文本并返回消息 ID。
     *
     * @param receiver 接收者用户名
     * @param content 文本内容
     * @param quoteId 被引用消息的 ID，可以为 null
     * @param quoteSummary 被引用消息的摘要，可以为 null
     * @param quoteSender 被引用消息的原发送者，可以为 null
     * @return 消息 ID，内容为空或发送失败时返回 null
     */
    public String sendPrivateTextReturningId(String receiver, String content, String quoteId,
                                             String quoteSummary, String quoteSender) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        if (content.length() > Constants.MESSAGE_MAX_LENGTH) {
            notifyMessage(ChatMessageFactory.error(username, "消息过长，最多 "
                    + Constants.MESSAGE_MAX_LENGTH + " 个字符"));
            return null;
        }
        return sendTrackedId(buildText(username, receiver, content, MessageType.TEXT_PRIVATE,
                quoteId, quoteSummary, quoteSender));
    }

    /** 构造文本消息，带引用信息时使用完整构造函数。 */
    private TextMessage buildText(String sender, String receiver, String content, MessageType type,
                                  String quoteId, String quoteSummary) {
        return buildText(sender, receiver, content, type, quoteId, quoteSummary, null);
    }

    private TextMessage buildText(String sender, String receiver, String content, MessageType type,
                                  String quoteId, String quoteSummary, String quoteSender) {
        if (quoteId == null && quoteSummary == null && quoteSender == null) {
            return ChatMessageFactory.text(sender, receiver, content, type);
        }
        TextMessage message = new TextMessage(sender, receiver, content, quoteId, quoteSummary, quoteSender);
        message.setType(type);
        return message;
    }

    /** 登记消息 ID 与接收者后发送，返回是否成功拿到消息 ID。 */
    private boolean sendTracked(TextMessage message) {
        return sendTrackedId(message) != null;
    }

    /** 登记待确认消息并返回消息 ID，发送失败时标记为发送失败。 */
    private String sendTrackedId(TextMessage message) {
        String messageId = message.ensureMessageId();
        pendingMessages.put(messageId, new PendingMessage(message));
        sentMessagePeers.put(messageId, message.getReceiver());
        notifyMessageState(messageId, SendState.SENDING, "");
        if (send(message)) {
            return messageId;
        }

        failPending(messageId, "尚未连接服务器");
        return null;
    }

    /**
     * 发送群聊文本。
     *
     * @param content 文本内容
     * @return 已提交发送返回 true
     */
    public boolean sendGroupText(String content) {
        return sendGroupText(content, null, null);
    }

    /**
     * 发送带引用信息的群聊文本。
     *
     * @param content 文本内容
     * @param quoteId 被引用消息的 ID，可以为 null
     * @param quoteSummary 被引用消息的摘要，可以为 null
     * @return 已提交发送返回 true
     */
    public boolean sendGroupText(String content, String quoteId, String quoteSummary) {
        return sendGroupTextReturningId(content, quoteId, quoteSummary) != null;
    }

    /**
     * 发送带引用信息的群聊文本并返回消息 ID。
     *
     * @param content 文本内容
     * @param quoteId 被引用消息的 ID，可以为 null
     * @param quoteSummary 被引用消息的摘要，可以为 null
     * @return 消息 ID，内容为空或发送失败时返回 null
     */
    public String sendGroupTextReturningId(String content, String quoteId, String quoteSummary) {
        return sendGroupTextReturningId(content, quoteId, quoteSummary, null);
    }

    /**
     * 发送带引用信息的群聊文本并返回消息 ID。
     *
     * @param content 文本内容
     * @param quoteId 被引用消息的 ID，可以为 null
     * @param quoteSummary 被引用消息的摘要，可以为 null
     * @param quoteSender 被引用消息的原发送者，可以为 null
     * @return 消息 ID，内容为空或发送失败时返回 null
     */
    public String sendGroupTextReturningId(String content, String quoteId, String quoteSummary,
                                           String quoteSender) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        if (content.length() > Constants.MESSAGE_MAX_LENGTH) {
            notifyMessage(ChatMessageFactory.error(username, "消息过长，最多 "
                    + Constants.MESSAGE_MAX_LENGTH + " 个字符"));
            return null;
        }
        TextMessage message = buildText(username, "", content, MessageType.TEXT_GROUP,
                quoteId, quoteSummary, quoteSender);
        String messageId = message.ensureMessageId();
        return send(message) ? messageId : null;
    }

    /**
     * 准备文件并发送传输请求。
     *
     * @param receiver 接收者用户名
     * @param file 待发送的文件
     * @return 传输 ID，准备失败时返回 null
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
     * 回应文件传输请求，同意时同时打开本地接收会话。
     *
     * @param request 对方的传输请求
     * @param accepted 是否同意接收
     * @param reason 拒绝原因，同意时可以为空
     * @return 应答已提交发送返回 true
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
     * 从文本消息中解析文件传输请求。
     *
     * @param message 携带请求内容的文本消息
     * @return 解析出的文件消息，解析失败或参数为 null 时返回 null
     */
    public static FileMessage decodeRequest(TextMessage message) {
        if (message == null) {
            return null;
        }
        return FileTransferCodec.decode(message.getContent(), message.getSender(), message.getReceiver());
    }

    /**
     * 请求服务器下发在线用户列表。
     *
     * @return 请求已提交发送返回 true
     */
    public boolean requestUserList() {
        SystemMessage message = ChatMessageFactory.control(username, Constants.SYSTEM_SENDER,
                MessageType.USER_LIST_REQUEST);
        return send(message);
    }

    /**
     * 请求修改昵称。
     *
     * @param nickname 新昵称
     * @return 请求已提交发送返回 true
     */
    public boolean requestNicknameChange(String nickname) {
        return sendUserUpdate("CHANGE_NICK", nickname);
    }

    /**
     * 请求修改头像，提交前检查 Base64 数据长度。
     *
     * @param base64 头像图片的 Base64 文本
     * @return 请求已提交发送返回 true，数据超长返回 false
     */
    public boolean requestAvatarChange(String base64) {
        if (base64 != null && base64.length() > Constants.AVATAR_MAX_LENGTH) {
            notifyMessage(ChatMessageFactory.error(username,
                    "头像过大，最多 " + Constants.AVATAR_MAX_LENGTH + " 字节，请换一张更小的图片"));
            return false;
        }
        return sendUserUpdate("CHANGE_AVATAR", base64);
    }

    /**
     * 请求修改密码。
     *
     * @param oldPassword 原密码
     * @param newPassword 新密码
     * @return 请求已提交发送返回 true
     */
    public boolean requestPasswordChange(String oldPassword, String newPassword) {
        TextMessage text = new TextMessage(username, Constants.SYSTEM_SENDER,
                (oldPassword == null ? "" : oldPassword) + "|" + (newPassword == null ? "" : newPassword));
        text.setType(MessageType.PASSWORD_CHANGE);
        return send(text);
    }

    /** 发送带命令与参数的用户信息更新请求。 */
    private boolean sendUserUpdate(String command, String argument) {
        TextMessage message = ChatMessageFactory.text(username, Constants.SYSTEM_SENDER,
                command + "|" + (argument == null ? "" : argument), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.USER_UPDATE);
        return send(message);
    }

    /**
     * 请求历史消息，使用默认分页条数。
     *
     * @param target 查询目标，群聊或用户名
     * @param fromDate 起始日期
     * @param toDate 结束日期
     * @return 请求已提交发送返回 true
     */
    public boolean requestHistory(String target, String fromDate, String toDate) {
        return requestHistory(target, fromDate, toDate, "");
    }

    /**
     * 请求指定会话的历史消息，使用默认分页条数。
     *
     * @param target 查询目标，群聊或用户名
     * @param fromDate 起始日期
     * @param toDate 结束日期
     * @param peer 会话对端
     * @return 请求已提交发送返回 true
     */
    public boolean requestHistory(String target, String fromDate, String toDate, String peer) {
        return requestHistory(target, fromDate, toDate, peer, "", DEFAULT_PAGE_SIZE);
    }

    /**
     * 按分页条件请求历史消息。
     *
     * @param target 查询目标，群聊或用户名
     * @param fromDate 起始日期
     * @param toDate 结束日期
     * @param peer 会话对端
     * @param beforeId 只取该消息 ID 更早的记录，为空表示从最新开始
     * @param pageSize 分页条数，为空时使用默认值
     * @return 请求已提交发送返回 true
     */
    public boolean requestHistory(String target, String fromDate, String toDate, String peer,
                                  String beforeId, String pageSize) {
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
     * 按关键词搜索消息。
     *
     * @param keyword 搜索关键词
     * @return 请求已提交发送返回 true，关键词为空返回 false
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
     * 请求撤回指定消息。
     *
     * @param messageId 待撤回的消息 ID
     * @return 请求已提交发送返回 true，消息 ID 为空返回 false
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
     * 请求导出全部聊天记录。
     *
     * @return 请求已提交发送返回 true
     */
    public boolean requestExport() {
        return requestExport("");
    }

    /**
     * 请求导出指定范围的聊天记录。
     *
     * @param scope 导出范围，为空表示全部
     * @return 请求已提交发送返回 true
     */
    public boolean requestExport(String scope) {
        TextMessage message = ChatMessageFactory.text(username, Constants.SYSTEM_SENDER,
                scope == null ? "" : scope, MessageType.TEXT_PRIVATE);
        message.setType(MessageType.EXPORT_REQUEST);
        return send(message);
    }

    /**
     * 发送退出登录请求并标记为主动关闭。
     *
     * @return 请求发送成功返回 true
     */
    public boolean sendLogout() {
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

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    /** 按配置间隔发送心跳，连续多个周期无应答时判定连接失效。 */
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

    /** 扫描超时未确认的消息，重发或标记为发送失败。 */
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
                // 连接断开时保留记录，等重连后再继续
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

    /** 移除待确认记录并通知发送失败。 */
    private void failPending(String messageId, String detail) {
        pendingMessages.remove(messageId);
        sentMessagePeers.remove(messageId);
        notifyMessageState(messageId, SendState.FAILED, detail);
    }

    /** 处理连接中断，已建立会话时启动重连线程，否则直接通知界面。 */
    private void handleConnectionLost(String reason) {
        if (userClosed) {
            return;
        }
        if (!sessionEstablished) {
            // 尚未登录成功，不再重连
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

    /** 按退避间隔反复重连，会话恢复成功后退出循环。 */
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

    /** 用会话令牌申请恢复会话并等待应答，返回是否结束重连。 */
    private boolean resumeSession() {
        String token = sessionToken;
        if (token == null || token.isEmpty()) {
            // 没有会话令牌时无法恢复，按会话过期处理
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

            requestUserList();
            notifyConnection(true, "已重新连接，会话已恢复");
            return true;
        }
        if (result.startsWith(RESUME_EXPIRED_PREFIX)) {
            // 服务器拒绝恢复会话，停止重连
            LOGGER.warning("会话恢复被拒绝，停止重连");
            finishSessionExpired();
            return true;
        }

        LOGGER.warning("会话恢复应答无法识别，稍后重试");
        return false;
    }

    /** 清理连接状态并通知界面会话已过期。 */
    private void finishSessionExpired() {
        synchronized (reconnectLock) {
            reconnecting = false;
        }
        connected = false;
        sessionActive = false;
        closeQuietly();
        notifyConnection(false, "会话已过期，请重新登录");
    }

    /** 等待接收线程结束，最多等一个连接超时时间。 */
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

    /** 返回第若干次重连应等待的退避时间。 */
    private long backoffOf(int attempt) {
        int index = Math.min(attempt, RECONNECT_BACKOFF_MS.length) - 1;
        return RECONNECT_BACKOFF_MS[Math.max(index, 0)];
    }

    /** 休眠指定时间，被中断时恢复中断标记并返回 false。 */
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
     * @param listener 待注册的监听器，null 时忽略
     */
    public void addMessageStateListener(MessageStateListener listener) {
        if (listener != null && !stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }

    /**
     * 注销发送状态监听器。
     *
     * @param listener 待注销的监听器
     */
    public void removeMessageStateListener(MessageStateListener listener) {
        stateListeners.remove(listener);
    }

    /** 通知所有发送状态监听器，单个监听器异常不影响其他监听器。 */
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
     * 返回待确认消息的接收者。
     *
     * @param messageId 消息 ID
     * @return 接收者用户名，消息不在待确认列表中时返回 null
     */
    public String getMessageReceiver(String messageId) {
        return messageId == null ? null : sentMessagePeers.get(messageId);
    }

    /**
     * 返回待确认消息的文本内容。
     *
     * @param messageId 消息 ID
     * @return 文本内容，消息不存在或不是文本消息时返回 null
     */
    public String getMessageContent(String messageId) {
        PendingMessage pending = messageId == null ? null : pendingMessages.get(messageId);
        if (pending == null || !(pending.message instanceof TextMessage)) {
            return null;
        }
        return ((TextMessage) pending.message).getContent();
    }

    /**
     * 返回当前是否处于重连流程。
     *
     * @return 正在重连返回 true
     */
    public boolean isReconnecting() {
        return reconnecting;
    }

    /**
     * 等待服务器确认的消息，记录重发次数与最近发送时间。
     */
    private static final class PendingMessage {
        private final Message message;

        private int retryCount;

        private long lastSentAt;

        private PendingMessage(Message message) {
            this.message = message;
            this.lastSentAt = System.currentTimeMillis();
        }
    }

    /**
     * 注册消息与连接状态监听器。
     *
     * @param listener 待注册的监听器，null 时忽略
     */
    public void addListener(ChatListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 注销消息与连接状态监听器。
     *
     * @param listener 待注销的监听器
     */
    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }

    /** 通知所有监听器收到消息，单个监听器异常不影响其他监听器。 */
    private void notifyMessage(Message message) {
        for (ChatListener listener : listeners) {
            try {
                listener.onMessage(message);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "监听器处理消息异常，已忽略", e);
            }
        }
    }

    /** 通知所有监听器连接状态变化，单个监听器异常不影响其他监听器。 */
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
     * 关闭客户端，停止全部线程并断开连接。
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

    /** 依次关闭输出流、输入流与套接字，忽略关闭异常。 */
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

    public boolean isConnected() {
        return connected;
    }

    public String getUsername() {
        return username;
    }

    public String getNickname() {
        return nickname == null ? username : nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getReceiveDir() {
        return receiveDir;
    }

    public void setReceiveDir(String receiveDir) {
        if (receiveDir != null && !receiveDir.trim().isEmpty()) {
            this.receiveDir = receiveDir.trim();
        }
    }

    public FileService getFileService() {
        return fileService;
    }

    /**
     * 返回仍在等待对方同意的文件传输 ID 列表。
     *
     * @return 传输 ID 列表
     */
    public List<String> pendingTransferIds() {
        return new ArrayList<>(pendingSends.keySet());
    }

    /**
     * 解析登录结果文本，返回成功标记与失败原因。
     *
     * @param content 服务器返回的结果文本
     * @return 长度为 2 的数组，依次为成功标记与失败原因
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
     * 从登录结果文本中取出用户名、昵称与角色字段。
     *
     * @param content 服务器返回的结果文本
     * @return 长度为 3 的数组，依次为用户名、昵称与角色，字段不足时返回 null
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

    public static String[] parseRegisterResult(String content) {
        return parseLoginResult(content);
    }

    /** 更新登录状态与用户信息，并唤醒等待登录结果的线程。 */
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

            // 登录成功后记录会话状态，供心跳与重连使用
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
     * 阻塞等待登录结果。
     *
     * @param timeoutMillis 最长等待时间，单位毫秒
     * @return 登录成功且连接正常返回 true
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
     * 阻塞等待满足条件的消息，超时返回 null。
     *
     * @param condition 消息筛选条件
     * @param timeoutMillis 最长等待时间，单位毫秒
     * @return 第一条满足条件的消息，超时或中断时返回 null
     */
    public Message waitForMessage(Predicate<Message> condition, long timeoutMillis) {
        java.util.concurrent.LinkedBlockingQueue<Message> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        ChatListener waiter = new ChatListener() {
            @Override
            public void onMessage(Message message) {
                if (condition.test(message)) {
                    queue.offer(message);
                }
            }

            @Override
            public void onConnectionChanged(boolean connected, String reason) {
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

    public String getLastLoginFailure() {
        return lastLoginFailure;
    }

    @Override
    public String toString() {
        return "ChatClient{user=" + username + ", connected=" + connected + "}";
    }
}

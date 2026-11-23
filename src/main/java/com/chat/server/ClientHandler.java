package com.chat.server;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.FileTransferCodec;
import com.chat.common.Message;
import com.chat.common.MessageFilter;
import com.chat.common.MessageType;
import com.chat.common.Result;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.common.UserListCodec;
import com.chat.exception.ChatException;
import com.chat.service.AiAssistantService;
import com.chat.service.MessageService;
import com.chat.service.UserService;
import com.chat.util.DateUtil;
import com.chat.util.MessageExporter;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 客户端连接处理器，读取并分派客户端消息，处理登录、消息收发、文件传输、历史查询与连接清理。
 */
public class ClientHandler extends AbstractMessageHandler implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".ClientHandler");

    private static final Pattern USERNAME_PATTERN = Pattern.compile(Constants.USERNAME_PATTERN);

    /** 文件传输编号允许的最大长度。 */
    private static final int MAX_TRANSFER_ID_LENGTH = 64;

    private static final String ACK_DELIVERED = "DELIVERED";

    private static final String ACK_OFFLINE = "OFFLINE";

    private static final String ACK_DUPLICATE = "DUPLICATE";

    private static final String ACK_FAILED = "FAILED";

    /** 单次登录最多补投的离线消息条数。 */
    private static final int OFFLINE_PUSH_LIMIT = 200;

    /** 关闭连接前排空接收缓冲的等待时间，单位毫秒。 */
    private static final int REPLY_CLOSE_DRAIN_MILLIS = 300;

    /** 历史记录默认每页条数。 */
    private static final int HISTORY_PAGE_DEFAULT_LIMIT = 50;

    /** 历史记录每页最小条数。 */
    private static final int HISTORY_PAGE_MIN_LIMIT = 1;

    /** 历史记录每页最大条数。 */
    private static final int HISTORY_PAGE_MAX_LIMIT = 200;

    /** 历史结果中分页游标行的前缀。 */
    private static final String HISTORY_CURSOR_PREFIX = "#beforeId=";

    /** 引用摘要保留的最大字符数。 */
    private static final int QUOTE_SUMMARY_MAX_LENGTH = 100;

    /** 历史与搜索结果的行格式版本标记。 */
    private static final String HISTORY_ROW_VERSION = "v2";

    /** 已补投但尚未收到送达确认的消息标识。 */
    private final Set<String> pendingOfflineAcks = ConcurrentHashMap.newKeySet();

    /** 进行中的文件传输会话，键为传输编号。 */
    private static final Map<String, TransferSession> TRANSFER_REGISTRY = new ConcurrentHashMap<>();

    private final Socket socket;

    private final ChatServer server;

    private final long maxFileSize;

    private final long heartbeatTimeoutMs;

    private ObjectInputStream in;

    private ObjectOutputStream out;

    private MessageFilter inputFilter;

    private final Object sendLock = new Object();

    private volatile String username;

    /** 最近一次收到消息的时间戳，单位毫秒。 */
    private volatile long lastActiveTime = System.currentTimeMillis();

    private volatile boolean closed;

    /**
     * 创建连接处理器。
     *
     * @param socket 客户端套接字
     * @param server 服务器实例
     * @param maxFileSize 单文件大小上限，单位字节
     * @param heartbeatTimeoutMs 心跳超时时间，单位毫秒
     */
    public ClientHandler(Socket socket, ChatServer server, long maxFileSize, long heartbeatTimeoutMs) {
        this.socket = socket;
        this.server = server;
        this.maxFileSize = maxFileSize;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    /**
     * 读取客户端消息并逐条分派，连接结束时执行清理。
     */
    @Override
    public void run() {
        try {
            initStreams();
            send(ChatMessageFactory.system("", "已连接聊天服务器，请先登录。"));
            while (!closed) {
                // 逐条读取并分派消息
                inputFilter.beginMessage();
                Object received = in.readObject();
                if (!(received instanceof Message)) {
                    LOGGER.warning("收到非消息对象，已忽略");
                    continue;
                }
                Message message = (Message) received;
                lastActiveTime = System.currentTimeMillis();
                dispatch(message, socket);
            }
        } catch (EOFException | SocketException e) {
            LOGGER.info(() -> "客户端连接结束: " + identify());
        } catch (java.io.InvalidClassException e) {
            LOGGER.warning(() -> "反序列化被白名单拒绝，连接即将关闭: " + e.getMessage());
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "连接读取异常", e);
        } catch (ClassNotFoundException e) {
            LOGGER.warning("反序列化失败，可能是协议版本不一致: " + e.getMessage());
            send(ChatMessageFactory.error(username, "消息格式无法识别，请确认客户端版本一致"));
        } finally {
            onConnectionClosed();
        }
    }

    /** 创建对象流并安装反序列化过滤器。 */
    private void initStreams() throws IOException {
        out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in = new ObjectInputStream(socket.getInputStream());

        inputFilter = MessageFilter.forServerInput();
        in.setObjectInputFilter(inputFilter);
        out.reset();
    }

    private String identify() {
        return username != null ? username : String.valueOf(socket.getRemoteSocketAddress());
    }

    /**
     * 向客户端发送一条消息。
     *
     * @param message 消息
     * @return 发送成功返回 true，连接已关闭或写入失败返回 false
     */
    public boolean send(Message message) {
        if (message == null || closed) {
            return false;
        }
        // 串行写入，发送后重置对象流缓存
        synchronized (sendLock) {
            try {
                out.writeObject(message);
                out.flush();

                out.reset();
                return true;
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "消息发送失败", e);
                close();
                return false;
            }
        }
    }

    /**
     * 处理私聊与群聊文本消息。
     *
     * @param message 消息对象
     * @param socket 客户端连接
     */
    @Override
    public void handleTextMessage(Message message, Socket socket) {
        // 校验登录态
        if (!requireLogin()) {
            return;
        }
        // 校验消息对象与正文
        if (!(message instanceof TextMessage text)) {
            LOGGER.warning(() -> "文本类消息的实际对象类型不符，已拒绝: "
                    + message.getClass().getName() + " / " + message.getType());
            send(ChatMessageFactory.error(username, "文本消息格式错误，已拒绝"));
            return;
        }
        String contentError = ChatMessageFactory.checkTextContent(text.getContent());
        if (contentError != null) {
            LOGGER.warning(() -> "文本消息正文校验未通过: " + username + " - " + contentError);
            send(ChatMessageFactory.error(username, contentError));
            return;
        }
        text.setSender(username);

        // 校验并清理引用信息
        TextMessage outgoing = sanitizeQuote(text);
        // 群聊直接广播并入库
        if (outgoing.getType() == MessageType.TEXT_GROUP) {
            outgoing.setReceiver("");
            server.getUserManager().broadcastText(outgoing);
            saveOrWarn(outgoing, "(群聊)");
            server.notify(ServerObserver.EventType.GROUP_MESSAGE,
                    username + " 群发: " + outgoing.getSummary());
            return;
        }
        String receiver = outgoing.getReceiver();
        if (receiver == null || receiver.isEmpty()) {
            send(ChatMessageFactory.error(username, "私聊消息缺少接收者"));
            return;
        }

        // 小助手消息转交智能体
        if (handOffToAiAssistant(outgoing, receiver)) {
            return;
        }

        String messageId = outgoing.ensureMessageId();
        try {
            // 按消息标识判断重复
            if (server.getMessageService().existsByMessageId(messageId)) {
                LOGGER.info(() -> "私聊消息重复，按幂等回执处理: " + username + " -> " + receiver
                        + " - " + messageId);
                sendAck(outgoing, ACK_DUPLICATE, "该消息此前已处理过");
                return;
            }
        } catch (ChatException e) {
            LOGGER.warning(() -> "私聊消息判重失败: " + username + " -> " + receiver
                    + " - " + e.getMessage());
            sendAck(outgoing, ACK_FAILED, "服务器无法校验消息标识：" + e.getMessage());
            return;
        }
        // 投递并写入聊天记录
        boolean delivered = server.getUserManager().sendTo(receiver, outgoing);

        Result<Boolean> saved = server.getMessageService().saveMessage(outgoing);
        if (!saved.isSuccess()) {
            LOGGER.warning(() -> "私聊消息入库失败: " + username + " -> " + receiver
                    + "，实际已送达=" + delivered + " - " + saved.getMessage());
            sendAck(outgoing, ACK_FAILED, "服务器未能写入聊天记录：" + saved.getMessage());
            return;
        }
        // 接收方离线时回执并保留离线消息
        if (!delivered) {
            LOGGER.info(() -> "私聊消息接收方离线，已入库待补投: " + username + " -> " + receiver);
            sendAck(outgoing, ACK_OFFLINE, "接收方不在线，消息将在其上线后补投");

            send(ChatMessageFactory.error(username,
                    "用户 " + receiver + " 不在线，消息已存入离线队列，将在其上线后送达"));
            return;
        }
        markDeliveredQuietly(messageId, receiver);
        sendAck(outgoing, ACK_DELIVERED, "");

        // 向发送者回显消息
        TextMessage echo = ChatMessageFactory.text(username, username, outgoing.getSummary(),
                MessageType.TEXT_PRIVATE);
        send(echo);
        server.notify(ServerObserver.EventType.PRIVATE_MESSAGE,
                username + " -> " + receiver + ": " + outgoing.getSummary());
    }

    /** 校验引用关系并重建文本消息。 */
    private TextMessage sanitizeQuote(TextMessage source) {
        if (!source.hasQuote()) {
            return source;
        }
        String quoteId = source.getQuoteId();
        // 查询被引用的原消息
        Message original = null;
        if (!quoteId.isEmpty()) {
            try {
                original = server.getMessageService().findByMessageId(quoteId);
            } catch (ChatException e) {
                LOGGER.warning(() -> "引用校验查询原消息失败，本次引用已清空: " + username
                        + " - " + quoteId + " - " + e.getMessage());
            }
        }
        boolean quotable = original != null
                && (username.equals(original.getSender())
                || username.equals(original.getReceiver())
                || original.isBroadcast());
        TextMessage rebuilt;
        // 校验引用者是否为原消息参与者
        if (quotable) {
            rebuilt = new TextMessage(source.getSender(), source.getReceiver(), source.getContent(),
                    quoteId, quoteSummaryOf(original), original.getSender());
        } else {
            LOGGER.info(() -> "引用未通过服务端校验，已清空: " + username + " - " + quoteId);
            rebuilt = new TextMessage(source.getSender(), source.getReceiver(), source.getContent());
        }

        rebuilt.setId(source.getId());
        rebuilt.setMessageId(source.getMessageId());
        rebuilt.setType(source.getType());
        rebuilt.setTimestamp(source.getTimestamp());
        return rebuilt;
    }

    private static String quoteSummaryOf(Message original) {
        String text = MessageExporter.contentOf(original);
        return text.length() <= QUOTE_SUMMARY_MAX_LENGTH
                ? text : text.substring(0, QUOTE_SUMMARY_MAX_LENGTH);
    }

    /** 把发给小助手的私聊消息转交智能体处理。 */
    private boolean handOffToAiAssistant(TextMessage text, String receiver) {
        if (!Constants.AI_USERNAME.equals(receiver)) {
            return false;
        }
        AiAssistantService assistant = server.getAiAssistant();
        if (assistant == null || !assistant.isEnabled()) {
            return false;
        }
        if (server.getUserManager().isOnline(receiver)) {
            LOGGER.warning(() -> "存在与小助手同名的在线账号，本条消息按普通私聊投递: " + receiver);
            return false;
        }
        sendAck(text, ACK_DELIVERED, "");

        TextMessage echo = ChatMessageFactory.text(username, username, text.getSummary(),
                MessageType.TEXT_PRIVATE);
        send(echo);

        send(ChatMessageFactory.text(Constants.AI_USERNAME, username,
                Constants.AI_THINKING_TEXT, MessageType.TEXT_PRIVATE));
        server.askAi(username, text.getContent());
        server.notify(ServerObserver.EventType.PRIVATE_MESSAGE,
                username + " -> " + receiver + ": " + text.getSummary());
        return true;
    }

    /** 发送包含消息标识与状态的处理回执。 */
    private void sendAck(Message target, String state, String detail) {
        String messageId = target.ensureMessageId();
        String body = messageId + '|' + state + '|' + (detail == null ? "" : detail);
        TextMessage ack = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username, body,
                MessageType.TEXT_PRIVATE);
        ack.setType(MessageType.MSG_ACK);

        ack.ensureMessageId();
        send(ack);
    }

    private void markDeliveredQuietly(String messageId, String receiver) {
        try {
            server.getMessageService().markDelivered(List.of(messageId));
        } catch (ChatException e) {
            LOGGER.warning(() -> "消息已送达但送达标记更新失败: " + receiver + " - " + messageId
                    + " - " + e.getMessage());
        }
    }

    private void saveOrWarn(Message message, String peer) {
        Result<Boolean> saved = server.getMessageService().saveMessage(message);
        if (saved.isSuccess()) {
            return;
        }
        LOGGER.warning(() -> "消息已送达但记录保存失败: " + username + " -> " + peer
                + " - " + saved.getMessage());
        send(ChatMessageFactory.error(username, "消息已送达，但服务器未能写入聊天记录：" + saved.getMessage()));
    }

    /**
     * 处理文件传输消息。
     *
     * @param message 消息对象
     * @param socket 客户端连接
     */
    @Override
    public void handleFileMessage(Message message, Socket socket) {
        if (!requireLogin()) {
            return;
        }

        // 发送者必须是当前登录用户
        if (!username.equals(message.getSender())) {
            LOGGER.warning(() -> "文件消息发送者与登录用户不一致，已拒绝: " + message.getSender()
                    + " / " + username);
            send(ChatMessageFactory.error(username, "文件消息发送者与登录用户不一致，已拒绝"));
            return;
        }
        message.setSender(username);

        // 解析并校验文件消息
        FileMessage fileMessage = decodeFileRequest(message);
        if (fileMessage == null) {
            send(ChatMessageFactory.error(username, "文件消息格式错误，无法解析"));
            return;
        }
        fileMessage.setSender(username);
        String receiver = fileMessage.getReceiver();
        if (receiver == null || receiver.isEmpty()) {
            send(ChatMessageFactory.error(username, "文件消息缺少接收者"));
            return;
        }

        // 小助手不接收文件
        if (Constants.AI_USERNAME.equals(receiver)) {
            rejectFileMessage(fileMessage, "小助手只支持文字问答，不能接收文件");
            return;
        }
        // 校验传输编号
        if (fileMessage.getTransferId() == null || fileMessage.getTransferId().isEmpty()
                || fileMessage.getTransferId().length() > MAX_TRANSFER_ID_LENGTH) {
            rejectFileMessage(fileMessage, "文件传输编号非法：不能为空且长度不超过 " + MAX_TRANSFER_ID_LENGTH);
            return;
        }
        // 校验接收者用户名
        if (!isValidFilePeer(receiver)) {
            rejectFileMessage(fileMessage, "文件消息接收者非法：用户名格式不正确");
            return;
        }
        // 按文件消息类型处理
        switch (fileMessage.getType()) {
            case FILE_REQUEST:

                handleFileRequest(message, fileMessage, receiver);
                break;
            case FILE_ACCEPT:
                if (acceptTransfer(fileMessage)) {
                    relay(fileMessage, receiver);
                }
                break;
            case FILE_REJECT:
            case FILE_RESULT:
                if (finishTransfer(fileMessage)) {
                    TRANSFER_REGISTRY.remove(fileMessage.getTransferId());
                    relay(fileMessage, receiver);
                    saveTransferRecord(fileMessage);
                }
                break;
            case FILE_CHUNK:
            case FILE_END:
                if (relayTransferStep(fileMessage)) {
                    relay(fileMessage, receiver);
                }
                break;
            default:
                send(ChatMessageFactory.error(username, "不支持的文件消息类型: " + fileMessage.getType()));
                break;
        }
    }

    private boolean isValidPeer(String peer) {
        return peer != null && !peer.equals(username) && USERNAME_PATTERN.matcher(peer).matches();
    }

    private boolean isValidFilePeer(String peer) {
        return peer != null && USERNAME_PATTERN.matcher(peer).matches();
    }

    /** 拒绝文件消息并清理相关传输会话。 */
    private void rejectFileMessage(FileMessage message, String reason) {
        String transferId = message == null ? null : message.getTransferId();
        TransferSession session = transferId == null ? null : TRANSFER_REGISTRY.get(transferId);
        if (session != null && session.involves(username)) {
            TRANSFER_REGISTRY.remove(transferId);
        }
        LOGGER.warning(() -> "文件消息被拒绝: " + username + " - " + reason);
        send(ChatMessageFactory.error(username, reason));
    }

    /** 校验并记录接收方对文件传输的同意。 */
    private boolean acceptTransfer(FileMessage message) {
        TransferSession session = TRANSFER_REGISTRY.get(message.getTransferId());
        if (session == null) {
            rejectFileMessage(message, "文件传输未登记，已拒绝应答");
            return false;
        }
        if (!username.equals(session.receiver) || !session.sender.equals(message.getReceiver())) {
            rejectFileMessage(message, "只有文件接收方可以应答该传输");
            return false;
        }
        if (session.accepted || session.ended) {
            rejectFileMessage(message, "文件传输状态不允许重复应答");
            return false;
        }
        session.accepted = true;
        return true;
    }

    /** 校验并记录文件传输数据帧。 */
    private boolean relayTransferStep(FileMessage message) {
        TransferSession session = TRANSFER_REGISTRY.get(message.getTransferId());
        if (session == null) {
            rejectFileMessage(message, "文件传输未登记，已拒绝数据帧");
            return false;
        }
        if (!username.equals(session.sender) || !session.receiver.equals(message.getReceiver())) {
            rejectFileMessage(message, "文件数据帧的发送者与登记不一致");
            return false;
        }
        if (!session.accepted) {
            rejectFileMessage(message, "接收方尚未同意，已拒绝数据帧");
            return false;
        }
        if (session.ended) {
            rejectFileMessage(message, "文件传输已结束，已拒绝重复结束帧");
            return false;
        }
        if (message.getType() == MessageType.FILE_END) {
            session.ended = true;
        }
        return true;
    }

    /** 校验文件传输的结束回执。 */
    private boolean finishTransfer(FileMessage message) {
        TransferSession session = TRANSFER_REGISTRY.get(message.getTransferId());
        if (session == null) {
            rejectFileMessage(message, "文件传输未登记或已结束，已拒绝重复回执");
            return false;
        }
        if (!session.involves(username) || !session.peerOf(username).equals(message.getReceiver())) {
            rejectFileMessage(message, "文件回执的参与者与登记不一致");
            return false;
        }
        return true;
    }

    /** 把消息对象还原为文件消息。 */
    private FileMessage decodeFileRequest(Message message) {
        if (message instanceof FileMessage fileMessage) {
            return fileMessage;
        }
        if (message instanceof TextMessage text) {
            if (ChatMessageFactory.splitFields(text.getContent()) == null) {
                return null;
            }
            return FileTransferCodec.decode(text.getContent(), text.getSender(), text.getReceiver());
        }
        return null;
    }

    /** 处理文件发送请求。 */
    private void handleFileRequest(Message original, FileMessage request, String receiver) {
        // 校验文件大小与接收方在线状态
        if (request.getFileSize() > maxFileSize) {
            send(ChatMessageFactory.error(username,
                    "文件超过大小上限 " + com.chat.util.FileUtil.humanSize(maxFileSize)));
            return;
        }
        if (!server.getUserManager().isOnline(receiver)) {
            send(ChatMessageFactory.error(username, "用户 " + receiver + " 不在线，无法发送文件"));
            return;
        }

        // 登记传输会话
        TransferSession previous = TRANSFER_REGISTRY.putIfAbsent(
                request.getTransferId(), new TransferSession(username, receiver));
        if (previous != null) {
            LOGGER.warning(() -> "文件传输编号重复，已拒绝请求: " + username
                    + " - " + request.getTransferId());
            send(ChatMessageFactory.error(username, "文件传输编号已存在，已拒绝重复请求"));
            return;
        }
        relay(original, receiver);
        server.notify(ServerObserver.EventType.FILE_TRANSFER,
                username + " 请求向 " + receiver + " 发送 " + request.getSummary());
    }

    /** 保存文件传输结果到聊天记录与文件日志。 */
    private void saveTransferRecord(FileMessage result) {
        String fileSender = result.getReceiver();
        if (fileSender == null || fileSender.isEmpty()) {
            return;
        }
        FileMessage record = ChatMessageFactory.file(fileSender, username, MessageType.FILE_RESULT);
        record.setFileName(result.getFileName());
        record.setFileSize(result.getFileSize());
        record.setSha256(result.getSha256());

        String detail = result.getMessage() == null ? "" : result.getMessage().trim();
        if (result.isAccepted()) {
            record.setMessage("文件传输完成");
        } else {
            record.setMessage(detail.isEmpty() ? "文件传输未完成" : "文件传输未完成：" + detail);
        }

        saveOrWarn(record, fileSender);

        server.getFileLogService().record(result, fileSender, username);
    }

    private void relay(Message message, String receiver) {
        boolean delivered = server.getUserManager().sendTo(receiver, message);
        if (!delivered) {
            send(ChatMessageFactory.error(username, "用户 " + receiver + " 不在线，传输已中断"));
        }
    }

    /**
     * 按类型处理控制消息。
     *
     * @param message 消息对象
     * @param socket 客户端连接
     */
    @Override
    public void handleSystemMessage(Message message, Socket socket) {
        switch (message.getType()) {
            case LOGIN:
                if (message instanceof TextMessage text) {
                    handleLogin(text);
                } else {
                    rejectTextRequirement(message);
                }
                break;
            case REGISTER:
                if (message instanceof TextMessage text) {
                    handleRegister(text);
                } else {
                    rejectTextRequirement(message);
                }
                break;
            case LOGOUT:
                close();
                break;
            case HEARTBEAT:
                send(ChatMessageFactory.control(Constants.SYSTEM_SENDER, username,
                        MessageType.HEARTBEAT_ACK));
                break;
            case MSG_ACK:
                handleDeliveryAck(message);
                break;
            case SESSION_RESUME:
                if (message instanceof TextMessage text) {
                    handleSessionResume(text);
                } else {
                    rejectTextRequirement(message);
                }
                break;
            case USER_LIST_REQUEST:
                sendUserList();
                break;
            case USER_UPDATE:
                if (message instanceof TextMessage text) {
                    handleUserUpdate(text);
                } else {
                    rejectTextRequirement(message);
                }
                break;
            case PASSWORD_CHANGE:
                if (message instanceof TextMessage text) {
                    handlePasswordChange(text);
                } else {
                    rejectTextRequirement(message);
                }
                break;
            case HISTORY_REQUEST:
                if (message instanceof TextMessage text) {
                    handleHistoryRequest(text);
                } else {
                    rejectTextRequirement(message);
                }
                break;
            case SEARCH_REQUEST:
                if (message instanceof TextMessage text) {
                    handleSearchRequest(text);
                } else {
                    rejectTextRequirement(message);
                }
                break;
            case MSG_RECALL:
                if (message instanceof TextMessage text) {
                    handleRecallRequest(text);
                } else {
                    rejectTextRequirement(message);
                }
                break;
            case EXPORT_REQUEST:
                if (message instanceof TextMessage text) {
                    handleExportRequest(socket, text);
                } else {
                    handleExportRequest(socket, null);
                }
                break;
            default:

                LOGGER.warning(() -> "收到服务器未处理的控制消息，已忽略: " + username
                        + " - " + message.getType());
                break;
        }
    }

    private void rejectTextRequirement(Message message) {
        LOGGER.warning(() -> "控制消息的实际对象类型不符，已拒绝: "
                + message.getClass().getName() + " / " + message.getType());
        send(ChatMessageFactory.error(username, "控制消息格式错误，已拒绝"));
    }

    /** 处理登录请求。 */
    private void handleLogin(TextMessage message) {
        // 已登录连接不能重复登录
        if (username != null) {
            send(ChatMessageFactory.error(username, "当前连接已登录"));
            return;
        }
        String[] parts = ChatMessageFactory.splitFields(message.getContent());
        if (parts == null || parts.length < 2 || parts[0].isEmpty()) {
            sendLoginResult(false, "登录请求格式错误", null);
            return;
        }
        String name = parts[0];
        String password = parts[1];

        // 校验账号密码
        Result<User> result = server.getUserService().login(name, password, clientAddress());
        if (!result.isSuccess()) {
            server.notify(ServerObserver.EventType.LOGIN_FAILED, name + " 登录失败: " + result.getMessage());
            sendLoginResult(false, result.getMessage(), null);
            return;
        }
        User user = result.getData();
        // 注册在线用户
        if (!server.getUserManager().register(user, this)) {
            sendLoginResult(false, "该账号已在其它位置登录", null);
            return;
        }
        // 返回登录结果并签发会话令牌
        this.username = user.getUsername();
        sendLoginResult(true, "登录成功，欢迎 " + user.getNickname(), user);
        onUserOnline(user.getUsername(), socket);

        sendSessionResumeResult(server.issueSessionToken(user.getUsername()));
        pushOfflineMessages();
    }

    /** 向刚登录的用户补投离线消息。 */
    private void pushOfflineMessages() {
        if (username == null) {
            return;
        }
        List<Message> pending;
        try {
            pending = server.getMessageService().findUndelivered(username, OFFLINE_PUSH_LIMIT);
        } catch (ChatException e) {
            LOGGER.warning(() -> "离线消息查询失败，本次跳过补投: " + username + " - " + e.getMessage());
            return;
        }
        int pushed = 0;
        // 只补投未送达的私聊文本消息
        for (Message item : pending) {
            if (!(item instanceof TextMessage) || item.getType() != MessageType.TEXT_PRIVATE) {
                continue;
            }
            String messageId = item.getMessageId();
            if (messageId == null || messageId.trim().isEmpty()) {
                continue;
            }

            item.setType(MessageType.OFFLINE_MESSAGE);
            if (!send(item)) {
                LOGGER.warning(() -> "离线消息补投中断，剩余记录将在下次登录重试: " + username);
                return;
            }
            pendingOfflineAcks.add(messageId);
            pushed++;
        }
        if (pushed > 0) {
            int count = pushed;
            LOGGER.info(() -> "已补投离线消息 " + count + " 条: " + username);
        }
    }

    /** 处理离线消息的送达确认。 */
    private void handleDeliveryAck(Message message) {
        if (username == null) {
            LOGGER.warning("未登录连接发来消息回执，已忽略");
            return;
        }
        if (!(message instanceof TextMessage text)) {
            rejectTextRequirement(message);
            return;
        }
        String[] parts = ChatMessageFactory.splitFields(text.getContent());
        if (parts == null || parts.length < 2) {
            LOGGER.warning(() -> "消息回执格式错误，已忽略: " + username);
            return;
        }
        String messageId = parts[0].trim();
        String state = parts[1].trim();
        if (!ACK_DELIVERED.equals(state)) {
            LOGGER.fine(() -> "收到非送达确认的消息回执，已忽略: " + username + " - " + state);
            return;
        }
        if (!pendingOfflineAcks.remove(messageId)) {
            LOGGER.warning(() -> "消息回执没有对应的离线补投记录，已忽略: " + username + " - " + messageId);
            return;
        }
        try {
            int updated = server.getMessageService().markDelivered(List.of(messageId));
            LOGGER.info(() -> "离线消息送达已确认 " + updated + " 条: " + username);
        } catch (ChatException e) {
            pendingOfflineAcks.add(messageId);
            LOGGER.warning(() -> "离线消息送达标记失败: " + username + " - " + messageId
                    + " - " + e.getMessage());
        }
    }

    /** 处理会话恢复请求。 */
    private void handleSessionResume(TextMessage message) {
        if (username != null) {
            send(ChatMessageFactory.error(username, "当前连接已登录，无需恢复会话"));
            return;
        }
        String token = message.getContent() == null ? "" : message.getContent().trim();
        String name = server.touchSessionToken(token);
        if (name == null) {
            LOGGER.warning(() -> "会话令牌无效或已过期，拒绝恢复: " + getRemoteAddress());
            sendSessionResumeResult("EXPIRED|会话令牌无效或已过期，请重新登录");
            closeAfterReply();
            return;
        }
        Result<User> found = server.getUserService().findByUsername(name);
        if (!found.isSuccess()) {
            LOGGER.warning(() -> "会话恢复失败，用户已不存在: " + name + " - " + found.getMessage());
            sendSessionResumeResult("EXPIRED|用户不存在或已被删除，请重新登录");
            closeAfterReply();
            return;
        }

        ClientHandler previous = server.getUserManager().get(name);
        if (previous != null && previous != this) {
            previous.close();
        }
        User user = found.getData();
        if (!server.getUserManager().register(user, this)) {
            LOGGER.warning(() -> "会话恢复时注册失败: " + name);
            sendSessionResumeResult("EXPIRED|该账号已在其它位置登录，请重新登录");
            closeAfterReply();
            return;
        }
        this.username = name;
        sendSessionResumeResult("OK");
        LOGGER.info(() -> "会话已恢复: " + name);
        onUserOnline(name, socket);
        pushOfflineMessages();
    }

    private void sendSessionResumeResult(String content) {
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER,
                username == null ? "" : username, content, MessageType.TEXT_PRIVATE);
        response.setType(MessageType.SESSION_RESUME);
        send(response);
    }

    /** 等待应答发出后排空接收缓冲并关闭连接。 */
    private void closeAfterReply() {
        try {
            socket.shutdownOutput();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "关闭输出通道失败（忽略）", e);
        }
        try {
            socket.setSoTimeout(REPLY_CLOSE_DRAIN_MILLIS);
            long deadline = System.currentTimeMillis() + REPLY_CLOSE_DRAIN_MILLIS;
            while (System.currentTimeMillis() < deadline && in.read() != -1) {
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "排空接收队列失败（忽略）", e);
        }
        close();
    }

    private void handleRegister(TextMessage message) {
        if (username != null) {
            send(ChatMessageFactory.error(username, "已登录状态下不能重复注册"));
            return;
        }
        String[] parts = ChatMessageFactory.splitFields(message.getContent());
        if (parts == null || parts.length < 2) {
            sendPlainResult(MessageType.REGISTER_RESULT, false, "注册请求格式错误");
            return;
        }
        String nickname = parts.length > 2 ? parts[2] : "";
        Result<User> result = server.getUserService().register(parts[0], parts[1], nickname);
        sendPlainResult(MessageType.REGISTER_RESULT, result.isSuccess(), result.getMessage());
        if (result.isSuccess()) {
            server.notify(ServerObserver.EventType.LOGIN, "新用户注册: " + parts[0]);
        }
    }

    /** 处理昵称、头像与删除用户等资料操作。 */
    private void handleUserUpdate(TextMessage message) {
        if (!requireLogin()) {
            return;
        }
        String content = message.getContent();

        String[] parts = ChatMessageFactory.splitFields(content,
                Constants.AVATAR_MAX_LENGTH + 64);
        if (parts == null) {
            send(ChatMessageFactory.error(username, "用户资料变更请求格式错误"));
            return;
        }
        String command = parts.length > 0 ? parts[0] : "";
        String argument = parts.length > 1 ? parts[1] : "";
        if (!"CHANGE_AVATAR".equals(command) && content.length() > Constants.MESSAGE_MAX_LENGTH) {
            send(ChatMessageFactory.error(username, "用户资料变更请求格式错误"));
            return;
        }
        UserService userService = server.getUserService();
        if ("CHANGE_NICK".equals(command)) {
            Result<User> result = userService.updateNickname(username, argument);
            sendProfileResult(Constants.PROFILE_SECTION_NICK, result.isSuccess(), result.getMessage());
            if (result.isSuccess()) {
                server.getUserManager().updateProfile(result.getData());
                server.getUserManager().broadcastUserList();
                server.notify(ServerObserver.EventType.LOGIN, username + " 修改昵称为 " + argument);
            }
            return;
        }
        if ("CHANGE_AVATAR".equals(command)) {
            Result<User> result = userService.updateAvatar(username, argument);
            sendProfileResult(Constants.PROFILE_SECTION_AVATAR, result.isSuccess(), result.getMessage());
            if (result.isSuccess()) {
                server.getUserManager().updateProfile(result.getData());
                server.getUserManager().broadcastUserList();
                server.notify(ServerObserver.EventType.LOGIN, username + " 更新了头像");
            }
            return;
        }
        if ("DELETE_USER".equals(command)) {
            Result<Boolean> result = userService.deleteUser(username, argument);

            send(ChatMessageFactory.system(username, result.getMessage()));
            if (result.isSuccess()) {
                server.notify(ServerObserver.EventType.LOGIN, username + " 删除了用户 " + argument);
            }
            return;
        }
        send(ChatMessageFactory.error(username, "不支持的用户操作: " + command));
    }

    private void sendProfileResult(String section, boolean success, String reason) {
        TextMessage message = ChatMessageFactory.text(Constants.SYSTEM_SENDER,
                username == null ? "" : username,
                (success ? "1" : "0") + "|" + section + "|" + (reason == null ? "" : reason),
                MessageType.TEXT_PRIVATE);
        message.setType(MessageType.PROFILE_RESULT);
        send(message);
    }

    private void handlePasswordChange(TextMessage message) {
        if (!requireLogin()) {
            return;
        }
        String[] parts = ChatMessageFactory.splitFields(message.getContent());
        if (parts == null || parts.length < 2) {
            send(ChatMessageFactory.error(username, "密码修改请求格式错误"));
            return;
        }
        Result<Boolean> result = server.getUserService().updatePassword(username, parts[0], parts[1]);
        sendProfileResult(Constants.PROFILE_SECTION_PASSWORD, result.isSuccess(), result.getMessage());
        if (result.isSuccess()) {
            server.notify(ServerObserver.EventType.LOGIN, username + " 修改了密码");
        }
    }

    private void sendPlainResult(MessageType type, boolean success, String reason) {
        TextMessage message = ChatMessageFactory.text(Constants.SYSTEM_SENDER,
                username == null ? "" : username,
                (success ? "1" : "0") + "|" + reason, MessageType.TEXT_PRIVATE);
        message.setType(type);
        send(message);
    }

    private void sendLoginResult(boolean success, String reason, User user) {
        StringBuilder builder = new StringBuilder(success ? "1" : "0").append('|').append(reason);
        if (user != null) {
            builder.append('|').append(user.getUsername())
                    .append('|').append(user.getNickname())
                    .append('|').append(user.getRole());
        }
        TextMessage message = ChatMessageFactory.text(Constants.SYSTEM_SENDER,
                username == null ? "" : username, builder.toString(), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.LOGIN_RESULT);
        send(message);
    }

    /**
     * 广播用户上线并通知观察者。
     *
     * @param username 用户名
     * @param socket 客户端连接
     */
    @Override
    public void onUserOnline(String username, Socket socket) {
        server.getUserManager().broadcastSystem("用户 " + username + " 上线了");
        server.getUserManager().broadcastUserList();
        server.notify(ServerObserver.EventType.LOGIN, username + " 登录成功");
    }

    /**
     * 广播用户下线。
     *
     * @param username 用户名
     */
    @Override
    public void onUserOffline(String username) {
        server.getUserManager().broadcastSystem("用户 " + username + " 下线了");
        server.getUserManager().broadcastUserList();
    }

    private void sendUserList() {
        List<User> users = server.getUserManager().onlineUsers();
        TextMessage message = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                UserListCodec.encode(users), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.USER_LIST);
        send(message);
    }

    /** 处理历史记录分页查询。 */
    private void handleHistoryRequest(TextMessage message) {
        if (!requireLogin()) {
            return;
        }
        // 解析查询字段
        String[] parts = ChatMessageFactory.splitFields(message.getContent());
        if (parts == null) {
            LOGGER.warning(() -> "历史查询报文非法，已拒绝: " + username);
            sendHistoryError("历史查询请求格式错误或字段超限");
            return;
        }
        String peer = parts.length > 3 ? parts[3].trim() : "";

        // 校验会话对象用户名
        boolean virtualMember = Constants.AI_USERNAME.equals(peer);
        if (!peer.isEmpty() && !virtualMember && !USERNAME_PATTERN.matcher(peer).matches()) {
            LOGGER.warning(() -> "历史查询会话对象非法，已拒绝: " + username + " - " + peer);
            sendHistoryError("会话对象用户名非法");
            return;
        }

        String cursorField = parts.length > 4 ? parts[4].trim() : "";
        // 解析分页游标
        Long beforeId = null;
        if (!cursorField.isEmpty()) {
            beforeId = parseHistoryCursor(cursorField);
            if (beforeId == null) {
                LOGGER.warning(() -> "历史查询分页游标非法，已拒绝: " + username + " - " + cursorField);
                sendHistoryError("分页游标非法");
                return;
            }
        }
        String limitField = parts.length > 5 ? parts[5].trim() : "";
        // 解析每页条数
        int limit = HISTORY_PAGE_DEFAULT_LIMIT;
        if (!limitField.isEmpty()) {
            Integer parsed = parseHistoryLimit(limitField);
            if (parsed == null) {
                LOGGER.warning(() -> "历史查询分页条数非法，已拒绝: " + username + " - " + limitField);
                sendHistoryError("每页条数需为 " + HISTORY_PAGE_MIN_LIMIT + "-"
                        + HISTORY_PAGE_MAX_LIMIT + " 的整数");
                return;
            }
            limit = parsed;
        }
        // 解析起止日期
        LocalDateTime from = parts.length > 1 && !parts[1].isEmpty()
                ? DateUtil.parse(parts[1] + " 00:00:00") : null;
        LocalDateTime to = parts.length > 2 && !parts[2].isEmpty()
                ? DateUtil.parse(parts[2] + " 23:59:59") : null;

        String target = username;
        MessageService messageService = server.getMessageService();
        StringBuilder builder = new StringBuilder();
        // 查询并组装结果行
        try {
            List<Message> messages = messageService.queryPage(target, peer, from, to, beforeId, limit);
            builder.append('1').append('|').append(messages.size()).append('|').append(peer)
                    .append('|').append(HISTORY_ROW_VERSION);

            builder.append('\n').append(HISTORY_CURSOR_PREFIX)
                    .append(messages.isEmpty() ? "" : messages.get(0).getId());
            for (Message item : messages) {
                builder.append('\n')
                        .append(DateUtil.format(item.getTimestamp())).append('|')
                        .append(item.getSender()).append('|')
                        .append(item.isBroadcast() ? Constants.BROADCAST_TAG : item.getReceiver())
                        .append('|').append(item.getMessageId() == null ? "" : item.getMessageId())
                        .append('|')
                        .append(MessageExporter.contentOf(item));
            }
        } catch (ChatException e) {
            LOGGER.warning(() -> "历史查询失败: " + username + " - " + e.getMessage());
            builder.append('0').append('|').append("查询失败: " + e.getMessage());
        }
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                builder.toString(), MessageType.TEXT_PRIVATE);
        response.setType(MessageType.HISTORY_RESULT);
        send(response);
    }

    private Long parseHistoryCursor(String value) {
        try {
            long cursor = Long.parseLong(value);
            return cursor > 0 ? cursor : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseHistoryLimit(String value) {
        int limit;
        try {
            limit = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
        if (limit < HISTORY_PAGE_MIN_LIMIT) {
            return null;
        }
        return Math.min(limit, HISTORY_PAGE_MAX_LIMIT);
    }

    /** 处理聊天记录搜索请求。 */
    private void handleSearchRequest(TextMessage message) {
        if (!requireLogin()) {
            return;
        }
        String keyword = message.getContent() == null ? "" : message.getContent().trim();
        if (keyword.isEmpty()) {
            sendSearchResult("0|搜索关键字不能为空");
            return;
        }
        if (keyword.length() > Constants.MESSAGE_MAX_LENGTH) {
            LOGGER.warning(() -> "搜索关键字超长，已拒绝: " + username);
            sendSearchResult("0|搜索关键字过长，最多 " + Constants.MESSAGE_MAX_LENGTH + " 个字符");
            return;
        }
        Result<List<Message>> result = server.getMessageService().search(username, keyword);
        StringBuilder builder = new StringBuilder();
        if (!result.isSuccess()) {
            builder.append('0').append('|').append(result.getMessage());
        } else {
            List<Message> messages = result.getData();
            builder.append('1').append('|').append(messages.size())
                    .append('|').append(HISTORY_ROW_VERSION);
            for (Message item : messages) {
                builder.append('\n')
                        .append(DateUtil.format(item.getTimestamp())).append('|')
                        .append(item.getSender()).append('|')
                        .append(item.isBroadcast() ? Constants.BROADCAST_TAG : item.getReceiver())
                        .append('|').append(item.getMessageId() == null ? "" : item.getMessageId())
                        .append('|')
                        .append(MessageExporter.contentOf(item));
            }
        }
        sendSearchResult(builder.toString());
    }

    private void sendSearchResult(String content) {
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                content, MessageType.TEXT_PRIVATE);
        response.setType(MessageType.SEARCH_RESULT);
        send(response);
    }

    /** 处理消息撤回请求。 */
    private void handleRecallRequest(TextMessage message) {
        if (!requireLogin()) {
            return;
        }
        String messageId = message.getContent() == null ? "" : message.getContent().trim();
        if (messageId.isEmpty()) {
            sendRecallReply("FAIL||缺少消息标识，无法撤回");
            return;
        }
        if (messageId.length() > Constants.MESSAGE_MAX_LENGTH) {
            LOGGER.warning(() -> "撤回请求的消息标识超长，已拒绝: " + username);
            sendRecallReply("FAIL||消息标识过长，无法撤回");
            return;
        }
        Result<Boolean> result;
        try {
            result = server.getMessageService().recall(messageId, username);
        } catch (ChatException e) {
            LOGGER.warning(() -> "消息撤回失败: " + username + " - " + messageId + " - " + e.getMessage());
            sendRecallReply("FAIL|" + messageId + "|撤回失败：" + e.getMessage());
            return;
        }
        if (!result.isSuccess()) {
            LOGGER.info(() -> "消息撤回被拒绝: " + username + " - " + messageId + " - " + result.getMessage());
            sendRecallReply("FAIL|" + messageId + "|" + result.getMessage());
            return;
        }
        sendRecallReply("OK|" + messageId);
        notifyRecallPeer(messageId);
    }

    /** 向会话对端或群内其他用户发送撤回通知。 */
    private void notifyRecallPeer(String messageId) {
        Message original;
        try {
            original = server.getMessageService().findByMessageId(messageId);
        } catch (ChatException e) {
            LOGGER.warning(() -> "撤回通知查询原消息失败: " + messageId + " - " + e.getMessage());
            return;
        }
        if (original == null) {
            return;
        }
        String peer = original.getReceiver();
        if (original.isBroadcast() || peer == null || peer.isEmpty()) {
            TextMessage groupNotice = ChatMessageFactory.text(Constants.SYSTEM_SENDER, "",
                    "PEER|" + messageId + "|" + username, MessageType.TEXT_GROUP);
            groupNotice.setType(MessageType.MSG_RECALL);
            for (String name : server.getUserManager().onlineNames()) {
                if (!username.equals(name)) {
                    server.getUserManager().sendTo(name, groupNotice);
                }
            }
            return;
        }
        if (username.equals(peer) || !server.getUserManager().isOnline(peer)) {
            return;
        }
        TextMessage notice = ChatMessageFactory.text(Constants.SYSTEM_SENDER, peer,
                "PEER|" + messageId + "|" + username, MessageType.TEXT_PRIVATE);
        notice.setType(MessageType.MSG_RECALL);
        server.getUserManager().sendTo(peer, notice);
    }

    private void sendRecallReply(String content) {
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                content, MessageType.TEXT_PRIVATE);
        response.setType(MessageType.MSG_RECALL);
        send(response);
    }

    private void sendHistoryError(String reason) {
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                "0|" + reason, MessageType.TEXT_PRIVATE);
        response.setType(MessageType.HISTORY_RESULT);
        send(response);
    }

    /** 处理聊天记录导出请求。 */
    private void handleExportRequest(Socket socket, TextMessage request) {
        if (!requireLogin()) {
            return;
        }
        String scope = request == null || request.getContent() == null
                ? "" : request.getContent().trim();
        Result<List<Message>> result = server.getMessageService()
                .queryHistory(username, (LocalDateTime) null, null);
        String content;
        if (!result.isSuccess()) {
            content = "0|" + result.getMessage();
        } else {
            List<Message> rows = filterExportScope(result.getData(), scope);
            if (rows.isEmpty()) {
                content = "0|" + (scope.isEmpty() ? "没有可导出的记录" : "这个会话里还没有可导出的记录");
            } else {
                content = "1|" + rows.size() + "\n"
                        + MessageExporter.render(rows, localAddressOf(socket));
            }
        }
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                content, MessageType.TEXT_PRIVATE);
        response.setType(MessageType.EXPORT_RESULT);
        send(response);
    }

    /** 按导出范围过滤聊天记录。 */
    private List<Message> filterExportScope(List<Message> rows, String scope) {
        if (rows == null || rows.isEmpty() || scope == null || scope.isEmpty()) {
            return rows == null ? List.of() : rows;
        }
        // 导出范围以 # 开头表示指定会话
        boolean group = Constants.BROADCAST_SCOPE.equals(scope);
        String peer = group ? null : scope.substring(1);
        List<Message> filtered = new java.util.ArrayList<>();
        for (Message row : rows) {
            if (row.isBroadcast()) {
                if (group) {
                    filtered.add(row);
                }
                continue;
            }
            if (group) {
                continue;
            }
            String sender = row.getSender() == null ? "" : row.getSender();
            String receiver = row.getReceiver() == null ? "" : row.getReceiver();

            if ((username.equals(sender) && peer.equals(receiver))
                    || (username.equals(receiver) && peer.equals(sender))) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private String localAddressOf(Socket socket) {
        if (socket == null || socket.getLocalAddress() == null) {
            return "";
        }
        return socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
    }

    private String clientAddress() {
        InetAddress address = socket.getInetAddress();
        return address == null ? "unknown" : address.getHostAddress();
    }

    private boolean requireLogin() {
        if (username == null) {
            send(ChatMessageFactory.error(null, "请先登录后再进行操作"));
            return false;
        }
        return true;
    }

    /**
     * 清理连接、在线状态与传输会话。
     */
    @Override
    public void onConnectionClosed() {
        if (closed) {
            return;
        }
        closed = true;
        String name = username;
        // 从服务器连接表移除
        server.removeHandler(this);
        // 注销在线用户
        if (name != null) {
            server.getUserManager().unregister(name);

            // 清理该用户的文件传输
            TRANSFER_REGISTRY.values().removeIf(session -> session.involves(name));
            onUserOffline(name);
            LOGGER.info(() -> "用户连接已清理: " + name);
        }
        closeQuietly(in, "输入流");
        closeQuietly(out, "输出流");
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "socket 关闭异常（忽略）", e);
        }
        server.getUserManager().broadcastUserList();
    }

    private void closeQuietly(Closeable resource, String name) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, name + "关闭异常（忽略）", e);
        }
    }

    /** 关闭连接并执行清理。 */
    public void close() {
        onConnectionClosed();
    }

    public String getUsername() {
        return username;
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }

    /** 更新最近活跃时间。 */
    public void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    /**
     * 判断连接是否已登录。
     *
     * @return 已登录返回 true
     */
    public boolean isLoggedIn() {
        return username != null;
    }

    public String getRemoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    /**
     * 返回进行中的文件传输编号集合。
     *
     * @return 传输编号集合
     */
    public static Set<String> activeTransfers() {
        return Set.copyOf(TRANSFER_REGISTRY.keySet());
    }

    /**
     * 判断连接是否心跳超时。
     *
     * @param now 当前时间戳，单位毫秒
     * @return 超时返回 true
     */
    public boolean isTimeout(long now) {
        return now - lastActiveTime > heartbeatTimeoutMs;
    }

    @Override
    public String toString() {
        return "ClientHandler{user=" + username + ", remote=" + getRemoteAddress() + "}";
    }

    /**
     * 记录一次文件传输的参与者与状态。
     */
    private static final class TransferSession {
        private final String sender;

        private final String receiver;

        private volatile boolean accepted;

        private volatile boolean ended;

        private TransferSession(String sender, String receiver) {
            this.sender = sender;
            this.receiver = receiver;
        }

        private boolean involves(String name) {
            return sender.equals(name) || receiver.equals(name);
        }

        private String peerOf(String name) {
            return sender.equals(name) ? receiver : sender;
        }
    }
}

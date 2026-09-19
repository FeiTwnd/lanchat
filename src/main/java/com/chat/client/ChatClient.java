package com.chat.client;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.Config;
import com.chat.common.FileMessage;
import com.chat.common.FileTransferCodec;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.Result;
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
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *   <li>心跳调度器 1 个，定期发送心跳包；</li>
 *   <li>文件发送线程按需创建，避免大文件阻塞聊天消息。</li>
 * </ul>
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

    /** 连接状态 */
    private volatile boolean connected;

    /** 当前登录用户名 */
    private volatile String username;

    /** 当前登录昵称，登录成功后由服务器下发 */
    private volatile String nickname;

    /** 最近一次登录失败原因，供界面与自动化测试读取 */
    private volatile String lastLoginFailure = "";

    /** 登录结果等待锁，便于命令行与测试同步等待登录完成 */
    private transient CountDownLatch loginLatch;

    /**
     * 构造客户端。
     */
    public ChatClient() {
        // 无状态初始化，连接时再创建线程与流
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
        if (connected) {
            LOGGER.warning("客户端已连接，忽略重复连接请求");
            return true;
        }
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), Constants.CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            // 先构造输出流并 flush，再构造输入流，避免两端同时等待对象流头部
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());
            connected = true;
            this.username = name;
            senderPool = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "chat-sender");
                thread.setDaemon(true);
                return thread;
            });
            receiverThread = new Thread(this::receiveLoop, "chat-receiver");
            receiverThread.setDaemon(true);
            receiverThread.start();
            startHeartbeat();
            notifyConnection(true, "已连接服务器 " + host + ":" + port);
            // 先重置等待锁再发送登录请求，避免服务器响应比锁初始化更快导致永久等待
            loginLatch = new CountDownLatch(1);
            lastLoginFailure = "";
            send(loginMessage(name, password));
            LOGGER.info(() -> "已连接服务器并发送登录请求: " + name + "@" + host + ":" + port);
            return true;
        } catch (IOException e) {
            closeQuietly();
            String reason = "无法连接服务器 " + host + ":" + port + "（" + e.getMessage() + "）";
            LOGGER.log(Level.WARNING, reason, e);
            notifyConnection(false, reason);
            return false;
        }
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
            if (connected) {
                notifyConnection(false, "与服务器的连接已断开");
            }
        } catch (IOException e) {
            if (connected) {
                LOGGER.log(Level.WARNING, "接收消息异常: " + e.getMessage(), e);
                notifyConnection(false, "接收消息失败: " + e.getMessage());
            }
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
     * <p>文件数据块先交给本地接收会话落盘，再继续回调界面，
     * 保证界面读到的进度与磁盘状态一致。</p>
     *
     * @param message 消息
     */
    private void handleIncoming(Message message) {
        if (message.getType() == MessageType.LOGIN_RESULT) {
            handleLoginResult(message);
        }
        if (message.getType() == MessageType.FILE_CHUNK) {
            handleIncomingChunk((FileMessage) message);
            return;
        }
        if (message.getType() == MessageType.FILE_END) {
            handleIncomingEnd((FileMessage) message);
            return;
        }
        if (message.getType() == MessageType.FILE_ACCEPT) {
            handleIncomingAccept((FileMessage) message);
        }
        notifyMessage(message);
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
        notifyMessage(resultMessage(source, source.getSender(), false, "文件接收失败: " + reason));
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
     * @param receiver 接收者用户名
     * @param content  正文
     * @return 发送成功返回 true
     */
    public boolean sendPrivateText(String receiver, String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        if (content.length() > Constants.MESSAGE_MAX_LENGTH) {
            notifyMessage(ChatMessageFactory.error(username, "消息过长，最多 "
                    + Constants.MESSAGE_MAX_LENGTH + " 个字符"));
            return false;
        }
        return send(ChatMessageFactory.text(username, receiver, content, MessageType.TEXT_PRIVATE));
    }

    /**
     * 发送群聊消息。
     *
     * @param content 正文
     * @return 发送成功返回 true
     */
    public boolean sendGroupText(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        if (content.length() > Constants.MESSAGE_MAX_LENGTH) {
            notifyMessage(ChatMessageFactory.error(username, "消息过长，最多 "
                    + Constants.MESSAGE_MAX_LENGTH + " 个字符"));
            return false;
        }
        return send(ChatMessageFactory.text(username, "", content, MessageType.TEXT_GROUP));
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
        String body = (target == null ? "" : target) + "|" + (fromDate == null ? "" : fromDate)
                + "|" + (toDate == null ? "" : toDate);
        TextMessage message = ChatMessageFactory.text(username, Constants.SYSTEM_SENDER, body,
                MessageType.TEXT_PRIVATE);
        message.setType(MessageType.HISTORY_REQUEST);
        return send(message);
    }

    /**
     * 启动心跳任务。
     */
    private void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatScheduler.scheduleWithFixedDelay(() -> {
            if (connected) {
                send(ChatMessageFactory.control(username, Constants.SYSTEM_SENDER, MessageType.HEARTBEAT));
            }
        }, Constants.HEARTBEAT_INTERVAL_MS, Constants.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
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
     */
    public void close() {
        if (!connected && senderPool == null) {
            return;
        }
        connected = false;
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
        if (senderPool != null) {
            senderPool.shutdownNow();
            senderPool = null;
        }
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

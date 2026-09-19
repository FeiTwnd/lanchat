package com.chat.server;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.FileTransferCodec;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.Result;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.common.UserListCodec;
import com.chat.service.MessageService;
import com.chat.service.UserService;
import com.chat.util.DateUtil;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 单个客户端连接的处理器。
 *
 * <p>职责：每个客户端连接独占一个 {@link ClientHandler} 实例，由服务器线程池分配线程执行
 * {@link #run()}。该实例负责：</p>
 * <ol>
 *   <li>读取并反序列化客户端发来的消息；</li>
 *   <li>按消息类型完成路由：私聊定向转发、群聊广播、文件消息中继、历史查询响应；</li>
 *   <li>维护连接状态：登录用户、最近活跃时间；</li>
 *   <li>在连接结束时清理在线状态与资源。</li>
 * </ol>
 *
 * <p>继承体系说明：本类继承 {@link AbstractMessageHandler}，因此“按类型分派”的骨架由父类提供，
 * 本类只实现与连接强相关的处理逻辑，体现模板方法模式。</p>
 *
 * <p>并发说明：发送操作在 {@link #sendLock} 上同步，避免心跳包与聊天消息的字节流交错；
 * 读取只在 {@link #run()} 单线程中进行，天然串行。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class ClientHandler extends AbstractMessageHandler implements Runnable {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".ClientHandler");

    /** 服务器全局传输登记表：传输编号 -> 相关用户名，用于校验文件消息的合法性 */
    private static final Map<String, String> TRANSFER_REGISTRY = new ConcurrentHashMap<>();

    /** 客户端连接 */
    private final Socket socket;

    /** 服务器引用，用于访问在线表与业务服务 */
    private final ChatServer server;

    /** 单文件大小上限（字节），取自服务器配置，保证提示文本与实际取值一致 */
    private final long maxFileSize;

    /** 心跳超时阈值（毫秒），取自服务器配置 */
    private final long heartbeatTimeoutMs;

    /** 对象输入流，读取客户端消息 */
    private ObjectInputStream in;

    /** 对象输出流，向客户端发送消息 */
    private ObjectOutputStream out;

    /** 发送锁 */
    private final Object sendLock = new Object();

    /** 当前登录用户名，未登录时为 null */
    private volatile String username;

    /** 最近活跃时间（任何消息都会刷新），用于心跳超时判定 */
    private volatile long lastActiveTime = System.currentTimeMillis();

    /** 连接是否已关闭 */
    private volatile boolean closed;

    /**
     * 构造连接处理器。
     *
     * @param socket 已接受的客户端连接
     * @param server 服务器实例
     */
    public ClientHandler(Socket socket, ChatServer server, long maxFileSize, long heartbeatTimeoutMs) {
        this.socket = socket;
        this.server = server;
        this.maxFileSize = maxFileSize;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    /**
     * 连接主循环。
     *
     * <p>流程：初始化流对象 -> 发送欢迎语 -> 循环读取消息并分派 -> 无论何种退出路径都在
     * {@code finally} 中执行资源清理。这保证异常断开与正常退出走同一套善后逻辑。</p>
     */
    @Override
    public void run() {
        try {
            initStreams();
            send(ChatMessageFactory.system("", "已连接聊天服务器，请先登录。"));
            while (!closed) {
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
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "连接读取异常", e);
        } catch (ClassNotFoundException e) {
            LOGGER.warning("反序列化失败，可能是协议版本不一致: " + e.getMessage());
            send(ChatMessageFactory.error(username, "消息格式无法识别，请确认客户端版本一致"));
        } finally {
            onConnectionClosed();
        }
    }

    /**
     * 初始化对象输入输出流。
     *
     * <p>必须先用输出流写一次头部，再构造输入流，否则两端会互相等待，是 Java 对象流最经典的死锁陷阱。</p>
     *
     * @throws IOException 初始化失败时抛出
     */
    private void initStreams() throws IOException {
        // 先创建输出流并 flush，确保对象流头部先行发送，避免两端同时阻塞在读取头部上
        out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in = new ObjectInputStream(socket.getInputStream());
        out.reset();
    }

    /**
     * 获取连接标识，用于日志。
     *
     * @return 已登录时返回用户名，否则返回远端地址
     */
    private String identify() {
        return username != null ? username : String.valueOf(socket.getRemoteSocketAddress());
    }

    /**
     * 线程安全地发送一条消息。
     *
     * @param message 待发送消息
     * @return 发送成功返回 true；连接已关闭或发送失败返回 false
     */
    public boolean send(Message message) {
        if (message == null || closed) {
            return false;
        }
        synchronized (sendLock) {
            try {
                out.writeObject(message);
                out.flush();
                // 定期重置对象流缓存，防止长时间连接持有大量已发送对象的引用（内存泄漏）
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
     * 处理文本消息：私聊定向转发，群聊广播。
     *
     * <p>安全要点：服务器强制以“连接已登录的用户名”覆盖消息中的发送者字段，
     * 防止恶意客户端伪造他人身份发送消息。</p>
     *
     * @param message 文本消息
     * @param socket  来源连接
     */
    @Override
    public void handleTextMessage(Message message, Socket socket) {
        if (!requireLogin()) {
            return;
        }
        message.setSender(username);
        if (message.getType() == MessageType.TEXT_GROUP) {
            message.setReceiver("");
            server.getUserManager().broadcastText(message);
            server.getMessageService().saveMessage(message);
            server.notify(ServerObserver.EventType.GROUP_MESSAGE,
                    username + " 群发: " + message.getSummary());
            return;
        }
        String receiver = message.getReceiver();
        if (receiver == null || receiver.isEmpty()) {
            send(ChatMessageFactory.error(username, "私聊消息缺少接收者"));
            return;
        }
        boolean delivered = server.getUserManager().sendTo(receiver, message);
        if (!delivered) {
            // 接收者不在线时明确回执失败，而不是静默丢弃
            send(ChatMessageFactory.error(username, "用户 " + receiver + " 不在线，消息未送达"));
            return;
        }
        server.getMessageService().saveMessage(message);
        // 回执给发送方，使其界面确认消息已发出
        TextMessage echo = ChatMessageFactory.text(username, username, message.getSummary(),
                MessageType.TEXT_PRIVATE);
        send(echo);
        server.notify(ServerObserver.EventType.PRIVATE_MESSAGE,
                username + " -> " + receiver + ": " + message.getSummary());
    }

    /**
     * 处理文件消息：在收发双方之间中继。
     *
     * <p>服务器不落盘、不拆包，只做“转发 + 合法性校验”，
     * 这样既能满足多人同时传文件的需求，又避免服务器成为磁盘与带宽瓶颈。</p>
     *
     * @param message 文件消息
     * @param socket  来源连接
     */
    @Override
    public void handleFileMessage(Message message, Socket socket) {
        if (!requireLogin()) {
            return;
        }
        message.setSender(username);
        // 文件请求的元信息由文本消息承载，需要先还原成 FileMessage；
        // 数据块与结束帧本身已是 FileMessage，可直接使用
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
        switch (fileMessage.getType()) {
            case FILE_REQUEST:
                // 转发原始消息对象（而非解析后的副本），
                // 保证接收方拿到的消息形态与发送方发出的完全一致，避免协议两端口径不一致
                handleFileRequest(message, fileMessage, receiver);
                break;
            case FILE_ACCEPT:
                TRANSFER_REGISTRY.put(fileMessage.getTransferId(), username);
                relay(fileMessage, receiver);
                break;
            case FILE_REJECT:
            case FILE_RESULT:
                TRANSFER_REGISTRY.remove(fileMessage.getTransferId());
                relay(fileMessage, receiver);
                break;
            case FILE_CHUNK:
            case FILE_END:
                relay(fileMessage, receiver);
                break;
            default:
                send(ChatMessageFactory.error(username, "不支持的文件消息类型: " + fileMessage.getType()));
                break;
        }
    }

    /**
     * 把 FILE_REQUEST 还原为 {@link FileMessage}。
     *
     * <p>其余文件消息由客户端直接以 {@link FileMessage} 对象发送，无需转换。</p>
     *
     * @param message 原始消息
     * @return 文件请求消息；无法解析时返回 null
     */
    private FileMessage decodeFileRequest(Message message) {
        if (message instanceof FileMessage) {
            return (FileMessage) message;
        }
        if (message instanceof TextMessage) {
            TextMessage text = (TextMessage) message;
            return FileTransferCodec.decode(text.getContent(), text.getSender(), text.getReceiver());
        }
        return null;
    }

    /**
     * 处理文件传输请求：校验接收者在线并转发原始消息。
     *
     * @param original 原始消息（可能是文本承载的请求）
     * @param request  解析后的文件请求
     * @param receiver 接收者用户名
     */
    private void handleFileRequest(Message original, FileMessage request, String receiver) {
        if (request.getFileSize() > maxFileSize) {
            send(ChatMessageFactory.error(username,
                    "文件超过大小上限 " + com.chat.util.FileUtil.humanSize(maxFileSize)));
            return;
        }
        if (!server.getUserManager().isOnline(receiver)) {
            send(ChatMessageFactory.error(username, "用户 " + receiver + " 不在线，无法发送文件"));
            return;
        }
        TRANSFER_REGISTRY.put(request.getTransferId(), receiver);
        relay(original, receiver);
        server.notify(ServerObserver.EventType.FILE_TRANSFER,
                username + " 请求向 " + receiver + " 发送 " + request.getSummary());
    }

    /**
     * 把消息转发给指定在线用户。
     *
     * @param message  消息
     * @param receiver 接收者用户名
     */
    private void relay(Message message, String receiver) {
        boolean delivered = server.getUserManager().sendTo(receiver, message);
        if (!delivered) {
            send(ChatMessageFactory.error(username, "用户 " + receiver + " 不在线，传输已中断"));
        }
    }

    /**
     * 处理系统控制类消息：登录、注册、注销、心跳、用户列表、历史查询、资料修改。
     *
     * @param message 控制消息
     * @param socket  来源连接
     */
    @Override
    public void handleSystemMessage(Message message, Socket socket) {
        switch (message.getType()) {
            case LOGIN:
                handleLogin((TextMessage) message);
                break;
            case REGISTER:
                handleRegister((TextMessage) message);
                break;
            case LOGOUT:
                close();
                break;
            case HEARTBEAT:
                send(ChatMessageFactory.control(Constants.SYSTEM_SENDER, username,
                        MessageType.HEARTBEAT_ACK));
                break;
            case USER_LIST_REQUEST:
                sendUserList();
                break;
            case USER_UPDATE:
                handleUserUpdate((TextMessage) message);
                break;
            case PASSWORD_CHANGE:
                handlePasswordChange((TextMessage) message);
                break;
            case HISTORY_REQUEST:
                handleHistoryRequest((TextMessage) message);
                break;
            default:
                LOGGER.fine(() -> "收到未处理的控制消息: " + message.getType());
                break;
        }
    }

    /**
     * 处理登录请求。
     *
     * <p>消息正文格式：{@code 用户名|密码}。校验通过后注册到在线表并广播最新用户列表。</p>
     *
     * @param message 携带凭据的文本消息
     */
    private void handleLogin(TextMessage message) {
        if (username != null) {
            send(ChatMessageFactory.error(username, "当前连接已登录"));
            return;
        }
        String content = message.getContent();
        int separator = content.indexOf('|');
        if (separator <= 0) {
            sendLoginResult(false, "登录请求格式错误", null);
            return;
        }
        String name = content.substring(0, separator);
        String password = content.substring(separator + 1);
        Result<User> result = server.getUserService().login(name, password);
        if (!result.isSuccess()) {
            server.notify(ServerObserver.EventType.LOGIN_FAILED, name + " 登录失败: " + result.getMessage());
            sendLoginResult(false, result.getMessage(), null);
            return;
        }
        User user = result.getData();
        if (!server.getUserManager().register(user, this)) {
            sendLoginResult(false, "该账号已在其它位置登录", null);
            return;
        }
        this.username = user.getUsername();
        sendLoginResult(true, "登录成功，欢迎 " + user.getNickname(), user);
        onUserOnline(user.getUsername(), socket);
    }

    /**
     * 处理注册请求。
     *
     * <p>正文格式：{@code 用户名|密码|昵称}。注册与登录分离可避免“把注册塞进登录分支”
     * 造成的一处逻辑两种语义。</p>
     *
     * @param message 携带注册信息的文本消息
     */
    private void handleRegister(TextMessage message) {
        if (username != null) {
            send(ChatMessageFactory.error(username, "已登录状态下不能重复注册"));
            return;
        }
        String[] parts = message.getContent().split("\\|", -1);
        if (parts.length < 2) {
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

    /**
     * 处理用户资料变更请求。
     *
     * <p>正文格式：{@code 指令|参数}，支持的指令为：</p>
     * <ul>
     *   <li>{@code CHANGE_NICK|新昵称} —— 修改自己的昵称；</li>
     *   <li>{@code DELETE_USER|目标用户名} —— 删除用户，仅管理员可执行。</li>
     * </ul>
     *
     * @param message 携带指令的文本消息
     */
    private void handleUserUpdate(TextMessage message) {
        if (!requireLogin()) {
            return;
        }
        String[] parts = message.getContent().split("\\|", -1);
        String command = parts.length > 0 ? parts[0] : "";
        String argument = parts.length > 1 ? parts[1] : "";
        UserService userService = server.getUserService();
        if ("CHANGE_NICK".equals(command)) {
            Result<User> result = userService.updateNickname(username, argument);
            send(ChatMessageFactory.system(username, result.getMessage()));
            if (result.isSuccess()) {
                // 昵称属于用户列表展示字段，修改后必须广播新列表，否则各客户端显示不一致
                server.getUserManager().broadcastUserList();
                server.notify(ServerObserver.EventType.LOGIN, username + " 修改昵称为 " + argument);
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

    /**
     * 处理修改密码请求。
     *
     * <p>正文格式：{@code 原密码|新密码}。必须校验原密码，避免会话被劫持后直接改密。</p>
     *
     * @param message 携带密码信息的文本消息
     */
    private void handlePasswordChange(TextMessage message) {
        if (!requireLogin()) {
            return;
        }
        String[] parts = message.getContent().split("\\|", -1);
        if (parts.length < 2) {
            send(ChatMessageFactory.error(username, "密码修改请求格式错误"));
            return;
        }
        Result<Boolean> result = server.getUserService().updatePassword(username, parts[0], parts[1]);
        send(ChatMessageFactory.system(username, result.getMessage()));
        if (result.isSuccess()) {
            server.notify(ServerObserver.EventType.LOGIN, username + " 修改了密码");
        }
    }

    /**
     * 下发仅含“是否成功 + 说明”的结果消息（不含用户资料）。
     *
     * @param type    结果消息类型
     * @param success 是否成功
     * @param reason  结果说明
     */
    private void sendPlainResult(MessageType type, boolean success, String reason) {
        TextMessage message = ChatMessageFactory.text(Constants.SYSTEM_SENDER,
                username == null ? "" : username,
                (success ? "1" : "0") + "|" + reason, MessageType.TEXT_PRIVATE);
        message.setType(type);
        send(message);
    }

    /**
     * 下发登录结果。
     *
     * @param success 是否成功
     * @param reason  结果说明
     * @param user    登录用户（成功时非空）
     */
    private void sendLoginResult(boolean success, String reason, User user) {
        // 正文首位标记是否成功，其后为用户资料（用竖线分隔），便于客户端单帧解析
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
     * 用户上线后的处理：广播系统通知与最新在线列表。
     *
     * @param username 用户名
     * @param socket   连接
     */
    @Override
    public void onUserOnline(String username, Socket socket) {
        server.getUserManager().broadcastSystem("用户 " + username + " 上线了");
        server.getUserManager().broadcastUserList();
        server.notify(ServerObserver.EventType.LOGIN, username + " 登录成功");
    }

    /**
     * 用户下线后的处理：广播系统通知与最新在线列表。
     *
     * @param username 用户名
     */
    @Override
    public void onUserOffline(String username) {
        server.getUserManager().broadcastSystem("用户 " + username + " 下线了");
        server.getUserManager().broadcastUserList();
    }

    /**
     * 下发当前在线用户列表。
     */
    private void sendUserList() {
        List<User> users = server.getUserManager().onlineUsers();
        TextMessage message = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                UserListCodec.encode(users), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.USER_LIST);
        send(message);
    }

    /**
     * 处理历史记录查询请求。
     *
     * <p>正文格式：{@code 用户名|起始日期|结束日期}，日期可为空表示不限制。</p>
     *
     * @param message 携带查询条件的文本消息
     */
    private void handleHistoryRequest(TextMessage message) {
        if (!requireLogin()) {
            return;
        }
        String[] parts = message.getContent().split("\\|", -1);
        String target = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : username;
        LocalDateTime from = parts.length > 1 && !parts[1].isEmpty()
                ? DateUtil.parse(parts[1] + " 00:00:00") : null;
        LocalDateTime to = parts.length > 2 && !parts[2].isEmpty()
                ? DateUtil.parse(parts[2] + " 23:59:59") : null;
        MessageService messageService = server.getMessageService();
        Result<List<Message>> result = messageService.queryHistory(target, from, to);
        StringBuilder builder = new StringBuilder();
        if (!result.isSuccess()) {
            builder.append('0').append('|').append(result.getMessage());
        } else {
            List<Message> messages = result.getData();
            builder.append('1').append('|').append(messages.size());
            for (Message item : messages) {
                builder.append('\n')
                        .append(DateUtil.format(item.getTimestamp())).append('|')
                        .append(item.getSender()).append('|')
                        .append(item.isBroadcast() ? Constants.BROADCAST_TAG : item.getReceiver()).append('|')
                        .append(item.getSummary().replace('\n', ' '));
            }
        }
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                builder.toString(), MessageType.TEXT_PRIVATE);
        response.setType(MessageType.HISTORY_RESULT);
        send(response);
    }

    /**
     * 校验当前连接是否已登录。
     *
     * @return 已登录返回 true；未登录时回执错误并返回 false
     */
    private boolean requireLogin() {
        if (username == null) {
            send(ChatMessageFactory.error(null, "请先登录后再进行操作"));
            return false;
        }
        return true;
    }

    /**
     * 处理连接关闭：注销在线状态、清理传输登记、关闭 socket。
     *
     * <p>本方法可能被 {@link #run()} 的 finally 与心跳扫描线程同时调用，
     * 因此使用 {@code closed} 标志保证幂等。</p>
     */
    @Override
    public void onConnectionClosed() {
        if (closed) {
            return;
        }
        closed = true;
        String name = username;
        server.removeHandler(this);
        if (name != null) {
            server.getUserManager().unregister(name);
            TRANSFER_REGISTRY.values().removeIf(name::equals);
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

    /**
     * 静默关闭可关闭资源。
     *
     * @param resource 资源对象，可为 null
     * @param name     资源名称，用于日志
     */
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

    /**
     * 主动关闭连接。
     */
    public void close() {
        onConnectionClosed();
    }

    /**
     * 获取当前登录用户名。
     *
     * @return 用户名；未登录时返回 null
     */
    public String getUsername() {
        return username;
    }

    /**
     * 获取最近活跃时间戳。
     *
     * @return 毫秒时间戳
     */
    public long getLastActiveTime() {
        return lastActiveTime;
    }

    /**
     * 刷新活跃时间，供心跳扫描使用。
     */
    public void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    /**
     * 是否已登录。
     *
     * @return 已登录返回 true
     */
    public boolean isLoggedIn() {
        return username != null;
    }

    /**
     * 获取远端地址描述，用于服务器界面展示。
     *
     * @return 远端 IP 与端口
     */
    public String getRemoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    /**
     * 获取传输登记表中已知的传输编号集合，供测试与排查使用。
     *
     * @return 传输编号集合
     */
    public static Set<String> activeTransfers() {
        return Set.copyOf(TRANSFER_REGISTRY.keySet());
    }

    /**
     * 判定连接是否因心跳超时被判定掉线。
     *
     * @param now 当前时间戳
     * @return 超时返回 true
     */
    public boolean isTimeout(long now) {
        return now - lastActiveTime > heartbeatTimeoutMs;
    }

    /**
     * 输出连接信息，便于日志排查。
     *
     * @return 形如 {@code ClientHandler{user=alice, remote=/192.168.1.5:51000}} 的字符串
     */
    @Override
    public String toString() {
        return "ClientHandler{user=" + username + ", remote=" + getRemoteAddress() + "}";
    }
}

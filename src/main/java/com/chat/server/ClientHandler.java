package com.chat.server;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.FileTransferCodec;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.Result;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.common.UserListCodec;
import com.chat.exception.ChatException;
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

    /** 用户名格式校验器，预编译正则避免每条报文都重新解析模式串 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile(Constants.USERNAME_PATTERN);

    /** 传输编号长度上限：正常编号是 36 位 UUID，留出余量即可，过长的键只会白白占用内存 */
    private static final int MAX_TRANSFER_ID_LENGTH = 64;

    /** 消息确认状态：接收方在线且已转发成功 */
    private static final String ACK_DELIVERED = "DELIVERED";

    /** 消息确认状态：接收方离线，消息已入库等待补投 */
    private static final String ACK_OFFLINE = "OFFLINE";

    /** 消息确认状态：该稳定消息标识此前已处理过，本次为幂等重发 */
    private static final String ACK_DUPLICATE = "DUPLICATE";

    /** 消息确认状态：既未送达也未入库 */
    private static final String ACK_FAILED = "FAILED";

    /**
     * 单次登录补投的离线消息条数上限。
     *
     * <p>取 200 与数据访问层的单次查询上限保持一致：补投是登录路径上的同步动作，
     * 积压过多时宁可分批（下次登录继续），也不能让登录本身长时间卡住。</p>
     */
    private static final int OFFLINE_PUSH_LIMIT = 200;

    /** 下发最后一帧回执后排空接收队列的等待上限（毫秒），超时即关闭连接 */
    private static final int REPLY_CLOSE_DRAIN_MILLIS = 300;

    /** 历史记录分页的默认每页条数，与数据访问层的默认值保持一致 */
    private static final int HISTORY_PAGE_DEFAULT_LIMIT = 50;

    /** 历史记录分页每页条数的下限：至少要能取到一条记录才有意义 */
    private static final int HISTORY_PAGE_MIN_LIMIT = 1;

    /** 历史记录分页每页条数的上限，与数据访问层的单次查询上限保持一致 */
    private static final int HISTORY_PAGE_MAX_LIMIT = 200;

    /** 历史查询响应中分页游标行的前缀，客户端据此识别并跳过该行 */
    private static final String HISTORY_CURSOR_PREFIX = "#beforeId=";

    /**
     * 本连接已下发、等待客户端确认的离线消息标识。
     *
     * <p>只有确实由本连接补投出去的消息才允许被标记为已送达：否则任何登录用户都能拿别人的
     * 标识把他人积压的离线消息“确认”掉，造成静默丢消息。</p>
     */
    private final Set<String> pendingOfflineAcks = ConcurrentHashMap.newKeySet();

    /** 服务器全局传输登记表：传输编号 -> 本次传输的收发双方与阶段状态，用于校验文件消息的合法性 */
    private static final Map<String, TransferSession> TRANSFER_REGISTRY = new ConcurrentHashMap<>();

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
        } catch (java.io.InvalidClassException e) {
            // 反序列化白名单拒绝该类型：可能是恶意 gadget 链，也可能是两端协议版本不一致。
            // 必须用 WARNING 记录，FINE 级别默认不输出，事后将无从判断连接为何中断
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
        // 绑定反序列化白名单必须紧跟在创建之后：连接建立后的第一帧就可能携带恶意类型，
        // 任何一次 readObject 之前都必须完成绑定
        in.setObjectInputFilter(ChatMessageFactory.serializationFilter());
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
     * <p>类型与正文校验：文本类消息只应由 {@link TextMessage} 承载，且正文需满足长度与字符集约束。
     * 客户端可以自行构造任意 {@link Message} 子类并填上 TEXT_PRIVATE 类型，
     * 若此处直接按文本处理，畸形内容会被当作正常聊天记录入库并转发，
     * 因此先用 {@code instanceof} 确认实际类型，再交由工厂的校验方法判定正文。</p>
     *
     * @param message 文本消息
     * @param socket  来源连接
     */
    @Override
    public void handleTextMessage(Message message, Socket socket) {
        if (!requireLogin()) {
            return;
        }
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
        if (text.getType() == MessageType.TEXT_GROUP) {
            text.setReceiver("");
            server.getUserManager().broadcastText(text);
            saveOrWarn(text, "(群聊)");
            server.notify(ServerObserver.EventType.GROUP_MESSAGE,
                    username + " 群发: " + text.getSummary());
            return;
        }
        String receiver = text.getReceiver();
        if (receiver == null || receiver.isEmpty()) {
            send(ChatMessageFactory.error(username, "私聊消息缺少接收者"));
            return;
        }
        // 判重必须排在投递与落库之前：网络重发会把同一条消息再次送到这里，
        // 命中后既不能再转发（接收方会重复看到一条），也不能再落库（message_id 唯一索引会插入失败）
        String messageId = text.ensureMessageId();
        try {
            if (server.getMessageService().existsByMessageId(messageId)) {
                LOGGER.info(() -> "私聊消息重复，按幂等回执处理: " + username + " -> " + receiver
                        + " - " + messageId);
                sendAck(text, ACK_DUPLICATE, "该消息此前已处理过");
                return;
            }
        } catch (ChatException e) {
            // 判重失败意味着本次无法保证幂等，落库同样可能失败；此时给不出确定结论，
            // 只能回 FAILED 交由客户端重试，而不是硬着头皮转发造成重复
            LOGGER.warning(() -> "私聊消息判重失败: " + username + " -> " + receiver
                    + " - " + e.getMessage());
            sendAck(text, ACK_FAILED, "服务器无法校验消息标识：" + e.getMessage());
            return;
        }
        boolean delivered = server.getUserManager().sendTo(receiver, text);
        // 入库不再以“送达成功”为前提：接收方离线时这条记录正是补投的唯一依据
        Result<Boolean> saved = server.getMessageService().saveMessage(text);
        if (!saved.isSuccess()) {
            // 送达与入库必须同时成立才算成功。只送达而未入库时，接收方事后补拉历史会看不到这条，
            // 因此按契约回 FAILED（即便消息实际已被转发出去），由客户端重试
            LOGGER.warning(() -> "私聊消息入库失败: " + username + " -> " + receiver
                    + "，实际已送达=" + delivered + " - " + saved.getMessage());
            sendAck(text, ACK_FAILED, "服务器未能写入聊天记录：" + saved.getMessage());
            return;
        }
        if (!delivered) {
            LOGGER.info(() -> "私聊消息接收方离线，已入库待补投: " + username + " -> " + receiver);
            sendAck(text, ACK_OFFLINE, "接收方不在线，消息将在其上线后补投");
            // 保留这条可见错误提示：旧版客户端不认识 MSG_ACK，只能靠它得知消息没有立即送达；
            // 文案同步改为“已存入离线队列”，否则会与 OFFLINE 回执的语义自相矛盾
            send(ChatMessageFactory.error(username,
                    "用户 " + receiver + " 不在线，消息已存入离线队列，将在其上线后送达"));
            return;
        }
        markDeliveredQuietly(messageId, receiver);
        sendAck(text, ACK_DELIVERED, "");
        // 回执给发送方，使其界面确认消息已发出
        TextMessage echo = ChatMessageFactory.text(username, username, text.getSummary(),
                MessageType.TEXT_PRIVATE);
        send(echo);
        server.notify(ServerObserver.EventType.PRIVATE_MESSAGE,
                username + " -> " + receiver + ": " + text.getSummary());
    }

    /**
     * 向发送方回执一条消息的处理结果。
     *
     * <p>正文格式为 {@code messageId|state|detail}，三字段固定：客户端据此把本地待确认项
     * 映射为“已送达 / 对方离线 / 发送失败”，并停止重试。</p>
     *
     * @param target 原消息，用于取稳定消息标识
     * @param state  状态值，取 {@link #ACK_DELIVERED}、{@link #ACK_OFFLINE}、
     *               {@link #ACK_DUPLICATE} 或 {@link #ACK_FAILED}
     * @param detail 中文补充说明，可为空
     */
    private void sendAck(Message target, String state, String detail) {
        String messageId = target.ensureMessageId();
        String body = messageId + '|' + state + '|' + (detail == null ? "" : detail);
        TextMessage ack = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username, body,
                MessageType.TEXT_PRIVATE);
        ack.setType(MessageType.MSG_ACK);
        // 回执自身也分配一个独立标识，避免与原消息共用同一个 messageId 造成“两条报文同标识”
        ack.ensureMessageId();
        send(ack);
    }

    /**
     * 把在线投递成功的消息标记为已送达。
     *
     * <p>标记失败只记录 warning，不改变已回给发送方的 DELIVERED 结论：消息确实已经到达接收方，
     * 若因此改判失败，客户端重试后接收方会重复收到。代价是这条记录仍处于未投递状态，
     * 接收方下次登录会被再补投一次，由客户端的标识去重兜底。</p>
     *
     * @param messageId 稳定消息标识
     * @param receiver  接收方用户名，仅用于日志
     */
    private void markDeliveredQuietly(String messageId, String receiver) {
        try {
            server.getMessageService().markDelivered(List.of(messageId));
        } catch (ChatException e) {
            LOGGER.warning(() -> "消息已送达但送达标记更新失败: " + receiver + " - " + messageId
                    + " - " + e.getMessage());
        }
    }

    /**
     * 保存聊天记录，失败时告知发送方。
     *
     * <p>消息已经送达却没能入库，事后在聊天窗口里补拉历史时就看不到这条，
     * 使用者只会看到"对方说发了、我这里没有"的矛盾现象。因此保存失败必须回执给发送方，
     * 而不是只写一行服务器日志。</p>
     *
     * @param message 已送达的消息
     * @param peer    接收者描述，仅用于日志
     */
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
     * 处理文件消息：在收发双方之间中继。
     *
     * <p>服务器不落盘、不拆包，只做“转发 + 合法性校验”，
     * 这样既能满足多人同时传文件的需求，又避免服务器成为磁盘与带宽瓶颈。</p>
     *
     * <p>安全要点：文件消息的发送者、接收者、传输编号与所处阶段全部由客户端提供，
     * 服务器必须先核对再中继，否则任何人都能凭空注册一次传输、
     * 冒充他人推进别人的传输，或把数据帧投递给无关用户。</p>
     *
     * @param message 文件消息
     * @param socket  来源连接
     */
    @Override
    public void handleFileMessage(Message message, Socket socket) {
        if (!requireLogin()) {
            return;
        }
        // 发送者字段完全由客户端填写，而服务器随后会用它覆盖消息发送者并原样中继，
        // 不先核对就等于替伪造者背书
        if (!username.equals(message.getSender())) {
            LOGGER.warning(() -> "文件消息发送者与登录用户不一致，已拒绝: " + message.getSender()
                    + " / " + username);
            send(ChatMessageFactory.error(username, "文件消息发送者与登录用户不一致，已拒绝"));
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
        if (fileMessage.getTransferId() == null || fileMessage.getTransferId().isEmpty()
                || fileMessage.getTransferId().length() > MAX_TRANSFER_ID_LENGTH) {
            rejectFileMessage(fileMessage, "文件传输编号非法：不能为空且长度不超过 " + MAX_TRANSFER_ID_LENGTH);
            return;
        }
        if (!isValidPeer(receiver)) {
            rejectFileMessage(fileMessage, "文件消息接收者非法：格式不正确或指向本人");
            return;
        }
        switch (fileMessage.getType()) {
            case FILE_REQUEST:
                // 转发原始消息对象（而非解析后的副本），
                // 保证接收方拿到的消息形态与发送方发出的完全一致，避免协议两端口径不一致
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

    /**
     * 校验用户名是否为可用的会话对象。
     *
     * <p>为什么不额外查询数据库确认"用户存在"：只有登录成功（已在数据库中核验存在）
     * 的用户才会进入在线表，而文件传输本就要求对端在线，因此格式校验配合在线判定
     * 已足以排除任意字符串，也避免为每一次文件请求增加一次数据库往返。</p>
     *
     * @param peer 待校验用户名
     * @return 合法且不是本人返回 true
     */
    private boolean isValidPeer(String peer) {
        return peer != null && !peer.equals(username) && USERNAME_PATTERN.matcher(peer).matches();
    }

    /**
     * 拒绝一条非法的文件消息：记录 warning、回执中文错误，并清理已失效的登记项。
     *
     * <p>清理只针对当前连接是参与者的登记项：若不加这个限制，任意第三方伪造一条畸形报文
     * 就能把别人的正常传输从登记表里抹掉，拒绝非法消息的手段反而成了拒绝服务。</p>
     *
     * @param message 非法消息
     * @param reason  中文拒绝原因，同时回执给客户端
     */
    private void rejectFileMessage(FileMessage message, String reason) {
        String transferId = message == null ? null : message.getTransferId();
        TransferSession session = transferId == null ? null : TRANSFER_REGISTRY.get(transferId);
        if (session != null && session.involves(username)) {
            TRANSFER_REGISTRY.remove(transferId);
        }
        LOGGER.warning(() -> "文件消息被拒绝: " + username + " - " + reason);
        send(ChatMessageFactory.error(username, reason));
    }

    /**
     * 校验接收方的同意应答并推进登记状态。
     *
     * @param message 同意消息
     * @return 校验通过返回 true；否则回执错误并返回 false
     */
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

    /**
     * 校验数据块与结束帧是否可以中继。
     *
     * <p>状态约束的意义：未登记编号不得凭空进入传输阶段，未获接收方同意不得开始发送数据，
     * 已收到结束帧的传输不得再次结束——否则接收方会重复拼接同一段数据或重复写出文件。</p>
     *
     * @param message 数据块或结束消息
     * @return 校验通过返回 true；否则回执错误并返回 false
     */
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

    /**
     * 校验结束回执（结果或被拒绝）是否来自本次传输的合法参与者。
     *
     * @param message 结果或拒绝消息
     * @return 校验通过返回 true；否则回执错误并返回 false
     */
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

    /**
     * 把 FILE_REQUEST 还原为 {@link FileMessage}。
     *
     * <p>其余文件消息由客户端直接以 {@link FileMessage} 对象发送，无需转换。</p>
     *
     * @param message 原始消息
     * @return 文件请求消息；无法解析时返回 null
     */
    private FileMessage decodeFileRequest(Message message) {
        if (message instanceof FileMessage fileMessage) {
            return fileMessage;
        }
        if (message instanceof TextMessage text) {
            // 承载请求的正文是竖线分隔的控制报文，先做字段数量与字符集校验，
            // 避免超长字段数组进入解码器
            if (ChatMessageFactory.splitFields(text.getContent()) == null) {
                return null;
            }
            return FileTransferCodec.decode(text.getContent(), text.getSender(), text.getReceiver());
        }
        return null;
    }

    /**
     * 处理文件传输请求：校验接收者在线并登记本次传输，然后转发原始消息。
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
        // 使用 putIfAbsent 而不是 put：旧实现允许同一传输编号被反复覆盖登记，
        // 攻击者可借此把进行中的传输接收者改到自己名下，从而截获文件数据帧
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

    /**
     * 把一次已结束的文件传输写入聊天记录。
     *
     * <p>为什么记在"结果"而不是"请求"上：请求发出时还不知道对方接不接受，
     * 记下来会让被拒绝的文件在历史记录里显示成一条正常记录；结果消息是整个传输
     * 唯一的终态，成功、失败、被拒绝各只会出现一次，一次传输正好落一行。</p>
     *
     * <p>方向必须反过来写：结果与拒绝消息都由**文件接收方**发出，此刻连接的
     * {@code username} 是接收方，消息里的 receiver 才是文件的发送方；
     * 照抄字段会让历史记录的方向整个颠倒。</p>
     *
     * <p>入库的只有文件名、大小、校验和与结果描述，文件内容本身不落库——
     * 服务器只中继数据块，不承担文件存储。</p>
     *
     * <p>同时写两张表：{@code chat_message} 供聊天历史与导出展示，
     * {@code chat_file_log} 供按结果统计与追溯（见 {@code FileLogService}）。</p>
     *
     * @param result 结果消息（{@code FILE_RESULT} 或 {@code FILE_REJECT}）
     */
    private void saveTransferRecord(FileMessage result) {
        String fileSender = result.getReceiver();
        if (fileSender == null || fileSender.isEmpty()) {
            return;
        }
        FileMessage record = ChatMessageFactory.file(fileSender, username, MessageType.FILE_RESULT);
        record.setFileName(result.getFileName());
        record.setFileSize(result.getFileSize());
        record.setSha256(result.getSha256());
        // 成功回执的正文是"文件已保存到 <接收方机器上的路径>"，写进双方共有的聊天记录没有意义；
        // 失败与被拒绝的原因则对使用者有用，保留下来
        String detail = result.getMessage() == null ? "" : result.getMessage().trim();
        if (result.isAccepted()) {
            record.setMessage("文件传输完成");
        } else {
            record.setMessage(detail.isEmpty() ? "文件传输未完成" : "文件传输未完成：" + detail);
        }
        // 保存失败的提示给到发起回执的连接（即文件接收方），日志里带上文件发送方以便对照
        saveOrWarn(record, fileSender);
        // 审计表另记一行：传输编号与 SUCCESS/REJECTED/FAILED 结果在这里保留，便于统计与追溯
        server.getFileLogService().record(result, fileSender, username);
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
     * <p>类型校验说明：控制报文正文都放在 {@link TextMessage} 中，但客户端可以塞入任意子类。
     * 这里统一用 {@code instanceof} 模式匹配，而不是直接强转——强转失败抛出的
     * {@code ClassCastException} 不在 {@link #run()} 的捕获范围内，会让整个连接线程退出，
     * 等于一条畸形报文就能踢掉一个用户。现在只拒绝该条消息并留下 warning，连接继续可用。</p>
     *
     * @param message 控制消息
     * @param socket  来源连接
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
                handleExportRequest(socket);
                break;
            default:
                // 已登录用户发来服务器专属类型（如各 RESULT、HEARTBEAT_ACK）属于协议异常，
                // 必须留下可观测日志，否则这类报文会被静默丢弃、无从排查
                LOGGER.warning(() -> "收到服务器未处理的控制消息，已忽略: " + username
                        + " - " + message.getType());
                break;
        }
    }

    /**
     * 拒绝实际类型与要求不符的控制消息。
     *
     * @param message 类型不符的消息
     */
    private void rejectTextRequirement(Message message) {
        LOGGER.warning(() -> "控制消息的实际对象类型不符，已拒绝: "
                + message.getClass().getName() + " / " + message.getType());
        send(ChatMessageFactory.error(username, "控制消息格式错误，已拒绝"));
    }

    /**
     * 处理登录请求。
     *
     * <p>消息正文格式：{@code 用户名|密码}。校验通过后注册到在线表并广播最新用户列表。</p>
     *
     * <p>正文先经 {@link ChatMessageFactory#splitFields(String)} 做字段数量与字符集校验，
     * 超限的正文直接按格式错误处理，不进入业务层。</p>
     *
     * @param message 携带凭据的文本消息
     */
    private void handleLogin(TextMessage message) {
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
        // 登录来源用于失败限流的维度：只用用户名计数时，换用户名即可绕过锁定；
        // 带上来源后，"同一来源爆破同一账号"会更快触发锁定，而共用出口 IP 的教室场景
        // 仍以用户名为主键，不会互相误伤
        Result<User> result = server.getUserService().login(name, password, clientAddress());
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
        // 令牌先于离线补投下发：补投条数可能较多，先给令牌可避免客户端在这段时间里无法断线重连
        sendSessionResumeResult(server.issueSessionToken(user.getUsername()));
        pushOfflineMessages();
    }

    /**
     * 补投当前用户积压的离线私聊消息。
     *
     * <p>为什么要“补投 + 客户端逐条确认”而不是直接标记已送达：TCP 写入成功只代表数据交到了
     * 对端操作系统的缓冲区，客户端进程崩溃时消息仍会丢失。逐条确认后，未确认的记录保持
     * 未投递状态，下次登录重新补投，配合客户端按标识去重即实现“至少一次投递”。</p>
     *
     * <p>补投失败不得影响登录本身：无论查库还是发送出错都只记录 warning，
     * 用户照常进入聊天界面，剩余记录留待下次登录再试。</p>
     */
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
        for (Message item : pending) {
            // 只补投私聊文本：文件传输结果同样以 delivered=0 落库，但它承载的是文件名与校验和，
            // 按离线文本重发只会凭空多出一条无意义的聊天记录
            if (!(item instanceof TextMessage) || item.getType() != MessageType.TEXT_PRIVATE) {
                continue;
            }
            String messageId = item.getMessageId();
            if (messageId == null || messageId.trim().isEmpty()) {
                // 没有稳定标识就无法匹配客户端的确认回执，跳过以免每次登录都重复补投同一条
                continue;
            }
            // 复用从库里读出的对象、只改类型：正文、发送者、接收者、标识与时间戳因此与原消息完全一致
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

    /**
     * 处理客户端回执的消息确认（当前仅离线补投需要落库）。
     *
     * <p>正文格式：{@code messageId|state|detail}。只有本连接确实补投出去的消息才允许标记为
     * 已送达，否则任何登录用户都能拿别人的标识把他人积压的离线消息确认掉，造成静默丢消息。</p>
     *
     * @param message 客户端回执
     */
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
            // 服务端回给发送方的 DELIVERED/OFFLINE 回执由客户端处理，不会回流到这里；
            // 其余状态与补投落库无关，记录后忽略即可
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
            // 放回待确认集合：客户端重发回执时再试一次；标记始终失败也不会丢消息，
            // 因为记录仍是未投递状态，下次登录会重新补投
            pendingOfflineAcks.add(messageId);
            LOGGER.warning(() -> "离线消息送达标记失败: " + username + " - " + messageId
                    + " - " + e.getMessage());
        }
    }

    /**
     * 处理会话恢复请求：用令牌换取登录态，不需要口令。
     *
     * <p>为什么不校验口令：客户端在内存中长期保留明文口令只为断线重连，风险远高于一次性令牌；
     * 令牌由服务端内存表签发，有效期 30 分钟且每次使用后顺延。</p>
     *
     * <p>令牌无效时回 {@code EXPIRED|<中文原因>} 并关闭连接：客户端据此停止重连并提示重新登录，
     * 若只是静默断开，客户端会陷入无意义的指数退避重试。</p>
     *
     * @param message 携带令牌的文本消息
     */
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
        // 必须先清掉同一用户名的旧连接：重连时旧连接往往只是心跳失联而未被服务端判定超时，
        // 若直接注册，在线表里会同时存在两条会话，后续消息可能被投给那条已经死掉的连接
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

    /**
     * 下发会话恢复相关的回执。
     *
     * <p>成功时正文为 {@code OK}，失败时正文为 {@code EXPIRED|<中文原因>}；
     * 同一类型承载两种结果，客户端只按正文前缀分流。</p>
     *
     * @param content 回执正文
     */
    private void sendSessionResumeResult(String content) {
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER,
                username == null ? "" : username, content, MessageType.TEXT_PRIVATE);
        response.setType(MessageType.SESSION_RESUME);
        send(response);
    }

    /**
     * 回执下发完成后关闭连接。
     *
     * <p>为什么不能直接 {@link #close()}：对端刚发来的报文在接收队列里往往还留有未读字节
     * （对象流每次 {@code reset()} 后会补发一个控制字节），此时关闭套接字会让内核发出 RST，
     * 而 RST 会把尚未被应用读取的最后一帧一并丢弃——客户端读到的是“连接重置”而不是
     * EXPIRED 回执，也就无法区分“会话过期”与“网络断开”。因此先 {@code shutdownOutput()}
     * 让已写入的回执随 FIN 先行，再有界地排空接收队列，最后才真正关闭。</p>
     */
    private void closeAfterReply() {
        try {
            socket.shutdownOutput();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "关闭输出通道失败（忽略）", e);
        }
        try {
            // 对端收到 FIN 后通常会立即关闭，此处随即读到流结束；若对端不关闭，
            // 由读取超时兜底，避免这条已经结束的连接长期占用工作线程
            socket.setSoTimeout(REPLY_CLOSE_DRAIN_MILLIS);
            long deadline = System.currentTimeMillis() + REPLY_CLOSE_DRAIN_MILLIS;
            while (System.currentTimeMillis() < deadline && in.read() != -1) {
                // 只丢弃残余字节，不再解析：此刻连接即将关闭，任何内容都不再有意义
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "排空接收队列失败（忽略）", e);
        }
        close();
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
        String[] parts = ChatMessageFactory.splitFields(message.getContent());
        if (parts == null) {
            send(ChatMessageFactory.error(username, "用户资料变更请求格式错误"));
            return;
        }
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
        String[] parts = ChatMessageFactory.splitFields(message.getContent());
        if (parts == null || parts.length < 2) {
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
     * <p>正文格式：{@code 用户名|起始日期|结束日期|会话对象|上一页最小主键|每页条数}。
     * 日期可为空表示不限制；会话对象为空时返回"本人收发的全部记录 + 广播"，
     * 非空时只返回这两个人之间的私聊记录——私聊窗口在打开时用它补拉最近的对话。
     * 后两段是本轮新增的分页字段，旧客户端只发四段时按"最新一页 + 默认条数"处理，
     * 因此字段位置必须向后追加，不能插在中间。</p>
     *
     * <p>安全要点：正文里的第一个字段由客户端自行填写，服务器绝不能信任它——
     * 只要改写这一个字符串，任何登录用户都能读到别人的聊天记录，属于典型的水平越权。
     * 因此查询对象一律取本连接登录成功后保存的用户名，客户端提交的值仅保留字段位置以兼容旧协议，
     * 绝不参与查询；会话对象虽然只在本人记录范围内做二次过滤，也无法绕过越权，
     * 但仍需校验格式并排除本人，避免非法字符串进入后续比较与回显。</p>
     *
     * <p>响应正文格式：成功为 {@code 1|条数|会话对象}，随后一行 {@code #beforeId=游标}，
     * 再跟各记录行；失败为 {@code 0|原因}。游标行以 {@code #} 开头，供客户端翻页时回传。</p>
     *
     * @param message 携带查询条件的文本消息
     */
    private void handleHistoryRequest(TextMessage message) {
        if (!requireLogin()) {
            return;
        }
        String[] parts = ChatMessageFactory.splitFields(message.getContent());
        if (parts == null) {
            LOGGER.warning(() -> "历史查询报文非法，已拒绝: " + username);
            sendHistoryError("历史查询请求格式错误或字段超限");
            return;
        }
        String peer = parts.length > 3 ? parts[3].trim() : "";
        if (!peer.isEmpty() && !USERNAME_PATTERN.matcher(peer).matches()) {
            LOGGER.warning(() -> "历史查询会话对象非法，已拒绝: " + username + " - " + peer);
            sendHistoryError("会话对象用户名非法");
            return;
        }
        if (username.equals(peer)) {
            LOGGER.warning(() -> "历史查询会话对象为本人，已拒绝: " + username);
            sendHistoryError("会话对象不能是本人");
            return;
        }
        // 分页字段是本轮新增的，旧客户端只发四段：缺省即"最新一页 + 默认条数"
        String cursorField = parts.length > 4 ? parts[4].trim() : "";
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
        LocalDateTime from = parts.length > 1 && !parts[1].isEmpty()
                ? DateUtil.parse(parts[1] + " 00:00:00") : null;
        LocalDateTime to = parts.length > 2 && !parts[2].isEmpty()
                ? DateUtil.parse(parts[2] + " 23:59:59") : null;
        // 查询对象恒为服务端保存的登录用户名：客户端提交的第一个字段被刻意忽略
        String target = username;
        MessageService messageService = server.getMessageService();
        StringBuilder builder = new StringBuilder();
        try {
            // 分页下推到 SQL：历史记录总量大，先全量查回再截断既慢又浪费内存
            List<Message> messages = messageService.queryPage(target, peer, from, to, beforeId, limit);
            builder.append('1').append('|').append(messages.size()).append('|').append(peer);
            // 游标行恒定占一行（无记录时值为空），使客户端解析位置固定，不必做分支判断；
            // 取值用本页最小主键，与 DAO 的 id < 游标 语义对应，翻页时不会漏记录或重复
            builder.append('\n').append(HISTORY_CURSOR_PREFIX)
                    .append(messages.isEmpty() ? "" : messages.get(0).getId());
            for (Message item : messages) {
                builder.append('\n')
                        .append(DateUtil.format(item.getTimestamp())).append('|')
                        .append(item.getSender()).append('|')
                        .append(item.isBroadcast() ? Constants.BROADCAST_TAG : item.getReceiver()).append('|')
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

    /**
     * 解析历史分页游标。
     *
     * @param value 已确认非空的上一页最小主键
     * @return 合法的主键；非数字或非正数时返回 null，由调用方回执错误
     */
    private Long parseHistoryCursor(String value) {
        try {
            long cursor = Long.parseLong(value);
            return cursor > 0 ? cursor : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析历史分页条数。
     *
     * <p>超出上限时截断而不是拒绝：分页条数属于性能参数，客户端多填只会拖慢自己，
     * 服务端按上限收敛即可，没必要为此让整次查询失败。</p>
     *
     * @param value 已确认非空的每页条数
     * @return 解析并截断后的条数；非数字或小于下限时返回 null，由调用方回执错误
     */
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

    /**
     * 处理聊天记录关键字搜索请求。
     *
     * <p>正文为单个字段：关键字。检索必须走带请求者参数的
     * {@link MessageService#search(String, String)}，由服务层按"本人发送 / 发给本人 / 广播"
     * 过滤可见范围；直接使用数据访问层的全库检索会把别人的私聊内容回给搜索者，
     * 与历史查询曾经存在的水平越权同源。</p>
     *
     * <p>响应正文格式：成功为 {@code 1|命中条数} 加各命中行，失败为 {@code 0|中文原因}；
     * 行格式与历史查询保持一致，客户端可复用同一套渲染。</p>
     *
     * @param message 携带关键字的文本消息
     */
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
            builder.append('1').append('|').append(messages.size());
            for (Message item : messages) {
                builder.append('\n')
                        .append(DateUtil.format(item.getTimestamp())).append('|')
                        .append(item.getSender()).append('|')
                        .append(item.isBroadcast() ? Constants.BROADCAST_TAG : item.getReceiver()).append('|')
                        .append(MessageExporter.contentOf(item));
            }
        }
        sendSearchResult(builder.toString());
    }

    /**
     * 下发搜索结果。
     *
     * @param content 结果正文
     */
    private void sendSearchResult(String content) {
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                content, MessageType.TEXT_PRIVATE);
        response.setType(MessageType.SEARCH_RESULT);
        send(response);
    }

    /**
     * 处理消息撤回请求。
     *
     * <p>正文为单个字段：稳定消息标识。业务规则（是否存在、是否本人发送、是否超过时限、
     * 是否已撤回）全部由 {@link MessageService#recall(String, String)} 判断，
     * 网络层只负责捕获异常并转成中文回执，避免规则散落造成漏判。</p>
     *
     * <p>回执给发起者：成功 {@code OK|messageId}，失败 {@code FAIL|messageId|中文原因}。
     * 撤回成功后再通知会话对端，使对端界面上的气泡同步变为"已撤回"。</p>
     *
     * @param message 携带消息标识的文本消息
     */
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
            // 撤回请求的正文不经过 splitFields，长度必须在这里自行收敛，避免超长字符串进入数据库查询
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

    /**
     * 撤回成功后通知会话对端。
     *
     * <p>对端身份的权威来源是库里的原始记录，而不是请求正文：正文里的任何字段都可被伪造，
     * 若照抄客户端提交的接收者，撤回通知就能被用来向任意用户投递报文。</p>
     *
     * <p>对端离线时直接跳过：历史查询对已撤回记录返回的是占位文本，
     * 对端下次上线补拉历史看到的就是"已撤回"，通知并非必需，也不必为此缓存待发通知。</p>
     *
     * @param messageId 已撤回消息的稳定标识
     */
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
        // 群聊记录接收者为空，没有单一对端，本轮只回发起者
        if (peer == null || peer.isEmpty() || !server.getUserManager().isOnline(peer)) {
            return;
        }
        TextMessage notice = ChatMessageFactory.text(Constants.SYSTEM_SENDER, peer,
                "PEER|" + messageId + "|" + username, MessageType.TEXT_PRIVATE);
        notice.setType(MessageType.MSG_RECALL);
        server.getUserManager().sendTo(peer, notice);
    }

    /**
     * 下发撤回回执。
     *
     * @param content 回执正文，形如 {@code OK|messageId} 或 {@code FAIL|messageId|原因}
     */
    private void sendRecallReply(String content) {
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                content, MessageType.TEXT_PRIVATE);
        response.setType(MessageType.MSG_RECALL);
        send(response);
    }

    /**
     * 下发历史查询失败回执。
     *
     * @param reason 中文失败原因
     */
    private void sendHistoryError(String reason) {
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                "0|" + reason, MessageType.TEXT_PRIVATE);
        response.setType(MessageType.HISTORY_RESULT);
        send(response);
    }

    /**
     * 处理聊天记录导出请求。
     *
     * <p>正文为空：导出对象恒为发起者本人，不需要参数。导出必须由服务端查库、客户端落盘——
     * 客户端没有数据库连接，只有与数据库同机的那台能直连，其余机器要么报连接失败，
     * 要么在本机空库里查出 0 条（本项目正是踩过这个坑才改成现在这条链路）。</p>
     *
     * <p>响应正文格式：成功为 {@code 1|条数} 换行后接渲染好的导出文本；失败为 {@code 0|原因}。
     * 导出文本里的"数据来源"取连接的本端地址，即客户端实际连上的那台服务器。</p>
     *
     * @param socket 发起导出的连接，用于取本端地址作为数据来源
     */
    private void handleExportRequest(Socket socket) {
        if (!requireLogin()) {
            return;
        }
        Result<List<Message>> result = server.getMessageService()
                .queryHistory(username, (LocalDateTime) null, null);
        String content;
        if (!result.isSuccess()) {
            content = "0|" + result.getMessage();
        } else if (result.getData().isEmpty()) {
            content = "0|没有可导出的记录";
        } else {
            content = "1|" + result.getData().size() + "\n"
                    + MessageExporter.render(result.getData(), localAddressOf(socket));
        }
        TextMessage response = ChatMessageFactory.text(Constants.SYSTEM_SENDER, username,
                content, MessageType.TEXT_PRIVATE);
        response.setType(MessageType.EXPORT_RESULT);
        send(response);
    }

    /**
     * 取连接的本端地址，形如 {@code 192.168.1.5:9527}。
     *
     * @param socket 连接
     * @return 地址描述；无法获取时返回空字符串
     */
    private String localAddressOf(Socket socket) {
        if (socket == null || socket.getLocalAddress() == null) {
            return "";
        }
        return socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
    }

    /**
     * 取连接的客户端地址，用作登录失败限流的来源维度。
     *
     * <p>刻意不返回 {@code null}：拿不到地址时回退为 {@code unknown}，
     * 使限流退化为"仅按用户名"而不是整体失效——限流宁可稍宽，也不能因为取不到地址就形同不存在。</p>
     *
     * @return 客户端 IP 文本；无法获取时返回 {@code unknown}
     */
    private String clientAddress() {
        InetAddress address = socket.getInetAddress();
        return address == null ? "unknown" : address.getHostAddress();
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
            // 无论用户是发送方还是接收方，其参与的所有传输都已无法继续，登记项必须随之清理
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

    /**
     * 一次文件传输的登记信息。
     *
     * <p>为什么登记的不止一个用户名：文件消息跨越请求、应答、数据块、结束、结果五个阶段，
     * 每个阶段都要判断"发起者是不是本次传输的合法参与者、当前状态是否允许进入下一阶段"。
     * 只存对端用户名无法区分收发双方，也就无法识别"未同意就传数据""重复结束"这类非法跳转。</p>
     */
    private static final class TransferSession {

        /** 文件发送方（文件请求的发起者） */
        private final String sender;

        /** 文件接收方 */
        private final String receiver;

        /**
         * 接收方是否已同意接收。
         *
         * <p>该状态由接收方连接线程写入、发送方连接线程读取，故声明为 volatile 保证可见性。</p>
         */
        private volatile boolean accepted;

        /** 是否已收到结束帧，用于拒绝重复结束 */
        private volatile boolean ended;

        /**
         * 构造登记项。
         *
         * @param sender   文件发送方用户名
         * @param receiver 文件接收方用户名
         */
        private TransferSession(String sender, String receiver) {
            this.sender = sender;
            this.receiver = receiver;
        }

        /**
         * 判断指定用户是否为本次传输的参与者。
         *
         * @param name 用户名
         * @return 是发送方或接收方返回 true
         */
        private boolean involves(String name) {
            return sender.equals(name) || receiver.equals(name);
        }

        /**
         * 取指定参与者在本传输中的对端用户名。
         *
         * @param name 参与者用户名
         * @return 对端用户名；name 不是参与者时返回发送方，调用前应先用 {@link #involves(String)} 判断
         */
        private String peerOf(String name) {
            return sender.equals(name) ? receiver : sender;
        }
    }
}

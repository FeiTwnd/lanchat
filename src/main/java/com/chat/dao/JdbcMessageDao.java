package com.chat.dao;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;
import com.chat.exception.ChatException;
import com.chat.util.MessageCipher;
import com.chat.util.MessageExporter;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 聊天消息数据访问的 JDBC 实现。
 *
 * <p>职责：把聊天记录持久化到 {@code chat_message} 表，并支持"用户 + 时间范围"检索、
 * 关键字模糊检索与导出。它是本项目**唯一**的消息存储实现——文件型实现已随
 * "存储只保留数据库"的调整删除。</p>
 *
 * <p>字段映射：文本与系统消息存 {@code content}；文件消息除 {@code content}（可读描述）外，
 * 还把文件名、大小与校验和存入专用列 {@code file_name/file_size/sha256}，
 * 这样既方便 SQL 直接统计文件传输，读回时也能无损还原成 {@link FileMessage}。</p>
 *
 * <p>存储加密：{@code content} 列在写入前经 {@link MessageCipher} 加密、读出后解密，
 * 数据库里保存的是 Base64 密文；文件名与收发双方仍为明文，
 * 以便保留按文件名、按用户检索与统计的能力。加密前写入的历史记录没有密文前缀，
 * 会被识别为明文原样返回，因此无需数据迁移。</p>
 *
 * <p>连接策略：与 {@link JdbcUserDao} 一致，每次操作独立获取连接并由 try-with-resources 关闭，
 * 不引入连接池，保持零第三方依赖。</p>
 *
 * <p>SQL 注入防护：所有条件一律使用 {@link PreparedStatement} 占位符，动态拼接的只有固定的条件片段。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class JdbcMessageDao implements MessageDao {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".JdbcMessageDao");

    /** 查询语句中使用的列清单，避免 SELECT * 带来的列顺序耦合；delivered/read_flag 仅作查询条件，不映射到模型 */
    private static final String COLUMNS =
            "id, message_id, msg_type, sender, receiver, content, file_name, file_size, sha256, recalled, create_time";

    /** 分页与补投的默认条数：调用方未指定或传入非法 limit 时使用 */
    private static final int DEFAULT_LIMIT = 50;

    /** 分页与补投的条数上限：防止客户端请求超大页导致一次性拉取过多记录 */
    private static final int MAX_LIMIT = 200;

    /** JDBC 连接地址 */
    private final String url;

    /** 数据库用户名 */
    private final String dbUser;

    /** 数据库密码 */
    private final String dbPassword;

    /** 是否可用：驱动类加载成功且建表语句执行成功 */
    private volatile boolean available;

    /** 不可用原因，供启动自检与界面提示展示 */
    private volatile String failureReason = "";

    /**
     * 使用连接参数构造 DAO，并尝试加载驱动与建表。
     *
     * @param driverClass JDBC 驱动类名，例如 {@code com.mysql.cj.jdbc.Driver}
     * @param url         连接地址
     * @param dbUser      数据库用户名
     * @param dbPassword  数据库密码
     */
    public JdbcMessageDao(String driverClass, String url, String dbUser, String dbPassword) {
        this.url = url;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        initialize(driverClass);
    }

    /**
     * 加载驱动并建表。
     *
     * <p>失败只把状态标记为不可用并记录原因，不在构造阶段抛异常：
     * 这样"数据库不可用"能由启动自检统一汇报，而不是在某个随机调用点炸掉。</p>
     *
     * @param driverClass 驱动类名
     */
    private void initialize(String driverClass) {
        try {
            Class.forName(driverClass);
            createTableIfAbsent();
            available = true;
            LOGGER.info(() -> "聊天记录存储已就绪: " + url);
        } catch (ClassNotFoundException e) {
            failureReason = "未找到 JDBC 驱动 " + driverClass + "，请把驱动加入项目依赖";
            available = false;
            LOGGER.severe(failureReason);
        } catch (SQLException e) {
            failureReason = "数据库连接或建表失败: " + e.getMessage();
            available = false;
            LOGGER.log(Level.SEVERE, failureReason, e);
        }
    }

    /**
     * 建表（幂等），语句与 {@code sql/schema.sql} 保持一致。
     *
     * <p>可靠链路相关的新增列：{@code message_id} 保存跨端稳定标识，{@code delivered} 记录是否已送达，
     * {@code read_flag}、{@code recalled} 为后续已读回执与撤回预留。
     * {@code message_id} 上的唯一索引允许存在多行 NULL（MySQL 唯一索引不约束 NULL 值），
     * 因此升级前写入的历史记录即使该列为 NULL 也不会冲突，无需数据迁移。</p>
     *
     * @throws SQLException 执行失败时抛出
     */
    private void createTableIfAbsent() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS chat_message ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "message_id VARCHAR(36) NULL,"
                + "msg_type VARCHAR(32) NOT NULL DEFAULT 'TEXT_PRIVATE',"
                + "sender VARCHAR(32) NOT NULL DEFAULT '',"
                + "receiver VARCHAR(32) NOT NULL DEFAULT '',"
                + "content TEXT NULL,"
                + "file_name VARCHAR(255) NULL,"
                + "file_size BIGINT NULL,"
                + "sha256 CHAR(64) NULL,"
                + "delivered TINYINT NOT NULL DEFAULT 0,"
                + "read_flag TINYINT NOT NULL DEFAULT 0,"
                + "recalled TINYINT NOT NULL DEFAULT 0,"
                + "create_time DATETIME NOT NULL,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_message_id (message_id),"
                + "KEY idx_receiver_delivered (receiver, delivered, id),"
                + "KEY idx_chat_message_time (create_time),"
                + "KEY idx_chat_message_sender (sender, create_time),"
                + "KEY idx_chat_message_receiver (receiver, create_time)"
                + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4";
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 打开数据库连接。
     *
     * @return 连接对象
     * @throws SQLException 连接失败时抛出
     */
    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, dbUser, dbPassword);
    }

    /**
     * 判断存储是否可用。
     *
     * @return 可用返回 true
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * 获取不可用原因。
     *
     * @return 原因描述；可用时为空字符串
     */
    public String failureReason() {
        return failureReason;
    }

    /**
     * 校验存储可用性。
     *
     * @throws ChatException 存储不可用时抛出，避免调用方拿到"静默失败"的空结果
     */
    private void requireAvailable() throws ChatException {
        if (!available) {
            throw new ChatException("聊天记录数据库不可用: " + failureReason);
        }
    }

    /**
     * 追加一条聊天记录。
     *
     * @param message 待保存消息
     * @return 保存成功返回 true
     * @throws ChatException 写入失败时抛出
     */
    @Override
    public boolean append(Message message) throws ChatException {
        requireAvailable();
        if (message == null) {
            throw new ChatException("消息为 null，无法保存聊天记录");
        }
        // 落库前统一补全稳定标识：旧版客户端不带该字段时由服务端生成，保证库中非空且便于去重
        String messageId = message.ensureMessageId();
        String sql = "INSERT INTO chat_message(message_id, msg_type, sender, receiver, content, file_name, "
                + "file_size, sha256, delivered, read_flag, recalled, create_time) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, messageId);
            // 类型为 null 时按系统消息入库，避免 NOT NULL 列写入失败
            ps.setString(2, message.getType() == null ? MessageType.SYSTEM.name() : message.getType().name());
            ps.setString(3, nullToEmpty(message.getSender()));
            ps.setString(4, nullToEmpty(message.getReceiver()));
            String content = contentOf(message);
            if (content == null) {
                ps.setNull(5, java.sql.Types.LONGVARCHAR);
            } else {
                ps.setString(5, MessageCipher.encrypt(content));
            }
            if (message instanceof FileMessage) {
                FileMessage file = (FileMessage) message;
                ps.setString(6, file.getFileName());
                ps.setLong(7, file.getFileSize());
                ps.setString(8, file.getSha256());
            } else {
                ps.setNull(6, java.sql.Types.VARCHAR);
                ps.setNull(7, java.sql.Types.BIGINT);
                ps.setNull(8, java.sql.Types.CHAR);
            }
            // 新入库的消息一律为“未送达、未读、未撤回”，由后续投递与回执更新
            ps.setInt(9, 0);
            ps.setInt(10, 0);
            ps.setInt(11, 0);
            ps.setTimestamp(12, Timestamp.valueOf(message.getTimestamp() == null
                    ? LocalDateTime.now() : message.getTimestamp()));
            boolean saved = ps.executeUpdate() > 0;
            if (saved) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        message.setId(keys.getLong(1));
                    }
                }
            }
            return saved;
        } catch (SQLException e) {
            throw new ChatException("聊天记录写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 取消息的可读正文，用于写入 {@code content} 列。
     *
     * @param message 消息对象
     * @return 正文；文件消息没有附言时返回 null
     */
    private String contentOf(Message message) {
        if (message instanceof TextMessage) {
            return ((TextMessage) message).getContent();
        }
        if (message instanceof SystemMessage) {
            return ((SystemMessage) message).getContent();
        }
        if (message instanceof FileMessage) {
            return ((FileMessage) message).getMessage();
        }
        return message.getSummary();
    }

    /**
     * 按用户与时间范围检索消息。
     *
     * @param username 用户名，null 或空表示不限制
     * @param from     起始时间（含），可为 null
     * @param to       结束时间（含），可为 null
     * @return 时间升序的消息列表，永不返回 null
     * @throws ChatException 读取失败时抛出
     */
    @Override
    public List<Message> query(String username, LocalDateTime from, LocalDateTime to) throws ChatException {
        requireAvailable();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM chat_message WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (username != null && !username.isEmpty()) {
            // 与文件实现保持同一语义：本人收发的消息，或接收者为空（广播/系统通知）
            sql.append(" AND (sender = ? OR receiver = ? OR receiver = '')");
            params.add(username);
            params.add(username);
        }
        if (from != null) {
            sql.append(" AND create_time >= ?");
            params.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND create_time <= ?");
            params.add(Timestamp.valueOf(to));
        }
        sql.append(" ORDER BY create_time ASC, id ASC");
        return executeQuery(sql.toString(), params);
    }

    /**
     * 键集分页查询聊天记录。
     *
     * <p>用 {@code id < ?} 而不是 {@code OFFSET} 做分页：OFFSET 需要数据库先扫描并丢弃前 N 行，
     * 页码越大越慢；键集分页始终从索引直接定位游标位置，翻到第几页代价都一样。</p>
     *
     * <p>取数时按 id 倒序取“最近 limit 条”，再在内存中反转为升序返回，
     * 这样界面按时间顺序追加即可，无需自己排序。</p>
     *
     * @param username 当前用户；null 或空白表示不限制
     * @param peer     对端用户名；仅当 username 也非空时才生效，否则按不限制对端处理
     * @param from     起始时间（含），可为 null
     * @param to       结束时间（含），可为 null
     * @param beforeId 分页游标，只取 id 小于该值的记录；为 null 时从最新一条开始取
     * @param limit    本页最多返回条数；小于等于 0 时用默认值，超过上限时截断
     * @return 按时间升序排列的消息列表，永不返回 null
     * @throws ChatException 读取失败时抛出
     */
    @Override
    public List<Message> queryPage(String username, String peer, LocalDateTime from, LocalDateTime to,
                                   Long beforeId, int limit) throws ChatException {
        requireAvailable();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM chat_message WHERE 1=1");
        List<Object> params = new ArrayList<>();
        String self = (username == null || username.trim().isEmpty()) ? null : username.trim();
        String target = (peer == null || peer.trim().isEmpty()) ? null : peer.trim();
        if (self != null && target != null) {
            // 指定对端时只取双方之间的私聊，避免把广播与无关会话混进同一个聊天窗口
            sql.append(" AND ((sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?))");
            params.add(self);
            params.add(target);
            params.add(target);
            params.add(self);
        } else if (self != null) {
            // 与 query 保持同一语义：本人收发的消息，或接收者为空（广播/系统通知）
            sql.append(" AND (sender = ? OR receiver = ? OR receiver = '')");
            params.add(self);
            params.add(self);
        }
        if (from != null) {
            sql.append(" AND create_time >= ?");
            params.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND create_time <= ?");
            params.add(Timestamp.valueOf(to));
        }
        if (beforeId != null) {
            sql.append(" AND id < ?");
            params.add(beforeId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(normalizeLimit(limit));
        List<Message> page = executeQuery(sql.toString(), params);
        Collections.reverse(page);
        return page;
    }

    /**
     * 判断稳定消息标识是否已存在，用于落库前去重。
     *
     * @param messageId 稳定消息标识；null 或空白直接返回 false，不执行 SQL
     * @return 已存在返回 true
     * @throws ChatException 读取失败时抛出
     */
    @Override
    public boolean existsByMessageId(String messageId) throws ChatException {
        requireAvailable();
        if (messageId == null || messageId.trim().isEmpty()) {
            return false;
        }
        String sql = "SELECT 1 FROM chat_message WHERE message_id = ? LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new ChatException("消息标识判重失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询某接收者尚未送达的消息，供登录后离线补投。
     *
     * @param receiver 接收者用户名；null 或空白时返回空列表
     * @param limit    最多返回条数；小于等于 0 时用默认值，超过上限时截断
     * @return 按主键升序（即时间先后）排列的消息列表，永不返回 null
     * @throws ChatException 读取失败时抛出
     */
    @Override
    public List<Message> findUndelivered(String receiver, int limit) throws ChatException {
        requireAvailable();
        if (receiver == null || receiver.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String sql = "SELECT " + COLUMNS + " FROM chat_message WHERE receiver = ? AND delivered = 0 "
                + "ORDER BY id ASC LIMIT ?";
        List<Object> params = new ArrayList<>();
        params.add(receiver.trim());
        params.add(normalizeLimit(limit));
        return executeQuery(sql, params);
    }

    /**
     * 批量把消息标记为已送达。
     *
     * <p>入参为空时直接返回 0，不拼接 {@code IN ()} 这种非法 SQL；
     * 标识先去重去空白，避免重复占位符带来的无谓开销。</p>
     *
     * @param messageIds 稳定消息标识集合；null 或空集合时返回 0
     * @return 实际更新的行数
     * @throws ChatException 更新失败时抛出
     */
    @Override
    public int markDelivered(Collection<String> messageIds) throws ChatException {
        requireAvailable();
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String messageId : messageIds) {
            if (messageId != null && !messageId.trim().isEmpty()) {
                ids.add(messageId.trim());
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("UPDATE chat_message SET delivered = 1 WHERE message_id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(')');
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (String messageId : ids) {
                ps.setString(index++, messageId);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new ChatException("消息送达标记失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按稳定消息标识查询单条消息。
     *
     * <p>撤回需要先确认"这条消息是否存在、是谁发的、什么时候发的"，
     * 因此必须先按标识取回原始记录再判断；只靠 UPDATE 的 WHERE 条件无法区分
     * "消息不存在"与"不是本人发的"这两种失败原因，也就给不出准确提示。</p>
     *
     * @param messageId 稳定消息标识；为 null 或空白时返回 null
     * @return 命中返回消息对象，未命中返回 null
     * @throws ChatException 读取失败时抛出
     */
    @Override
    public Message findByMessageId(String messageId) throws ChatException {
        requireAvailable();
        if (messageId == null || messageId.trim().isEmpty()) {
            return null;
        }
        String sql = "SELECT " + COLUMNS + " FROM chat_message WHERE message_id = ? LIMIT 1";
        List<Message> found = executeQuery(sql, List.of(messageId.trim()));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 把指定消息标记为已撤回。
     *
     * <p>{@code sender = ?} 是必须项：撤回只能由发送者本人发起，把身份条件写进 SQL
     * 可以避免"先查再改"之间被插入其它请求造成越权撤回。重复撤回返回 0 行，
     * 由上层按幂等处理。</p>
     *
     * @param messageId 稳定消息标识
     * @param sender    发起撤回的用户名
     * @return 实际更新的行数；已撤回或不属于该发送者时返回 0
     * @throws ChatException 更新失败时抛出
     */
    @Override
    public boolean markRecalled(String messageId, String sender) throws ChatException {
        requireAvailable();
        if (messageId == null || messageId.trim().isEmpty() || sender == null || sender.trim().isEmpty()) {
            return false;
        }
        String sql = "UPDATE chat_message SET recalled = 1 WHERE message_id = ? AND sender = ? AND recalled = 0";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId.trim());
            ps.setString(2, sender.trim());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ChatException("消息撤回失败: " + e.getMessage(), e);
        }
    }

    /**
     * 归一化分页条数：非法值回落到默认值，超限值截断到上限。
     *
     * @param limit 调用方传入的条数
     * @return 落在 [1, MAX_LIMIT] 区间内的合法条数
     */
    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 在全部消息中按关键字模糊检索。
     *
     * <p>检索范围与原文件实现一致：正文、文件名、发送者与接收者。
     * 但正文以密文入库，SQL 的 {@code LIKE} 只能匹配到密文，无法再在数据库侧完成正文过滤，
     * 因此改为逐行解密后在内存中匹配；只保留命中行，内存占用与命中数量成正比，
     * 对课程设计的数据量而言代价可忽略。</p>
     *
     * @param keyword 关键字，不允许为空
     * @return 命中的消息列表（时间升序），永不返回 null
     * @throws ChatException 关键字为空或读取失败时抛出
     */
    @Override
    public List<Message> search(String keyword) throws ChatException {
        requireAvailable();
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new ChatException("检索关键字不能为空");
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        String sql = "SELECT " + COLUMNS + " FROM chat_message ORDER BY create_time ASC, id ASC";
        List<Message> hits = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Message message = map(rs);
                if (matches(message, needle)) {
                    hits.add(message);
                }
            }
            return hits;
        } catch (SQLException e) {
            throw new ChatException("聊天记录检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断一条消息是否命中关键字。
     *
     * @param message 已解密的消息
     * @param needle  已转小写的关键字
     * @return 正文、文件名、发送者或接收者任一包含关键字时返回 true
     */
    private boolean matches(Message message, String needle) {
        StringBuilder text = new StringBuilder();
        String content = contentOf(message);
        text.append(content == null ? "" : content).append(' ')
                .append(nullToEmpty(message.getSender())).append(' ')
                .append(nullToEmpty(message.getReceiver()));
        if (message instanceof FileMessage) {
            text.append(' ').append(nullToEmpty(((FileMessage) message).getFileName()));
        }
        return text.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * 执行带参数的查询并映射为消息列表。
     *
     * @param sql    查询语句
     * @param params 参数列表，按占位符顺序绑定
     * @return 消息列表
     * @throws ChatException 查询失败时抛出
     */
    private List<Message> executeQuery(String sql, List<Object> params) throws ChatException {
        List<Message> messages = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    messages.add(map(rs));
                }
            }
            return messages;
        } catch (SQLException e) {
            throw new ChatException("聊天记录查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把结果集当前行映射为消息对象。
     *
     * @param rs 结果集，游标已定位到目标行
     * @return 消息对象
     * @throws SQLException 读取失败时抛出
     */
    private Message map(ResultSet rs) throws SQLException {
        MessageType type = MessageType.fromName(rs.getString("msg_type"));
        String sender = nullToEmpty(rs.getString("sender"));
        String receiver = nullToEmpty(rs.getString("receiver"));
        // 正文以密文入库，读出后立即解密，保证上层拿到的始终是明文
        String content = MessageCipher.decrypt(rs.getString("content"));
        if (rs.getInt("recalled") == 1) {
            // 已撤回的消息在读出时就用占位文本替换正文：数据库无法"部分删除"原文，
            // 只有在这一层统一替换，历史查询、关键字检索与导出才不会泄露已撤回内容
            content = Constants.RECALLED_PLACEHOLDER;
        }
        String fileName = rs.getString("file_name");
        Message message;
        if (fileName != null || type == MessageType.FILE_REQUEST || type == MessageType.FILE_RESULT) {
            FileMessage file = ChatMessageFactory.file(sender, receiver, type);
            file.setFileName(fileName == null ? "" : fileName);
            long size = rs.getLong("file_size");
            file.setFileSize(rs.wasNull() ? 0L : size);
            file.setSha256(rs.getString("sha256"));
            file.setMessage(content);
            message = file;
        } else if (type == MessageType.SYSTEM || type == MessageType.ERROR) {
            message = ChatMessageFactory.system(receiver, content);
            message.setType(type);
        } else {
            message = ChatMessageFactory.text(sender, receiver, content, MessageType.TEXT_PRIVATE);
            message.setType(type);
        }
        message.setId(rs.getLong("id"));
        // 稳定标识允许为 NULL（升级前的历史数据），原样读回由上层决定是否补生成
        message.setMessageId(rs.getString("message_id"));
        Timestamp timestamp = rs.getTimestamp("create_time");
        if (timestamp != null) {
            message.setTimestamp(timestamp.toLocalDateTime());
        }
        return message;
    }

    /**
     * 把消息列表导出为文本文件。
     *
     * <p>导出结果仍然是磁盘文本文件：数据库负责"存"，导出是给使用者留存与打印用的副本。
     * 文本的渲染与落盘统一委托给 {@link MessageExporter}——客户端的导出走
     * "服务端查库渲染、客户端存文件"的链路，两处必须产出同一种文本，因此格式只保留一份实现。</p>
     *
     * @param messages 待导出消息
     * @param target   目标文件
     * @return 实际写入的消息条数
     * @throws IOException   读写失败时抛出
     * @throws ChatException 参数非法时抛出
     */
    @Override
    public int exportTo(List<Message> messages, File target) throws IOException, ChatException {
        return MessageExporter.write(messages, target);
    }

    /**
     * 新增消息（{@link BaseDao} 语义：等价于追加）。
     *
     * @param entity 消息对象
     * @return 保存成功返回 true
     * @throws ChatException 写入失败时抛出
     */
    @Override
    public boolean save(Message entity) throws ChatException {
        return append(entity);
    }

    /**
     * 更新消息。
     *
     * <p>聊天记录是追加型数据，不提供原地修改，因此恒定返回 false。</p>
     *
     * @param entity 消息对象
     * @return 恒定返回 false
     */
    @Override
    public boolean update(Message entity) {
        LOGGER.fine("聊天记录为追加型数据，不支持原地更新");
        return false;
    }

    /**
     * 按消息编号删除记录。
     *
     * @param id 消息编号
     * @return 删除成功返回 true
     * @throws ChatException 执行失败时抛出
     */
    @Override
    public boolean deleteById(Long id) throws ChatException {
        requireAvailable();
        if (id == null) {
            return false;
        }
        String sql = "DELETE FROM chat_message WHERE id = ?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ChatException("聊天记录删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按编号查询消息。
     *
     * @param id 消息编号
     * @return 消息对象；不存在时返回 null
     * @throws ChatException 读取失败时抛出
     */
    @Override
    public Message findById(Long id) throws ChatException {
        requireAvailable();
        if (id == null) {
            return null;
        }
        List<Message> messages = executeQuery("SELECT " + COLUMNS
                + " FROM chat_message WHERE id = ?", List.of(id));
        return messages.isEmpty() ? null : messages.get(0);
    }

    /**
     * 查询全部聊天记录。
     *
     * @return 时间升序的消息列表
     * @throws ChatException 读取失败时抛出
     */
    @Override
    public List<Message> findAll() throws ChatException {
        return query(null, null, null);
    }

    /**
     * 统计聊天记录总数。
     *
     * @return 消息条数
     * @throws ChatException 读取失败时抛出
     */
    @Override
    public long count() throws ChatException {
        requireAvailable();
        String sql = "SELECT COUNT(*) FROM chat_message";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new ChatException("聊天记录统计失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把 null 转为空串，保证 NOT NULL 列写入合法。
     *
     * @param value 原始值
     * @return 非 null 字符串
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 从配置文件构造 JDBC 消息 DAO。
     *
     * @return JDBC DAO 实例，可通过 {@link #isAvailable()} 判断是否真的可用
     */
    public static JdbcMessageDao fromConfig() {
        return new JdbcMessageDao(com.chat.common.Config.dbDriver(),
                com.chat.common.Config.dbUrl(),
                com.chat.common.Config.dbUser(),
                com.chat.common.Config.dbPassword());
    }

    /**
     * 输出数据源信息，便于日志排查（不包含密码）。
     *
     * @return 形如 {@code JdbcMessageDao{url=..., available=true}} 的字符串
     */
    @Override
    public String toString() {
        return "JdbcMessageDao{url=" + url + ", available=" + available + "}";
    }
}

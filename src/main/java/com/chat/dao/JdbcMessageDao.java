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
 * 基于 JDBC 的聊天记录存储，负责建表与消息的追加、查询、分页、撤回和导出。
 */
public class JdbcMessageDao implements MessageDao {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".JdbcMessageDao");

    /** 查询时选取的列清单。 */
    private static final String COLUMNS =
            "id, message_id, msg_type, sender, receiver, content, file_name, file_size, sha256, recalled, create_time";

    /** 未指定或非法 limit 时使用的条数。 */
    private static final int DEFAULT_LIMIT = 50;

    /** 单次查询允许返回的最大条数。 */
    private static final int MAX_LIMIT = 200;

    private final String url;

    private final String dbUser;

    private final String dbPassword;

    private volatile boolean available;

    private volatile String failureReason = "";

    /**
     * 使用指定连接参数创建实例并初始化存储。
     *
     * @param driverClass JDBC 驱动类名
     * @param url 数据库连接地址
     * @param dbUser 数据库用户名
     * @param dbPassword 数据库密码
     */
    public JdbcMessageDao(String driverClass, String url, String dbUser, String dbPassword) {
        this.url = url;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        initialize(driverClass);
    }

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

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, dbUser, dbPassword);
    }

    public boolean isAvailable() {
        return available;
    }

    public String failureReason() {
        return failureReason;
    }

    private void requireAvailable() throws ChatException {
        if (!available) {
            throw new ChatException("聊天记录数据库不可用: " + failureReason);
        }
    }

    /**
     * 追加一条聊天记录，文件消息同时写入文件信息。
     *
     * @param message 待保存的消息
     * @return 写入成功返回 true
     * @throws ChatException 数据库不可用、消息为空或写入失败时抛出
     */
    @Override
    public boolean append(Message message) throws ChatException {
        requireAvailable();
        if (message == null) {
            throw new ChatException("消息为 null，无法保存聊天记录");
        }

        String messageId = message.ensureMessageId();
        String sql = "INSERT INTO chat_message(message_id, msg_type, sender, receiver, content, file_name, "
                + "file_size, sha256, delivered, read_flag, recalled, create_time) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, messageId);

            ps.setString(2, message.getType() == null ? MessageType.SYSTEM.name() : message.getType().name());
            ps.setString(3, nullToEmpty(message.getSender()));
            ps.setString(4, nullToEmpty(message.getReceiver()));
            // 正文加密后写入 content 列
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

            ps.setInt(9, 0);
            ps.setInt(10, 0);
            ps.setInt(11, 0);
            ps.setTimestamp(12, Timestamp.valueOf(message.getTimestamp() == null
                    ? LocalDateTime.now() : message.getTimestamp()));
            boolean saved = ps.executeUpdate() > 0;
            // 写入成功后回填自增主键
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
     * 提取消息中可持久化的文本内容。
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
     * 按用户与时间范围查询聊天记录。
     *
     * @param username 参与会话的用户名，为 null 时不限制
     * @param from 起始时间，为 null 时不限制
     * @param to 结束时间，为 null 时不限制
     * @return 按时间升序排列的消息列表
     * @throws ChatException 数据库不可用或查询失败时抛出
     */
    @Override
    public List<Message> query(String username, LocalDateTime from, LocalDateTime to) throws ChatException {
        requireAvailable();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM chat_message WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (username != null && !username.isEmpty()) {
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
     * 分页查询会话记录。
     *
     * @param username 当前用户名，为 null 时不限制
     * @param peer 对方用户名，为 null 时不限制
     * @param from 起始时间，为 null 时不限制
     * @param to 结束时间，为 null 时不限制
     * @param beforeId 只返回主键小于该值的记录，为 null 时从头开始
     * @param limit 每页条数
     * @return 按时间升序排列的一页消息
     * @throws ChatException 数据库不可用或查询失败时抛出
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
            sql.append(" AND ((sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?))");
            params.add(self);
            params.add(target);
            params.add(target);
            params.add(self);
        } else if (self != null) {
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
        // 查询按 id 倒序，返回前恢复为时间正序
        Collections.reverse(page);
        return page;
    }

    /**
     * 判断消息标识是否已存在。
     *
     * @param messageId 消息标识
     * @return 已存在返回 true
     * @throws ChatException 数据库不可用或查询失败时抛出
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
     * 查询指定接收者尚未投递的消息。
     *
     * @param receiver 接收者用户名
     * @param limit 最多返回的条数
     * @return 未投递的消息列表
     * @throws ChatException 数据库不可用或查询失败时抛出
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
     * 批量标记消息为已投递。
     *
     * @param messageIds 消息标识集合
     * @return 实际更新的条数
     * @throws ChatException 数据库不可用或更新失败时抛出
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
        // 按去重后的标识数量拼装 IN 占位符
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
     * 按消息标识查询单条记录。
     *
     * @param messageId 消息标识
     * @return 对应消息，不存在返回 null
     * @throws ChatException 数据库不可用或查询失败时抛出
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
     * 撤回指定发送者的消息。
     *
     * @param messageId 消息标识
     * @param sender 发送者用户名，需与记录一致
     * @return 撤回成功返回 true
     * @throws ChatException 数据库不可用或更新失败时抛出
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

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 按关键字检索聊天记录。
     *
     * @param keyword 检索关键字
     * @return 命中的消息列表
     * @throws ChatException 关键字为空、数据库不可用或查询失败时抛出
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
     * 执行查询并把结果集映射为消息列表。
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
     * 把结果集当前行映射为对应类型的消息对象。
     */
    private Message map(ResultSet rs) throws SQLException {
        MessageType type = MessageType.fromName(rs.getString("msg_type"));
        String sender = nullToEmpty(rs.getString("sender"));
        String receiver = nullToEmpty(rs.getString("receiver"));

        String content = MessageCipher.decrypt(rs.getString("content"));
        // 已撤回的消息用占位文本替换正文
        if (rs.getInt("recalled") == 1) {
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

        message.setMessageId(rs.getString("message_id"));
        Timestamp timestamp = rs.getTimestamp("create_time");
        if (timestamp != null) {
            message.setTimestamp(timestamp.toLocalDateTime());
        }
        return message;
    }

    /**
     * 将消息导出到文本文件。
     *
     * @param messages 待导出的消息
     * @param target 目标文件
     * @return 导出的记录条数
     * @throws IOException 文件写入失败时抛出
     * @throws ChatException 导出参数校验失败时抛出
     */
    @Override
    public int exportTo(List<Message> messages, File target) throws IOException, ChatException {
        return MessageExporter.write(messages, target);
    }

    @Override
    public boolean save(Message entity) throws ChatException {
        return append(entity);
    }

    @Override
    public boolean update(Message entity) {
        LOGGER.fine("聊天记录为追加型数据，不支持原地更新");
        return false;
    }

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

    @Override
    public List<Message> findAll() throws ChatException {
        return query(null, null, null);
    }

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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 使用配置文件中的数据库参数创建实例。
     *
     * @return 聊天记录存储实例
     */
    public static JdbcMessageDao fromConfig() {
        return new JdbcMessageDao(com.chat.common.Config.dbDriver(),
                com.chat.common.Config.dbUrl(),
                com.chat.common.Config.dbUser(),
                com.chat.common.Config.dbPassword());
    }

    @Override
    public String toString() {
        return "JdbcMessageDao{url=" + url + ", available=" + available + "}";
    }
}

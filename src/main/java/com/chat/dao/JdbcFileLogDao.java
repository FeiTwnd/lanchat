package com.chat.dao;

import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.exception.ChatException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 文件传输日志表 {@code chat_file_log} 的 JDBC 实现。
 *
 * <p>职责：把一次文件传输的结果写入审计表，供统计与事后追溯使用。表结构与
 * {@code sql/schema.sql} 中的定义保持一致，并由本类在启动时幂等创建
 * （与 {@link JdbcMessageDao}、{@link JdbcUserDao} 的做法相同，保证"只跑程序、不手工建表"也能用）。</p>
 *
 * <p>为什么单独一张表而不是只写聊天记录表：聊天记录面向"聊天内容展示"，
 * 一次传输只留一条消息；本表面向"传输审计"，带有 SUCCESS/REJECTED/FAILED 结果与
 * 传输会话编号，可以按结果统计成功率、按传输编号定位某一次传输。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class JdbcFileLogDao {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".JdbcFileLogDao");

    /** JDBC 连接地址 */
    private final String url;

    /** 数据库用户名 */
    private final String dbUser;

    /** 数据库密码 */
    private final String dbPassword;

    /** 是否可用：驱动加载成功且建表语句执行成功 */
    private volatile boolean available;

    /** 不可用原因 */
    private volatile String failureReason = "";

    /**
     * 使用连接参数构造 DAO，并尝试加载驱动与建表。
     *
     * @param driverClass JDBC 驱动类名
     * @param url         连接地址
     * @param dbUser      数据库用户名
     * @param dbPassword  数据库密码
     */
    public JdbcFileLogDao(String driverClass, String url, String dbUser, String dbPassword) {
        this.url = url;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        initialize(driverClass);
    }

    /**
     * 按配置文件构造 DAO。
     *
     * @return 指向配置文件所描述数据库的 DAO
     */
    public static JdbcFileLogDao fromConfig() {
        return new JdbcFileLogDao(Config.dbDriver(), Config.dbUrl(), Config.dbUser(), Config.dbPassword());
    }

    /**
     * 加载驱动并建表。
     *
     * <p>失败只标记为不可用并记录原因，不在构造阶段抛异常：传输日志属于旁路功能，
     * 它的不可用不应该让服务器起不来。</p>
     *
     * @param driverClass 驱动类名
     */
    private void initialize(String driverClass) {
        try {
            Class.forName(driverClass);
            createTableIfAbsent();
            available = true;
            LOGGER.info(() -> "文件传输日志存储已就绪: " + url);
        } catch (ClassNotFoundException e) {
            failureReason = "未找到 JDBC 驱动 " + driverClass;
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
     * @throws SQLException 执行失败时抛出
     */
    private void createTableIfAbsent() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS chat_file_log ("
                + "transfer_id VARCHAR(64) NOT NULL,"
                + "sender VARCHAR(32) NOT NULL,"
                + "receiver VARCHAR(32) NOT NULL,"
                + "file_name VARCHAR(255) NOT NULL,"
                + "file_size BIGINT NOT NULL,"
                + "sha256 CHAR(64) NULL,"
                + "result VARCHAR(16) NOT NULL,"
                + "remark VARCHAR(255) NULL,"
                + "finished_at DATETIME NOT NULL,"
                + "PRIMARY KEY (transfer_id),"
                + "KEY idx_chat_file_log_sender (sender, finished_at),"
                + "KEY idx_chat_file_log_receiver (receiver, finished_at)"
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
     * 判断日志存储是否可用。
     *
     * @return 可用返回 true
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * 获取不可用原因。
     *
     * @return 原因描述；可用时返回空字符串
     */
    public String failureReason() {
        return failureReason;
    }

    /**
     * 追加一条文件传输日志。
     *
     * <p>用 {@code REPLACE INTO} 而不是 {@code INSERT}：传输编号是主键，
     * 极端情况下同一次传输可能重复上报结果（例如接收方先报失败、结束帧又报一次），
     * 此时应当用最后的结果覆盖，而不是让服务器抛主键冲突。</p>
     *
     * @param transferId 传输会话编号
     * @param sender     文件发送者用户名
     * @param receiver   文件接收者用户名
     * @param fileName   文件名
     * @param fileSize   文件大小（字节）
     * @param sha256     文件校验和，可为 null
     * @param result     传输结果：SUCCESS / REJECTED / FAILED
     * @param remark     结果说明或失败原因，可为 null
     * @param finishedAt 传输结束时间
     * @return 写入成功返回 true
     * @throws ChatException 写入失败时抛出
     */
    public boolean append(String transferId, String sender, String receiver, String fileName,
                          long fileSize, String sha256, String result, String remark,
                          LocalDateTime finishedAt) throws ChatException {
        if (!available) {
            throw new ChatException("文件传输日志数据库不可用: " + failureReason);
        }
        String sql = "REPLACE INTO chat_file_log(transfer_id, sender, receiver, file_name, file_size, "
                + "sha256, result, remark, finished_at) VALUES(?,?,?,?,?,?,?,?,?)";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, transferId);
            ps.setString(2, sender);
            ps.setString(3, receiver);
            ps.setString(4, fileName);
            ps.setLong(5, fileSize);
            ps.setString(6, sha256);
            ps.setString(7, result);
            ps.setString(8, remark);
            ps.setTimestamp(9, Timestamp.valueOf(finishedAt == null ? LocalDateTime.now() : finishedAt));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ChatException("文件传输日志写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 输出数据源信息，便于日志排查（不包含密码）。
     *
     * @return 形如 {@code JdbcFileLogDao{url=..., available=true}} 的字符串
     */
    @Override
    public String toString() {
        return "JdbcFileLogDao{url=" + url + ", available=" + available + "}";
    }
}

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
 * 基于 JDBC 的文件传输日志存储，负责建表与写入传输结果记录。
 */
public class JdbcFileLogDao {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".JdbcFileLogDao");

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
    public JdbcFileLogDao(String driverClass, String url, String dbUser, String dbPassword) {
        this.url = url;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        initialize(driverClass);
    }

    /**
     * 使用配置文件中的数据库参数创建实例。
     *
     * @return 文件传输日志存储实例
     */
    public static JdbcFileLogDao fromConfig() {
        return new JdbcFileLogDao(Config.dbDriver(), Config.dbUrl(), Config.dbUser(), Config.dbPassword());
    }

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

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, dbUser, dbPassword);
    }

    public boolean isAvailable() {
        return available;
    }

    public String failureReason() {
        return failureReason;
    }

    /**
     * 写入一条文件传输日志。
     *
     * @param transferId 传输标识
     * @param sender 发送者用户名
     * @param receiver 接收者用户名
     * @param fileName 文件名
     * @param fileSize 文件字节数
     * @param sha256 文件 SHA-256 摘要
     * @param result 传输结果
     * @param remark 备注
     * @param finishedAt 完成时间，为 null 时取当前时间
     * @return 写入成功返回 true
     * @throws ChatException 数据库不可用或写入失败时抛出
     */
    public boolean append(String transferId, String sender, String receiver, String fileName,
                          long fileSize, String sha256, String result, String remark,
                          LocalDateTime finishedAt) throws ChatException {
        // 数据库不可用时拒绝写入
        if (!available) {
            throw new ChatException("文件传输日志数据库不可用: " + failureReason);
        }
        String sql = "REPLACE INTO chat_file_log(transfer_id, sender, receiver, file_name, file_size, "
                + "sha256, result, remark, finished_at) VALUES(?,?,?,?,?,?,?,?,?)";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            // 按列顺序绑定参数
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

    @Override
    public String toString() {
        return "JdbcFileLogDao{url=" + url + ", available=" + available + "}";
    }
}

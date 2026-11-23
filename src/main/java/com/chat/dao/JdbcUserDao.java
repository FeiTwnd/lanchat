package com.chat.dao;

import com.chat.common.Constants;
import com.chat.common.User;
import com.chat.exception.ChatException;
import com.chat.exception.UserNotFoundException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 JDBC 的用户表实现，负责建表与用户记录的增删改查。
 */
public class JdbcUserDao implements UserDao {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".JdbcUserDao");

    private final String url;

    private final String dbUser;

    private final String dbPassword;

    private volatile boolean available;

    private volatile String failureReason = "";

    /**
     * 使用指定连接参数创建实例并尝试初始化。
     *
     * @param driverClass JDBC 驱动类名
     * @param url 数据库连接地址
     * @param dbUser 数据库用户名
     * @param dbPassword 数据库密码
     */
    public JdbcUserDao(String driverClass, String url, String dbUser, String dbPassword) {
        this.url = url;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.available = tryInitialize(driverClass);
    }

    private boolean tryInitialize(String driverClass) {
        try {
            Class.forName(driverClass);
            createTableIfAbsent();
            LOGGER.info(() -> "用户数据存储已就绪: " + url);
            return true;
        } catch (ClassNotFoundException e) {
            failureReason = "未找到 JDBC 驱动 " + driverClass + "，请把驱动加入项目依赖";
            LOGGER.severe(failureReason);
            return false;
        } catch (SQLException e) {
            failureReason = "数据库连接或建表失败: " + e.getMessage();
            LOGGER.log(Level.SEVERE, failureReason, e);
            return false;
        }
    }

    private void createTableIfAbsent() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS chat_user ("
                + "username VARCHAR(32) PRIMARY KEY,"
                + "nickname VARCHAR(64) NOT NULL,"
                + "salt CHAR(32) NOT NULL,"

                + "password_hash VARCHAR(128) NOT NULL,"
                + "role VARCHAR(16) NOT NULL DEFAULT 'USER',"
                + "create_time DATETIME NULL,"
                + "last_login_time DATETIME NULL,"

                + "avatar MEDIUMTEXT NULL,"

                + "KEY idx_chat_user_nickname (nickname),"
                + "KEY idx_chat_user_role (role))";
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 确认 chat_user 表存在 avatar 列，列已存在时忽略重复列错误。
     *
     * @throws SQLException 添加列失败时抛出
     */
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
     * 把结果集当前行映射为用户对象。
     */
    private User map(ResultSet rs) throws SQLException {
        User user = new User();
        user.setUsername(rs.getString("username"));
        user.setNickname(rs.getString("nickname"));
        user.setSalt(rs.getString("salt"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setRole(rs.getString("role"));
        user.setAvatar(rs.getString("avatar"));
        Timestamp create = rs.getTimestamp("create_time");
        if (create != null) {
            user.setCreateTime(create.toLocalDateTime());
        }
        Timestamp lastLogin = rs.getTimestamp("last_login_time");
        if (lastLogin != null) {
            user.setLastLoginTime(lastLogin.toLocalDateTime());
        }
        return user;
    }

    /**
     * 插入一条用户记录。
     *
     * @param entity 用户对象
     * @return 写入成功返回 true；数据库不可用或实体为空返回 false
     * @throws ChatException 数据库操作失败时抛出
     */
    @Override
    public boolean save(User entity) throws ChatException {
        if (!available || entity == null) {
            return false;
        }
        String sql = "INSERT INTO chat_user(username, nickname, salt, password_hash, role, create_time, "
                + "last_login_time, avatar) VALUES(?,?,?,?,?,?,?,?)";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            // 复用按 INSERT 列顺序填充参数的方法
            fill(ps, entity);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ChatException("用户写入数据库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新一条用户记录。
     *
     * @param entity 用户对象
     * @return 更新成功返回 true；数据库不可用或实体为空返回 false
     * @throws ChatException 数据库操作失败时抛出
     */
    @Override
    public boolean update(User entity) throws ChatException {
        if (!available || entity == null) {
            return false;
        }
        String sql = "UPDATE chat_user SET nickname=?, salt=?, password_hash=?, role=?, create_time=?, "
                + "last_login_time=?, avatar=? WHERE username=?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            // 按 UPDATE 语句的列顺序绑定参数
            ps.setString(1, entity.getNickname());
            ps.setString(2, entity.getSalt());
            ps.setString(3, entity.getPasswordHash());
            ps.setString(4, entity.getRole());
            ps.setTimestamp(5, entity.getCreateTime() == null ? null : Timestamp.valueOf(entity.getCreateTime()));
            ps.setTimestamp(6, entity.getLastLoginTime() == null ? null : Timestamp.valueOf(entity.getLastLoginTime()));
            ps.setString(7, entity.getAvatar());
            ps.setString(8, entity.getUsername());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ChatException("用户更新数据库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按 INSERT 语句的列顺序填充参数。
     */
    private void fill(PreparedStatement ps, User user) throws SQLException {
        ps.setString(1, user.getUsername());
        ps.setString(2, user.getNickname());
        ps.setString(3, user.getSalt());
        ps.setString(4, user.getPasswordHash());
        ps.setString(5, user.getRole());
        ps.setTimestamp(6, user.getCreateTime() == null ? null : Timestamp.valueOf(user.getCreateTime()));
        ps.setTimestamp(7, user.getLastLoginTime() == null ? null : Timestamp.valueOf(user.getLastLoginTime()));
        ps.setString(8, user.getAvatar());
    }

    @Override
    public boolean deleteById(String id) throws UserNotFoundException, ChatException {
        return deleteByUsername(id);
    }

    /**
     * 按用户名删除用户记录。
     *
     * @param username 用户名
     * @return 删除成功返回 true
     * @throws UserNotFoundException 数据库不可用或用户不存在时抛出
     * @throws ChatException 数据库操作失败时抛出
     */
    @Override
    public boolean deleteByUsername(String username) throws UserNotFoundException, ChatException {
        if (!available) {
            throw new UserNotFoundException("数据库模式不可用");
        }
        // 用户不存在时不执行删除
        if (findByUsername(username) == null) {
            throw new UserNotFoundException("用户不存在: " + username);
        }
        String sql = "DELETE FROM chat_user WHERE username=?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ChatException("用户删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public User findById(String id) throws ChatException {
        return findByUsername(id);
    }

    /**
     * 按用户名查询用户。
     *
     * @param username 用户名
     * @return 对应用户，不存在返回 null
     * @throws ChatException 数据库操作失败时抛出
     */
    @Override
    public User findByUsername(String username) throws ChatException {
        if (!available || username == null) {
            return null;
        }
        String sql = "SELECT * FROM chat_user WHERE username=?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new ChatException("用户查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断用户名是否已存在。
     *
     * @param username 用户名
     * @return 已存在返回 true
     * @throws ChatException 数据库操作失败时抛出
     */
    @Override
    public boolean exists(String username) throws ChatException {
        return findByUsername(username) != null;
    }

    /**
     * 保存用户，用户名已存在时改为更新。
     *
     * @param user 用户对象
     * @return 写入成功返回 true
     * @throws ChatException 数据库操作失败时抛出
     */
    @Override
    public boolean saveOrUpdate(User user) throws ChatException {
        if (exists(user.getUsername())) {
            return update(user);
        }
        return save(user);
    }

    @Override
    public List<User> findAll() throws ChatException {
        List<User> users = new ArrayList<>();
        if (!available) {
            return users;
        }
        String sql = "SELECT * FROM chat_user ORDER BY username";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(map(rs));
            }
            return users;
        } catch (SQLException e) {
            throw new ChatException("用户列表查询失败: " + e.getMessage(), e);
        }
    }

    @Override
    public long count() throws ChatException {
        if (!available) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM chat_user";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new ChatException("用户统计失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用配置文件中的数据库参数创建实例。
     *
     * @return 用户数据访问实例
     */
    public static JdbcUserDao fromConfig() {
        return new JdbcUserDao(com.chat.common.Config.dbDriver(),
                com.chat.common.Config.dbUrl(),
                com.chat.common.Config.dbUser(),
                com.chat.common.Config.dbPassword());
    }

    /**
     * 使用指定连接参数创建实例。
     *
     * @param driverClass JDBC 驱动类名
     * @param url 数据库连接地址
     * @param user 数据库用户名
     * @param password 数据库密码
     * @return 用户数据访问实例
     */
    public static JdbcUserDao open(String driverClass, String url, String user, String password) {
        return new JdbcUserDao(driverClass, url, user, password);
    }

    @Override
    public String toString() {
        return "JdbcUserDao{url=" + url + ", available=" + available + "}";
    }
}

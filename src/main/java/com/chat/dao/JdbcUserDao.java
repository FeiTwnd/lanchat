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
 * 用户数据访问的 JDBC 实现（可选加分项）。
 *
 * <p>职责：把用户持久化到关系型数据库，与 {@link UserDaoImpl} 构成 {@link UserDao} 的两种实现，
 * 由 {@link #isAvailable()} 在运行期探测驱动是否存在，实现“有数据库用数据库、没有则降级文件”的无缝切换。</p>
 *
 * <p>为什么不在编译期依赖驱动：项目要求不使用任何第三方框架且需保持“零依赖即可编译运行”。
 * 本类只使用 {@code java.sql} 标准接口，驱动类名由配置提供，通过反射加载，
 * 因此没有 JDBC 驱动时也能照常编译，只是运行期 {@link #isAvailable()} 返回 false。</p>
 *
 * <p>SQL 注入防护：所有涉及用户输入的语句一律使用 {@link PreparedStatement} 参数占位符，
 * 禁止字符串拼接 SQL。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class JdbcUserDao implements UserDao {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".JdbcUserDao");

    /** JDBC 连接地址 */
    private final String url;

    /** 数据库用户名 */
    private final String dbUser;

    /** 数据库密码 */
    private final String dbPassword;

    /** 是否可用：驱动类加载成功且建表语句执行成功 */
    private boolean available;

    /**
     * 使用配置构造 DAO，并尝试建表。
     *
     * @param driverClass JDBC 驱动类名，例如 {@code com.mysql.cj.jdbc.Driver}
     * @param url         连接地址
     * @param dbUser      数据库用户名
     * @param dbPassword  数据库密码
     */
    public JdbcUserDao(String driverClass, String url, String dbUser, String dbPassword) {
        this.url = url;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.available = tryInitialize(driverClass);
    }

    /**
     * 尝试加载驱动并建表。
     *
     * <p>任何失败都只降级为 available=false，不中断程序启动——
     * 数据库是加分项而非主链路，不能因为数据库不可用导致整个聊天室无法使用。</p>
     *
     * @param driverClass 驱动类名
     * @return 初始化成功返回 true
     */
    private boolean tryInitialize(String driverClass) {
        try {
            Class.forName(driverClass);
            createTableIfAbsent();
            LOGGER.info(() -> "数据库模式已启用: " + url);
            return true;
        } catch (ClassNotFoundException e) {
            LOGGER.info("未找到 JDBC 驱动 " + driverClass + "，用户数据将使用文件存储");
            return false;
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "数据库初始化失败，用户数据将使用文件存储: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 建表（幂等）。
     *
     * @throws SQLException 执行失败时抛出
     */
    private void createTableIfAbsent() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS chat_user ("
                + "username VARCHAR(32) PRIMARY KEY,"
                + "nickname VARCHAR(64) NOT NULL,"
                + "salt CHAR(32) NOT NULL,"
                + "password_hash CHAR(64) NOT NULL,"
                + "role VARCHAR(16) NOT NULL DEFAULT 'USER',"
                + "create_time DATETIME NULL,"
                + "last_login_time DATETIME NULL)";
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
     * 判断数据库模式是否可用。
     *
     * @return 可用返回 true
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * 把结果集当前行映射为用户对象。
     *
     * @param rs 结果集，游标已定位到目标行
     * @return 用户对象
     * @throws SQLException 读取失败时抛出
     */
    private User map(ResultSet rs) throws SQLException {
        User user = new User();
        user.setUsername(rs.getString("username"));
        user.setNickname(rs.getString("nickname"));
        user.setSalt(rs.getString("salt"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setRole(rs.getString("role"));
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
     * 新增用户。
     *
     * @param entity 用户对象
     * @return 新增成功返回 true
     * @throws ChatException 执行失败时抛出
     */
    @Override
    public boolean save(User entity) throws ChatException {
        if (!available || entity == null) {
            return false;
        }
        String sql = "INSERT INTO chat_user(username, nickname, salt, password_hash, role, create_time, "
                + "last_login_time) VALUES(?,?,?,?,?,?,?)";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            fill(ps, entity);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ChatException("用户写入数据库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新用户。
     *
     * @param entity 用户对象
     * @return 更新成功返回 true
     * @throws ChatException 执行失败时抛出
     */
    @Override
    public boolean update(User entity) throws ChatException {
        if (!available || entity == null) {
            return false;
        }
        String sql = "UPDATE chat_user SET nickname=?, salt=?, password_hash=?, role=?, create_time=?, "
                + "last_login_time=? WHERE username=?";
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entity.getNickname());
            ps.setString(2, entity.getSalt());
            ps.setString(3, entity.getPasswordHash());
            ps.setString(4, entity.getRole());
            ps.setTimestamp(5, entity.getCreateTime() == null ? null : Timestamp.valueOf(entity.getCreateTime()));
            ps.setTimestamp(6, entity.getLastLoginTime() == null ? null : Timestamp.valueOf(entity.getLastLoginTime()));
            ps.setString(7, entity.getUsername());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ChatException("用户更新数据库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 填充新增语句的参数。
     *
     * @param ps   预编译语句
     * @param user 用户对象
     * @throws SQLException 参数设置失败时抛出
     */
    private void fill(PreparedStatement ps, User user) throws SQLException {
        ps.setString(1, user.getUsername());
        ps.setString(2, user.getNickname());
        ps.setString(3, user.getSalt());
        ps.setString(4, user.getPasswordHash());
        ps.setString(5, user.getRole());
        ps.setTimestamp(6, user.getCreateTime() == null ? null : Timestamp.valueOf(user.getCreateTime()));
        ps.setTimestamp(7, user.getLastLoginTime() == null ? null : Timestamp.valueOf(user.getLastLoginTime()));
    }

    /**
     * 按用户名删除用户。
     *
     * @param id 用户名
     * @return 删除成功返回 true
     * @throws UserNotFoundException 用户不存在时抛出
     * @throws ChatException         执行失败时抛出
     */
    @Override
    public boolean deleteById(String id) throws UserNotFoundException, ChatException {
        return deleteByUsername(id);
    }

    /**
     * 按用户名删除用户。
     *
     * @param username 用户名
     * @return 删除成功返回 true
     * @throws UserNotFoundException 用户不存在时抛出
     * @throws ChatException         执行失败时抛出
     */
    @Override
    public boolean deleteByUsername(String username) throws UserNotFoundException, ChatException {
        if (!available) {
            throw new UserNotFoundException("数据库模式不可用");
        }
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

    /**
     * 按用户名查询用户。
     *
     * @param id 用户名
     * @return 用户对象；不存在时返回 null
     * @throws ChatException 查询失败时抛出
     */
    @Override
    public User findById(String id) throws ChatException {
        return findByUsername(id);
    }

    /**
     * 按用户名查询用户。
     *
     * @param username 用户名
     * @return 用户对象；不存在或数据库不可用时返回 null
     * @throws ChatException 查询失败时抛出
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
     * 判断用户名是否存在。
     *
     * @param username 用户名
     * @return 存在返回 true
     * @throws ChatException 查询失败时抛出
     */
    @Override
    public boolean exists(String username) throws ChatException {
        return findByUsername(username) != null;
    }

    /**
     * 保存或更新用户。
     *
     * @param user 用户对象
     * @return 写入成功返回 true
     * @throws ChatException 执行失败时抛出
     */
    @Override
    public boolean saveOrUpdate(User user) throws ChatException {
        if (exists(user.getUsername())) {
            return update(user);
        }
        return save(user);
    }

    /**
     * 查询全部用户。
     *
     * @return 用户列表，永不返回 null
     * @throws ChatException 查询失败时抛出
     */
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

    /**
     * 统计用户总数。
     *
     * @return 用户数量
     * @throws ChatException 查询失败时抛出
     */
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
     * 从配置文件构造 JDBC DAO 的工厂方法。
     *
     * <p>数据库属于可选加分项，必须由使用者在 {@code config/chat.properties} 中
     * 显式设置 {@code db.enabled=true} 才会尝试连接。这一“显式开启”策略是必要的：
     * 若仅凭 classpath 上是否存在驱动就自动连库，一旦本机恰好在运行 MySQL，
     * 程序会静默改用数据库存储，导致用户数据出现在预期之外的位置（测试环境尤其危险）。</p>
     *
     * @return JDBC DAO 实例；未启用或配置缺失时返回不可用实例
     */
    public static JdbcUserDao fromConfig() {
        boolean enabled = com.chat.common.Config.getBoolean("db.enabled", false);
        if (!enabled) {
            LOGGER.info("未启用数据库模式（db.enabled=false），用户数据使用文件存储");
            return new JdbcUserDao("未启用", "未启用", "", "");
        }
        String driver = com.chat.common.Config.get("db.driver", "com.mysql.cj.jdbc.Driver");
        String url = com.chat.common.Config.get("db.url", "");
        String user = com.chat.common.Config.get("db.username", "");
        String password = com.chat.common.Config.get("db.password", "");
        if (url.isEmpty()) {
            url = "jdbc:mysql://localhost:3306/lanchat?useSSL=false&serverTimezone=UTC"
                    + "&allowPublicKeyRetrieval=true";
        }
        return new JdbcUserDao(driver, url, user, password);
    }

    /**
     * 显式构造可用的数据库用户 DAO，供集成测试或演示直接调用。
     *
     * @param driverClass 驱动类名
     * @param url         连接地址
     * @param user        数据库用户名
     * @param password    数据库密码
     * @return JDBC DAO 实例，可通过 {@link #isAvailable()} 判断是否真的可用
     */
    public static JdbcUserDao open(String driverClass, String url, String user, String password) {
        return new JdbcUserDao(driverClass, url, user, password);
    }

    /**
     * 输出数据源信息，便于日志排查（不包含密码）。
     *
     * @return 形如 {@code JdbcUserDao{url=..., available=true}} 的字符串
     */
    @Override
    public String toString() {
        return "JdbcUserDao{url=" + url + ", available=" + available + "}";
    }
}

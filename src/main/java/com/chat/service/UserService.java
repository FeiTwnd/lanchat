package com.chat.service;

import com.chat.common.Constants;
import com.chat.common.Result;
import com.chat.common.User;
import com.chat.dao.JdbcUserDao;
import com.chat.dao.UserDao;
import com.chat.dao.UserDaoImpl;
import com.chat.exception.ChatException;
import com.chat.exception.UserNotFoundException;
import com.chat.util.SecurityUtil;

import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 用户业务服务。
 *
 * <p>职责：承载用户注册、登录、资料修改、删除、查询等业务规则，
 * 是“界面/网络层”与“持久化层”之间唯一的业务出入口。</p>
 *
 * <p>为什么 Service 不设接口：全系统只有这一种用户业务实现，
 * 额外定义 {@code UserServiceInterface} 只会增加跳转成本而无任何替换收益。</p>
 *
 * <p>线程安全：本类自身无可变状态，线程安全由 {@link UserDao} 实现保证。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class UserService {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".UserService");

    /** 用户名格式校验器 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile(Constants.USERNAME_PATTERN);

    /** 用户持久化实现 */
    private final UserDao userDao;

    /**
     * 默认构造：优先使用数据库（若驱动与配置可用），否则回退文件存储。
     *
     * <p>该策略使“加分项数据库”成为可选增强，而不是运行前提。</p>
     */
    public UserService() {
        UserDao dao;
        JdbcUserDao jdbc = JdbcUserDao.fromConfig();
        if (jdbc.isAvailable()) {
            dao = jdbc;
            LOGGER.info("用户服务使用数据库存储");
        } else {
            dao = new UserDaoImpl();
            LOGGER.info("用户服务使用文件存储");
        }
        this.userDao = dao;
    }

    /**
     * 指定 DAO 构造服务，便于单元测试注入内存或临时文件实现。
     *
     * @param userDao 用户持久化实现
     * @throws IllegalArgumentException 当 userDao 为 null 时抛出
     */
    public UserService(UserDao userDao) {
        if (userDao == null) {
            throw new IllegalArgumentException("UserDao 不能为 null");
        }
        this.userDao = userDao;
    }

    /**
     * 注册新用户。
     *
     * <p>业务规则：用户名需匹配 {@link Constants#USERNAME_PATTERN} 且全局唯一；
     * 密码长度 6-32；昵称为空时默认取用户名；密码仅以“盐 + 散列”形式落盘。</p>
     *
     * @param username 用户名
     * @param password 明文密码
     * @param nickname 昵称，可为空
     * @return 成功时携带脱敏后的用户对象（不含散列与盐）
     */
    public Result<User> register(String username, String password, String nickname) {
        Result<String> validation = validate(username, password);
        if (!validation.isSuccess()) {
            return Result.fail(validation.getMessage());
        }
        try {
            if (userDao.exists(username)) {
                return Result.fail("用户名已存在: " + username);
            }
            User user = new User(username, normalizeNickname(nickname, username));
            user.setSalt(SecurityUtil.generateSalt());
            user.setPasswordHash(SecurityUtil.hashPassword(password, user.getSalt()));
            user.setRole(Constants.ROLE_USER);
            user.setCreateTime(LocalDateTime.now());
            if (!userDao.save(user)) {
                return Result.fail("注册失败，用户名可能已被占用");
            }
            LOGGER.info(() -> "用户注册成功: " + username);
            User safe = copyForTransfer(user);
            return Result.ok("注册成功", safe);
        } catch (ChatException e) {
            LOGGER.warning("注册失败: " + e.getMessage());
            return Result.fail("注册失败: " + e.getMessage());
        }
    }

    /**
     * 初始化管理员账号（幂等）。
     *
     * <p>仅在账号不存在时创建，密码取配置项 {@code admin.password}，
     * 缺省值见 {@link Constants#ADMIN_DEFAULT_PASSWORD}。首次启动后应立刻修改。</p>
     *
     * @return 本次调用是否新建了管理员
     */
    public boolean initAdminIfAbsent() {
        try {
            if (userDao.exists(Constants.ADMIN_USERNAME)) {
                return false;
            }
            String password = com.chat.common.Config.get("admin.password", Constants.ADMIN_DEFAULT_PASSWORD);
            User admin = new User(Constants.ADMIN_USERNAME, Constants.ADMIN_NICKNAME);
            admin.setSalt(SecurityUtil.generateSalt());
            admin.setPasswordHash(SecurityUtil.hashPassword(password, admin.getSalt()));
            admin.setRole(Constants.ROLE_ADMIN);
            admin.setCreateTime(LocalDateTime.now());
            boolean created = userDao.save(admin);
            if (created) {
                LOGGER.info("已创建默认管理员账号 admin，请登录后立即修改密码");
            }
            return created;
        } catch (ChatException e) {
            LOGGER.warning("管理员账号初始化失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 用户登录校验。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 成功时携带脱敏用户对象及最近登录时间更新结果
     */
    public Result<User> login(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return Result.fail("用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            return Result.fail("密码不能为空");
        }
        try {
            User user = userDao.findByUsername(username.trim());
            if (user == null) {
                return Result.fail("用户不存在: " + username);
            }
            if (!SecurityUtil.verifyPassword(password, user.getSalt(), user.getPasswordHash())) {
                LOGGER.warning(() -> "登录失败（密码错误）: " + username);
                return Result.fail("密码错误");
            }
            user.setLastLoginTime(LocalDateTime.now());
            userDao.update(user);
            LOGGER.info(() -> "用户登录成功: " + username);
            return Result.ok("登录成功", copyForTransfer(user));
        } catch (ChatException e) {
            LOGGER.warning("登录异常: " + e.getMessage());
            return Result.fail("登录失败: " + e.getMessage());
        }
    }

    /**
     * 修改昵称。
     *
     * @param username 用户名
     * @param nickname 新昵称
     * @return 成功时携带更新后的脱敏用户对象
     */
    public Result<User> updateNickname(String username, String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            return Result.fail("昵称不能为空");
        }
        if (nickname.trim().length() > Constants.NICKNAME_MAX_LENGTH) {
            return Result.fail("昵称长度不能超过 " + Constants.NICKNAME_MAX_LENGTH + " 个字符");
        }
        try {
            User user = userDao.findByUsername(username);
            if (user == null) {
                return Result.fail("用户不存在: " + username);
            }
            user.setNickname(nickname.trim());
            if (!userDao.update(user)) {
                return Result.fail("昵称修改失败");
            }
            LOGGER.info(() -> "用户昵称已更新: " + username + " -> " + nickname);
            return Result.ok("昵称修改成功", copyForTransfer(user));
        } catch (ChatException e) {
            return Result.fail("昵称修改失败: " + e.getMessage());
        }
    }

    /**
     * 修改密码。
     *
     * <p>安全要求：必须校验旧密码，防止会话被劫持后直接改密码。</p>
     *
     * @param username    用户名
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 操作结果
     */
    public Result<Boolean> updatePassword(String username, String oldPassword, String newPassword) {
        Result<String> validation = validatePassword(newPassword);
        if (!validation.isSuccess()) {
            return Result.fail(validation.getMessage());
        }
        try {
            User user = userDao.findByUsername(username);
            if (user == null) {
                return Result.fail("用户不存在: " + username);
            }
            if (!SecurityUtil.verifyPassword(oldPassword, user.getSalt(), user.getPasswordHash())) {
                return Result.fail("原密码错误");
            }
            user.setSalt(SecurityUtil.generateSalt());
            user.setPasswordHash(SecurityUtil.hashPassword(newPassword, user.getSalt()));
            if (!userDao.update(user)) {
                return Result.fail("密码修改失败");
            }
            LOGGER.info(() -> "用户密码已更新: " + username);
            return Result.ok("密码修改成功", Boolean.TRUE);
        } catch (ChatException e) {
            return Result.fail("密码修改失败: " + e.getMessage());
        }
    }

    /**
     * 删除用户（仅管理员）。
     *
     * @param operator 操作者用户名
     * @param target   被删除用户名
     * @return 操作结果
     */
    public Result<Boolean> deleteUser(String operator, String target) {
        if (operator == null || target == null) {
            return Result.fail("操作者与被删除用户不能为空");
        }
        if (Constants.ADMIN_USERNAME.equals(target)) {
            return Result.fail("默认管理员账号不允许删除");
        }
        try {
            User admin = userDao.findByUsername(operator);
            if (admin == null || !admin.isAdmin()) {
                LOGGER.warning(() -> "越权删除尝试: " + operator + " -> " + target);
                return Result.fail("权限不足：仅管理员可删除用户");
            }
            if (!userDao.deleteByUsername(target)) {
                return Result.fail("删除失败");
            }
            LOGGER.info(() -> "用户已删除: " + target + "（操作者 " + operator + "）");
            return Result.ok("用户删除成功", Boolean.TRUE);
        } catch (UserNotFoundException e) {
            return Result.fail("用户不存在: " + target);
        } catch (ChatException e) {
            return Result.fail("删除失败: " + e.getMessage());
        }
    }

    /**
     * 按用户名查询用户（返回脱敏对象）。
     *
     * @param username 用户名
     * @return 成功时携带脱敏用户对象
     */
    public Result<User> findByUsername(String username) {
        try {
            User user = userDao.findByUsername(username);
            if (user == null) {
                return Result.fail("用户不存在: " + username);
            }
            return Result.ok(copyForTransfer(user));
        } catch (ChatException e) {
            return Result.fail("查询失败: " + e.getMessage());
        }
    }

    /**
     * 查询全部用户（返回脱敏对象列表），供管理端展示。
     *
     * @return 成功时携带用户列表
     */
    public Result<List<User>> listAll() {
        try {
            List<User> users = userDao.findAll();
            users.replaceAll(this::copyForTransfer);
            return Result.ok(users);
        } catch (ChatException e) {
            return Result.fail("查询失败: " + e.getMessage());
        }
    }

    /**
     * 统计注册用户数量。
     *
     * @return 用户数量
     */
    public long count() {
        try {
            return userDao.count();
        } catch (ChatException e) {
            LOGGER.warning("用户统计失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 校验用户名与密码格式。
     *
     * @param username 用户名
     * @param password 密码
     * @return 校验结果
     */
    private Result<String> validate(String username, String password) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            return Result.fail("用户名需为 3-16 位字母、数字或下划线，且以字母开头");
        }
        return validatePassword(password);
    }

    /**
     * 校验密码长度。
     *
     * @param password 密码
     * @return 校验结果
     */
    private Result<String> validatePassword(String password) {
        if (password == null || password.length() < Constants.PASSWORD_MIN_LENGTH
                || password.length() > Constants.PASSWORD_MAX_LENGTH) {
            return Result.fail("密码长度需为 " + Constants.PASSWORD_MIN_LENGTH + "-"
                    + Constants.PASSWORD_MAX_LENGTH + " 位");
        }
        return Result.ok("校验通过", password);
    }

    /**
     * 归一化昵称：为空时退化为用户名，过长时截断。
     *
     * @param nickname 原始昵称
     * @param username 用户名
     * @return 合法昵称
     */
    private String normalizeNickname(String nickname, String username) {
        if (nickname == null || nickname.trim().isEmpty()) {
            return username;
        }
        String value = nickname.trim();
        return value.length() > Constants.NICKNAME_MAX_LENGTH
                ? value.substring(0, Constants.NICKNAME_MAX_LENGTH) : value;
    }

    /**
     * 生成用于网络传输或界面展示的用户副本，剔除密码散列与盐。
     *
     * @param user 原始用户
     * @return 脱敏副本
     */
    private User copyForTransfer(User user) {
        User copy = new User(user.getUsername(), user.getNickname());
        copy.setRole(user.getRole());
        copy.setCreateTime(user.getCreateTime());
        copy.setLastLoginTime(user.getLastLoginTime());
        copy.setOnline(user.isOnline());
        return copy;
    }
}

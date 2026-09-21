package com.chat.service;

import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.common.Result;
import com.chat.common.User;
import com.chat.dao.JdbcUserDao;
import com.chat.dao.UserDao;
import com.chat.exception.ChatException;
import com.chat.exception.UserNotFoundException;
import com.chat.util.SecurityUtil;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>线程安全：持久化的线程安全由 {@link UserDao} 实现保证；本类额外持有的登录失败计数
 * 使用 {@link java.util.concurrent.ConcurrentHashMap}，可被多个连接线程并发读写，
 * 计数只用于限流，个别并发下的偏差不影响安全结论。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class UserService {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".UserService");

    /** 用户名格式校验器 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile(Constants.USERNAME_PATTERN);

    /** 同一账号连续登录失败的最大次数，超过后开始锁定 */
    private static final int MAX_LOGIN_FAILURES = 5;

    /** 登录失败锁定时长（分钟） */
    private static final int LOGIN_LOCK_MINUTES = 5;

    /** 登录失败锁定时长（毫秒），由分钟数换算，避免在判断中重复写算式 */
    private static final long LOGIN_LOCK_MILLIS = LOGIN_LOCK_MINUTES * 60_000L;

    /** 账号或口令错误的统一提示：用户不存在与密码错误必须完全一致，防止账号枚举 */
    private static final String LOGIN_FAILED_MESSAGE = "用户名或密码错误";

    /** 限流生效时的提示，明确告知等待时长，避免用户反复重试 */
    private static final String LOGIN_LOCKED_MESSAGE = "登录失败次数过多，请 " + LOGIN_LOCK_MINUTES + " 分钟后再试";

    /** 用户持久化实现 */
    private final UserDao userDao;

    /**
     * 登录失败计数器：键为「用户名小写@来源」，值为累计失败次数。
     *
     * <p>只放在内存中：限流是抵御在线暴破的实时防线，进程重启后清零可以接受；
     * 落库反而会给每次失败增加一次数据库写入，把限流本身变成放大攻击的入口。</p>
     */
    private final ConcurrentHashMap<String, Integer> loginFailureCounts = new ConcurrentHashMap<>();

    /** 登录锁定起始时间：键与 {@link #loginFailureCounts} 相同，值为锁定开始时的毫秒时间戳 */
    private final ConcurrentHashMap<String, Long> loginLockStartTimes = new ConcurrentHashMap<>();

    /**
     * 默认构造：使用数据库存储（本项目唯一的存储方式）。
     *
     * <p>不做任何降级：数据库不可用时把原因记录下来，由
     * {@link #isStorageAvailable()} 交给服务器启动自检统一汇报。</p>
     */
    public UserService() {
        this.userDao = JdbcUserDao.fromConfig();
        LOGGER.info("用户服务使用数据库存储");
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
     * 判断用户存储是否可用。
     *
     * @return 可用返回 true
     */
    public boolean isStorageAvailable() {
        return userDao instanceof JdbcUserDao && ((JdbcUserDao) userDao).isAvailable();
    }

    /**
     * 获取用户存储不可用的原因。
     *
     * @return 原因描述；可用时返回空字符串
     */
    public String storageFailureReason() {
        return userDao instanceof JdbcUserDao ? ((JdbcUserDao) userDao).failureReason() : "";
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
     * <p>仅在账号不存在时创建，口令取配置项 {@code admin.password}。刻意不提供内置默认口令：
     * 内置口令会让“忘记配置”退化成“所有人共用一个公开口令”。未配置或长度不合法时
     * 只记录告警并跳过创建，由部署者自行补齐配置后重启。</p>
     *
     * @return 本次调用是否新建了管理员
     */
    public boolean initAdminIfAbsent() {
        try {
            if (userDao.exists(Constants.ADMIN_USERNAME)) {
                return false;
            }
            String password = Config.get("admin.password", "");
            if (password.isEmpty()) {
                LOGGER.warning("未配置 admin.password，跳过创建管理员账号，"
                        + "请在 config/chat.properties 中配置后重启");
                return false;
            }
            if (password.length() < Constants.PASSWORD_MIN_LENGTH
                    || password.length() > Constants.PASSWORD_MAX_LENGTH) {
                LOGGER.warning("admin.password 长度需为 " + Constants.PASSWORD_MIN_LENGTH + "-"
                        + Constants.PASSWORD_MAX_LENGTH + " 位，跳过创建管理员账号，"
                        + "请在 config/chat.properties 中修正后重启");
                return false;
            }
            User admin = new User(Constants.ADMIN_USERNAME, Constants.ADMIN_NICKNAME);
            admin.setSalt(SecurityUtil.generateSalt());
            admin.setPasswordHash(SecurityUtil.hashPassword(password, admin.getSalt()));
            admin.setRole(Constants.ROLE_ADMIN);
            admin.setCreateTime(LocalDateTime.now());
            boolean created = userDao.save(admin);
            if (created) {
                LOGGER.info("已按 admin.password 配置创建管理员账号 " + Constants.ADMIN_USERNAME);
            }
            return created;
        } catch (ChatException e) {
            LOGGER.warning("管理员账号初始化失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 用户登录校验（来源未知）。
     *
     * <p>保留该重载是为了兼容既有调用方，实际逻辑委托给
     * {@link #login(String, String, String)}，来源按 "unknown" 统计。</p>
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 成功时携带脱敏用户对象及最近登录时间更新结果
     */
    public Result<User> login(String username, String password) {
        return login(username, password, "unknown");
    }

    /**
     * 用户登录校验（带来源标识）。
     *
     * <p>安全规则：</p>
     * <ul>
     *   <li>限流：同一「用户名 + 来源」连续失败达到 {@link #MAX_LOGIN_FAILURES} 次后锁定
     *       {@link #LOGIN_LOCK_MINUTES} 分钟，抵御在线暴力破解；</li>
     *   <li>防账号枚举：用户不存在与密码错误返回完全相同的提示文案，
     *       避免攻击者据此判断某个用户名是否已注册；日志中仍区分记录以便排障；</li>
     *   <li>透明升级：登录成功时若发现历史单轮散列，立即改用 PBKDF2 重新散列，
     *       并与最近登录时间合并为同一次写库，不额外增加一次数据库往返。</li>
     * </ul>
     *
     * @param username 用户名
     * @param password 明文密码
     * @param source   登录来源标识（如客户端 IP），可为 null，为 null 时按 "unknown" 统计
     * @return 成功时携带脱敏用户对象及最近登录时间更新结果
     */
    public Result<User> login(String username, String password, String source) {
        if (username == null || username.trim().isEmpty()) {
            return Result.fail("用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            return Result.fail("密码不能为空");
        }
        String name = username.trim();
        // 用户名不区分大小写地统计失败次数，避免通过变换大小写绕过限流
        String failureKey = name.toLowerCase() + "@"
                + (source == null || source.trim().isEmpty() ? "unknown" : source.trim());
        if (isLocked(failureKey)) {
            LOGGER.warning(() -> "登录被限流拒绝: " + name);
            return Result.fail(LOGIN_LOCKED_MESSAGE);
        }
        try {
            User user = userDao.findByUsername(name);
            if (user == null) {
                LOGGER.warning(() -> "登录失败（用户不存在）: " + name);
                recordFailure(failureKey);
                return Result.fail(LOGIN_FAILED_MESSAGE);
            }
            if (!SecurityUtil.verifyPassword(password, user.getSalt(), user.getPasswordHash())) {
                LOGGER.warning(() -> "登录失败（密码错误）: " + name);
                recordFailure(failureKey);
                return Result.fail(LOGIN_FAILED_MESSAGE);
            }
            clearFailures(failureKey);
            boolean upgraded = SecurityUtil.isLegacyHash(user.getPasswordHash());
            if (upgraded) {
                user.setSalt(SecurityUtil.generateSalt());
                user.setPasswordHash(SecurityUtil.hashPassword(password, user.getSalt()));
            }
            user.setLastLoginTime(LocalDateTime.now());
            userDao.update(user);
            if (upgraded) {
                LOGGER.info(() -> "已将历史口令散列升级为 PBKDF2: " + name);
            }
            LOGGER.info(() -> "用户登录成功: " + name);
            return Result.ok("登录成功", copyForTransfer(user));
        } catch (ChatException e) {
            LOGGER.warning("登录异常: " + e.getMessage());
            return Result.fail("登录失败: " + e.getMessage());
        }
    }

    /**
     * 判断指定计数键是否处于锁定期。
     *
     * <p>锁定到期后直接清除计数，使账号在等待期满后自动恢复可用，
     * 不需要管理员手工解锁。</p>
     *
     * @param key 计数键（用户名小写@来源）
     * @return 处于锁定期返回 true
     */
    private boolean isLocked(String key) {
        Long lockStart = loginLockStartTimes.get(key);
        if (lockStart == null) {
            return false;
        }
        if (System.currentTimeMillis() - lockStart < LOGIN_LOCK_MILLIS) {
            return true;
        }
        clearFailures(key);
        return false;
    }

    /**
     * 记录一次登录失败，达到上限时开始计时锁定。
     *
     * @param key 计数键（用户名小写@来源）
     */
    private void recordFailure(String key) {
        int failures = loginFailureCounts.merge(key, 1, Integer::sum);
        if (failures >= MAX_LOGIN_FAILURES) {
            loginLockStartTimes.put(key, System.currentTimeMillis());
            LOGGER.warning(() -> "登录失败次数达到上限，已锁定 " + LOGIN_LOCK_MINUTES + " 分钟: " + key);
        }
    }

    /**
     * 清除指定计数键的失败次数与锁定状态。
     *
     * @param key 计数键（用户名小写@来源）
     */
    private void clearFailures(String key) {
        loginFailureCounts.remove(key);
        loginLockStartTimes.remove(key);
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

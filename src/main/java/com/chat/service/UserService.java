package com.chat.service;

import com.chat.common.AvatarCodec;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 用户业务服务，负责注册、登录、资料修改、删除与查询。
 */
public class UserService {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".UserService");

    private static final Pattern USERNAME_PATTERN = Pattern.compile(Constants.USERNAME_PATTERN);

    private static final Set<String> RESERVED_USERNAMES = Set.of("all", "system", "ai");

    private static final int MAX_LOGIN_FAILURES = 5;

    private static final int LOGIN_LOCK_MINUTES = 5;

    private static final long LOGIN_LOCK_MILLIS = LOGIN_LOCK_MINUTES * 60_000L;

    private static final String LOGIN_FAILED_MESSAGE = "用户名或密码错误";

    private static final String LOGIN_LOCKED_MESSAGE = "登录失败次数过多，请 " + LOGIN_LOCK_MINUTES + " 分钟后再试";

    private final UserDao userDao;

    /** 登录失败计数，键为“用户名小写@来源”。 */
    private final ConcurrentHashMap<String, Integer> loginFailureCounts = new ConcurrentHashMap<>();

    /** 登录锁定起始时间，键与失败计数相同，值为毫秒时间戳。 */
    private final ConcurrentHashMap<String, Long> loginLockStartTimes = new ConcurrentHashMap<>();

    public UserService() {
        this.userDao = JdbcUserDao.fromConfig();
        LOGGER.info("用户服务使用数据库存储");
    }

    /**
     * 使用指定的用户存储创建服务。
     *
     * @param userDao 用户存储实现
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
     * @return 原因描述，可用时为空字符串
     */
    public String storageFailureReason() {
        return userDao instanceof JdbcUserDao ? ((JdbcUserDao) userDao).failureReason() : "";
    }

    /**
     * 注册新用户。
     *
     * @param username 用户名
     * @param password 明文密码
     * @param nickname 昵称，可为空
     * @return 注册结果，成功时携带脱敏用户
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
            // 生成盐与密码散列
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
     * 在管理员账号不存在时按配置创建。
     *
     * @return 本次是否新建了管理员账号
     */
    public boolean initAdminIfAbsent() {
        try {
            // 已有管理员账号时跳过
            if (userDao.exists(Constants.ADMIN_USERNAME)) {
                return false;
            }
            String password = Config.get("admin.password", "");
            if (password.isEmpty()) {
                LOGGER.warning("未配置 admin.password，跳过创建管理员账号，"
                        + "请在 config/chat.properties 中配置后重启");
                return false;
            }
            // 校验配置中的管理员密码长度
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

    public Result<User> login(String username, String password) {
        return login(username, password, "unknown");
    }

    /**
     * 校验用户名与密码并完成登录。
     *
     * @param username 用户名
     * @param password 明文密码
     * @param source 登录来源标识，参与失败计数
     * @return 登录结果，成功时携带脱敏用户
     */
    public Result<User> login(String username, String password, String source) {
        if (username == null || username.trim().isEmpty()) {
            return Result.fail("用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            return Result.fail("密码不能为空");
        }
        String name = username.trim();
        // 失败计数按用户名与来源组合
        String failureKey = name.toLowerCase() + "@"
                + (source == null || source.trim().isEmpty() ? "unknown" : source.trim());
        // 锁定期内直接拒绝
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
            // 登录成功后清除失败计数
            clearFailures(failureKey);
            // 单轮散列在登录成功时升级为 PBKDF2
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

    private void recordFailure(String key) {
        int failures = loginFailureCounts.merge(key, 1, Integer::sum);
        if (failures >= MAX_LOGIN_FAILURES) {
            loginLockStartTimes.put(key, System.currentTimeMillis());
            LOGGER.warning(() -> "登录失败次数达到上限，已锁定 " + LOGIN_LOCK_MINUTES + " 分钟: " + key);
        }
    }

    private void clearFailures(String key) {
        loginFailureCounts.remove(key);
        loginLockStartTimes.remove(key);
    }

    /**
     * 修改昵称。
     *
     * @param username 用户名
     * @param nickname 新昵称
     * @return 修改结果，成功时携带脱敏用户
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
     * 修改头像。
     *
     * @param username 用户名
     * @param base64 头像的 Base64 文本，空值表示恢复默认头像
     * @return 修改结果，成功时携带脱敏用户
     */
    public Result<User> updateAvatar(String username, String base64) {
        // 校验 Base64 头像数据
        String reason = AvatarCodec.check(base64);
        if (reason != null) {
            return Result.fail(reason);
        }
        try {
            User user = userDao.findByUsername(username);
            if (user == null) {
                return Result.fail("用户不存在: " + username);
            }
            user.setAvatar(base64);
            if (!userDao.update(user)) {
                return Result.fail("头像修改失败");
            }
            boolean cleared = !user.hasAvatar();
            LOGGER.info(() -> "用户头像已更新: " + username + (cleared ? "（已恢复默认）" : ""));
            return Result.ok(cleared ? "已恢复默认头像" : "头像修改成功", copyForTransfer(user));
        } catch (ChatException e) {
            return Result.fail("头像修改失败: " + e.getMessage());
        }
    }

    /**
     * 修改密码。
     *
     * @param username 用户名
     * @param oldPassword 原密码
     * @param newPassword 新密码
     * @return 修改结果
     */
    public Result<Boolean> updatePassword(String username, String oldPassword, String newPassword) {
        // 校验新密码长度
        Result<String> validation = validatePassword(newPassword);
        if (!validation.isSuccess()) {
            return Result.fail(validation.getMessage());
        }
        try {
            User user = userDao.findByUsername(username);
            if (user == null) {
                return Result.fail("用户不存在: " + username);
            }
            // 校验原密码
            if (!SecurityUtil.verifyPassword(oldPassword, user.getSalt(), user.getPasswordHash())) {
                return Result.fail("原密码错误");
            }
            // 重新生成盐与密码散列
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
     * 删除用户，仅管理员可执行。
     *
     * @param operator 操作者用户名
     * @param target 被删除的用户名
     * @return 删除结果
     */
    public Result<Boolean> deleteUser(String operator, String target) {
        if (operator == null || target == null) {
            return Result.fail("操作者与被删除用户不能为空");
        }
        if (Constants.ADMIN_USERNAME.equals(target)) {
            return Result.fail("默认管理员账号不允许删除");
        }
        try {
            // 仅管理员可以删除用户
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
     * 按用户名查询用户。
     *
     * @param username 用户名
     * @return 查询结果，成功时携带脱敏用户
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
     * 查询全部用户。
     *
     * @return 脱敏后的用户列表
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
     * 统计用户数量。
     *
     * @return 用户数，查询失败返回 0
     */
    public long count() {
        try {
            return userDao.count();
        } catch (ChatException e) {
            LOGGER.warning("用户统计失败: " + e.getMessage());
            return 0;
        }
    }

    private Result<String> validate(String username, String password) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            return Result.fail("用户名需为 3-16 位字母、数字或下划线，且以字母开头");
        }

        // 拒绝系统保留用户名
        if (RESERVED_USERNAMES.contains(username.toLowerCase(Locale.ROOT))) {
            LOGGER.info(() -> "注册被拒绝：用户名是系统保留字: " + username);
            return Result.fail("该用户名是系统保留字，请换一个");
        }
        return validatePassword(password);
    }

    private Result<String> validatePassword(String password) {
        if (password == null || password.length() < Constants.PASSWORD_MIN_LENGTH
                || password.length() > Constants.PASSWORD_MAX_LENGTH) {
            return Result.fail("密码长度需为 " + Constants.PASSWORD_MIN_LENGTH + "-"
                    + Constants.PASSWORD_MAX_LENGTH + " 位");
        }
        return Result.ok("校验通过", password);
    }

    private String normalizeNickname(String nickname, String username) {
        if (nickname == null || nickname.trim().isEmpty()) {
            return username;
        }
        String value = nickname.trim();
        return value.length() > Constants.NICKNAME_MAX_LENGTH
                ? value.substring(0, Constants.NICKNAME_MAX_LENGTH) : value;
    }

    /**
     * 复制一份用于对外传输的用户对象，不携带密码散列与盐。
     *
     * @param user 原用户对象
     * @return 脱敏后的用户副本
     */
    private User copyForTransfer(User user) {
        User copy = new User(user.getUsername(), user.getNickname());
        copy.setRole(user.getRole());
        copy.setCreateTime(user.getCreateTime());
        copy.setLastLoginTime(user.getLastLoginTime());
        copy.setOnline(user.isOnline());

        copy.setAvatar(user.getAvatar());
        return copy;
    }
}

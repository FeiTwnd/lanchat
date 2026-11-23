package com.chat.test;

import com.chat.common.Constants;
import com.chat.common.Result;
import com.chat.common.User;
import com.chat.dao.JdbcUserDao;
import com.chat.service.UserService;
import com.chat.util.SecurityUtil;

/**
 * 覆盖注册、登录、资料修改、删除权限与用户列表脱敏。
 */
public class UserServiceTest {
    private JdbcUserDao userDao;

    /** 重置测试库并构造用户服务。 */
    private UserService createService() {
        userDao = TestDatabase.userDao();
        return new UserService(userDao);
    }

    /** 验证注册成功，返回对象不含密码散列与盐值。 */
    @Test("用户服务：注册成功且返回对象不含密码散列")
    public void testRegisterSuccess() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        UserService service = createService();
        Result<User> result = service.register("alice", "123456", "爱丽丝");
        TestRunner.assertTrue(result.isSuccess(), "注册应成功: " + result.getMessage());
        User user = result.getData();
        TestRunner.assertEquals("alice", user.getUsername(), "用户名应一致");
        TestRunner.assertEquals("爱丽丝", user.getNickname(), "昵称应一致");
        TestRunner.assertEquals(Constants.ROLE_USER, user.getRole(), "默认角色应为 USER");
        TestRunner.assertNull(user.getPasswordHash(), "返回对象不应携带密码散列");
        TestRunner.assertNull(user.getSalt(), "返回对象不应携带盐值");
        TestRunner.assertEquals(1L, userDao.count(), "测试库中应恰好有 1 条用户记录");
    }

    /** 验证用户名与密码格式非法时拒绝注册。 */
    @Test("用户服务：用户名与密码格式非法时拒绝注册")
    public void testRegisterValidation() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        UserService service = createService();
        TestRunner.assertFalse(service.register("ab", "123456", "").isSuccess(),
                "用户名过短应拒绝");
        TestRunner.assertFalse(service.register("1abc", "123456", "").isSuccess(),
                "用户名以数字开头应拒绝");
        TestRunner.assertFalse(service.register("alice", "123", "").isSuccess(),
                "密码过短应拒绝");
        TestRunner.assertFalse(service.register("alice",
                "123456789012345678901234567890123", "").isSuccess(), "密码过长应拒绝");
        TestRunner.assertFalse(service.register("中文名", "123456", "").isSuccess(),
                "用户名含中文应拒绝");
        TestRunner.assertEquals(0L, userDao.count(), "非法请求不应写入任何用户");
    }

    /** 验证重复用户名注册失败并提示用户名已存在。 */
    @Test("用户服务：重复用户名注册失败")
    public void testRegisterDuplicate() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        UserService service = createService();
        TestRunner.assertTrue(service.register("bob", "123456", "鲍勃").isSuccess(),
                "首次注册应成功");
        Result<User> second = service.register("bob", "654321", "鲍勃2");
        TestRunner.assertFalse(second.isSuccess(), "重复注册应失败");
        TestRunner.assertTrue(second.getMessage().contains("已存在"), "失败原因应提示用户名已存在");
        TestRunner.assertEquals(1L, userDao.count(), "重复注册后库中仍应只有 1 条记录");
    }

    /** 验证正确凭据可登录并记录最近登录时间，错误凭据统一提示。 */
    @Test("用户服务：登录成功并可查询到最近登录时间")
    public void testLogin() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        UserService service = createService();
        service.register("carol", "123456", "卡罗尔");
        Result<User> success = service.login("carol", "123456");
        TestRunner.assertTrue(success.isSuccess(), "正确密码应登录成功");
        TestRunner.assertNotNull(success.getData().getLastLoginTime(), "应记录最近登录时间");
        TestRunner.assertNotNull(userDao.findByUsername("carol").getLastLoginTime(),
                "最近登录时间应被写回数据库");

        Result<User> wrongPassword = service.login("carol", "wrong");
        TestRunner.assertFalse(wrongPassword.isSuccess(), "错误密码应失败");
        Result<User> unknownUser = service.login("nobody", "123456");
        TestRunner.assertFalse(unknownUser.isSuccess(), "不存在的用户应失败");
        TestRunner.assertEquals("用户名或密码错误", wrongPassword.getMessage(),
                "登录失败应使用统一文案");
        TestRunner.assertEquals(wrongPassword.getMessage(), unknownUser.getMessage(),
                "用户不存在与密码错误的提示必须完全一致，避免暴露账号是否存在");
        TestRunner.assertFalse(service.login("", "123456").isSuccess(), "空用户名应失败");
        TestRunner.assertFalse(service.login("carol", "").isSuccess(), "空密码应失败");
    }

    /** 验证数据库保存的是加盐散列而非明文密码。 */
    @Test("用户服务：数据库中的密码为加盐散列，不含明文")
    public void testPasswordHashed() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        UserService service = createService();
        service.register("dave", "MySecret123", "戴夫");

        User raw = userDao.findByUsername("dave");
        TestRunner.assertNotNull(raw, "应能从测试库读到原始用户记录");
        TestRunner.assertNotNull(raw.getSalt(), "应保存盐值");
        TestRunner.assertNotEquals("MySecret123", raw.getPasswordHash(), "不得保存明文密码");
        TestRunner.assertTrue(raw.getPasswordHash().startsWith("pbkdf2$120000$"),
                "新注册用户的口令散列应为 PBKDF2 格式");
        TestRunner.assertEquals(78, raw.getPasswordHash().length(),
                "PBKDF2 散列应为 pbkdf2$120000$ 前缀加 64 位十六进制，共 78 字符");
        TestRunner.assertFalse(SecurityUtil.isLegacyHash(raw.getPasswordHash()),
                "PBKDF2 散列不应被识别为历史散列");
        TestRunner.assertTrue(
                SecurityUtil.verifyPassword("MySecret123", raw.getSalt(), raw.getPasswordHash()),
                "保存的散列应能校验原始密码");
    }

    /** 验证修改昵称后查询结果与数据库同步更新。 */
    @Test("用户服务：修改昵称后查询结果更新")
    public void testUpdateNickname() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        UserService service = createService();
        service.register("erin", "123456", "旧昵称");
        TestRunner.assertTrue(service.updateNickname("erin", "新昵称").isSuccess(), "修改昵称应成功");
        TestRunner.assertEquals("新昵称", service.findByUsername("erin").getData().getNickname(),
                "查询结果应为新昵称");
        TestRunner.assertEquals("新昵称", userDao.findByUsername("erin").getNickname(),
                "数据库中的昵称应已更新");
        TestRunner.assertFalse(service.updateNickname("erin", "").isSuccess(), "空昵称应拒绝");
        TestRunner.assertFalse(service.updateNickname("nobody", "x").isSuccess(), "用户不存在应失败");
    }

    /** 验证修改密码需校验原密码，修改后新密码可登录。 */
    @Test("用户服务：修改密码校验原密码，并可用新密码登录")
    public void testUpdatePassword() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        UserService service = createService();
        service.register("frank", "123456", "弗兰克");
        TestRunner.assertFalse(service.updatePassword("frank", "wrongOld", "abcdef").isSuccess(),
                "原密码错误应拒绝修改");
        TestRunner.assertTrue(service.updatePassword("frank", "123456", "abcdef").isSuccess(),
                "原密码正确应修改成功");
        TestRunner.assertTrue(service.login("frank", "abcdef").isSuccess(), "应能用新密码登录");
        TestRunner.assertFalse(service.login("frank", "123456").isSuccess(), "旧密码应失效");
    }

    /** 验证普通用户无权删除用户，管理员可删除且默认管理员受保护。 */
    @Test("用户服务：普通用户无权删除，管理员可删除")
    public void testDeletePermission() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        UserService service = createService();
        service.register("gina", "123456", "吉娜");
        service.register("henry", "123456", "亨利");
        TestRunner.assertFalse(service.deleteUser("gina", "henry").isSuccess(),
                "普通用户删除他人应被拒绝");
        TestRunner.assertNotNull(service.findByUsername("henry").getData(),
                "越权删除失败后目标用户仍应存在");

        User admin = new User(Constants.ADMIN_USERNAME, Constants.ADMIN_NICKNAME);
        admin.setSalt(SecurityUtil.generateSalt());
        admin.setPasswordHash(SecurityUtil.hashPassword("admin123", admin.getSalt()));
        admin.setRole(Constants.ROLE_ADMIN);
        TestRunner.assertTrue(userDao.save(admin), "应能写入管理员账号");
        TestRunner.assertFalse(service.initAdminIfAbsent(), "管理员已存在时不应重复创建");
        TestRunner.assertTrue(service.deleteUser(Constants.ADMIN_USERNAME, "henry").isSuccess(),
                "管理员应能删除用户");
        TestRunner.assertFalse(service.findByUsername("henry").isSuccess(), "删除后应查询不到");
        TestRunner.assertNull(userDao.findByUsername("henry"), "数据库中该用户应已被删除");
        TestRunner.assertFalse(service.deleteUser(Constants.ADMIN_USERNAME,
                Constants.ADMIN_USERNAME).isSuccess(), "默认管理员不允许被删除");
    }

    /** 验证用户列表返回脱敏后的用户对象。 */
    @Test("用户服务：列表查询返回脱敏用户对象")
    public void testListAll() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        UserService service = createService();
        service.register("ivan", "123456", "伊万");
        service.register("judy", "123456", "朱迪");
        Result<java.util.List<User>> result = service.listAll();
        TestRunner.assertTrue(result.isSuccess(), "查询应成功");
        TestRunner.assertEquals(2, result.getData().size(), "应返回两条用户数据");
        for (User user : result.getData()) {
            TestRunner.assertNull(user.getPasswordHash(), "列表结果不应包含密码散列");
            TestRunner.assertNull(user.getSalt(), "列表结果不应包含盐值");
        }
    }
}

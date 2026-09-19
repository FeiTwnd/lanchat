package com.chat.test;

import com.chat.common.Constants;
import com.chat.common.Result;
import com.chat.common.User;
import com.chat.dao.UserDaoImpl;
import com.chat.service.UserService;

import java.nio.file.Path;

/**
 * 用户业务服务单元测试。
 *
 * <p>覆盖点：注册成功、用户名格式校验、密码长度校验、重复用户名、
 * 登录成功/失败、昵称修改、密码修改（含原密码校验）、删除权限控制、查询。</p>
 *
 * <p>隔离策略：通过构造器注入临时文件 DAO，避免污染真实用户数据。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class UserServiceTest {

    /** 当前测试使用的临时目录 */
    private Path dir;

    /**
     * 创建使用临时数据文件的用户服务。
     *
     * @param prefix 临时目录前缀
     * @return 用户服务实例
     * @throws Exception 创建临时目录失败
     */
    private UserService createService(String prefix) throws Exception {
        dir = TestRunner.createTempDir(prefix);
        return new UserService(new UserDaoImpl(dir.resolve("users.txt").toString()));
    }

    /**
     * 清理临时目录。
     */
    private void cleanup() {
        TestRunner.deleteRecursively(dir);
        dir = null;
    }

    /**
     * 用例 1：注册成功并返回脱敏用户对象。
     *
     * @throws Exception 测试异常
     */
    @Test("用户服务：注册成功且返回对象不含密码散列")
    public void testRegisterSuccess() throws Exception {
        UserService service = createService("usersvc-register");
        try {
            Result<User> result = service.register("alice", "123456", "爱丽丝");
            TestRunner.assertTrue(result.isSuccess(), "注册应成功: " + result.getMessage());
            User user = result.getData();
            TestRunner.assertEquals("alice", user.getUsername(), "用户名应一致");
            TestRunner.assertEquals("爱丽丝", user.getNickname(), "昵称应一致");
            TestRunner.assertEquals(Constants.ROLE_USER, user.getRole(), "默认角色应为 USER");
            TestRunner.assertNull(user.getPasswordHash(), "返回对象不应携带密码散列");
            TestRunner.assertNull(user.getSalt(), "返回对象不应携带盐值");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 2：用户名与密码格式校验。
     *
     * @throws Exception 测试异常
     */
    @Test("用户服务：用户名与密码格式非法时拒绝注册")
    public void testRegisterValidation() throws Exception {
        UserService service = createService("usersvc-validate");
        try {
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
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 3：重复用户名注册被拒绝。
     *
     * @throws Exception 测试异常
     */
    @Test("用户服务：重复用户名注册失败")
    public void testRegisterDuplicate() throws Exception {
        UserService service = createService("usersvc-dup");
        try {
            TestRunner.assertTrue(service.register("bob", "123456", "鲍勃").isSuccess(),
                    "首次注册应成功");
            Result<User> second = service.register("bob", "654321", "鲍勃2");
            TestRunner.assertFalse(second.isSuccess(), "重复注册应失败");
            TestRunner.assertTrue(second.getMessage().contains("已存在"), "失败原因应提示用户名已存在");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 4：登录成功与失败。
     *
     * @throws Exception 测试异常
     */
    @Test("用户服务：登录成功并可查询到最近登录时间")
    public void testLogin() throws Exception {
        UserService service = createService("usersvc-login");
        try {
            service.register("carol", "123456", "卡罗尔");
            Result<User> success = service.login("carol", "123456");
            TestRunner.assertTrue(success.isSuccess(), "正确密码应登录成功");
            TestRunner.assertNotNull(success.getData().getLastLoginTime(), "应记录最近登录时间");

            TestRunner.assertFalse(service.login("carol", "wrong").isSuccess(), "错误密码应失败");
            TestRunner.assertFalse(service.login("nobody", "123456").isSuccess(), "不存在的用户应失败");
            TestRunner.assertFalse(service.login("", "123456").isSuccess(), "空用户名应失败");
            TestRunner.assertFalse(service.login("carol", "").isSuccess(), "空密码应失败");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 5：密码以加盐散列形式存储。
     *
     * @throws Exception 测试异常
     */
    @Test("用户服务：数据库中的密码为加盐散列，不含明文")
    public void testPasswordHashed() throws Exception {
        UserService service = createService("usersvc-hash");
        try {
            service.register("dave", "MySecret123", "戴夫");
            User raw = new UserDaoImpl(dir.resolve("users.txt").toString()).findByUsername("dave");
            TestRunner.assertNotNull(raw.getSalt(), "应保存盐值");
            TestRunner.assertNotEquals("MySecret123", raw.getPasswordHash(), "不得保存明文密码");
            TestRunner.assertEquals(64, raw.getPasswordHash().length(), "散列长度应为 64 字符");
            TestRunner.assertTrue(
                    com.chat.util.SecurityUtil.verifyPassword("MySecret123", raw.getSalt(), raw.getPasswordHash()),
                    "保存的散列应能校验原始密码");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 6：修改昵称。
     *
     * @throws Exception 测试异常
     */
    @Test("用户服务：修改昵称后查询结果更新")
    public void testUpdateNickname() throws Exception {
        UserService service = createService("usersvc-nick");
        try {
            service.register("erin", "123456", "旧昵称");
            TestRunner.assertTrue(service.updateNickname("erin", "新昵称").isSuccess(), "修改昵称应成功");
            TestRunner.assertEquals("新昵称", service.findByUsername("erin").getData().getNickname(),
                    "查询结果应为新昵称");
            TestRunner.assertFalse(service.updateNickname("erin", "").isSuccess(), "空昵称应拒绝");
            TestRunner.assertFalse(service.updateNickname("nobody", "x").isSuccess(), "用户不存在应失败");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 7：修改密码需要正确的原密码。
     *
     * @throws Exception 测试异常
     */
    @Test("用户服务：修改密码校验原密码，并可用新密码登录")
    public void testUpdatePassword() throws Exception {
        UserService service = createService("usersvc-pwd");
        try {
            service.register("frank", "123456", "弗兰克");
            TestRunner.assertFalse(service.updatePassword("frank", "wrongOld", "abcdef").isSuccess(),
                    "原密码错误应拒绝修改");
            TestRunner.assertTrue(service.updatePassword("frank", "123456", "abcdef").isSuccess(),
                    "原密码正确应修改成功");
            TestRunner.assertTrue(service.login("frank", "abcdef").isSuccess(), "应能用新密码登录");
            TestRunner.assertFalse(service.login("frank", "123456").isSuccess(), "旧密码应失效");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 8：删除用户的权限控制。
     *
     * @throws Exception 测试异常
     */
    @Test("用户服务：普通用户无权删除，管理员可删除")
    public void testDeletePermission() throws Exception {
        UserService service = createService("usersvc-delete");
        try {
            service.register("gina", "123456", "吉娜");
            service.register("henry", "123456", "亨利");
            TestRunner.assertFalse(service.deleteUser("gina", "henry").isSuccess(),
                    "普通用户删除他人应被拒绝");
            TestRunner.assertNotNull(service.findByUsername("henry").getData(),
                    "越权删除失败后目标用户仍应存在");

            TestRunner.assertTrue(service.initAdminIfAbsent(), "应成功创建管理员");
            TestRunner.assertTrue(service.deleteUser(Constants.ADMIN_USERNAME, "henry").isSuccess(),
                    "管理员应能删除用户");
            TestRunner.assertFalse(service.findByUsername("henry").isSuccess(), "删除后应查询不到");
            TestRunner.assertFalse(service.deleteUser(Constants.ADMIN_USERNAME,
                    Constants.ADMIN_USERNAME).isSuccess(), "默认管理员不允许被删除");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 9：用户列表查询不泄露凭据。
     *
     * @throws Exception 测试异常
     */
    @Test("用户服务：列表查询返回脱敏用户对象")
    public void testListAll() throws Exception {
        UserService service = createService("usersvc-list");
        try {
            service.register("ivan", "123456", "伊万");
            service.register("judy", "123456", "朱迪");
            Result<java.util.List<User>> result = service.listAll();
            TestRunner.assertTrue(result.isSuccess(), "查询应成功");
            TestRunner.assertEquals(2, result.getData().size(), "应返回两条用户数据");
            for (User user : result.getData()) {
                TestRunner.assertNull(user.getPasswordHash(), "列表结果不应包含密码散列");
                TestRunner.assertNull(user.getSalt(), "列表结果不应包含盐值");
            }
        } finally {
            cleanup();
        }
    }
}

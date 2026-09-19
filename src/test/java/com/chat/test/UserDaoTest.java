package com.chat.test;

import com.chat.common.Constants;
import com.chat.common.User;
import com.chat.dao.JdbcUserDao;
import com.chat.exception.ChatException;
import com.chat.exception.UserNotFoundException;
import com.chat.util.SecurityUtil;

import java.time.LocalDateTime;

/**
 * 用户数据访问层单元测试。
 *
 * <p>覆盖点：新增、查询、更新、删除、写入后可被新实例重新加载、
 * 重复用户名拒绝、异常路径（删除不存在的用户）。</p>
 *
 * <p>隔离策略：统一使用独立测试库 {@code lanchat_test}（见 {@link TestDatabase}），
 * 每个用例开始时清空 {@code chat_user} 表，用例之间互不干扰；
 * 测试库由 {@link TestDatabase} 自动把库名替换得到，绝不会写入开发者真实使用的库。
 * 数据库不可用时用例通过 {@link TestRunner#skip(String)} 报告跳过，而不是判为失败。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class UserDaoTest {

    /**
     * 用例 1：新增并查询用户。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：新增后可查询，字段完整")
    public void testSaveAndFind() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        JdbcUserDao dao = TestDatabase.userDao();
        TestRunner.assertTrue(dao.isAvailable(), "测试库应可用: " + dao.failureReason());

        User user = buildUser("alice", "张三");
        TestRunner.assertTrue(dao.save(user), "新增用户应成功");
        User loaded = dao.findByUsername("alice");
        TestRunner.assertNotNull(loaded, "应能按用户名查询到用户");
        TestRunner.assertEquals("张三", loaded.getNickname(), "昵称应被正确保存");
        TestRunner.assertEquals(Constants.ROLE_USER, loaded.getRole(), "默认角色应为普通用户");
        TestRunner.assertEquals(64, loaded.getPasswordHash().length(), "密码散列应被完整回读");
        TestRunner.assertEquals(1L, dao.count(), "用户总数应为 1");
    }

    /**
     * 用例 2：重复用户名不允许新增。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：重复用户名新增被拒绝且不覆盖原记录")
    public void testDuplicateUsername() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        JdbcUserDao dao = TestDatabase.userDao();
        TestRunner.assertTrue(dao.save(buildUser("bob", "李四")), "首次新增应成功");
        // 数据库以 username 为主键，重复写入会触发主键冲突，DAO 把它转成受检的 ChatException；
        // 语义与原先的"新增返回 false"一致：重复用户名不会被写成第二条记录
        boolean rejected = false;
        try {
            dao.save(buildUser("bob", "李四2"));
        } catch (ChatException e) {
            rejected = true;
        }
        TestRunner.assertTrue(rejected, "重复用户名应被拒绝");
        TestRunner.assertEquals(1L, dao.count(), "重复新增后总数仍应为 1");
        TestRunner.assertEquals("李四", dao.findByUsername("bob").getNickname(), "原记录不应被覆盖");
    }

    /**
     * 用例 3：更新用户资料。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：更新昵称后重新查询生效")
    public void testUpdate() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        JdbcUserDao dao = TestDatabase.userDao();
        User user = buildUser("carol", "旧昵称");
        dao.save(user);
        user.setNickname("新昵称");
        user.setLastLoginTime(LocalDateTime.now());
        TestRunner.assertTrue(dao.update(user), "更新应成功");

        User reloaded = dao.findByUsername("carol");
        TestRunner.assertEquals("新昵称", reloaded.getNickname(), "重新查询应得到更新后的昵称");
        TestRunner.assertNotNull(reloaded.getLastLoginTime(), "最近登录时间应被写入");
    }

    /**
     * 用例 4：删除用户及异常路径。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：删除生效，删除不存在用户抛 UserNotFoundException")
    public void testDelete() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        JdbcUserDao dao = TestDatabase.userDao();
        dao.save(buildUser("dave", "大卫"));
        TestRunner.assertTrue(dao.deleteByUsername("dave"), "删除应成功");
        TestRunner.assertNull(dao.findByUsername("dave"), "删除后应查询不到");
        boolean thrown = false;
        try {
            dao.deleteByUsername("not-exist");
        } catch (UserNotFoundException e) {
            thrown = true;
        }
        TestRunner.assertTrue(thrown, "删除不存在用户应抛出 UserNotFoundException");
    }

    /**
     * 用例 5：写入数据库后可被新实例重新加载。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：数据入库后可被新实例重新加载")
    public void testPersistence() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        JdbcUserDao dao = TestDatabase.userDao();
        dao.save(buildUser("erin", "艾琳"));
        dao.save(buildUser("frank", "弗兰克"));

        // 换一个全新的 DAO 实例：数据必须来自数据库，而不是上一个实例的内存状态
        JdbcUserDao reloaded = new JdbcUserDao(TestDatabase.driver(), TestDatabase.url(),
                TestDatabase.user(), TestDatabase.password());
        TestRunner.assertEquals(2L, reloaded.count(), "重新加载后应包含两条用户数据");
        TestRunner.assertNotNull(reloaded.findByUsername("erin"), "重新加载后应能查询到 erin");
    }

    /**
     * 用例 6：saveOrUpdate 语义。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：saveOrUpdate 兼具新增与更新能力")
    public void testSaveOrUpdate() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        JdbcUserDao dao = TestDatabase.userDao();
        User user = buildUser("gina", "吉娜");
        TestRunner.assertTrue(dao.saveOrUpdate(user), "首次保存应成功");
        user.setNickname("吉娜2");
        TestRunner.assertTrue(dao.saveOrUpdate(user), "二次保存应更新成功");
        TestRunner.assertEquals(1L, dao.count(), "不应产生重复记录");
        TestRunner.assertEquals("吉娜2", dao.findByUsername("gina").getNickname(), "昵称应被更新");
    }

    /**
     * 用例 7：空值参数校验。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：保存或更新空对象被拒绝")
    public void testNullEntity() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        JdbcUserDao dao = TestDatabase.userDao();
        TestRunner.assertFalse(dao.save(null), "保存空对象应被拒绝");
        TestRunner.assertFalse(dao.update(null), "更新空对象应被拒绝");
        TestRunner.assertEquals(0L, dao.count(), "空对象不应产生任何记录");
    }

    /**
     * 构造测试用用户对象。
     *
     * @param username 用户名
     * @param nickname 昵称
     * @return 用户对象
     */
    private User buildUser(String username, String nickname) {
        User user = new User(username, nickname);
        user.setSalt(SecurityUtil.generateSalt());
        user.setPasswordHash(SecurityUtil.hashPassword("123456", user.getSalt()));
        user.setCreateTime(LocalDateTime.now());
        return user;
    }
}

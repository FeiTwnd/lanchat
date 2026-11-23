package com.chat.test;

import com.chat.common.Constants;
import com.chat.common.User;
import com.chat.dao.JdbcUserDao;
import com.chat.exception.ChatException;
import com.chat.exception.UserNotFoundException;
import com.chat.util.SecurityUtil;

import java.time.LocalDateTime;

/**
 * 覆盖用户记录的增删改查、唯一约束与头像字段持久化。
 */
public class UserDaoTest {
    /** 验证新增用户后可查询到且字段完整。 */
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

        TestRunner.assertTrue(loaded.getPasswordHash().startsWith("pbkdf2$"),
                "密码散列应为 PBKDF2 格式并被完整回读");
        TestRunner.assertEquals(1L, dao.count(), "用户总数应为 1");
    }

    /** 验证重复用户名新增被拒绝且原记录不被覆盖。 */
    @Test("用户 DAO：重复用户名新增被拒绝且不覆盖原记录")
    public void testDuplicateUsername() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        JdbcUserDao dao = TestDatabase.userDao();
        TestRunner.assertTrue(dao.save(buildUser("bob", "李四")), "首次新增应成功");

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

    /** 验证更新昵称与最近登录时间后重新查询生效。 */
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

    /** 验证删除用户生效，删除不存在的用户抛出 UserNotFoundException。 */
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

    /** 验证新 DAO 实例能重新加载已入库的用户数据。 */
    @Test("用户 DAO：数据入库后可被新实例重新加载")
    public void testPersistence() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        JdbcUserDao dao = TestDatabase.userDao();
        dao.save(buildUser("erin", "艾琳"));
        dao.save(buildUser("frank", "弗兰克"));

        JdbcUserDao reloaded = new JdbcUserDao(TestDatabase.driver(), TestDatabase.url(),
                TestDatabase.user(), TestDatabase.password());
        TestRunner.assertEquals(2L, reloaded.count(), "重新加载后应包含两条用户数据");
        TestRunner.assertNotNull(reloaded.findByUsername("erin"), "重新加载后应能查询到 erin");
    }

    /** 验证 saveOrUpdate 首次执行新增，再次执行更新。 */
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

    /** 验证保存或更新空对象被拒绝且不产生记录。 */
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

    /** 验证头像可写入、更新时保留并可清空。 */
    @Test("用户 DAO：头像可写入、回读与清空")
    public void testAvatarColumn() throws Exception {
        if (!TestDatabase.isAvailable()) {
            TestRunner.skip(TestDatabase.unavailableReason());
        }
        JdbcUserDao dao = TestDatabase.userDao();
        User user = buildUser("helen", "海伦");
        String avatar = "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4";
        user.setAvatar(avatar);
        TestRunner.assertTrue(dao.save(user), "带头像新增应成功");
        TestRunner.assertEquals(avatar, dao.findByUsername("helen").getAvatar(), "头像应被完整回读");

        user.setNickname("海伦2");
        TestRunner.assertTrue(dao.update(user), "更新应成功");
        TestRunner.assertEquals(avatar, dao.findByUsername("helen").getAvatar(),
                "更新昵称后头像应保留");

        user.setAvatar(null);
        TestRunner.assertTrue(dao.update(user), "清空头像的更新应成功");
        TestRunner.assertNull(dao.findByUsername("helen").getAvatar(), "清空后应为 null");
    }

    /** 构造带盐值与密码散列的用户对象。 */
    private User buildUser(String username, String nickname) {
        User user = new User(username, nickname);
        user.setSalt(SecurityUtil.generateSalt());
        user.setPasswordHash(SecurityUtil.hashPassword("123456", user.getSalt()));
        user.setCreateTime(LocalDateTime.now());
        return user;
    }
}

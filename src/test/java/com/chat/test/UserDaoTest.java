package com.chat.test;

import com.chat.common.Constants;
import com.chat.common.User;
import com.chat.dao.UserDaoImpl;
import com.chat.exception.ChatException;
import com.chat.exception.UserNotFoundException;
import com.chat.util.SecurityUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户数据访问层单元测试。
 *
 * <p>覆盖点：新增、查询、更新、删除、持久化落盘与重启后重新加载、
 * 重复用户名拒绝、异常路径（删除不存在的用户）。</p>
 *
 * <p>隔离策略：每个测试方法使用独立的临时文件，互不干扰，测试结束后删除。</p>
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
        Path dir = TestRunner.createTempDir("userdao-save");
        try {
            UserDaoImpl dao = new UserDaoImpl(dir.resolve("users.txt").toString());
            User user = buildUser("alice", "张三");
            TestRunner.assertTrue(dao.save(user), "新增用户应成功");
            User loaded = dao.findByUsername("alice");
            TestRunner.assertNotNull(loaded, "应能按用户名查询到用户");
            TestRunner.assertEquals("张三", loaded.getNickname(), "昵称应被正确保存");
            TestRunner.assertEquals(Constants.ROLE_USER, loaded.getRole(), "默认角色应为普通用户");
            TestRunner.assertEquals(1L, dao.count(), "用户总数应为 1");
        } finally {
            TestRunner.deleteRecursively(dir);
        }
    }

    /**
     * 用例 2：重复用户名不允许新增。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：重复用户名新增返回 false")
    public void testDuplicateUsername() throws Exception {
        Path dir = TestRunner.createTempDir("userdao-dup");
        try {
            UserDaoImpl dao = new UserDaoImpl(dir.resolve("users.txt").toString());
            TestRunner.assertTrue(dao.save(buildUser("bob", "李四")), "首次新增应成功");
            TestRunner.assertFalse(dao.save(buildUser("bob", "李四2")), "重复用户名应新增失败");
            TestRunner.assertEquals(1L, dao.count(), "重复新增后总数仍应为 1");
        } finally {
            TestRunner.deleteRecursively(dir);
        }
    }

    /**
     * 用例 3：更新用户资料。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：更新昵称后重新查询生效")
    public void testUpdate() throws Exception {
        Path dir = TestRunner.createTempDir("userdao-update");
        try {
            UserDaoImpl dao = new UserDaoImpl(dir.resolve("users.txt").toString());
            User user = buildUser("carol", "旧昵称");
            dao.save(user);
            user.setNickname("新昵称");
            user.setLastLoginTime(LocalDateTime.now());
            TestRunner.assertTrue(dao.update(user), "更新应成功");
            TestRunner.assertEquals("新昵称", dao.findByUsername("carol").getNickname(),
                    "重新查询应得到更新后的昵称");
        } finally {
            TestRunner.deleteRecursively(dir);
        }
    }

    /**
     * 用例 4：删除用户及异常路径。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：删除生效，删除不存在用户抛 UserNotFoundException")
    public void testDelete() throws Exception {
        Path dir = TestRunner.createTempDir("userdao-delete");
        try {
            UserDaoImpl dao = new UserDaoImpl(dir.resolve("users.txt").toString());
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
        } finally {
            TestRunner.deleteRecursively(dir);
        }
    }

    /**
     * 用例 5：持久化到磁盘并可重新加载。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：数据落盘后可被新实例重新加载")
    public void testPersistence() throws Exception {
        Path dir = TestRunner.createTempDir("userdao-persist");
        try {
            File file = dir.resolve("users.txt").toFile();
            UserDaoImpl dao = new UserDaoImpl(file.getAbsolutePath());
            dao.save(buildUser("erin", "艾琳"));
            dao.save(buildUser("frank", "弗兰克"));
            TestRunner.assertTrue(file.isFile(), "数据文件应被创建");
            List<String> lines = Files.readAllLines(file.toPath(), Constants.CHARSET);
            TestRunner.assertTrue(lines.size() >= 3, "文件应包含表头与两行数据");

            UserDaoImpl reloaded = new UserDaoImpl(file.getAbsolutePath());
            TestRunner.assertEquals(2L, reloaded.count(), "重新加载后应包含两条用户数据");
            TestRunner.assertNotNull(reloaded.findByUsername("erin"), "重新加载后应能查询到 erin");
        } finally {
            TestRunner.deleteRecursively(dir);
        }
    }

    /**
     * 用例 6：saveOrUpdate 语义。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：saveOrUpdate 兼具新增与更新能力")
    public void testSaveOrUpdate() throws Exception {
        Path dir = TestRunner.createTempDir("userdao-upsert");
        try {
            UserDaoImpl dao = new UserDaoImpl(dir.resolve("users.txt").toString());
            User user = buildUser("gina", "吉娜");
            TestRunner.assertTrue(dao.saveOrUpdate(user), "首次保存应成功");
            user.setNickname("吉娜2");
            TestRunner.assertTrue(dao.saveOrUpdate(user), "二次保存应更新成功");
            TestRunner.assertEquals(1L, dao.count(), "不应产生重复记录");
            TestRunner.assertEquals("吉娜2", dao.findByUsername("gina").getNickname(), "昵称应被更新");
        } finally {
            TestRunner.deleteRecursively(dir);
        }
    }

    /**
     * 用例 7：空值参数校验。
     *
     * @throws Exception 测试过程中出现异常
     */
    @Test("用户 DAO：保存空对象抛 ChatException")
    public void testNullEntity() throws Exception {
        Path dir = TestRunner.createTempDir("userdao-null");
        try {
            UserDaoImpl dao = new UserDaoImpl(dir.resolve("users.txt").toString());
            boolean thrown = false;
            try {
                dao.save(null);
            } catch (ChatException e) {
                thrown = true;
            }
            TestRunner.assertTrue(thrown, "保存空对象应抛出 ChatException");
        } finally {
            TestRunner.deleteRecursively(dir);
        }
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

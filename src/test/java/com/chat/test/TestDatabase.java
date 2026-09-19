package com.chat.test;

import com.chat.common.Config;
import com.chat.dao.JdbcMessageDao;
import com.chat.dao.JdbcUserDao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 测试用数据库助手。
 *
 * <p>职责：把单元测试与集成测试指向一个**独立的测试库**（默认 {@code lanchat_test}），
 * 由 DAO 自行建表、由本类在每个用例前清空数据，从而在"存储只保留数据库"之后，
 * 仍然保持原先"临时目录互不干扰"的隔离效果。</p>
 *
 * <p>为什么要换库名而不是直接用 {@code db.url}：测试会写入并清空数据，
 * 一旦连到开发者真实使用的 {@code lanchat} 库就会造成数据丢失。
 * 因此这里强制把连接地址中的库名替换为 {@code lanchat_test}，
 * 并依靠 {@code createDatabaseIfNotExist=true} 让库不存在时自动创建；
 * 需要指向别处时可用系统属性 {@code lanchat.test.db.url} 覆盖。</p>
 *
 * <p>数据库不可用时：{@link #isAvailable()} 返回 false 并给出原因，
 * 用例通过 {@link TestRunner#skip(String)} 报告"跳过"，而不是产生一堆失败。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class TestDatabase {

    /** 测试库名 */
    private static final String TEST_DATABASE = "lanchat_test";

    /** 覆盖用系统属性名 */
    private static final String URL_PROPERTY = "lanchat.test.db.url";

    /** 可用性探测结果，-1 未探测、1 可用、0 不可用 */
    private static int availableState = -1;

    /** 不可用原因 */
    private static String unavailableReason = "";

    /** 私有构造，禁止实例化工具类 */
    private TestDatabase() {
    }

    /**
     * 测试库连接地址。
     *
     * @return 形如 {@code jdbc:mysql://localhost:3306/lanchat_test?...} 的地址
     */
    public static String url() {
        String override = System.getProperty(URL_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        String base = Config.dbUrl();
        int question = base.indexOf('?');
        String params = question >= 0 ? base.substring(question + 1) : "";
        String head = question >= 0 ? base.substring(0, question) : base;
        int slash = head.lastIndexOf('/');
        String target = (slash >= 0 ? head.substring(0, slash + 1) : head) + TEST_DATABASE;
        if (!params.contains("createDatabaseIfNotExist")) {
            params = "createDatabaseIfNotExist=true" + (params.isEmpty() ? "" : "&" + params);
        }
        if (!params.contains("characterEncoding")) {
            params = params + "&characterEncoding=utf8";
        }
        return target + "?" + params;
    }

    /**
     * 测试库用户名。
     *
     * @return 用户名
     */
    public static String user() {
        return Config.dbUser();
    }

    /**
     * 测试库密码。
     *
     * @return 密码
     */
    public static String password() {
        return Config.dbPassword();
    }

    /**
     * 测试库驱动类名。
     *
     * @return 驱动类名
     */
    public static String driver() {
        return Config.dbDriver();
    }

    /**
     * 测试库是否可用（驱动存在且能连上）。
     *
     * @return 可用返回 true
     */
    public static boolean isAvailable() {
        if (availableState < 0) {
            probe();
        }
        return availableState == 1;
    }

    /**
     * 探测测试库可用性，结果只计算一次。
     */
    private static void probe() {
        try {
            Class.forName(driver());
            try (Connection ignored = DriverManager.getConnection(url(), user(), password())) {
                availableState = 1;
                unavailableReason = "";
            }
        } catch (ClassNotFoundException e) {
            availableState = 0;
            unavailableReason = "未找到 JDBC 驱动 " + driver();
        } catch (SQLException e) {
            availableState = 0;
            unavailableReason = "无法连接测试库 " + url() + "（" + e.getMessage() + "）";
        }
    }

    /**
     * 获取不可用原因。
     *
     * @return 原因描述；可用时为空字符串
     */
    public static String unavailableReason() {
        if (availableState < 0) {
            probe();
        }
        return unavailableReason;
    }

    /**
     * 重置测试数据：先让 DAO 建表，再清空两张表。
     *
     * <p>建表交给 DAO 完成，是为了让建表语句只有一份（DAO 构造函数里的
     * {@code CREATE TABLE IF NOT EXISTS}），测试不再维护第二份 DDL。</p>
     */
    public static void reset() {
        // 触发建表：DAO 构造函数会执行 CREATE TABLE IF NOT EXISTS
        new JdbcUserDao(driver(), url(), user(), password());
        new JdbcMessageDao(driver(), url(), user(), password());
        try (Connection connection = DriverManager.getConnection(url(), user(), password());
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE chat_user");
            statement.execute("TRUNCATE TABLE chat_message");
        } catch (SQLException e) {
            throw new IllegalStateException("测试数据清理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建指向测试库的用户 DAO。
     *
     * @return JDBC 用户 DAO
     */
    public static JdbcUserDao userDao() {
        reset();
        return new JdbcUserDao(driver(), url(), user(), password());
    }

    /**
     * 创建指向测试库的消息 DAO。
     *
     * @return JDBC 消息 DAO
     */
    public static JdbcMessageDao messageDao() {
        reset();
        return new JdbcMessageDao(driver(), url(), user(), password());
    }
}

package com.chat.test;

import com.chat.common.Config;
import com.chat.dao.JdbcMessageDao;
import com.chat.dao.JdbcUserDao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 提供测试库的连接参数、可用性探测与测试数据清理。
 */
public final class TestDatabase {
    /** 测试库名称，与运行库隔离。 */
    private static final String TEST_DATABASE = "lanchat_test";

    /** 覆盖测试库地址的系统属性名。 */
    private static final String URL_PROPERTY = "lanchat.test.db.url";

    /** 探测结果：-1 表示未探测，0 表示不可用，1 表示可用。 */
    private static int availableState = -1;

    private static String unavailableReason = "";

    private TestDatabase() {
    }

    /**
     * 构造测试库连接地址，必要时追加建库与编码参数。
     *
     * @return JDBC 连接地址
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
     * 返回测试库用户名。
     *
     * @return 数据库用户名
     */
    public static String user() {
        return Config.dbUser();
    }

    /**
     * 返回测试库密码。
     *
     * @return 数据库密码
     */
    public static String password() {
        return Config.dbPassword();
    }

    /**
     * 返回 JDBC 驱动类名。
     *
     * @return 驱动类名
     */
    public static String driver() {
        return Config.dbDriver();
    }

    /**
     * 探测测试库是否可用，结果只探测一次。
     *
     * @return 可用返回 true
     */
    public static boolean isAvailable() {
        if (availableState < 0) {
            probe();
        }
        return availableState == 1;
    }

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
     * 返回测试库不可用的原因。
     *
     * @return 原因文本，可用时为空串
     */
    public static String unavailableReason() {
        if (availableState < 0) {
            probe();
        }
        return unavailableReason;
    }

    /**
     * 清空测试库中的用户表与消息表。
     */
    public static void reset() {
        // 构造 DAO 以创建缺失的表结构
        new JdbcUserDao(driver(), url(), user(), password());
        new JdbcMessageDao(driver(), url(), user(), password());
        // 清空测试表
        try (Connection connection = DriverManager.getConnection(url(), user(), password());
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE chat_user");
            statement.execute("TRUNCATE TABLE chat_message");
        } catch (SQLException e) {
            throw new IllegalStateException("测试数据清理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重置测试数据并返回用户 DAO。
     *
     * @return 用户 DAO 实例
     */
    public static JdbcUserDao userDao() {
        reset();
        return new JdbcUserDao(driver(), url(), user(), password());
    }

    /**
     * 重置测试数据并返回消息 DAO。
     *
     * @return 消息 DAO 实例
     */
    public static JdbcMessageDao messageDao() {
        reset();
        return new JdbcMessageDao(driver(), url(), user(), password());
    }
}

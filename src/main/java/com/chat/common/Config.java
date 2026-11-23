package com.chat.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 读取 config/chat.properties 中的配置项，部分配置项支持系统属性覆盖。
 */
public final class Config {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".Config");

    private static final Properties PROPERTIES = new Properties();

    /** 配置文件是否读取成功 */
    private static boolean loaded;

    static {
        load();
    }

    private Config() {
    }

    private static void load() {
        try (InputStream in = new FileInputStream(Constants.CONFIG_FILE)) {
            PROPERTIES.load(in);
            loaded = true;
            LOGGER.info(() -> "配置加载成功: " + Constants.CONFIG_FILE);
        } catch (IOException e) {
            loaded = false;
            LOGGER.log(Level.WARNING, "配置文件读取失败，使用内置默认值: " + Constants.CONFIG_FILE, e);
        }
    }

    /**
     * 返回配置文件是否读取成功。
     *
     * @return 读取成功时返回 true
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 读取字符串配置项。
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值，缺失或为空时返回默认值
     */
    public static String get(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    /**
     * 读取整数配置项。
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值，缺失或格式非法时返回默认值
     */
    public static int getInt(String key, int defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warning("配置项 " + key + " 不是合法整数: " + value + "，使用默认值 " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * 读取长整数配置项。
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值，缺失或格式非法时返回默认值
     */
    public static long getLong(String key, long defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warning("配置项 " + key + " 不是合法整数: " + value + "，使用默认值 " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * 读取布尔配置项。
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值，缺失时返回默认值
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * 返回数据目录，系统属性 lanchat.data.dir 优先。
     *
     * @return 数据目录路径
     */
    public static String dataDir() {
        String fromProperty = System.getProperty("lanchat.data.dir");
        if (fromProperty != null && !fromProperty.trim().isEmpty()) {
            return fromProperty.trim();
        }
        return get("data.dir", Constants.DATA_DIR);
    }

    /**
     * 返回导出目录。
     *
     * @return 数据目录下的 export 子目录路径
     */
    public static String exportDir() {
        return dataDir() + java.io.File.separator + "export";
    }

    /**
     * 返回数据库驱动类名。
     *
     * @return 驱动类名
     */
    public static String dbDriver() {
        return override("lanchat.db.driver", get("db.driver", Constants.DB_DRIVER));
    }

    /**
     * 返回数据库连接地址。
     *
     * @return 连接地址
     */
    public static String dbUrl() {
        return override("lanchat.db.url", get("db.url", Constants.DB_URL));
    }

    /**
     * 返回数据库用户名。
     *
     * @return 用户名
     */
    public static String dbUser() {
        return override("lanchat.db.user", get("db.username", Constants.DB_USER));
    }

    /**
     * 返回数据库密码。
     *
     * @return 密码，未配置时为空串
     */
    public static String dbPassword() {
        return override("lanchat.db.password", get("db.password", ""));
    }

    /**
     * 返回消息加密密钥。
     *
     * @return 密钥，未配置时为空串
     */
    public static String messageSecret() {
        return override("lanchat.crypto.secret", get("security.message.secret", ""));
    }

    /**
     * 返回 AI 工作流编号。
     *
     * @return 工作流编号，未配置时为空串
     */
    public static String aiWorkflowId() {
        return override("lanchat.ai.workflow.id", get("ai.workflow.id", ""));
    }

    /**
     * 返回 AI 接口地址。
     *
     * @return 接口地址
     */
    public static String aiApiUrl() {
        return override("lanchat.ai.url", get("ai.api.url", Constants.AI_API_URL));
    }

    /**
     * 返回 AI 接口访问令牌。
     *
     * @return 访问令牌，未配置时为空串
     */
    public static String aiApiToken() {
        return override("lanchat.ai.token", get("ai.api.token", ""));
    }

    /** 系统属性非空时优先于配置文件值。 */
    private static String override(String property, String fromFileValue) {
        String value = System.getProperty(property);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return fromFileValue;
    }

    /**
     * 返回心跳发送间隔。
     *
     * @return 间隔毫秒数
     */
    public static long heartbeatIntervalMs() {
        return getLong("heartbeat.interval.ms", Constants.HEARTBEAT_INTERVAL_MS);
    }

    /**
     * 返回心跳超时时间。
     *
     * @return 超时毫秒数
     */
    public static long heartbeatTimeoutMs() {
        return getLong("heartbeat.timeout.ms", Constants.HEARTBEAT_TIMEOUT_MS);
    }

    /**
     * 返回单个文件大小上限。
     *
     * @return 上限字节数
     */
    public static long maxFileSize() {
        return getLong("file.max.size", Constants.MAX_FILE_SIZE);
    }

    /**
     * 返回服务端最大连接数。
     *
     * @return 最大连接数
     */
    public static int maxConnections() {
        return getInt("server.max.connections", Constants.MAX_CONNECTIONS);
    }

    /**
     * 返回服务端监听端口。
     *
     * @return 端口号
     */
    public static int serverPort() {
        return getInt("server.port", Constants.DEFAULT_SERVER_PORT);
    }

    /**
     * 返回局域网发现端口。
     *
     * @return 端口号
     */
    public static int discoveryPort() {
        return getInt("discovery.port", Constants.DISCOVERY_PORT);
    }

    /**
     * 返回接收文件目录。
     *
     * @return 目录路径
     */
    public static String receivedDir() {
        return get("file.received.dir", Constants.RECEIVED_DIR);
    }
}

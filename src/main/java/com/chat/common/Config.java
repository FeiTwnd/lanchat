package com.chat.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 配置加载类。
 *
 * <p>职责：从 {@code config/chat.properties} 读取运行期配置（端口、路径、线程池参数等），
 * 并覆盖 {@link Constants} 中定义的默认值。配置缺失或读取失败时静默回退到默认值，
 * 保证程序在任何环境下都能启动——这是课程演示场景下最重要的可用性保障。</p>
 *
 * <p>设计说明：使用静态初始化 + 不可变 {@link Properties} 快照，读多写少，
 * 无需加锁；刻意不引入任何配置框架，符合“不用现成框架”的题目约束。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class Config {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".Config");

    /** 配置项内存快照 */
    private static final Properties PROPERTIES = new Properties();

    /** 配置文件是否成功加载 */
    private static boolean loaded;

    static {
        load();
    }

    /** 私有构造，禁止实例化工具类 */
    private Config() {
    }

    /**
     * 加载配置文件。
     *
     * <p>使用 try-with-resources 保证输入流关闭；文件不存在时记录警告并继续使用默认值，
     * 而不是抛出异常中断启动流程。</p>
     */
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
     * 判断配置文件是否成功加载。
     *
     * @return 加载成功返回 true
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 读取字符串配置。
     *
     * @param key          配置键
     * @param defaultValue 缺省值
     * @return 配置值；不存在时返回 defaultValue
     */
    public static String get(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    /**
     * 读取整型配置。
     *
     * <p>数值非法时回退默认值并记录警告，避免因配置笔误导致程序无法启动。</p>
     *
     * @param key          配置键
     * @param defaultValue 缺省值
     * @return 配置数值；解析失败时返回 defaultValue
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
     * 读取长整型配置。
     *
     * @param key          配置键
     * @param defaultValue 缺省值
     * @return 配置数值；解析失败时返回 defaultValue
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
     * 读取布尔配置。
     *
     * @param key          配置键
     * @param defaultValue 缺省值
     * @return 仅当值为 "true"（忽略大小写）时返回 true，否则返回 defaultValue
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * 获取数据根目录。
     *
     * <p>取值优先级：系统属性 {@code lanchat.data.dir} > 配置文件 {@code data.dir} > 内置默认值。
     * 系统属性优先级最高，是为了让自动化测试能够把数据隔离到临时目录，
     * 从而避免测试污染开发者的真实数据。</p>
     *
     * @return 数据根目录路径
     */
    public static String dataDir() {
        String fromProperty = System.getProperty("lanchat.data.dir");
        if (fromProperty != null && !fromProperty.trim().isEmpty()) {
            return fromProperty.trim();
        }
        return get("data.dir", Constants.DATA_DIR);
    }

    /**
     * 获取聊天记录导出目录。
     *
     * @return 导出目录路径
     */
    public static String exportDir() {
        return dataDir() + java.io.File.separator + "export";
    }

    /**
     * 获取数据库驱动类名。
     *
     * <p>取值优先级：系统属性 {@code lanchat.db.driver} > 配置文件 {@code db.driver}。</p>
     *
     * @return 驱动类名
     */
    public static String dbDriver() {
        return override("lanchat.db.driver", get("db.driver", Constants.DB_DRIVER));
    }

    /**
     * 获取数据库连接地址。
     *
     * <p>取值优先级：系统属性 {@code lanchat.db.url} > 配置文件 {@code db.url}。
     * 提供系统属性覆盖是为了让自动化测试与演示使用独立的库，
     * 避免测试数据写进开发者的真实数据库。</p>
     *
     * @return JDBC 连接地址
     */
    public static String dbUrl() {
        return override("lanchat.db.url", get("db.url", Constants.DB_URL));
    }

    /**
     * 获取数据库用户名。
     *
     * @return 数据库用户名
     */
    public static String dbUser() {
        return override("lanchat.db.user", get("db.username", Constants.DB_USER));
    }

    /**
     * 获取数据库密码。
     *
     * @return 数据库密码；未配置时为空字符串
     */
    public static String dbPassword() {
        return override("lanchat.db.password", get("db.password", ""));
    }

    /**
     * 获取聊天记录加密口令。
     *
     * <p>取值优先级：系统属性 {@code lanchat.crypto.secret} > 配置文件 {@code security.message.secret}。
     * 该口令用于派生 AES 密钥，所有读写同一数据库的进程（服务器与客户端导出的场景）
     * 必须使用同一口令，否则查出来的记录会显示为无法解密。</p>
     *
     * @return 加密口令；未配置时返回内置默认口令
     */
    public static String messageSecret() {
        return override("lanchat.crypto.secret",
                get("security.message.secret", Constants.DB_MESSAGE_SECRET));
    }

    /**
     * 读取系统属性覆盖值。
     *
     * @param property      系统属性名
     * @param fromFileValue 配置文件中的取值
     * @return 系统属性存在且非空时优先返回，否则返回配置文件取值
     */
    private static String override(String property, String fromFileValue) {
        String value = System.getProperty(property);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return fromFileValue;
    }

    /**
     * 获取客户端心跳发送间隔。
     *
     * @return 毫秒数，默认 {@link Constants#HEARTBEAT_INTERVAL_MS}
     */
    public static long heartbeatIntervalMs() {
        return getLong("heartbeat.interval.ms", Constants.HEARTBEAT_INTERVAL_MS);
    }

    /**
     * 获取服务器判定连接掉线的心跳超时阈值。
     *
     * @return 毫秒数，默认 {@link Constants#HEARTBEAT_TIMEOUT_MS}
     */
    public static long heartbeatTimeoutMs() {
        return getLong("heartbeat.timeout.ms", Constants.HEARTBEAT_TIMEOUT_MS);
    }

    /**
     * 获取单文件大小上限。
     *
     * @return 字节数，默认 {@link Constants#MAX_FILE_SIZE}
     */
    public static long maxFileSize() {
        return getLong("file.max.size", Constants.MAX_FILE_SIZE);
    }

    /**
     * 获取允许的最大并发连接数。
     *
     * @return 连接数，默认 {@link Constants#MAX_CONNECTIONS}
     */
    public static int maxConnections() {
        return getInt("server.max.connections", Constants.MAX_CONNECTIONS);
    }

    /**
     * 获取服务器端口。
     *
     * @return 实际生效的 TCP 端口
     */
    public static int serverPort() {
        return getInt("server.port", Constants.DEFAULT_SERVER_PORT);
    }

    /**
     * 获取发现服务端口。
     *
     * @return 实际生效的 UDP 端口
     */
    public static int discoveryPort() {
        return getInt("discovery.port", Constants.DISCOVERY_PORT);
    }

    /**
     * 获取接收文件保存目录。
     *
     * @return 保存目录绝对或相对路径
     */
    public static String receivedDir() {
        return get("file.received.dir", Constants.RECEIVED_DIR);
    }

}

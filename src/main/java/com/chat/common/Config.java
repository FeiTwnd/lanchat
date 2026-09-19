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
     * 获取用户数据文件路径。
     *
     * @return 用户数据文件路径
     */
    public static String userFile() {
        return dataDir() + java.io.File.separator + "users.txt";
    }

    /**
     * 获取聊天历史目录。
     *
     * @return 历史记录目录路径
     */
    public static String historyDir() {
        return dataDir() + java.io.File.separator + "history";
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

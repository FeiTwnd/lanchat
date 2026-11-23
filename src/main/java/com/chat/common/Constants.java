package com.chat.common;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 全局常量定义，包括网络端口、超时时间、文件限制、协议标识与格式。
 */
public final class Constants {
    private Constants() {
    }

    public static final int DEFAULT_SERVER_PORT = 9527;

    public static final int DISCOVERY_PORT = 30000;

    /** 服务端监听所有网卡 */
    public static final String SERVER_BIND_HOST = "0.0.0.0";

    public static final int CONNECT_TIMEOUT_MS = 3000;

    /** Socket 读取超时（毫秒） */
    public static final int SO_TIMEOUT_MS = 60000;

    public static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    public static final long HEARTBEAT_TIMEOUT_MS = 60_000L;

    /** 空闲连接扫描间隔（毫秒） */
    public static final long SCAN_INTERVAL_MS = 20_000L;

    public static final int MAX_CONNECTIONS = 200;

    public static final long WORKER_KEEP_ALIVE_SECONDS = 60L;

    /** 文件分块大小（字节）：64 KiB */
    public static final int FILE_CHUNK_SIZE = 64 * 1024;

    /** 单个文件大小上限（字节）：200 MiB */
    public static final long MAX_FILE_SIZE = 200L * 1024 * 1024;

    /** 文件大小上限的展示文本 */
    public static final String MAX_FILE_SIZE_TEXT = "200MB";

    public static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "gif", "bmp"};

    /** 生成头像的边长（像素） */
    public static final int AVATAR_SIDE = 96;

    /** 头像 Base64 文本的最大长度 */
    public static final int AVATAR_MAX_LENGTH = 32768;

    /** 头像图片单边最大像素 */
    public static final int AVATAR_IMAGE_MAX_SIDE = 4096;

    /** 头像图片最大像素总数 */
    public static final int AVATAR_MAX_PIXELS = 256 * 256;

    public static final String ROOT_DIR = System.getProperty("user.dir", ".");

    public static final String CONFIG_FILE = ROOT_DIR + File.separator + "config" + File.separator + "chat.properties";

    public static final String DATA_DIR = ROOT_DIR + File.separator + "data";

    public static final String RECEIVED_DIR = DATA_DIR + File.separator + "received";

    public static final String EXPORT_DIR = DATA_DIR + File.separator + "export";

    public static final String DB_DRIVER = "com.mysql.cj.jdbc.Driver";

    public static final String DB_URL = "jdbc:mysql://localhost:3306/lanchat?useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    public static final String DB_USER = "root";

    public static final String ADMIN_USERNAME = "admin";

    public static final String ADMIN_NICKNAME = "系统管理员";

    public static final String ROLE_USER = "USER";

    public static final String ROLE_ADMIN = "ADMIN";

    public static final String AI_USERNAME = "ai";

    public static final String AI_DISPLAY_NAME = "AI小助手";

    public static final String AI_API_URL = "https://api.coze.cn/v1/workflow/run";

    public static final int AI_CONNECT_TIMEOUT_MS = 5_000;

    public static final long AI_REQUEST_TIMEOUT_MS = 30_000L;

    public static final String AI_THINKING_TEXT = "正在思考，请稍候……";

    /** 用户名格式：字母开头，3 到 16 位字母、数字或下划线 */
    public static final String USERNAME_PATTERN = "^[A-Za-z][A-Za-z0-9_]{2,15}$";

    public static final int PASSWORD_MIN_LENGTH = 6;

    public static final int PASSWORD_MAX_LENGTH = 32;

    public static final int NICKNAME_MAX_LENGTH = 20;

    /** 单条消息的最大字符数 */
    public static final int MESSAGE_MAX_LENGTH = 2000;

    /** 系统消息的发送者标识 */
    public static final String SYSTEM_SENDER = "SYSTEM";

    /** 广播消息的接收者标识 */
    public static final String BROADCAST_TAG = "ALL";

    /** 导出与广播范围标识共用的前缀 */
    public static final String EXPORT_SCOPE_PREFIX = "@";

    /** 广播范围的完整标识 */
    public static final String BROADCAST_SCOPE = EXPORT_SCOPE_PREFIX + BROADCAST_TAG;

    public static final String PROFILE_SECTION_NICK = "NICK";

    public static final String PROFILE_SECTION_AVATAR = "AVATAR";

    public static final String PROFILE_SECTION_PASSWORD = "PASSWORD";

    /** 个人资料结果的字段数 */
    public static final int PROFILE_RESULT_FIELD_COUNT = 3;

    public static final Charset CHARSET = StandardCharsets.UTF_8;

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /** 协议文本的字段分隔符：制表符 */
    public static final String FIELD_SEPARATOR = "\t";

    public static final String LOGGER_NAME = "com.chat";

    public static final String RECALLED_PLACEHOLDER = "[该消息已撤回]";

    /** 消息撤回时限（分钟） */
    public static final int RECALL_WINDOW_MINUTES = 2;

    public static final String APP_NAME = "局域网聊天程序";

    public static final String APP_VERSION = "v1.0";
}

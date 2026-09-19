package com.chat.common;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 全局常量定义类。
 *
 * <p>职责：集中存放端口、路径、大小限制、格式串等固定值，杜绝业务代码中的硬编码。
 * 凡是可能因部署环境变化的配置项（如端口、数据目录），本类只提供“默认值”，
 * 运行期实际取值由 {@link Config} 从 {@code config/chat.properties} 覆盖。</p>
 *
 * <p>命名规范：所有常量使用全大写加下划线。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class Constants {

    /** 私有构造，禁止实例化工具类 */
    private Constants() {
    }

    // ==================== 网络参数 ====================

    /** TCP 服务端口默认值，服务器与客户端必须一致 */
    public static final int DEFAULT_SERVER_PORT = 9527;

    /** UDP 局域网自动发现端口默认值，广播报文发送到该端口 */
    public static final int DISCOVERY_PORT = 30000;

    /** 服务器绑定地址，0.0.0.0 表示监听本机所有网卡 */
    public static final String SERVER_BIND_HOST = "0.0.0.0";

    /** 客户端连接超时时间（毫秒），防止服务器未启动时界面长时间卡死 */
    public static final int CONNECT_TIMEOUT_MS = 3000;

    /** Socket 读超时（毫秒），用于及时感知连接断开 */
    public static final int SO_TIMEOUT_MS = 60000;

    // ==================== 心跳与在线判定 ====================

    /** 客户端心跳发送间隔（毫秒），默认 15 秒 */
    public static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    /** 服务器判定连接空闲掉线的阈值（毫秒），默认 60 秒 */
    public static final long HEARTBEAT_TIMEOUT_MS = 60_000L;

    /** 服务器扫描超时连接的周期（毫秒） */
    public static final long SCAN_INTERVAL_MS = 20_000L;

    // ==================== 线程池参数 ====================

    /** 网络连接处理线程池大小上限，限制同时在线连接数 */
    public static final int MAX_CONNECTIONS = 200;

    /**
     * 连接线程池的空闲线程保活时间（秒）。
     *
     * <p>连接处理线程整个生命周期都阻塞在读取消息上，属于「一个连接一个线程」模型，
     * 因此线程池必须按需创建而不是固定大小：固定线程池会在连接数超过线程数时
     * 让新连接永久排队（服务器看起来接受了连接却毫无响应）。</p>
     */
    public static final long WORKER_KEEP_ALIVE_SECONDS = 60L;

    // ==================== 文件传输 ====================

    /** 文件分块大小：64KB，兼顾传输效率与内存占用 */
    public static final int FILE_CHUNK_SIZE = 64 * 1024;

    /** 单文件大小上限：200MB，超过直接拒绝，防止磁盘被占满 */
    public static final long MAX_FILE_SIZE = 200L * 1024 * 1024;

    /** 服务器允许的最大文件体积提示文本，用于错误信息拼装 */
    public static final String MAX_FILE_SIZE_TEXT = "200MB";

    // ==================== 路径与目录 ====================

    /** 项目根目录（以 user.dir 为基准），是其它路径的解析基准 */
    public static final String ROOT_DIR = System.getProperty("user.dir", ".");

    /** 配置文件路径 */
    public static final String CONFIG_FILE = ROOT_DIR + File.separator + "config" + File.separator + "chat.properties";

    /** 数据目录：仅用于接收文件与导出聊天记录，用户与聊天记录本身存数据库 */
    public static final String DATA_DIR = ROOT_DIR + File.separator + "data";

    /** 接收文件的默认保存目录 */
    public static final String RECEIVED_DIR = DATA_DIR + File.separator + "received";

    /** 聊天记录导出目录 */
    public static final String EXPORT_DIR = DATA_DIR + File.separator + "export";

    // ==================== 数据库（唯一存储） ====================

    /** 数据库驱动类名默认值 */
    public static final String DB_DRIVER = "com.mysql.cj.jdbc.Driver";

    /** 数据库连接地址默认值：本机 lanchat 库 */
    public static final String DB_URL = "jdbc:mysql://localhost:3306/lanchat?useSSL=false"
            + "&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    /** 数据库用户名默认值 */
    public static final String DB_USER = "root";

    // ==================== 默认账号 ====================

    /** 管理员用户名，首次启动时若不存在则自动创建 */
    public static final String ADMIN_USERNAME = "admin";

    /** 管理员默认昵称 */
    public static final String ADMIN_NICKNAME = "系统管理员";

    /** 管理员默认密码，仅用于首次初始化，生产环境应立刻修改 */
    public static final String ADMIN_DEFAULT_PASSWORD = "admin123";

    /** 普通用户角色标识 */
    public static final String ROLE_USER = "USER";

    /** 管理员角色标识 */
    public static final String ROLE_ADMIN = "ADMIN";

    // ==================== 业务校验规则 ====================

    /** 用户名合法格式：3-16 位字母、数字或下划线，必须以字母开头 */
    public static final String USERNAME_PATTERN = "^[A-Za-z][A-Za-z0-9_]{2,15}$";

    /** 密码长度下限 */
    public static final int PASSWORD_MIN_LENGTH = 6;

    /** 密码长度上限 */
    public static final int PASSWORD_MAX_LENGTH = 32;

    /** 昵称长度上限 */
    public static final int NICKNAME_MAX_LENGTH = 20;

    /** 单条消息内容长度上限 */
    public static final int MESSAGE_MAX_LENGTH = 2000;

    // ==================== 协议与格式 ====================

    /** 系统消息的固定发送者标识 */
    public static final String SYSTEM_SENDER = "SYSTEM";

    /** 广播接收者标识（日志与界面展示用） */
    public static final String BROADCAST_TAG = "ALL";

    /** 编码统一使用 UTF-8，避免中文乱码 */
    public static final Charset CHARSET = StandardCharsets.UTF_8;

    /** 日期时间格式：用于界面展示与消息记录 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 仅日期格式：用于按日期检索与展示 */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /** 字段分隔符：用户文件与历史日志均使用制表符分隔，避免与消息正文中的空格冲突 */
    public static final String FIELD_SEPARATOR = "\t";

    /** 日志统一前缀，便于在控制台与文件中检索 */
    public static final String LOGGER_NAME = "com.chat";

    // ==================== 界面文本 ====================

    /** 程序名称 */
    public static final String APP_NAME = "局域网聊天程序";

    /** 程序版本号，显示于登录窗口标题 */
    public static final String APP_VERSION = "v1.0";
}

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

    /**
     * 可作为图片发送的文件扩展名（小写，不含点）。
     *
     * <p>仅供文件选择器过滤使用，不作为安全校验：扩展名是用户可随意改写的，
     * 服务端与接收端都不依赖它判断内容，实际发送走的仍是通用文件通道。</p>
     */
    public static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "gif", "bmp"};

    // ==================== 头像 ====================

    /**
     * 头像的显示边长（像素）。
     *
     * <p>客户端把用户选择的图片统一裁切缩放成这个尺寸的 PNG 再上传，
     * 因此服务端存下来的就是最终显示尺寸，各界面直接按需缩放即可。</p>
     */
    public static final int AVATAR_SIDE = 96;

    /**
     * 头像 Base64 文本的长度上限。
     *
     * <p>96×96 的 PNG 通常在 4-12KB，Base64 后约 6-16KB，取 32KB 留出充足余量。
     * 设上限的目的不是防图片本身，而是防止头像列被塞入任意长文本：
     * 头像会随每次用户列表广播下发给所有在线用户，无上限等于给广播报文开了个口子。</p>
     */
    public static final int AVATAR_MAX_LENGTH = 32768;

    /**
     * 用户选择头像图片时的源图边长上限（像素）：4096。
     *
     * <p>为什么需要这个上限：{@code ImageIO.read} 在缩放之前就会把整张图解成
     * {@code BufferedImage}，一亿像素的图即约 400MB，足以让客户端直接 OOM。
     * 4096 边长的图约占 67MB，属于可以接受的瞬时开销，也覆盖了手机拍照与
     * 常见壁纸的尺寸；超过它的图在读取尺寸后立即拒绝并提示，而不是硬读进内存。</p>
     *
     * <p>注意它与 {@link #AVATAR_SIDE} 的分工：本常量管"允许多大的图进来"，
     * 进来之后一律按短边等比缩放并居中裁切成 {@code AVATAR_SIDE} 见方的正方形——
     * 直接把非正方形图片拉伸成正方形会把人物压扁，必须裁而不是拉。</p>
     */
    public static final int AVATAR_IMAGE_MAX_SIDE = 4096;

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

    /**
     * 数据库连接地址默认值：本机 lanchat 库。
     *
     * <p>时区必须填数据库所在机器的真实时区（此处为东八区）。驱动会把 JDBC 时间在
     * “连接时区”与 JVM 时区之间换算：若连接时区填 UTC 而数据库实际在东八区，
     * 程序里写入的 09:21 会被换算成 01:21 存进 DATETIME 列——程序自己读写一致（显示正常），
     * 但用 mysql 命令行或图形工具直接看库时会整整差 8 小时。</p>
     */
    public static final String DB_URL = "jdbc:mysql://localhost:3306/lanchat?useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    /** 数据库用户名默认值 */
    public static final String DB_USER = "root";

    // ==================== 默认账号 ====================

    /**
     * 管理员用户名，不存在时由 {@code admin.password} 配置的口令创建。
     *
     * <p>刻意不提供任何内置口令：内置口令会让“忘记配置”退化成“所有人共用一个公开口令”，
     * 宁可跳过创建并在日志中告警，也不允许程序自己决定一个口令。</p>
     */
    public static final String ADMIN_USERNAME = "admin";

    /** 管理员昵称，仅在创建管理员账号时使用 */
    public static final String ADMIN_NICKNAME = "系统管理员";

    /** 普通用户角色标识 */
    public static final String ROLE_USER = "USER";

    /** 管理员角色标识 */
    public static final String ROLE_ADMIN = "ADMIN";

    // ==================== 智能体小助手 ====================

    /**
     * 智能体小助手的用户名。
     *
     * <p>固定在代码里而不是做成配置项：它同时是"这条私聊该不该转给机器人"的判定依据，
     * 值一旦可改，配置与客户端界面之间就不再有单一的对应关系。取 {@code ai} 这个两位名
     * 是刻意为之——{@link #USERNAME_PATTERN} 要求用户名至少 3 位，因此真实账号
     * 不可能是这个名字，机器人不会与真人撞号。</p>
     */
    public static final String AI_USERNAME = "ai";

    /**
     * 智能体小助手在好友树里显示的昵称。
     *
     * <p>刻意不从配置文件读取：{@link Config} 用 {@code Properties.load(InputStream)}
     * 加载配置，而它按 ISO-8859-1 解码，中文昵称写进 properties 会变成乱码。
     * 改昵称请直接改这里的字面量——源码是 UTF-8，中文字面量不经过那一步解码。</p>
     */
    public static final String AI_DISPLAY_NAME = "AI小助手";

    /** 智能体平台的开放接口地址：一问一答的非流式接口，一次请求直接拿到回答 */
    public static final String AI_API_URL = "https://api.coze.cn/open_api/v2/chat";

    /** 与智能体平台的建连超时（毫秒），明显短于问答总超时，连不上时不必白等 */
    public static final int AI_CONNECT_TIMEOUT_MS = 5_000;

    /** 单次问答的总超时（毫秒）：超过即放弃，并如实告知提问者 */
    public static final long AI_REQUEST_TIMEOUT_MS = 30_000L;

    /**
     * 提问后立刻回给提问者的提示语。
     *
     * <p>必须有这一句：外部平台的回答通常要等几秒到十几秒，没有它，提问者看到的是
     * 自己那条消息发出去之后的一片安静，无从判断"到底发出去没有"。</p>
     */
    public static final String AI_THINKING_TEXT = "正在思考，请稍候……";

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

    /**
     * 聊天记录导出范围的前缀。
     *
     * <p>导出请求的正文用它标记"导出哪一个会话"：{@code @ALL} 是群聊大厅，
     * {@code @用户名} 是与该用户的私聊，正文为空则导出本人全部记录。
     * 之所以要这个前缀而不是直接写用户名：用户名允许出现字母数字下划线，
     * 单看一个 {@code ALL} 无从分辨那是群聊还是某个真叫这个名字的账号，
     * 而用户名里不可能出现 {@code @}。</p>
     */
    public static final String EXPORT_SCOPE_PREFIX = "@";

    /** 导出范围：群聊大厅 */
    public static final String BROADCAST_SCOPE = EXPORT_SCOPE_PREFIX + BROADCAST_TAG;

    /** 资料变更回执的分区标识：昵称 */
    public static final String PROFILE_SECTION_NICK = "NICK";

    /** 资料变更回执的分区标识：头像 */
    public static final String PROFILE_SECTION_AVATAR = "AVATAR";

    /** 资料变更回执的分区标识：密码 */
    public static final String PROFILE_SECTION_PASSWORD = "PASSWORD";

    /**
     * 资料变更回执的正文分隔后的字段数：{@code 成败|分区|原因}。
     *
     * <p>原因放在最后一列，因此按竖线切分时要用这个上限而不是只切两段——
     * 否则原因里若含竖线会把后半句截掉。</p>
     */
    public static final int PROFILE_RESULT_FIELD_COUNT = 3;

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

    /**
     * 已撤回消息的占位正文。
     *
     * <p>撤回后正文不再回传给任何一方（包括发送者本人）：数据库里的原文无法被"部分删除"，
     * 只能在读出时用占位文本替代，这样历史查询、关键字检索与导出都不会再泄露已撤回内容。</p>
     */
    public static final String RECALLED_PLACEHOLDER = "[该消息已撤回]";

    /** 允许撤回的时间窗口（分钟）：与主流即时通讯软件一致，超过则只能保留记录 */
    public static final int RECALL_WINDOW_MINUTES = 2;

    // ==================== 界面文本 ====================

    /** 程序名称 */
    public static final String APP_NAME = "局域网聊天程序";

    /** 程序版本号，显示于登录窗口标题 */
    public static final String APP_VERSION = "v1.0";
}

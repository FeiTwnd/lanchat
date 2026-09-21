package com.chat.common;

/**
 * 消息类型枚举。
 *
 * <p>职责：集中定义客户端与服务器之间所有可传输的消息种类，是协议的唯一权威定义。
 * 客户端与服务器双方必须以本枚举为准，禁止在业务代码中出现字符串字面量形式的类型名。</p>
 *
 * <p>设计说明：每种类型绑定一段中文描述，便于日志输出与界面提示；
 * {@link #fromName(String)} 用于配置文件或历史记录文本反查枚举，避免抛异常导致读档失败。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public enum MessageType {

    /** 客户端请求注册 */
    REGISTER("注册请求"),
    /** 服务器返回注册结果 */
    REGISTER_RESULT("注册结果"),
    /** 客户端请求登录 */
    LOGIN("登录请求"),
    /** 服务器返回登录结果 */
    LOGIN_RESULT("登录结果"),
    /** 客户端主动退出 */
    LOGOUT("退出登录"),
    /** 私聊文本消息 */
    TEXT_PRIVATE("私聊消息"),
    /** 群聊文本消息 */
    TEXT_GROUP("群聊消息"),
    /** 服务器推送在线用户列表快照 */
    USER_LIST("在线用户列表"),
    /** 客户端请求刷新在线用户列表 */
    USER_LIST_REQUEST("请求在线用户列表"),
    /** 用户上线通知 */
    USER_ONLINE("用户上线"),
    /** 用户下线通知 */
    USER_OFFLINE("用户下线"),
    /** 用户资料变更通知 */
    USER_UPDATE("用户资料变更"),
    /** 修改密码请求 */
    PASSWORD_CHANGE("修改密码请求"),
    /** 文件传输请求（发送方 -> 接收方） */
    FILE_REQUEST("文件传输请求"),
    /** 接收方同意接收 */
    FILE_ACCEPT("同意接收文件"),
    /** 接收方拒绝接收 */
    FILE_REJECT("拒绝接收文件"),
    /** 文件数据块 */
    FILE_CHUNK("文件数据块"),
    /** 文件传输结束（携带校验和） */
    FILE_END("文件传输结束"),
    /** 文件传输结果回执 */
    FILE_RESULT("文件传输结果"),
    /** 历史消息查询请求 */
    HISTORY_REQUEST("历史记录查询"),
    /** 历史消息查询结果 */
    HISTORY_RESULT("历史记录结果"),
    /** 聊天记录导出请求（服务端查库并渲染，回传文本由客户端落盘） */
    EXPORT_REQUEST("聊天记录导出请求"),
    /** 聊天记录导出结果 */
    EXPORT_RESULT("聊天记录导出结果"),
    /** 心跳包 */
    HEARTBEAT("心跳包"),
    /** 心跳应答 */
    HEARTBEAT_ACK("心跳应答"),
    /** 服务端对消息的送达确认（携带稳定消息标识，发送方据此标记已送达） */
    MSG_ACK("消息确认"),
    /** 登录后补投的离线消息（接收方离线期间积压、上线后由服务端补发） */
    OFFLINE_MESSAGE("离线消息"),
    /** 断线重连时用会话令牌恢复登录态 */
    SESSION_RESUME("会话恢复"),
    /** 系统通知（上线、下线、欢迎语、错误提示等） */
    SYSTEM("系统通知"),
    /** 错误通知 */
    ERROR("错误通知");

    /** 类型的可读中文描述，用于日志与界面展示 */
    private final String description;

    MessageType(String description) {
        this.description = description;
    }

    /**
     * 获取类型的可读描述。
     *
     * @return 中文描述，例如“私聊消息”
     */
    public String getDescription() {
        return description;
    }

    /**
     * 根据枚举名安全地反查消息类型。
     *
     * <p>历史记录文件或配置中写入的类型名可能来自旧版本，此处不抛异常，
     * 未知名称统一回退为 {@link #SYSTEM}，保证读取历史记录不会中断。</p>
     *
     * @param name 枚举名称，允许为 null
     * @return 匹配的消息类型，未匹配时返回 {@link #SYSTEM}
     */
    public static MessageType fromName(String name) {
        if (name == null) {
            return SYSTEM;
        }
        for (MessageType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return SYSTEM;
    }
}

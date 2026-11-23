package com.chat.common;

/**
 * 消息协议中的消息类型枚举，覆盖注册、登录、聊天、文件、心跳与系统通知。
 */
public enum MessageType {
    REGISTER("注册请求"),

    REGISTER_RESULT("注册结果"),

    LOGIN("登录请求"),

    LOGIN_RESULT("登录结果"),

    LOGOUT("退出登录"),

    TEXT_PRIVATE("私聊消息"),

    TEXT_GROUP("群聊消息"),

    USER_LIST("在线用户列表"),

    USER_LIST_REQUEST("请求在线用户列表"),

    USER_ONLINE("用户上线"),

    USER_OFFLINE("用户下线"),

    USER_UPDATE("用户资料变更"),

    PASSWORD_CHANGE("修改密码请求"),

    FILE_REQUEST("文件传输请求"),

    FILE_ACCEPT("同意接收文件"),

    FILE_REJECT("拒绝接收文件"),

    FILE_CHUNK("文件数据块"),

    FILE_END("文件传输结束"),

    FILE_RESULT("文件传输结果"),

    HISTORY_REQUEST("历史记录查询"),

    HISTORY_RESULT("历史记录结果"),

    EXPORT_REQUEST("聊天记录导出请求"),

    EXPORT_RESULT("聊天记录导出结果"),

    HEARTBEAT("心跳包"),

    HEARTBEAT_ACK("心跳应答"),

    MSG_ACK("消息确认"),

    OFFLINE_MESSAGE("离线消息"),

    SESSION_RESUME("会话恢复"),

    MSG_RECALL("消息撤回"),

    SEARCH_REQUEST("聊天记录搜索请求"),

    SEARCH_RESULT("聊天记录搜索结果"),

    READ_RECEIPT("已读回执"),

    SYSTEM("系统通知"),

    ERROR("错误通知"),

    PROFILE_RESULT("个人资料变更结果");

    /** 类型的中文说明 */
    private final String description;

    MessageType(String description) {
        this.description = description;
    }

    /**
     * 返回类型的中文说明。
     *
     * @return 类型说明
     */
    public String getDescription() {
        return description;
    }

    /**
     * 按名称查找消息类型，忽略大小写与首尾空白。
     *
     * @param name 类型名称
     * @return 匹配的类型，未匹配时返回 SYSTEM
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
        // 名称未知时按系统通知处理
        return SYSTEM;
    }
}

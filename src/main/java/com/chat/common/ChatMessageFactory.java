package com.chat.common;

import java.io.ObjectInputFilter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 创建各类消息对象，并校验消息正文与协议字段格式。
 */
public final class ChatMessageFactory {
    /** 消息自增编号 */
    private static final AtomicLong SEQUENCE = new AtomicLong(1L);

    /** 协议文本允许的最大字段数 */
    public static final int MAX_FIELD_COUNT = 16;

    /** 反序列化白名单与资源上限 */
    private static final String SERIALIZATION_FILTER_PATTERN =
            "com.chat.common.*;java.lang.*;java.time.*;java.util.*;!*;"
                    + "maxdepth=8;maxarray=65536";

    private ChatMessageFactory() {
    }

    /**
     * 检查文本消息正文。
     *
     * @param content 消息正文
     * @return 错误说明，正文合法时返回 null
     */
    public static String checkTextContent(String content) {
        if (content == null) {
            return "消息正文不能为空";
        }
        if (content.length() > Constants.MESSAGE_MAX_LENGTH) {
            return "消息过长，最多 " + Constants.MESSAGE_MAX_LENGTH + " 个字符";
        }
        if (hasIllegalControlChar(content)) {
            return "消息正文包含非法控制字符";
        }
        return null;
    }

    /**
     * 按竖线拆分协议文本，使用默认长度上限。
     *
     * @param text 待拆分的文本
     * @return 拆分结果，不合法时返回 null
     */
    public static String[] splitFields(String text) {
        return splitFields(text, Constants.MESSAGE_MAX_LENGTH);
    }

    /**
     * 按竖线拆分协议文本。
     *
     * @param text 待拆分的文本
     * @param maxLength 文本长度上限
     * @return 拆分结果，不合法时返回 null
     */
    public static String[] splitFields(String text, int maxLength) {
        if (text == null || text.length() > maxLength) {
            return null;
        }
        String[] fields = text.split("\\|", -1);
        if (fields.length > MAX_FIELD_COUNT) {
            return null;
        }
        for (String field : fields) {
            if (hasIllegalControlChar(field)) {
                return null;
            }
        }
        return fields;
    }

    /**
     * 判断文本是否含除换行与制表符外的控制字符。
     *
     * @param value 待检查的文本
     * @return 含非法控制字符时返回 true
     */
    public static boolean hasIllegalControlChar(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t') {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建消息反序列化过滤器。
     *
     * @return 带白名单与资源上限的过滤器
     */
    public static ObjectInputFilter serializationFilter() {
        return ObjectInputFilter.Config.createFilter(SERIALIZATION_FILTER_PATTERN);
    }

    /**
     * 创建文本消息。
     *
     * @param sender 发送者
     * @param receiver 接收者
     * @param content 消息正文
     * @param type 私聊或群聊类型
     * @return 文本消息
     * @throws IllegalArgumentException 类型不是私聊或群聊时
     */
    public static TextMessage text(String sender, String receiver, String content, MessageType type) {
        if (type != MessageType.TEXT_PRIVATE && type != MessageType.TEXT_GROUP) {
            throw new IllegalArgumentException("文本消息类型非法: " + type);
        }
        TextMessage message = new TextMessage(sender, receiver, content);
        apply(message, type);
        return message;
    }

    /**
     * 创建系统通知消息。
     *
     * @param receiver 接收者，为空表示广播
     * @param content 通知内容
     * @return 系统通知
     */
    public static SystemMessage system(String receiver, String content) {
        SystemMessage message = new SystemMessage(receiver, content);
        apply(message, MessageType.SYSTEM);
        return message;
    }

    /**
     * 创建错误通知消息。
     *
     * @param receiver 接收者
     * @param reason 错误原因
     * @return 错误通知
     */
    public static SystemMessage error(String receiver, String reason) {
        SystemMessage message = new SystemMessage(receiver, reason);
        apply(message, MessageType.ERROR);
        return message;
    }

    /**
     * 创建文件消息。
     *
     * @param sender 发送者
     * @param receiver 接收者
     * @param type 文件消息类型
     * @return 文件消息
     */
    public static FileMessage file(String sender, String receiver, MessageType type) {
        FileMessage message = new FileMessage(sender, receiver);
        apply(message, type);
        return message;
    }

    /**
     * 创建控制类系统消息。
     *
     * @param sender 发送者
     * @param receiver 接收者
     * @param type 控制消息类型
     * @return 系统消息
     */
    public static SystemMessage control(String sender, String receiver, MessageType type) {
        SystemMessage message = new SystemMessage(receiver, "");
        message.setSender(sender);
        apply(message, type);
        return message;
    }

    private static void apply(Message message, MessageType type) {
        message.setType(type);
        message.setId(SEQUENCE.getAndIncrement());
    }
}

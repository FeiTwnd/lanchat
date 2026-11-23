package com.chat.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息的公共基类，保存编号、类型、收发双方与时间戳。
 */
public abstract class Message implements Serializable {
    private static final long serialVersionUID = 20250101L;

    private long id;

    /** 消息的全局编号 */
    private String messageId;

    private MessageType type;

    private String sender;

    private String receiver;

    private LocalDateTime timestamp;

    /**
     * 创建消息并记录当前时间。
     */
    protected Message() {
        this.timestamp = LocalDateTime.now();
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    /**
     * 确保消息编号存在，缺失时生成随机 UUID。
     *
     * @return 消息编号
     */
    public String ensureMessageId() {
        if (messageId == null || messageId.trim().isEmpty()) {
            messageId = java.util.UUID.randomUUID().toString();
        }
        return messageId;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 返回消息的简要描述。
     *
     * @return 用于展示的消息摘要
     */
    public abstract String getSummary();

    /**
     * 判断消息是否为广播消息。
     *
     * @return 接收者为空时返回 true
     */
    public boolean isBroadcast() {
        return receiver == null || receiver.isEmpty();
    }

    @Override
    public String toString() {
        String receiverText = isBroadcast() ? Constants.BROADCAST_TAG : receiver;
        return getClass().getSimpleName() + "{id=" + id + ", messageId=" + messageId + ", type=" + type
                + ", sender=" + sender + " -> " + receiverText
                + ", time=" + timestamp + "}";
    }
}

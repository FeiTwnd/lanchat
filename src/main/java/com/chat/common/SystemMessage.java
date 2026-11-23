package com.chat.common;

/**
 * 系统通知消息，内容由服务端生成。
 */
public class SystemMessage extends Message {
    private static final long serialVersionUID = 20250104L;

    private final String content;

    /**
     * 创建系统通知消息。
     *
     * @param receiver 接收者，为空表示广播
     * @param content 通知内容
     * @throws IllegalArgumentException 内容为 null 时
     */
    public SystemMessage(String receiver, String content) {
        if (content == null) {
            throw new IllegalArgumentException("系统通知内容不能为 null");
        }
        setSender(Constants.SYSTEM_SENDER);
        setReceiver(receiver == null ? "" : receiver);
        this.content = content;
    }

    public String getContent() {
        return content;
    }

    @Override
    public String getSummary() {
        return content;
    }
}

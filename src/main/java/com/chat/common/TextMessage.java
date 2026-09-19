package com.chat.common;

/**
 * 文本消息。
 *
 * <p>职责：承载私聊与群聊的文本正文。两类场景共用同一子类，区别仅在于
 * {@link MessageType}（{@link MessageType#TEXT_PRIVATE} 或 {@link MessageType#TEXT_GROUP}），
 * 从而避免为“私聊消息”“群聊消息”重复定义两个几乎相同的类。</p>
 *
 * <p>不可变约定：正文字段无 setter，构造后不可修改，可安全地在多个线程间传递引用。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class TextMessage extends Message {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250103L;

    /** 消息正文 */
    private final String content;

    /**
     * 构造文本消息。
     *
     * <p>构造器只负责填充数据，消息类型由 {@link ChatMessageFactory} 统一设置，
     * 保证“谁创建、谁指定类型”这一职责不被分散。</p>
     *
     * @param sender   发送者用户名
     * @param receiver 接收者用户名，群聊传空字符串
     * @param content  消息正文，不允许为 null
     * @throws IllegalArgumentException 当 content 为 null 时抛出
     */
    public TextMessage(String sender, String receiver, String content) {
        if (content == null) {
            throw new IllegalArgumentException("消息正文不能为 null");
        }
        setSender(sender);
        setReceiver(receiver == null ? "" : receiver);
        this.content = content;
    }

    /**
     * 获取消息正文。
     *
     * @return 文本内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取消息摘要：过长正文截断展示，避免界面被长文本撑破。
     *
     * @return 最长 30 个字符的摘要
     */
    @Override
    public String getSummary() {
        if (content.length() <= 30) {
            return content;
        }
        return content.substring(0, 30) + "...";
    }
}

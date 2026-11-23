package com.chat.common;

/**
 * 文本消息，可携带被引用消息的编号、摘要与发送者。
 */
public class TextMessage extends Message {
    private static final long serialVersionUID = 20250103L;

    private final String content;

    /** 被引用消息的编号 */
    private final String quoteId;

    /** 被引用消息的摘要 */
    private final String quoteSummary;

    /** 被引用消息的发送者 */
    private final String quoteSender;

    /**
     * 创建不带引用的文本消息。
     *
     * @param sender 发送者
     * @param receiver 接收者
     * @param content 消息正文
     */
    public TextMessage(String sender, String receiver, String content) {
        this(sender, receiver, content, null, null, null);
    }

    /**
     * 创建带引用编号与摘要的文本消息。
     *
     * @param sender 发送者
     * @param receiver 接收者
     * @param content 消息正文
     * @param quoteId 被引用消息的编号
     * @param quoteSummary 被引用消息的摘要
     */
    public TextMessage(String sender, String receiver, String content,
                       String quoteId, String quoteSummary) {
        this(sender, receiver, content, quoteId, quoteSummary, null);
    }

    /**
     * 创建带完整引用信息的文本消息。
     *
     * @param sender 发送者
     * @param receiver 接收者
     * @param content 消息正文
     * @param quoteId 被引用消息的编号
     * @param quoteSummary 被引用消息的摘要
     * @param quoteSender 被引用消息的发送者
     */
    public TextMessage(String sender, String receiver, String content,
                       String quoteId, String quoteSummary, String quoteSender) {
        if (content == null) {
            throw new IllegalArgumentException("消息正文不能为 null");
        }
        setSender(sender);
        setReceiver(receiver == null ? "" : receiver);
        this.content = content;
        this.quoteId = quoteId == null ? "" : quoteId;
        this.quoteSummary = quoteSummary == null ? "" : quoteSummary;
        this.quoteSender = quoteSender == null ? "" : quoteSender;
    }

    public String getContent() {
        return content;
    }

    public String getQuoteId() {
        return quoteId == null ? "" : quoteId;
    }

    public String getQuoteSummary() {
        return quoteSummary == null ? "" : quoteSummary;
    }

    public String getQuoteSender() {
        return quoteSender == null ? "" : quoteSender;
    }

    /**
     * 判断消息是否引用了其他消息。
     *
     * @return 引用编号或摘要非空时返回 true
     */
    public boolean hasQuote() {
        return !getQuoteId().isEmpty() || !getQuoteSummary().isEmpty();
    }

    @Override
    public String getSummary() {
        if (content.length() <= 30) {
            return content;
        }
        return content.substring(0, 30) + "...";
    }
}

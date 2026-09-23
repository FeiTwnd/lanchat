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
     * 被引用消息的稳定标识；为空表示这条消息没有引用其它消息。
     *
     * <p>保持 final：引用关系在消息创建时确定，之后不再变化，因此不会破坏
     * "构造后不可修改、可安全跨线程传递引用"这一约定。</p>
     */
    private final String quoteId;

    /** 被引用消息的摘要文本，仅用于界面展示引用块，不参与任何业务判断 */
    private final String quoteSummary;

    /**
     * 被引用消息的发送者显示名；为空表示未记录（旧版对端发来的消息）。
     *
     * <p>引用块要按"谁：内容"两段展示（与主流即时通讯软件一致），只有摘要而无发送者
     * 就只能显示一行无主的内容，读者无法判断引用的是谁说的话。因此该字段与
     * {@link #quoteSummary} 成对使用。</p>
     */
    private final String quoteSender;

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
        this(sender, receiver, content, null, null, null);
    }

    /**
     * 构造带引用信息的文本消息。
     *
     * <p>引用只是"随消息一起展示的附加信息"，因此不单独定义消息类型，
     * 也不改变原有构造器的语义；不引用任何消息时，两个引用参数传 null 即可。</p>
     *
     * @param sender       发送者用户名
     * @param receiver     接收者用户名，群聊传空字符串
     * @param content      消息正文，不允许为 null
     * @param quoteId      被引用消息的稳定标识，可为 null
     * @param quoteSummary 被引用消息的摘要，可为 null
     * @throws IllegalArgumentException 当 content 为 null 时抛出
     */
    public TextMessage(String sender, String receiver, String content,
                       String quoteId, String quoteSummary) {
        this(sender, receiver, content, quoteId, quoteSummary, null);
    }

    /**
     * 构造带完整引用信息（含被引用者）的文本消息。
     *
     * <p>保留上一级 5 参构造器并让它委托到这里，而不是就地把它改成 6 参：
     * 既有调用点（服务端的转发与回执、测试用例）不必逐个修改，
     * 新增参数对它们是"不传即无"的可选信息。</p>
     *
     * @param sender       发送者用户名
     * @param receiver     接收者用户名，群聊传空字符串
     * @param content      消息正文，不允许为 null
     * @param quoteId      被引用消息的稳定标识，可为 null
     * @param quoteSummary 被引用消息的摘要，可为 null
     * @param quoteSender  被引用消息的发送者显示名，可为 null
     * @throws IllegalArgumentException 当 content 为 null 时抛出
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

    /**
     * 获取消息正文。
     *
     * @return 文本内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取被引用消息的稳定标识。
     *
     * <p>返回空串而不是 null：旧版对端发来的消息没有该字段，反序列化后为 null，
     * 统一成空串可以让调用方少写一次判空（"" 表示没有引用）。</p>
     *
     * @return 被引用消息标识；没有引用时返回空串
     */
    public String getQuoteId() {
        return quoteId == null ? "" : quoteId;
    }

    /**
     * 获取被引用消息的摘要文本。
     *
     * @return 引用摘要；没有引用时返回空串
     */
    public String getQuoteSummary() {
        return quoteSummary == null ? "" : quoteSummary;
    }

    /**
     * 获取被引用消息的发送者显示名。
     *
     * <p>与其它引用字段一样把 null 统一成空串：旧版对端发来的消息没有该字段，
     * 反序列化后为 null，调用方少写一次判空。</p>
     *
     * @return 被引用消息的发送者显示名；未记录时返回空串
     */
    public String getQuoteSender() {
        return quoteSender == null ? "" : quoteSender;
    }

    /**
     * 判断本条消息是否引用了其它消息。
     *
     * @return 有引用返回 true
     */
    public boolean hasQuote() {
        return !getQuoteId().isEmpty() || !getQuoteSummary().isEmpty();
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

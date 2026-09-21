package com.chat.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息抽象基类。
 *
 * <p>职责：定义所有网络消息的公共属性（消息编号、类型、发送者、接收者、时间戳），
 * 并通过抽象方法 {@link #getSummary()} 让子类各自决定如何在界面上摘要展示，
 * 从而把“多态”真正落到业务逻辑中，而不是只为满足语法要求。</p>
 *
 * <p>继承体系：</p>
 * <ul>
 *   <li>{@link TextMessage} —— 文本消息（私聊/群聊共用）</li>
 *   <li>{@link FileMessage} —— 文件元信息与数据块</li>
 *   <li>{@link SystemMessage} —— 系统通知（上线、下线、错误提示）</li>
 * </ul>
 *
 * <p>序列化说明：本类的子类通过 Java 原生序列化跨越网络传输，
 * {@code serialVersionUID} 固定为 {@value #serialVersionUID}，避免两端编译版本差异导致反序列化失败。
 * 由于子类由 ObjectOutputStream 的引用缓存处理，无需自定义 {@code writeObject}。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public abstract class Message implements Serializable {

    /** 序列化版本号，协议变更时必须同步修改 */
    private static final long serialVersionUID = 20250101L;

    /** 消息唯一编号，由发送方本地递增生成，用于日志追踪与去重 */
    private long id;

    /**
     * 跨端唯一的稳定消息标识（UUID 字符串）。
     *
     * <p>用途：服务端 ACK 确认、消息去重、离线补投、撤回与已读回执都依赖一个两端一致的标识。
     * 与 {@link #id} 的区别在于：{@code id} 只是发送方本地递增序号，落库后还会被服务端自增主键覆盖，
     * 两端编号互不相关，因此无法用来定位同一条消息。</p>
     *
     * <p>关于序列化版本号：新增本字段不修改 {@code serialVersionUID}，
     * 因为 Java 原生序列化对新增字段是向后兼容的——未升级的对端读到的是 {@code null}，
     * 由服务端落库前调用 {@link #ensureMessageId()} 补生成即可；
     * 反过来，若为此上调版本号，旧客户端会因版本号不匹配直接反序列化失败，反而破坏兼容。</p>
     */
    private String messageId;

    /** 消息类型，决定接收方的处理策略 */
    private MessageType type;

    /** 发送者用户名；系统消息固定为 {@link Constants#SYSTEM_SENDER} */
    private String sender;

    /** 接收者用户名；群聊或广播消息为空字符串表示所有人 */
    private String receiver;

    /** 消息产生时间 */
    private LocalDateTime timestamp;

    /**
     * 受保护的无参构造。
     *
     * <p>仅供子类构造器调用，时间戳在此初始化，保证任何消息都带有时序信息。
     * 外部代码不得直接实例化本类（抽象类本身也无法实例化），统一走 {@link ChatMessageFactory} 工厂。</p>
     */
    protected Message() {
        this.timestamp = LocalDateTime.now();
    }

    /**
     * 获取消息编号。
     *
     * @return 消息唯一编号
     */
    public long getId() {
        return id;
    }

    /**
     * 设置消息编号。
     *
     * <p>仅由工厂方法与历史记录读取逻辑调用，用于恢复原始编号。</p>
     *
     * @param id 消息编号
     */
    public void setId(long id) {
        this.id = id;
    }

    /**
     * 获取稳定消息标识。
     *
     * @return 跨端唯一标识；旧版对端发来的消息或尚未赋值时可能为 null
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * 设置稳定消息标识。
     *
     * <p>由发送方在构造消息时生成，或由服务端读取旧版客户端的消息后补写。</p>
     *
     * @param messageId 跨端唯一标识
     */
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    /**
     * 确保稳定消息标识非空，必要时生成并保存。
     *
     * <p>服务端在落库前统一调用本方法：这样即使旧版客户端不带该字段，
     * 数据库中也不会出现空标识，去重与 ACK 逻辑无需再判空。
     * 已存在的值不会被覆盖，保证同一条消息在重发、补投等多次落库路径中标识稳定。</p>
     *
     * @return 已有的或新生成的稳定消息标识，必定非空
     */
    public String ensureMessageId() {
        if (messageId == null || messageId.trim().isEmpty()) {
            messageId = java.util.UUID.randomUUID().toString();
        }
        return messageId;
    }

    /**
     * 获取消息类型。
     *
     * @return 消息类型枚举
     */
    public MessageType getType() {
        return type;
    }

    /**
     * 设置消息类型（由工厂方法调用，业务代码不应修改）。
     *
     * @param type 消息类型
     */
    public void setType(MessageType type) {
        this.type = type;
    }

    /**
     * 获取发送者用户名。
     *
     * @return 发送者用户名，系统消息为 {@link Constants#SYSTEM_SENDER}
     */
    public String getSender() {
        return sender;
    }

    /**
     * 设置发送者用户名（由工厂方法或服务器改写时调用）。
     *
     * @param sender 发送者用户名
     */
    public void setSender(String sender) {
        this.sender = sender;
    }

    /**
     * 获取接收者用户名。
     *
     * @return 接收者用户名；空字符串表示广播
     */
    public String getReceiver() {
        return receiver;
    }

    /**
     * 设置接收者用户名。
     *
     * @param receiver 接收者用户名，广播时传空字符串
     */
    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    /**
     * 获取消息时间戳。
     *
     * @return 消息产生时间
     */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    /**
     * 设置消息时间戳。
     *
     * <p>从历史记录文件恢复消息时需要显式设置原始时间，否则会丢失真实时序。</p>
     *
     * @param timestamp 消息时间
     */
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 获取消息内容摘要，用于界面单行展示与日志输出。
     *
     * <p>抽象方法体现多态：文本消息返回正文，文件消息返回文件名与大小，
     * 系统消息返回通知内容，调用方无需判断具体子类型。</p>
     *
     * @return 单行摘要文本
     */
    public abstract String getSummary();

    /**
     * 判断本条消息是否为广播消息（群聊或系统公告）。
     *
     * @return 接收者为空字符串时返回 true
     */
    public boolean isBroadcast() {
        return receiver == null || receiver.isEmpty();
    }

    /**
     * 返回对象的简要描述，刻意不包含消息正文，避免日志被大文本或文件字节撑爆。
     *
     * @return 形如 {@code TextMessage{id=1, messageId=..., type=TEXT_PRIVATE, sender=alice -> bob}} 的字符串
     */
    @Override
    public String toString() {
        String receiverText = isBroadcast() ? Constants.BROADCAST_TAG : receiver;
        return getClass().getSimpleName() + "{id=" + id + ", messageId=" + messageId + ", type=" + type
                + ", sender=" + sender + " -> " + receiverText
                + ", time=" + timestamp + "}";
    }
}

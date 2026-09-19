package com.chat.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息工厂类。
 *
 * <p>职责：集中创建各类消息对象，并在创建时统一分配消息编号、填充消息类型与时间戳。
 * 体现“工厂模式”，同时把“编号从哪来”“类型谁来设”这类易错细节收敛到唯一入口，
 * 业务代码只需调用 {@code ChatMessageFactory.text(...)} 即可。</p>
 *
 * <p>线程安全：编号使用 {@link AtomicLong} 生成，多线程并发创建不会产生重复编号。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class ChatMessageFactory {

    /** 全局递增的消息编号生成器 */
    private static final AtomicLong SEQUENCE = new AtomicLong(1L);

    /** 私有构造，禁止实例化工具类 */
    private ChatMessageFactory() {
    }

    /**
     * 创建文本消息（私聊或群聊）。
     *
     * @param sender   发送者用户名
     * @param receiver 接收者用户名，群聊传空字符串
     * @param content  消息正文
     * @param type     消息类型，仅允许 {@link MessageType#TEXT_PRIVATE} 或 {@link MessageType#TEXT_GROUP}
     * @return 已设置编号与类型的文本消息
     * @throws IllegalArgumentException 当 type 不是文本类消息时抛出
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
     * @param receiver 接收者用户名，广播传空字符串
     * @param content  通知正文
     * @return 已设置编号与类型的系统消息
     */
    public static SystemMessage system(String receiver, String content) {
        SystemMessage message = new SystemMessage(receiver, content);
        apply(message, MessageType.SYSTEM);
        return message;
    }

    /**
     * 创建错误通知消息。
     *
     * @param receiver 接收者用户名
     * @param reason   错误原因
     * @return 已设置编号与类型的错误消息
     */
    public static SystemMessage error(String receiver, String reason) {
        SystemMessage message = new SystemMessage(receiver, reason);
        apply(message, MessageType.ERROR);
        return message;
    }

    /**
     * 创建文件消息骨架。
     *
     * @param sender   发送者用户名
     * @param receiver 接收者用户名
     * @param type     文件相关消息类型
     * @return 已设置编号与类型的文件消息
     */
    public static FileMessage file(String sender, String receiver, MessageType type) {
        FileMessage message = new FileMessage(sender, receiver);
        apply(message, type);
        if (type == MessageType.FILE_RESULT) {
            System.out.println("[FAC] file() argSender=" + sender + " argReceiver=" + receiver
                    + " msgSender=" + message.getSender() + " msgReceiver=" + message.getReceiver());
        }
        return message;
    }

    /**
     * 创建无负载的控制类消息。
     *
     * <p>用于 {@link MessageType#HEARTBEAT}、{@link MessageType#LOGOUT} 等只需类型即可表达语义的场景，
     * 统一使用 {@link SystemMessage} 作为载体，正文留空。</p>
     *
     * @param sender   发送者用户名
     * @param receiver 接收者用户名，广播传空字符串
     * @param type     消息类型
     * @return 控制消息
     */
    public static SystemMessage control(String sender, String receiver, MessageType type) {
        SystemMessage message = new SystemMessage(receiver, "");
        message.setSender(sender);
        apply(message, type);
        return message;
    }

    /**
     * 为消息统一分配编号并写入类型。
     *
     * @param message 目标消息
     * @param type    消息类型
     */
    private static void apply(Message message, MessageType type) {
        message.setType(type);
        message.setId(SEQUENCE.getAndIncrement());
    }
}

package com.chat.common;

import java.io.ObjectInputFilter;
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

    /**
     * 单条报文允许的最大字段数量。
     *
     * <p>协议以竖线分隔字段，若不设上限，客户端可以提交包含成千上万个字段的超长正文，
     * 服务端解析时会一次性创建巨大的字符串数组，既拖慢处理又可能耗尽内存（拒绝服务）。
     * 取 16 是因为现有控制报文最多只用到 5 个字段，留出充足余量又不会失控。</p>
     */
    public static final int MAX_FIELD_COUNT = 16;

    /**
     * 反序列化白名单过滤器串。
     *
     * <p>只放行协议真正会用到的类型：本项目消息类与服务端下发的业务类位于
     * {@code com.chat.common}；字符串、包装类型与枚举依赖 {@code java.lang}；
     * 消息时间戳使用 {@code java.time}；{@code java.util} 当前报文并未直接使用，
     * 保留它是为了兼容既定的过滤器基线并给后续批量消息类扩展留出余量。
     * 末尾的 {@code !*} 表示其余一律拒绝，从而阻断依赖链 gadget 类被加载；
     * 深度、引用数、字节数与数组长度四项上限用于抵御"反序列化炸弹"造成的资源耗尽。</p>
     */
    private static final String SERIALIZATION_FILTER_PATTERN =
            "com.chat.common.*;java.lang.*;java.time.*;java.util.*;!*;"
                    + "maxdepth=8;maxrefs=1000;maxbytes=4194304;maxarray=65536";

    /** 私有构造，禁止实例化工具类 */
    private ChatMessageFactory() {
    }

    /**
     * 校验文本消息正文是否符合协议约束。
     *
     * <p>为什么不把校验塞进 {@link #text} 构造流程：工厂方法被服务端与客户端共同使用，
     * 直接抛异常会让"用户输入过长"这类正常业务失败变成连接级错误。
     * 因此改为返回中文原因，由入口决定如何回执。</p>
     *
     * @param content 消息正文，可为 null
     * @return 合法返回 null；非法返回可直接回执给客户端的中文原因
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
     * 按竖线切分控制报文正文，并校验字段数量与字段内容。
     *
     * <p>为什么超限时返回 null 而不是截断：截断会让客户端以为自己的请求被完整接受，
     * 双方对"实际生效的是哪几个字段"产生分歧；返回 null 由调用方回执中文错误，
     * 语义只有"接受"与"拒绝"两种。</p>
     *
     * @param text 报文正文，可为 null
     * @return 字段数组；正文为 null、超过正文长度上限、字段数超过 {@link #MAX_FIELD_COUNT}
     *         或任一字段含非法控制字符时返回 null
     */
    public static String[] splitFields(String text) {
        if (text == null || text.length() > Constants.MESSAGE_MAX_LENGTH) {
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
     * 判断字符串是否含破坏行式协议的控制字符。
     *
     * <p>为什么放行换行与制表符：历史查询结果用 {@code \n} 作为行分隔、
     * 本地用户文件用制表符作为字段分隔，二者是既有协议的组成部分；
     * 其余控制字符（回车、退格、NUL 等）会污染日志行的切分，必须拒绝。</p>
     *
     * @param value 待检查字符串，可为 null
     * @return 含非法控制字符返回 true；null 或合法时返回 false
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
     * 构造反序列化白名单过滤器。
     *
     * <p>为什么必须有过滤器：Java 原生反序列化会依据字节流中声明的类名递归实例化对象，
     * 攻击者只要能让服务端读取一段精心构造的数据，就能借助第三方库中可被串联的
     * {@code readObject} 形成 gadget 链执行任意代码；即便不执行代码，超深的嵌套引用
     * 也能让反序列化过程耗尽内存。过滤器在类加载前就拒绝白名单之外的类型，
     * 并限制递归深度、引用数、总字节数与数组长度。</p>
     *
     * <p>放行清单由实测得出：全部协议报文（文本、系统、文件请求、文件数据块、登录结果）
     * 都能通过过滤器完成一次完整往返；消息类与其父类位于 {@code com.chat.common}，
     * 字符串、包装类型与枚举依赖 {@code java.lang}，时间戳依赖 {@code java.time}。
     * 两点实测结论需要留意：其一，{@code java.util.*} 只匹配该包下的直接成员，
     * {@code java.util.concurrent.atomic} 这类子包并不在放行范围内；
     * 其二，{@code byte[]} 等基本类型数组不受类名白名单约束，其长度由 {@code maxarray} 单独限制，
     * 文件数据块因此可以正常传输，而 {@code java.io.File}、{@code java.util.concurrent.*}
     * 等非协议类型会被拒绝。</p>
     *
     * @return 可直接绑定到 {@code ObjectInputStream} 的过滤器实例
     */
    public static ObjectInputFilter serializationFilter() {
        return ObjectInputFilter.Config.createFilter(SERIALIZATION_FILTER_PATTERN);
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

package com.chat.common;

import java.io.ObjectInputFilter;

/**
 * 按"每条消息"计算预算的反序列化过滤器。
 *
 * <p>职责：在 {@link ChatMessageFactory#serializationFilter()} 的类型白名单之上，
 * 额外限制<b>单条消息</b>能消耗的字节数与引用数，把"反序列化资源放大"这条攻击面收掉。</p>
 *
 * <p><b>为什么不能只用 JDK 过滤器里的 {@code maxbytes} / {@code maxrefs}</b>：
 * 那两个取值绑定的是同一个 {@code ObjectInputStream} 上的<b>累计</b>量，而不是单条消息的规模
 * （见 {@link ObjectInputFilter.FilterInfo#streamBytes()} 与 {@link ObjectInputFilter.FilterInfo#references()}）。
 * 把累计上限当作单条上限使用会得到两个都不想要的结果：</p>
 * <ul>
 *   <li>设小了：正常聊天几十条之后整条连接被判定为攻击（缺陷 D-23 的成因）；</li>
 *   <li>设大了（例如 1GiB）：未登录连接发一个只有 71MB、却声明持有 1500 万个元素的
 *       {@code java.util.LinkedList}，就能让服务端堆增长到几百 MB 并 OOM ——
 *       每个元素在流里只是一个 5 字节的 {@code TC_REFERENCE}，却要分配一个节点，
 *       放大倍数接近 8 倍。</li>
 * </ul>
 *
 * <p><b>本类的做法</b>：每次 {@code readObject} 之前由读循环调用 {@link #beginMessage()}
 * 把基线推到当前的累计量，之后所有检查都只看"相对基线的增量"。
 * 这样每条消息都有独立预算，长连接不会因为"聊得久"而被误杀，
 * 单条超大的恶意报文也一定会在读到预算上限时被 {@link Status#REJECTED}。</p>
 *
 * <p>线程约束：一个实例只服务一条 {@code ObjectInputStream}，而该流只被一个读线程读取，
 * 因此内部状态不需要同步。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class MessageFilter implements ObjectInputFilter {

    /**
     * 服务端读取客户端报文时的单条字节预算：1 MiB。
     *
     * <p>客户端能发来的最大合法报文是 64KiB 的文件分块（见 {@code Constants.FILE_CHUNK_SIZE}）
     * 与 32KB 头像的 Base64，两者都含对象流头部开销后仍远小于 1 MiB；
     * 留出十倍余量是为了给协议新增字段时不至于立刻触顶。</p>
     */
    private static final long SERVER_INPUT_MAX_BYTES = 1L << 20;

    /** 服务端读取客户端报文时的单条引用预算：20 万 */
    private static final long SERVER_INPUT_MAX_REFS = 200_000L;

    /**
     * 客户端读取服务端报文时的单条字节预算：64 MiB。
     *
     * <p>比服务端宽松得多，因为服务端下发的单条报文天然更大：用户列表带着全体在线用户的头像
     * （每人约 13KB，百人量级约 1.3MB），聊天记录一页最多 200 条，而"导出我的聊天记录"
     * 更是把整段历史渲染成一份文本放在一条消息里（当前没有分块，也没有上限）。
     * 客户端面对的是自己配置的服务器，这里的预算只用于兜底异常报文，不作为安全边界。</p>
     */
    private static final long CLIENT_INPUT_MAX_BYTES = 64L << 20;

    /** 客户端读取服务端报文时的单条引用预算：800 万 */
    private static final long CLIENT_INPUT_MAX_REFS = 8_000_000L;

    /** 类型白名单与结构限制（深度、单数组长度） */
    private final ObjectInputFilter delegate;

    /** 单条消息的字节预算 */
    private final long maxBytesPerMessage;

    /** 单条消息的引用预算 */
    private final long maxRefsPerMessage;

    /** 上一条消息开始时的累计字节数 */
    private long baseBytes;

    /** 上一条消息开始时的累计引用数 */
    private long baseRefs;

    /** 观察到的最新累计字节数 */
    private long lastBytes;

    /** 观察到的最新累计引用数 */
    private long lastRefs;

    /**
     * 构造过滤器。
     *
     * @param delegate            类型白名单过滤器，通常来自 {@link ChatMessageFactory#serializationFilter()}
     * @param maxBytesPerMessage  单条消息允许新增的字节数
     * @param maxRefsPerMessage   单条消息允许新增的引用数
     */
    public MessageFilter(ObjectInputFilter delegate, long maxBytesPerMessage, long maxRefsPerMessage) {
        this.delegate = delegate;
        this.maxBytesPerMessage = maxBytesPerMessage;
        this.maxRefsPerMessage = maxRefsPerMessage;
    }

    /**
     * 创建"服务端读取客户端报文"用的过滤器。
     *
     * @return 单条预算为 {@value #SERVER_INPUT_MAX_BYTES} 字节的过滤器
     */
    public static MessageFilter forServerInput() {
        return new MessageFilter(ChatMessageFactory.serializationFilter(),
                SERVER_INPUT_MAX_BYTES, SERVER_INPUT_MAX_REFS);
    }

    /**
     * 创建"客户端读取服务端报文"用的过滤器。
     *
     * @return 单条预算为 {@value #CLIENT_INPUT_MAX_BYTES} 字节的过滤器
     */
    public static MessageFilter forClientInput() {
        return new MessageFilter(ChatMessageFactory.serializationFilter(),
                CLIENT_INPUT_MAX_BYTES, CLIENT_INPUT_MAX_REFS);
    }

    /**
     * 标记"下一条消息从这里开始计预算"。
     *
     * <p>必须在每次 {@code readObject} 之前调用：不调用的话基线一直停在 0，
     * 预算会退化成累计上限，D-23 那类"聊久了掉线"的问题就会回来。</p>
     */
    public void beginMessage() {
        baseBytes = lastBytes;
        baseRefs = lastRefs;
    }

    /**
     * 检查当前对象是否允许反序列化。
     *
     * <p>先按增量判断预算，超限直接拒绝；预算之内再交给白名单过滤器。
     * 用 {@code Math.max} 记录观测值是为了只增不减：累计量在任何实现下都不应回退，
     * 万一回退也不会把预算算错。</p>
     *
     * @param info 当前读取位置的统计信息
     * @return 允许继续、拒绝或不做判断
     */
    @Override
    public Status checkInput(FilterInfo info) {
        lastBytes = Math.max(lastBytes, info.streamBytes());
        lastRefs = Math.max(lastRefs, info.references());
        if (lastBytes - baseBytes > maxBytesPerMessage) {
            return Status.REJECTED;
        }
        if (lastRefs - baseRefs > maxRefsPerMessage) {
            return Status.REJECTED;
        }
        return delegate.checkInput(info);
    }
}

package com.chat.common;

import java.io.ObjectInputFilter;

/**
 * 按单条消息限制反序列化字节数与引用数的过滤器。
 */
public final class MessageFilter implements ObjectInputFilter {
    /** 服务端单条消息的字节预算：1 MiB */
    private static final long SERVER_INPUT_MAX_BYTES = 1L << 20;

    /** 服务端单条消息的引用数预算 */
    private static final long SERVER_INPUT_MAX_REFS = 200_000L;

    /** 客户端单条消息的字节预算：64 MiB */
    private static final long CLIENT_INPUT_MAX_BYTES = 64L << 20;

    /** 客户端单条消息的引用数预算 */
    private static final long CLIENT_INPUT_MAX_REFS = 8_000_000L;

    private final ObjectInputFilter delegate;

    private final long maxBytesPerMessage;

    private final long maxRefsPerMessage;

    /** 上一条消息结束时的累计字节数 */
    private long baseBytes;

    /** 上一条消息结束时的累计引用数 */
    private long baseRefs;

    /** 累计字节数的最大值 */
    private long lastBytes;

    /** 累计引用数的最大值 */
    private long lastRefs;

    /**
     * 创建过滤器并指定单条消息的预算。
     *
     * @param delegate 下层过滤器
     * @param maxBytesPerMessage 单条消息的字节上限
     * @param maxRefsPerMessage 单条消息的引用数上限
     */
    public MessageFilter(ObjectInputFilter delegate, long maxBytesPerMessage, long maxRefsPerMessage) {
        this.delegate = delegate;
        this.maxBytesPerMessage = maxBytesPerMessage;
        this.maxRefsPerMessage = maxRefsPerMessage;
    }

    /**
     * 创建使用服务端预算的过滤器。
     *
     * @return 服务端输入过滤器
     */
    public static MessageFilter forServerInput() {
        return new MessageFilter(ChatMessageFactory.serializationFilter(),
                SERVER_INPUT_MAX_BYTES, SERVER_INPUT_MAX_REFS);
    }

    /**
     * 创建使用客户端预算的过滤器。
     *
     * @return 客户端输入过滤器
     */
    public static MessageFilter forClientInput() {
        return new MessageFilter(ChatMessageFactory.serializationFilter(),
                CLIENT_INPUT_MAX_BYTES, CLIENT_INPUT_MAX_REFS);
    }

    /** 记录当前累计值，作为下一条消息的统计起点。 */
    public void beginMessage() {
        baseBytes = lastBytes;
        baseRefs = lastRefs;
    }

    @Override
    public Status checkInput(FilterInfo info) {
        // 记录消息流累计的最大值
        lastBytes = Math.max(lastBytes, info.streamBytes());
        lastRefs = Math.max(lastRefs, info.references());
        // 超出单条消息的预算时拒绝
        if (lastBytes - baseBytes > maxBytesPerMessage) {
            return Status.REJECTED;
        }
        if (lastRefs - baseRefs > maxRefsPerMessage) {
            return Status.REJECTED;
        }
        // 交给下层过滤器继续判断
        return delegate.checkInput(info);
    }
}

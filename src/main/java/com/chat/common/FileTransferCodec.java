package com.chat.common;

/**
 * 文件传输请求编解码器。
 *
 * <p>职责：把文件请求的“请求方信息 + 文件元信息”编码为文本，使文件请求可以复用
 * 现有的文本消息通道传输，无需为“一次请求”新增消息结构。</p>
 *
 * <p>编码格式（字段以竖线分隔）：</p>
 * <pre>
 * transferId|fileName|fileSize|totalChunks|sha256
 * </pre>
 *
 * <p>注意：文件“数据块”不走本编解码器，而是直接使用 {@link FileMessage} 承载字节数组，
 * 因为文本编码会把二进制数据放大并增加一次转换开销。</p>
 *
 * <p>边界处理：文本通道的内容来自对端，属于不可信输入，因此在解析时先限制报文长度与
 * 字段数量，再逐字段做格式校验；数值字段拒绝符号与空白，避免 {@code Long.parseLong}
 * 接受 {@code +5}、前导空格这类“看起来像数字但不符合协议”的写法。
 * 解析失败一律返回 null（与本类既有风格一致），由调用方按“忽略该请求”处理。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class FileTransferCodec {

    /** 字段分隔符 */
    private static final char SEPARATOR = '|';

    /**
     * 编码文本长度上限（字符数）。
     *
     * <p>取 1024：五个字段中只有文件名可能较长（其上限为
     * {@link FileMessage#MAX_FILE_NAME_LENGTH}），其余字段为数字或 64 位摘要，
     * 正常请求远低于该值；超长文本说明对端实现异常或恶意构造，直接拒绝以防内存被无谓占用。</p>
     */
    private static final int MAX_TEXT_LENGTH = 1024;

    /** 允许的最大字段数：协议要求 5 个字段，多出的部分一律视为非法 */
    private static final int MAX_FIELD_COUNT = 5;

    /** 私有构造，禁止实例化工具类 */
    private FileTransferCodec() {
    }

    /**
     * 把文件传输请求编码为文本。
     *
     * @param request 文件请求消息
     * @return 编码文本；请求为 null 时返回空串
     */
    public static String encode(FileMessage request) {
        if (request == null) {
            return "";
        }
        return String.join(String.valueOf(SEPARATOR),
                nullToEmpty(request.getTransferId()),
                escapeField(nullToEmpty(request.getFileName())),
                String.valueOf(request.getFileSize()),
                String.valueOf(request.getTotalChunks()),
                nullToEmpty(request.getSha256()));
    }

    /**
     * 从文本解析文件传输请求。
     *
     * <p>解析成功只代表“结构合法”，文件名、体积、块数、校验和的完整校验由
     * {@link FileMessage#failReason()} 在业务层完成，这里只拒绝明显不可能的数值，
     * 避免把负数或超大值继续往下传。</p>
     *
     * @param text     编码文本
     * @param sender   发送者用户名
     * @param receiver 接收者用户名
     * @return 文件消息；格式非法时返回 null
     */
    public static FileMessage decode(String text, String sender, String receiver) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TEXT_LENGTH) {
            return null;
        }
        String[] parts = splitFields(trimmed);
        if (parts == null) {
            return null;
        }
        FileMessage request = ChatMessageFactory.file(sender, receiver, MessageType.FILE_REQUEST);
        request.setTransferId(parts[0]);
        request.setFileName(parts[1]);
        long fileSize = parseNonNegativeLong(parts[2]);
        if (fileSize < 0) {
            return null;
        }
        request.setFileSize(fileSize);
        int totalChunks = parseIntInRange(parts[3], 0, FileMessage.MAX_TOTAL_CHUNKS);
        if (totalChunks < 0) {
            return null;
        }
        request.setTotalChunks(totalChunks);
        request.setSha256(parts[4]);
        return request;
    }

    /**
     * 按分隔符切分字段并限制字段数量。
     *
     * @param text 待切分文本
     * @return 长度为 5 的字段数组；字段数不符时返回 null
     */
    private static String[] splitFields(String text) {
        String[] parts = text.split("\\" + SEPARATOR, -1);
        if (parts.length != MAX_FIELD_COUNT) {
            return null;
        }
        return parts;
    }

    /**
     * 解析非负整数。
     *
     * @param text 待解析文本
     * @param min  允许的最小值
     * @param max  允许的最大值
     * @return 解析结果；格式非法或超出范围时返回 -1
     */
    private static int parseIntInRange(String text, int min, int max) {
        long value = parseNonNegativeLong(text);
        if (value < min || value > max) {
            return -1;
        }
        return (int) value;
    }

    /**
     * 解析非负十进制整数。
     *
     * <p>只接受纯数字：若交给 {@link Long#parseLong} 直接解析，{@code +1}、{@code " 1"}
     * 都会被接受，使协议出现多种等价写法，不利于排查问题。</p>
     *
     * @param text 待解析文本
     * @return 解析结果；格式非法、为空或为负数时返回 -1
     */
    private static long parseNonNegativeLong(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return -1;
            }
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            // 纯数字却解析失败只可能是超出 long 表示范围，按非法输入处理
            return -1;
        }
    }

    /**
     * 转义字段内的分隔符，保证解码时字段数稳定。
     *
     * <p>正常情况下文件名已由 {@code FileUtil.sanitizeFileName} 清洗，但本类
     * 作为公共编解码入口不能依赖调用方纪律：一旦文件名带竖线而未转义，
     * 对端会因字段数超出而直接丢弃整个请求，表现为“文件发不出去且无提示”。</p>
     *
     * @param value 原始字段值
     * @return 已替换分隔符的字段值
     */
    private static String escapeField(String value) {
        if (value.indexOf(SEPARATOR) < 0) {
            return value;
        }
        return value.replace(SEPARATOR, '_');
    }

    /**
     * 把 null 转为空串，保证字段数量稳定。
     *
     * @param value 原始值
     * @return 非 null 字符串
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

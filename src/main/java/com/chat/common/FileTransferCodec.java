package com.chat.common;

/**
 * 文件传输请求编解码器。
 *
 * <p>职责：把文件请求的“请求方信息 + 文件元信息”编码为文本，使文件请求可以复用
 * 现有的文本消息通道传输，无需为“一次请求”新增消息结构。</p>
 *
 * <p>编码格式（字段以竖线分隔）：</p>
 * <pre>
 * transferId|fileName|fileSize|totalChunks|sha256|接收目录提示
 * </pre>
 *
 * <p>注意：文件“数据块”不走本编解码器，而是直接使用 {@link FileMessage} 承载字节数组，
 * 因为文本编码会把二进制数据放大并增加一次转换开销。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class FileTransferCodec {

    /** 字段分隔符 */
    private static final char SEPARATOR = '|';

    /** 私有构造，禁止实例化工具类 */
    private FileTransferCodec() {
    }

    /**
     * 把文件传输请求编码为文本。
     *
     * @param request 文件请求消息
     * @return 编码文本
     */
    public static String encode(FileMessage request) {
        return String.join(String.valueOf(SEPARATOR),
                nullToEmpty(request.getTransferId()),
                nullToEmpty(request.getFileName()),
                String.valueOf(request.getFileSize()),
                String.valueOf(request.getTotalChunks()),
                nullToEmpty(request.getSha256()));
    }

    /**
     * 从文本解析文件传输请求。
     *
     * @param text   编码文本
     * @param sender 发送者用户名
     * @param receiver 接收者用户名
     * @return 文件消息；格式非法时返回 null
     */
    public static FileMessage decode(String text, String sender, String receiver) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String[] parts = text.split("\\" + SEPARATOR, -1);
        if (parts.length < 5) {
            return null;
        }
        FileMessage request = ChatMessageFactory.file(sender, receiver, MessageType.FILE_REQUEST);
        request.setTransferId(parts[0]);
        request.setFileName(parts[1]);
        try {
            request.setFileSize(Long.parseLong(parts[2]));
            request.setTotalChunks(Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
        request.setSha256(parts[4]);
        return request;
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

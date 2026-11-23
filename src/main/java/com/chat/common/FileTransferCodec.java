package com.chat.common;

/**
 * 文件传输请求文本的编解码工具，字段之间用竖线分隔。
 */
public final class FileTransferCodec {
    /** 字段分隔符 */
    private static final char SEPARATOR = '|';

    /** 请求文本的最大长度 */
    private static final int MAX_TEXT_LENGTH = 1024;

    /** 请求文本的字段数 */
    private static final int MAX_FIELD_COUNT = 5;

    private FileTransferCodec() {
    }

    /**
     * 把文件传输请求编码为竖线分隔的文本。
     *
     * @param request 文件传输请求
     * @return 编码结果，请求为 null 时返回空串
     */
    public static String encode(FileMessage request) {
        if (request == null) {
            return "";
        }
        // 字段顺序：传输编号、文件名、大小、分块数、校验和
        return String.join(String.valueOf(SEPARATOR),
                nullToEmpty(request.getTransferId()),
                escapeField(nullToEmpty(request.getFileName())),
                String.valueOf(request.getFileSize()),
                String.valueOf(request.getTotalChunks()),
                nullToEmpty(request.getSha256()));
    }

    /**
     * 解析文件传输请求文本。
     *
     * @param text 请求文本
     * @param sender 发送者
     * @param receiver 接收者
     * @return 解析出的请求，格式不合法时返回 null
     */
    public static FileMessage decode(String text, String sender, String receiver) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        // 校验文本长度
        if (trimmed.isEmpty() || trimmed.length() > MAX_TEXT_LENGTH) {
            return null;
        }
        String[] parts = splitFields(trimmed);
        if (parts == null) {
            return null;
        }
        FileMessage request = ChatMessageFactory.file(sender, receiver, MessageType.FILE_REQUEST);
        // 解析各字段并校验取值范围
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

    private static String[] splitFields(String text) {
        String[] parts = text.split("\\" + SEPARATOR, -1);
        if (parts.length != MAX_FIELD_COUNT) {
            return null;
        }
        return parts;
    }

    /** 解析指定范围内的非负整数，越界时返回 -1。 */
    private static int parseIntInRange(String text, int min, int max) {
        long value = parseNonNegativeLong(text);
        if (value < min || value > max) {
            return -1;
        }
        return (int) value;
    }

    /** 解析非负长整数，含非数字字符时返回 -1。 */
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
            return -1;
        }
    }

    /** 把字段内的分隔符替换为下划线。 */
    private static String escapeField(String value) {
        if (value.indexOf(SEPARATOR) < 0) {
            return value;
        }
        return value.replace(SEPARATOR, '_');
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

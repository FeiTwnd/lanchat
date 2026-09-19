package com.chat.exception;

/**
 * 文件传输异常。
 *
 * <p>职责：表示文件传输链路上的失败，包括文件过大、目标目录不可写、
 * 分块序号错乱、校验和不一致、接收方拒绝等。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class FileTransferException extends ChatException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250109L;

    /** 关联的传输会话编号，便于在日志中定位是哪一次传输失败 */
    private final String transferId;

    /**
     * 使用错误信息构造异常。
     *
     * @param message 错误描述
     */
    public FileTransferException(String message) {
        this(message, (String) null);
    }

    /**
     * 使用错误信息与原始异常构造异常。
     *
     * @param message 错误描述
     * @param cause   原始异常
     */
    public FileTransferException(String message, Throwable cause) {
        super(message, cause);
        this.transferId = null;
    }

    /**
     * 使用错误信息与传输编号构造异常。
     *
     * @param message    错误描述
     * @param transferId 传输会话编号，可为 null
     */
    public FileTransferException(String message, String transferId) {
        super(message, 500);
        this.transferId = transferId;
    }

    /**
     * 获取传输会话编号。
     *
     * @return 传输编号，可能为 null
     */
    public String getTransferId() {
        return transferId;
    }
}

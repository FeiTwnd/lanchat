package com.chat.exception;

/**
 * 文件传输失败时抛出的异常，可关联传输标识。
 */
public class FileTransferException extends ChatException {
    private static final long serialVersionUID = 20250109L;

    private final String transferId;

    public FileTransferException(String message) {
        this(message, (String) null);
    }

    public FileTransferException(String message, Throwable cause) {
        super(message, cause);
        this.transferId = null;
    }

    public FileTransferException(String message, String transferId) {
        super(message, 500);
        this.transferId = transferId;
    }

    public String getTransferId() {
        return transferId;
    }
}

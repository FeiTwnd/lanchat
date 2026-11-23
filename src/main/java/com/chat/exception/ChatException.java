package com.chat.exception;

/**
 * 聊天程序的通用受检异常，可携带业务错误码。
 */
public class ChatException extends Exception {
    private static final long serialVersionUID = 20250107L;

    /** 业务错误码，0 表示未指定。 */
    private final int errorCode;

    public ChatException(String message) {
        super(message);
        this.errorCode = 0;
    }

    public ChatException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public ChatException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = 0;
    }

    public int getErrorCode() {
        return errorCode;
    }
}

package com.chat.exception;

/**
 * 用户不存在时抛出的异常。
 */
public class UserNotFoundException extends ChatException {
    private static final long serialVersionUID = 20250108L;

    public UserNotFoundException(String message) {
        super(message, 404);
    }

    public UserNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

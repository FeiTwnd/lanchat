package com.chat.exception;

/**
 * 用户不存在或不在线异常。
 *
 * <p>职责：区分“用户相关”的业务失败与其它业务失败，便于上层针对性地提示
 * （例如私聊时对方已下线，应提示“对方不在线”而不是笼统的“操作失败”）。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class UserNotFoundException extends ChatException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250108L;

    /**
     * 使用错误信息构造异常。
     *
     * @param message 错误描述，通常包含具体用户名
     */
    public UserNotFoundException(String message) {
        super(message, 404);
    }

    /**
     * 使用错误信息与原始异常构造异常。
     *
     * @param message 错误描述
     * @param cause   原始异常
     */
    public UserNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

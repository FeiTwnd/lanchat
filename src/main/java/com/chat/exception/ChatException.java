package com.chat.exception;

/**
 * 聊天系统业务异常基类（受检异常）。
 *
 * <p>职责：统一表示“业务规则不满足”这一失败场景，例如用户名已存在、参数非法、持久化失败等。
 * 使用受检异常而非运行时异常，强制调用方显式处理，符合课程对“异常必须处理、不能空 catch”的要求。</p>
 *
 * <p>异常层次：</p>
 * <pre>
 * Exception
 *   └── ChatException               业务异常基类
 *         ├── UserNotFoundException 用户不存在 / 不在线
 *         └── FileTransferException 文件传输失败
 * </pre>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class ChatException extends Exception {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250107L;

    /** 触发异常的原始错误码，便于上层做差异化处理；0 表示无特定错误码 */
    private final int errorCode;

    /**
     * 使用错误信息构造异常。
     *
     * @param message 错误描述
     */
    public ChatException(String message) {
        super(message);
        this.errorCode = 0;
    }

    /**
     * 使用错误信息与错误码构造异常。
     *
     * @param message   错误描述
     * @param errorCode 自定义错误码
     */
    public ChatException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 包装底层异常并附加业务语义。
     *
     * <p>用于把 {@link java.io.IOException}、{@link java.sql.SQLException} 等底层异常
     * 转换为统一的业务异常，避免底层实现细节向上层泄漏。</p>
     *
     * @param message 业务描述
     * @param cause   原始异常
     */
    public ChatException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = 0;
    }

    /**
     * 获取自定义错误码。
     *
     * @return 错误码，未设置时返回 0
     */
    public int getErrorCode() {
        return errorCode;
    }
}

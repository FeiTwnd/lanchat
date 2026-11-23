package com.chat.common;

import java.io.Serializable;

/**
 * 通用操作结果，包含成功标志、说明与数据。
 */
public class Result<T> implements Serializable {
    private static final long serialVersionUID = 20250102L;

    private final boolean success;

    private final String message;

    private final T data;

    private Result(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    /**
     * 创建携带数据的成功结果。
     *
     * @param data 结果数据
     * @return 成功结果
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(true, "操作成功", data);
    }

    /**
     * 创建带自定义说明的成功结果。
     *
     * @param message 结果说明
     * @param data 结果数据
     * @return 成功结果
     */
    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(true, message, data);
    }

    /**
     * 创建失败结果。
     *
     * @param message 失败说明
     * @return 失败结果
     */
    public static <T> Result<T> fail(String message) {
        return new Result<>(false, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    @Override
    public String toString() {
        return "Result{success=" + success + ", message=" + message + "}";
    }
}

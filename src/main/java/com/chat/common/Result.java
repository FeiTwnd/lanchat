package com.chat.common;

import java.io.Serializable;

/**
 * 通用业务返回结果封装（泛型）。
 *
 * <p>职责：把“是否成功、提示信息、业务数据”三要素打包，使 Service 层不需要靠返回 null
 * 或抛异常来表达“业务失败”，调用方也不必区分“异常”与“正常失败”两种分支。</p>
 *
 * <p>泛型意义：{@code Result<User>}、{@code Result<java.util.List<User>>} 等让编译期即可
 * 校验数据类型，避免强转。本类不可变，天然线程安全。</p>
 *
 * @param <T> 业务数据类型
 * @author Java 课程设计
 * @version 1.0
 */
public class Result<T> implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250102L;

    /** 是否成功 */
    private final boolean success;

    /** 提示信息，成功时为“操作成功”等说明，失败时为失败原因 */
    private final String message;

    /** 业务数据，失败时通常为 null */
    private final T data;

    /**
     * 私有构造，强制通过 {@link #ok(Object)} 与 {@link #fail(String)} 创建，
     * 保证成功/失败的语义不会被误用。
     *
     * @param success 是否成功
     * @param message 提示信息
     * @param data    业务数据
     */
    private Result(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    /**
     * 构造成功结果。
     *
     * @param data 业务数据，可为 null 表示无返回数据
     * @param <T>  业务数据类型
     * @return 成功结果对象
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(true, "操作成功", data);
    }

    /**
     * 构造带自定义提示的成功结果。
     *
     * @param message 成功提示信息
     * @param data    业务数据
     * @param <T>     业务数据类型
     * @return 成功结果对象
     */
    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(true, message, data);
    }

    /**
     * 构造失败结果。
     *
     * @param message 失败原因
     * @param <T>     业务数据类型
     * @return 失败结果对象，数据为 null
     */
    public static <T> Result<T> fail(String message) {
        return new Result<>(false, message, null);
    }

    /**
     * 判断是否成功。
     *
     * @return 成功返回 true
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取提示信息。
     *
     * @return 提示信息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取业务数据。
     *
     * @return 业务数据，可能为 null
     */
    public T getData() {
        return data;
    }

    /**
     * 输出结果摘要，便于日志排查。
     *
     * @return 形如 {@code Result{success=true, message=操作成功}} 的字符串
     */
    @Override
    public String toString() {
        return "Result{success=" + success + ", message=" + message + "}";
    }
}

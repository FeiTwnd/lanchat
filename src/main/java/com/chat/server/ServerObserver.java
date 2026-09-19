package com.chat.server;

/**
 * 服务器事件观察者接口。
 *
 * <p>职责：把服务器内部发生的关键事件（用户上线、下线、文件传输、异常等）
 * 以回调形式通知给界面层，使 {@link ServerUI} 能够实时刷新日志与在线列表，
 * 而服务器核心逻辑无需了解任何 Swing 组件。</p>
 *
 * <p>设计模式：这是观察者模式的实际应用，也是“界面与业务解耦”的关键一环——
 * 若没有本接口，服务器代码里就会直接出现 {@code JTextArea.append(...)}，导致核心逻辑无法脱离界面单独测试。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public interface ServerObserver {

    /**
     * 服务器事件类型。
     */
    enum EventType {
        /** 服务器启动 */
        SERVER_START,
        /** 服务器停止 */
        SERVER_STOP,
        /** 用户上线 */
        USER_ONLINE,
        /** 用户下线 */
        USER_OFFLINE,
        /** 登录成功 */
        LOGIN,
        /** 登录失败 */
        LOGIN_FAILED,
        /** 私聊消息 */
        PRIVATE_MESSAGE,
        /** 群聊消息 */
        GROUP_MESSAGE,
        /** 文件传输 */
        FILE_TRANSFER,
        /** 错误事件 */
        ERROR
    }

    /**
     * 接收服务器事件。
     *
     * <p>实现方必须保证本方法快速返回且不抛异常：它在网络线程中被调用，
     * 任何耗时操作都应切换到界面线程执行。</p>
     *
     * @param type    事件类型
     * @param content 事件描述
     */
    void onEvent(EventType type, String content);
}

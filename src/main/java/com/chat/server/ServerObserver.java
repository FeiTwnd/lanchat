package com.chat.server;

/**
 * 服务器事件观察者接口，接收服务器运行、用户上下线、消息、文件传输与错误事件。
 */
public interface ServerObserver {
    /**
     * 服务器事件类型。
     */
    enum EventType {
        SERVER_START,

        SERVER_STOP,

        USER_ONLINE,

        USER_OFFLINE,

        LOGIN,

        LOGIN_FAILED,

        PRIVATE_MESSAGE,

        GROUP_MESSAGE,

        FILE_TRANSFER,

        ERROR
    }

    /**
     * 接收服务器事件。
     *
     * @param type 事件类型
     * @param content 事件描述
     */
    void onEvent(EventType type, String content);
}

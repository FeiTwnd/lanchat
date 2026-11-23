package com.chat.server;

import com.chat.common.Message;

import java.net.Socket;

/**
 * 服务器端消息处理器接口，定义文本、文件与控制消息的处理入口，以及用户上下线和连接关闭回调。
 */
public interface MessageHandler {
    /**
     * 处理私聊与群聊文本消息。
     *
     * @param message 消息对象
     * @param socket 客户端连接
     */
    void handleTextMessage(Message message, Socket socket);

    /**
     * 处理用户列表消息。
     *
     * @param message 消息对象
     * @param socket 客户端连接
     */
    void handleUserList(Message message, Socket socket);

    /**
     * 处理文件传输相关消息。
     *
     * @param message 消息对象
     * @param socket 客户端连接
     */
    void handleFileMessage(Message message, Socket socket);

    /**
     * 处理登录、注册与控制类消息。
     *
     * @param message 消息对象
     * @param socket 客户端连接
     */
    void handleSystemMessage(Message message, Socket socket);

    /**
     * 用户登录成功后的回调。
     *
     * @param username 用户名
     * @param socket 客户端连接
     */
    void onUserOnline(String username, Socket socket);

    /**
     * 用户下线后的回调。
     *
     * @param username 用户名
     */
    void onUserOffline(String username);

    /**
     * 连接关闭后的清理回调。
     */
    void onConnectionClosed();
}

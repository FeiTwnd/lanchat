package com.chat.server;

import com.chat.common.Message;

import java.net.Socket;

/**
 * 消息处理器接口。
 *
 * <p>职责：定义“收到一个消息之后做什么”的契约。接口把“消息如何传输”与“消息如何处理”解耦，
 * 使服务端的连接处理器与测试用的内存处理器可以共享同一套调用方式。</p>
 *
 * <p>接口方法按消息类型划分而非只留一个 {@code handle(Message)}：
 * 这样实现类只关心自己支持的消息种类，其余种类由抽象基类
 * {@link AbstractMessageHandler} 统一兜底，避免在实现类中写一长串 {@code if-else} 判断。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public interface MessageHandler {

    /**
     * 处理文本消息（私聊或群聊）。
     *
     * @param message 文本消息
     * @param socket  消息来源连接，服务端据此回写响应；内存测试时可传 null
     */
    void handleTextMessage(Message message, Socket socket);

    /**
     * 处理在线用户列表消息。
     *
     * @param message 用户列表消息（快照或刷新请求）
     * @param socket  消息来源连接
     */
    void handleUserList(Message message, Socket socket);

    /**
     * 处理文件消息（请求、应答、数据块、结束、结果）。
     *
     * @param message 文件消息
     * @param socket  消息来源连接
     */
    void handleFileMessage(Message message, Socket socket);

    /**
     * 处理系统类消息（登录、注销、心跳、历史查询等控制语义）。
     *
     * @param message 消息
     * @param socket  消息来源连接
     */
    void handleSystemMessage(Message message, Socket socket);

    /**
     * 处理用户上线事件。
     *
     * @param username 上线用户名
     * @param socket   该用户的连接
     */
    void onUserOnline(String username, Socket socket);

    /**
     * 处理用户下线事件。
     *
     * @param username 下线用户名
     */
    void onUserOffline(String username);

    /**
     * 处理连接关闭：释放资源、清理在线状态。
     *
     * <p>实现方必须保证本方法可被重复调用而不产生副作用，因为异常路径与正常路径都可能触发。</p>
     */
    void onConnectionClosed();
}

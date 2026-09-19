package com.chat.client;

import com.chat.common.Message;

/**
 * 客户端消息监听器接口。
 *
 * <p>职责：把“收到网络消息”这件事以回调形式通知界面层，使网络层完全不依赖 Swing。
 * 每个界面窗口都可以注册自己的监听器，按需处理关心的消息类型。</p>
 *
 * <p>线程约束：回调发生在网络接收线程或发送线程中，
 * 实现方若需要更新界面，必须自行切换到事件分发线程。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public interface ChatListener {

    /**
     * 收到一条来自服务器的消息。
     *
     * @param message 消息对象，永不为 null
     */
    void onMessage(Message message);

    /**
     * 连接状态发生变化。
     *
     * @param connected 当前是否处于连接状态
     * @param reason    状态变化原因，连接正常时为空字符串
     */
    void onConnectionChanged(boolean connected, String reason);
}

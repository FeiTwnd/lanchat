package com.chat.client;

import com.chat.common.Message;

/**
 * 客户端事件回调接口，接收服务器下发的消息与连接状态变化。
 */
public interface ChatListener {
    /**
     * 收到服务器消息时回调。
     *
     * @param message 收到的消息对象
     */
    void onMessage(Message message);

    /**
     * 连接状态变化时回调。
     *
     * @param connected 当前是否已连接
     * @param reason 状态变化的说明文字
     */
    void onConnectionChanged(boolean connected, String reason);
}

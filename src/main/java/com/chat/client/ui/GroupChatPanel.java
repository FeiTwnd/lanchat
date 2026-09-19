package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;

/**
 * 群聊面板：接收群聊文本与系统通知。
 *
 * <p>继承 {@link BaseChatPanel} 复用渲染逻辑，本类只负责群聊特有的过滤条件
 * （接收者为空的群聊消息与系统通知）与发送目标（广播）。</p>
 *
 * <p>与私聊面板的差异被压缩到两个方法内，这是把公共逻辑上移到抽象基类后
 * 带来的直接收益：新增一种聊天场景只需再写一个几十行的子类。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class GroupChatPanel extends BaseChatPanel {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250117L;

    /**
     * 构造群聊面板。
     *
     * @param client 客户端
     */
    public GroupChatPanel(ChatClient client) {
        super(client);
        getInputField().setToolTipText(placeholder());
    }

    /**
     * 发送群聊消息。
     *
     * @param content 正文
     * @return 发送成功返回 true
     */
    @Override
    protected boolean doSend(String content) {
        boolean sent = client.sendGroupText(content);
        if (sent) {
            appendMessage("我", content, COLOR_SELF);
        } else {
            appendLine("[系统] 消息发送失败，请检查网络连接", COLOR_ERROR);
        }
        return sent;
    }

    /**
     * 获取聊天对象名称。
     *
     * @return 固定返回“群聊”
     */
    @Override
    public String getChatTarget() {
        return "群聊";
    }

    /**
     * 接收群聊消息与系统通知。
     *
     * @param message 消息
     * @return 应显示返回 true
     */
    @Override
    public boolean accepts(Message message) {
        if (message.getType() == MessageType.TEXT_GROUP) {
            return true;
        }
        return message.getType() == MessageType.SYSTEM || message.getType() == MessageType.ERROR;
    }

    /**
     * 群聊消息按发送者着色，系统通知使用灰色、错误使用红色。
     *
     * @param message 消息
     */
    @Override
    public void onMessage(Message message) {
        if (!accepts(message)) {
            return;
        }
        if (message.getType() == MessageType.TEXT_GROUP && message instanceof TextMessage) {
            String sender = message.getSender();
            if (sender != null && sender.equals(client.getUsername())) {
                // 自己在发送时已经本地回显（见 doSend），而服务器会把群聊广播回发送者，
                // 这里忽略自己的回显，否则同一条消息会显示两遍
                return;
            }
            appendMessage(sender, ((TextMessage) message).getContent(), COLOR_OTHER);
            return;
        }
        String content = message instanceof SystemMessage
                ? ((SystemMessage) message).getContent() : message.getSummary();
        appendLine("[系统] " + content,
                message.getType() == MessageType.ERROR ? COLOR_ERROR : COLOR_SYSTEM);
    }
}

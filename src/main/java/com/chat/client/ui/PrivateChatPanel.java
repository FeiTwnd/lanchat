package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.TextMessage;

/**
 * 私聊面板：仅处理与指定好友之间的私聊消息。
 *
 * <p>继承 {@link BaseChatPanel} 复用消息渲染与输入发送逻辑，
 * 本类只负责两件差异化的行为：过滤条件（{@link #accepts(Message)}）
 * 与发送目标（{@link #doSend(String)}）。</p>
 *
 * <p>为什么是独立类而不是私有内部类：{@link ClientUI} 需要按对端用户名
 * 复用并操作面板，若定义为私有内部类，其类型对外不可见，
 * 外部代码将无法调用面板自身的公开方法。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class PrivateChatPanel extends BaseChatPanel {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250115L;

    /** 对方用户名 */
    private final String peer;

    /**
     * 构造私聊面板。
     *
     * @param client 客户端
     * @param peer   对方用户名
     */
    public PrivateChatPanel(ChatClient client, String peer) {
        super(client);
        this.peer = peer;
        getInputField().setToolTipText(placeholder());
    }

    /**
     * 获取对方用户名。
     *
     * @return 对方用户名
     */
    public String getPeer() {
        return peer;
    }

    /**
     * 发送私聊消息。
     *
     * @param content 正文
     * @return 发送成功返回 true
     */
    @Override
    protected boolean doSend(String content) {
        boolean sent = client.sendPrivateText(peer, content);
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
     * @return 对方用户名
     */
    @Override
    public String getChatTarget() {
        return peer;
    }

    /**
     * 仅接收“发件人或收件人为本面板好友”的私聊消息。
     *
     * @param message 消息
     * @return 应显示返回 true
     */
    @Override
    public boolean accepts(Message message) {
        if (message.getType() != MessageType.TEXT_PRIVATE) {
            return false;
        }
        return peer.equals(message.getSender()) || peer.equals(message.getReceiver());
    }

    /**
     * 私聊消息按发送者着色渲染。
     *
     * @param message 消息
     */
    @Override
    public void onMessage(Message message) {
        if (!accepts(message)) {
            return;
        }
        String content = message instanceof TextMessage
                ? ((TextMessage) message).getContent() : message.getSummary();
        boolean fromPeer = peer.equals(message.getSender());
        appendMessage(fromPeer ? peer : "我", content, fromPeer ? COLOR_OTHER : COLOR_SELF);
    }
}

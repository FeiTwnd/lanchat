package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Constants;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.TextMessage;

import java.awt.BorderLayout;

/**
 * 私聊窗口。
 *
 * <p>职责：与单个好友一对一聊天。窗口在收到该好友的消息时由
 * {@link ClientUI} 自动创建，无需用户手动双击打开。</p>
 *
 * <p>与群聊窗口的差异仅在于“消息过滤条件”和“发送目标”：
 * 因此本类只需覆写 {@link PrivateChatPanel#accepts(Message)} 与
 * {@link PrivateChatPanel#doSend(String)}。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class PrivateChatUI extends BaseUI {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250114L;

    /** 聊天面板 */
    private final transient PrivateChatPanel panel;

    /**
     * 构造私聊窗口。
     *
     * @param client 客户端实例
     * @param peer   对方用户名
     */
    public PrivateChatUI(ChatClient client, String peer) {
        super("与 " + peer + " 私聊 - " + Constants.APP_NAME);
        this.panel = new PrivateChatPanel(client, peer);
        setLayout(new BorderLayout());
        add(panel, BorderLayout.CENTER);
        panel.appendLine("[系统] 这是与 " + peer + " 的私聊窗口，消息仅双方可见", COLOR_SYSTEM);
        setSize(620, 480);
        centerOnScreen();
    }

    /**
     * 获取聊天面板，供外部投递消息。
     *
     * @return 聊天面板
     */
    public PrivateChatPanel getPanel() {
        return panel;
    }

    /**
     * 获取对方用户名。
     *
     * @return 对方用户名
     */
    public String getPeer() {
        return panel.getPeer();
    }
}

/**
 * 私聊面板：仅处理来自指定好友的私聊消息。
 *
 * <p>定义为包级类而非私有内部类，是为了让 {@link ClientUI} 能直接访问其面板方法
 * （私有内部类会因“类型不可见”导致外部无法调用其特有方法）。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
class PrivateChatPanel extends BaseChatPanel {

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
    PrivateChatPanel(ChatClient client, String peer) {
        super(client);
        this.peer = peer;
        getInputField().setToolTipText(placeholder());
    }

    /**
     * 获取对方用户名。
     *
     * @return 对方用户名
     */
    String getPeer() {
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
     * 仅接收“发件人或收件人为本窗口好友”的私聊消息。
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

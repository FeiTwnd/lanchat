package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Constants;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;

import java.awt.BorderLayout;

/**
 * 群聊窗口。
 *
 * <p>职责：展示所有在线用户都能看到的群聊消息与系统通知
 * （用户上线、下线、服务器公告等）。</p>
 *
 * <p>与私聊的差异：群聊消息的接收者为空字符串，系统通知同样广播，
 * 因此本面板的过滤条件相应放宽。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class GroupChatUI extends BaseUI {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250116L;

    /** 聊天面板 */
    private final transient GroupChatPanel panel;

    /**
     * 构造群聊窗口。
     *
     * @param client 客户端实例
     * @param nickname 当前用户昵称，仅用于标题展示
     */
    public GroupChatUI(ChatClient client, String nickname) {
        super(Constants.APP_NAME + " - 群聊大厅");
        this.panel = new GroupChatPanel(client);
        setLayout(new BorderLayout());
        add(panel, BorderLayout.CENTER);
        panel.appendLine("[系统] 已进入群聊大厅（当前用户: " + nickname + "），发言对所有在线用户可见",
                COLOR_SYSTEM);
        setSize(700, 520);
        centerOnScreen();
    }

    /**
     * 获取群聊面板，供外部投递消息。
     *
     * @return 群聊面板
     */
    public GroupChatPanel getPanel() {
        return panel;
    }
}

/**
 * 群聊面板：接收群聊文本与系统通知。
 *
 * @author Java 课程设计
 * @version 1.0
 */
class GroupChatPanel extends BaseChatPanel {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250117L;

    /**
     * 构造群聊面板。
     *
     * @param client 客户端
     */
    GroupChatPanel(ChatClient client) {
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
     * 群聊消息按发送者着色，系统通知使用灰色。
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
            boolean self = sender != null && sender.equals(client.getUsername());
            appendMessage(self ? "我" : sender, ((TextMessage) message).getContent(),
                    self ? COLOR_SELF : COLOR_OTHER);
            return;
        }
        String content = message instanceof SystemMessage
                ? ((SystemMessage) message).getContent() : message.getSummary();
        appendLine("[系统] " + content,
                message.getType() == MessageType.ERROR ? COLOR_ERROR : COLOR_SYSTEM);
    }
}

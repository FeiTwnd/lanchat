package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Constants;

import java.awt.BorderLayout;

/**
 * 与单个用户的私聊窗口，承载私聊面板。
 */
public class PrivateChatUI extends BaseUI {
    private static final long serialVersionUID = 20250114L;

    private final transient PrivateChatPanel panel;

    /**
     * 创建指定用户的私聊窗口。
     *
     * @param client 已登录的客户端
     * @param peer 私聊对方用户名
     */
    public PrivateChatUI(ChatClient client, String peer) {
        super("与 " + peer + " 私聊 - " + Constants.APP_NAME);
        this.panel = new PrivateChatPanel(client, peer);
        body().setLayout(new BorderLayout());
        body().add(panel, BorderLayout.CENTER);
        panel.appendLine("[系统] 这是与 " + peer + " 的私聊窗口，消息仅双方可见", COLOR_SYSTEM);
        setSize(620, 480);
        centerOnScreen();
    }

    public PrivateChatPanel getPanel() {
        return panel;
    }

    public String getPeer() {
        return panel.getPeer();
    }
}

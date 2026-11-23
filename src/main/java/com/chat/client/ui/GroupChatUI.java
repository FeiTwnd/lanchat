package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Constants;

import java.awt.BorderLayout;
import java.util.List;
import java.util.function.Supplier;

/**
 * 群聊大厅窗口，承载群聊面板并在进入时显示系统提示。
 */
public class GroupChatUI extends BaseUI {
    private static final long serialVersionUID = 20250116L;

    private final transient GroupChatPanel panel;

    /**
     * 创建群聊窗口。
     *
     * @param client 已登录的客户端
     * @param nickname 当前用户昵称
     * @param onlineUsers 在线用户名列表的来源
     */
    public GroupChatUI(ChatClient client, String nickname, Supplier<List<String>> onlineUsers) {
        super(Constants.APP_NAME + " - 群聊大厅");
        this.panel = new GroupChatPanel(client, onlineUsers);
        body().setLayout(new BorderLayout());
        body().add(panel, BorderLayout.CENTER);
        panel.appendLine("[系统] 已进入群聊大厅（当前用户: " + nickname + "），发言对所有在线用户可见",
                COLOR_SYSTEM);
        setSize(700, 520);
        centerOnScreen();
    }

    public GroupChatPanel getPanel() {
        return panel;
    }
}

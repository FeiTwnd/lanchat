package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Constants;

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
        body().setLayout(new BorderLayout());
        body().add(panel, BorderLayout.CENTER);
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

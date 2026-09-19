package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Constants;

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

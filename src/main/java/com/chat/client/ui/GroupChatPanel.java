package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Constants;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;

import java.awt.Toolkit;
import java.io.File;
import java.util.List;
import java.util.function.Supplier;

/**
 * 群聊消息面板，负责发送群消息、群发文件以及接收并展示群聊消息。
 */
public class GroupChatPanel extends BaseChatPanel {
    private static final long serialVersionUID = 20250117L;

    /** 当前在线用户名列表的来源。 */
    private final transient Supplier<List<String>> onlineUsers;

    /**
     * 创建群聊面板。
     *
     * @param client 已登录的客户端
     * @param onlineUsers 在线用户名列表的来源
     */
    public GroupChatPanel(ChatClient client, Supplier<List<String>> onlineUsers) {
        super(client);
        this.onlineUsers = onlineUsers;
    }

    /**
     * 发送一条群聊文本，成功后在消息区回显。
     *
     * @param content 消息正文
     * @return 是否发送成功
     */
    @Override
    protected boolean doSend(String content) {
        // 发送群消息并取回服务端分配的消息编号
        String messageId = client.sendGroupTextReturningId(content, null, null);
        if (messageId != null) {
            appendMessage("我", content, COLOR_SELF, messageId);
        } else {
            appendLine("[系统] 消息发送失败，请检查网络连接", COLOR_ERROR);
        }
        return messageId != null;
    }

    /**
     * 判断除自己之外是否还有在线用户。
     *
     * @return 存在其他在线用户时返回 true
     */
    @Override
    protected boolean hasSendTarget() {
        return onlineUsers.get().stream().anyMatch(name -> !name.equals(client.getUsername()));
    }

    /**
     * 向所有其他在线用户发起文件传输。
     *
     * @param file 待发送的文件
     */
    @Override
    protected void sendFileTo(File file) {
        // 收集除自己之外的在线用户
        List<String> targets = onlineUsers.get().stream()
                .filter(name -> !name.equals(client.getUsername()))
                .toList();

        if (targets.isEmpty()) {
            appendLine("[系统] " + noTargetReason(), COLOR_ERROR);
            return;
        }
        if (!askConfirm("将把文件 " + file.getName() + " 发送给当前在线的 "
                + targets.size() + " 位用户：\n" + String.join("、", targets) + "\n\n是否继续？")) {
            return;
        }
        // 逐个用户打开传输窗口并直接发送
        for (String target : targets) {
            FileTransferUI window = FileTransferUI.windowFor(client, target);
            window.setVisible(true);
            window.sendNow(file);
        }
        appendLine("[系统] 已向 " + targets.size() + " 位在线用户发起文件传输：" + file.getName()
                + "（" + String.join("、", targets) + "，各自确认后开始传输）", COLOR_SYSTEM);
    }

    /**
     * 返回可以 @ 提及的在线用户名列表。
     *
     * @return 除自己以外的非空用户名
     */
    @Override
    protected List<String> mentionCandidates() {
        return onlineUsers.get().stream()
                .filter(name -> name != null && !name.isEmpty())
                .filter(name -> !name.equals(client.getUsername()))
                .toList();
    }

    @Override
    protected boolean isQuoteSupported() {
        return true;
    }

    /**
     * 发送带引用信息的群聊消息。
     *
     * @param content 消息正文
     * @param quoteId 被引用消息的编号
     * @param quoteSummary 被引用消息的摘要
     * @return 是否发送成功
     */
    @Override
    protected boolean sendQuoted(String content, String quoteId, String quoteSummary) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        if (content.length() > Constants.MESSAGE_MAX_LENGTH) {
            appendLine("[系统] 消息过长，最多 " + Constants.MESSAGE_MAX_LENGTH + " 个字符", COLOR_ERROR);
            return false;
        }
        String quoteSender = getPendingQuoteSender();
        String messageId = client.sendGroupTextReturningId(content, quoteId, quoteSummary, quoteSender);
        if (messageId != null) {
            appendMessage("我", content, COLOR_SELF, messageId, quoteSummary, quoteSender,
                    client.getUsername());
        } else {
            appendLine("[系统] 消息发送失败，请检查网络连接", COLOR_ERROR);
        }
        return messageId != null;
    }

    /**
     * 判断文本中是否 @ 了指定用户名，用户名之后不能紧跟名称字符。
     *
     * @param username 用户名
     * @param content 消息正文
     * @return 命中提及时返回 true
     */
    static boolean mentions(String username, String content) {
        if (username == null || username.isEmpty() || content == null) {
            return false;
        }
        String token = "@" + username;
        int from = 0;
        while (true) {
            int index = content.indexOf(token, from);
            if (index < 0) {
                return false;
            }
            int end = index + token.length();
            if (end >= content.length() || !isNameChar(content.charAt(end))) {
                return true;
            }
            from = end;
        }
    }

    private static boolean isNameChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    @Override
    public String getChatTarget() {
        return "群聊";
    }

    /**
     * 判断消息是否属于群聊面板。
     *
     * @param message 待判断的消息
     * @return 群聊文本、系统消息或错误消息返回 true
     */
    @Override
    public boolean accepts(Message message) {
        if (message.getType() == MessageType.TEXT_GROUP) {
            return true;
        }
        return message.getType() == MessageType.SYSTEM || message.getType() == MessageType.ERROR;
    }

    /**
     * 处理服务端推送的群聊、系统与错误消息。
     *
     * @param message 收到的消息
     */
    @Override
    public void onMessage(Message message) {
        if (!accepts(message)) {
            return;
        }
        if (message.getType() == MessageType.TEXT_GROUP && message instanceof TextMessage) {
            String sender = message.getSender();
            if (sender != null && sender.equals(client.getUsername())) {
                // 跳过自己发出的群聊消息
                return;
            }
            String content = ((TextMessage) message).getContent();
            boolean mention = mentions(client.getUsername(), content);
            if (mention) {
                // 被 @ 时发出提示音
                Toolkit.getDefaultToolkit().beep();
            }
            appendIncoming(sender, content, COLOR_OTHER, message, mention);
            return;
        }
        String content = message instanceof SystemMessage
                ? ((SystemMessage) message).getContent() : message.getSummary();
        appendLine("[系统] " + content,
                message.getType() == MessageType.ERROR ? COLOR_ERROR : COLOR_SYSTEM);
    }
}

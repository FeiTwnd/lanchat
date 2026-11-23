package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.TextMessage;
import com.chat.util.DateUtil;

/**
 * 私聊消息面板，负责与指定用户之间的消息收发、文件传输和聊天记录加载。
 */
public class PrivateChatPanel extends BaseChatPanel {
    private static final long serialVersionUID = 20250115L;

    private final String peer;

    /**
     * 创建与指定用户的私聊面板。
     *
     * @param client 已登录的客户端
     * @param peer 私聊对方用户名
     */
    public PrivateChatPanel(ChatClient client, String peer) {
        super(client);
        this.peer = peer;
    }

    public String getPeer() {
        return peer;
    }

    /**
     * 发送一条私聊文本，成功后在消息区回显。
     *
     * @param content 消息正文
     * @return 是否发送成功
     */
    @Override
    protected boolean doSend(String content) {
        // 发送私聊消息并取回服务端分配的消息编号
        String messageId = client.sendPrivateTextReturningId(peer, content, null, null);
        if (messageId != null) {
            appendMessage("我", content, COLOR_SELF, messageId);
        } else {
            appendLine("[系统] 消息发送失败，请检查网络连接", COLOR_ERROR);
        }
        return messageId != null;
    }

    /**
     * 向私聊对方发起文件传输。
     *
     * @param file 待发送的文件
     */
    @Override
    protected void sendFileTo(java.io.File file) {
        // 打开与该用户的传输窗口并立即发送
        FileTransferUI window = FileTransferUI.windowFor(client, peer);
        window.setVisible(true);
        window.sendNow(file);
        appendLine("[系统] 已向 " + peer + " 发起文件传输：" + file.getName() + (isSelfChat()
                ? "（发给自己，自动接收）"
                : "（" + peer + " 确认后开始传输）"), COLOR_SYSTEM);
    }

    /**
     * 判断文件发送目标是否可用，自己与自己私聊时始终可发。
     *
     * @return 可发送时返回 true
     */
    @Override
    protected boolean hasSendTarget() {
        return isSelfChat() || super.hasSendTarget();
    }

    /**
     * 返回文件无法发送时的提示文本。
     *
     * @return 提示文本
     */
    @Override
    protected String noTargetReason() {
        return peer + " 当前不在线，无法发送文件（消息仍可发出，对方下次上线后会收到）";
    }

    private boolean isSelfChat() {
        String self = client.getUsername();
        return self != null && self.equals(peer);
    }

    /**
     * 加载并显示聊天记录。
     *
     * @param records 每行依次为时间、发送者、接收者、消息编号和正文
     * @return 实际显示的记录条数
     */
    public int fillHistory(java.util.List<String[]> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        boolean selfChat = isSelfChat();
        java.util.List<HistoryEntry> history = new java.util.ArrayList<>(records.size());
        for (String[] record : records) {
            if (record.length < 5) {
                continue;
            }
            boolean fromPeer = peer.equals(record[1]);

            // 私聊自己或发送者不是对方时，记为本人发出
            boolean mine = selfChat || !fromPeer;
            history.add(new HistoryEntry(mine ? "我" : peer, record[4], mine,
                    DateUtil.parse(record[0]), record[3]));
        }

        int rendered = prependHistory(history);
        if (rendered > 0) {
            prependLine("[系统] 以上为最近 " + rendered + " 条历史记录", COLOR_SYSTEM);
        }
        return rendered;
    }

    @Override
    protected boolean isQuoteSupported() {
        return true;
    }

    /**
     * 发送带引用信息的私聊消息。
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
        if (content.length() > com.chat.common.Constants.MESSAGE_MAX_LENGTH) {
            appendLine("[系统] 消息过长，最多 " + com.chat.common.Constants.MESSAGE_MAX_LENGTH + " 个字符",
                    COLOR_ERROR);
            return false;
        }
        String quoteSender = getPendingQuoteSender();
        String messageId = client.sendPrivateTextReturningId(peer, content, quoteId, quoteSummary,
                quoteSender);
        if (messageId != null) {
            appendMessage("我", content, COLOR_SELF, messageId, quoteSummary, quoteSender,
                    client.getUsername());
        } else {
            appendLine("[系统] 消息发送失败，请检查网络连接", COLOR_ERROR);
        }
        return messageId != null;
    }

    @Override
    public String getChatTarget() {
        return peer;
    }

    /**
     * 判断消息是否属于当前私聊会话。
     *
     * @param message 待判断的消息
     * @return 发送者或接收者为私聊对方时返回 true
     */
    @Override
    public boolean accepts(Message message) {
        if (message.getType() != MessageType.TEXT_PRIVATE) {
            return false;
        }
        return peer.equals(message.getSender()) || peer.equals(message.getReceiver());
    }

    /**
     * 处理属于当前私聊会话的消息。
     *
     * @param message 收到的消息
     */
    @Override
    public void onMessage(Message message) {
        if (!accepts(message)) {
            return;
        }
        String content = message instanceof TextMessage
                ? ((TextMessage) message).getContent() : message.getSummary();
        boolean fromPeer = peer.equals(message.getSender());

        appendIncoming(fromPeer ? peer : "我", content,
                fromPeer ? COLOR_OTHER : COLOR_SELF, message, false);
    }
}

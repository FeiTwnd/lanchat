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
 * 群聊面板：接收群聊文本与系统通知，并支持把文件群发给所有在线用户。
 *
 * <p>继承 {@link BaseChatPanel} 复用渲染逻辑，本类只负责群聊特有的过滤条件
 * （接收者为空的群聊消息与系统通知）与发送目标（广播）。</p>
 *
 * <p>群发文件的接收方列表由外部注入（{@link Supplier}）而不是本类自己去查：
 * 在线名单属于主窗口的界面状态，面板只需要"点下去的那一刻有哪些人在线"这一份数据，
 * 注入比反向依赖主窗口或 JTree 更简单，也让面板可以脱离主窗口单独实例化。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class GroupChatPanel extends BaseChatPanel {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250117L;

    /** 在线用户名提供者，取值为当前在线用户的用户名列表 */
    private final transient Supplier<List<String>> onlineUsers;

    /**
     * 构造群聊面板。
     *
     * @param client      客户端
     * @param onlineUsers 在线用户名提供者，用于群发文件
     */
    public GroupChatPanel(ChatClient client, Supplier<List<String>> onlineUsers) {
        super(client);
        this.onlineUsers = onlineUsers;
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
     * 群发文件：向当前在线且不是自己的每个用户各发起一次独立的文件传输。
     *
     * <p>本项目没有"群"实体，群聊本身就是全员广播，因此群文件的语义就是逐个发给每个在线用户：
     * 每个接收方各自确认、各自显示进度，一个人拒收不影响其他人。</p>
     *
     * @param file 待发送文件
     */
    @Override
    protected void sendFileTo(File file) {
        List<String> targets = onlineUsers.get().stream()
                .filter(name -> !name.equals(client.getUsername()))
                .toList();
        if (targets.isEmpty()) {
            appendLine("[系统] 当前没有其他在线用户，无法发送文件", COLOR_ERROR);
            return;
        }
        if (!askConfirm("将把文件 " + file.getName() + " 发送给当前在线的 "
                + targets.size() + " 位用户：\n" + String.join("、", targets) + "\n\n是否继续？")) {
            return;
        }
        for (String target : targets) {
            FileTransferUI window = FileTransferUI.windowFor(client, target);
            window.setVisible(true);
            window.sendNow(file);
        }
        appendLine("[系统] 已向 " + targets.size() + " 位在线用户发起文件传输：" + file.getName()
                + "（" + String.join("、", targets) + "，各自确认后开始传输）", COLOR_SYSTEM);
    }

    /**
     * @ 补全候选：当前在线的其他用户。
     *
     * <p>排除自己：给自己 @ 提醒没有意义，留在候选里只会增加误选概率。</p>
     *
     * @return 在线用户名列表
     */
    @Override
    protected List<String> mentionCandidates() {
        return onlineUsers.get().stream()
                .filter(name -> name != null && !name.isEmpty())
                .filter(name -> !name.equals(client.getUsername()))
                .toList();
    }

    /**
     * 群聊支持引用发送。
     *
     * @return 固定返回 true
     */
    @Override
    protected boolean isQuoteSupported() {
        return true;
    }

    /**
     * 发送带引用的群聊消息。
     *
     * <p>群聊本来就不做发送确认（见 {@code ChatClient.sendGroupText}），
     * 因此这里直接用带引用参数的文本消息走同一条广播通道，不需要绕开任何重发机制。</p>
     *
     * @param content      正文
     * @param quoteId      被引用消息的稳定标识
     * @param quoteSummary 被引用消息的摘要
     * @return 发送成功返回 true
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
        TextMessage message = new TextMessage(client.getUsername(), "", content, quoteId, quoteSummary);
        message.setType(MessageType.TEXT_GROUP);
        message.ensureMessageId();
        boolean sent = client.send(message);
        if (sent) {
            appendMessage("我", content, COLOR_SELF);
        } else {
            appendLine("[系统] 消息发送失败，请检查网络连接", COLOR_ERROR);
        }
        return sent;
    }

    /**
     * 判断正文是否 @ 了指定用户。
     *
     * <p>纯函数。必须要求名字后面是边界：否则 {@code @alice} 会在 {@code @alice2} 中被误判成提醒，
     * 把提示音发给一个根本没被点名的人。</p>
     *
     * @param username 被判断的用户名
     * @param content  消息正文
     * @return 被提及返回 true
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

    /**
     * 判断字符是否可作为用户名的一部分。
     *
     * @param value 字符
     * @return 是字母、数字或下划线返回 true
     */
    private static boolean isNameChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
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
            String content = ((TextMessage) message).getContent();
            boolean mention = mentions(client.getUsername(), content);
            if (mention) {
                // 被点名的消息用系统提示音提醒：不引入音频素材，只用标准库的蜂鸣
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

package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.TextMessage;
import com.chat.util.DateUtil;

/**
 * 私聊面板：仅处理与指定好友之间的私聊消息。
 *
 * <p>继承 {@link BaseChatPanel} 复用消息渲染与输入发送逻辑，
 * 本类只负责三件差异化的行为：过滤条件（{@link #accepts(Message)}）、
 * 发送目标（{@link #doSend(String)}）与文件接收方（{@link #sendFileTo(java.io.File)}）。</p>
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
     * 发送文件：接收方固定为当前私聊窗口的对端。
     *
     * @param file 待发送文件
     */
    @Override
    protected void sendFileTo(java.io.File file) {
        FileTransferUI window = FileTransferUI.windowFor(client, peer);
        window.setVisible(true);
        window.sendNow(file);
        appendLine("[系统] 已向 " + peer + " 发起文件传输：" + file.getName() + "（" + peer
                + " 确认后开始传输）", COLOR_SYSTEM);
    }

    /**
     * 用服务器返回的历史记录填充聊天区。
     *
     * <p>只在窗口刚创建时调用一次，用于补齐"关闭窗口再打开"或"重启客户端"之后
     * 看不到此前对话的问题。已经由实时通道渲染过的消息会被跳过，避免同一条消息出现两次。</p>
     *
     * <p>解析出的时间交给基类按"今天/昨天/本年/跨年"规则显示：历史记录可能来自昨天甚至去年，
     * 只显示时分会让使用者误以为是很久以前的当天消息。</p>
     *
     * @param records 历史记录，按时间升序，每项为 {@code {时间, 发送者用户名, 正文}}
     * @return 实际渲染的条数
     */
    public int fillHistory(java.util.List<String[]> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        java.util.List<HistoryEntry> history = new java.util.ArrayList<>(records.size());
        for (String[] record : records) {
            if (record.length < 3) {
                continue;
            }
            boolean fromPeer = peer.equals(record[1]);
            history.add(new HistoryEntry(fromPeer ? peer : "我", record[2], !fromPeer,
                    DateUtil.parse(record[0])));
        }
        // 基类按时间升序插到列表最前面，并负责跳过已经渲染过的消息
        int rendered = prependHistory(history);
        if (rendered > 0) {
            prependLine("[系统] 以上为最近 " + rendered + " 条历史记录", COLOR_SYSTEM);
        }
        return rendered;
    }

    /**
     * 私聊当前不支持引用发送，因此不提供"引用"右键菜单。
     *
     * <p>引用信息必须随消息一起上线才能被对端识别为结构化引用。私聊的发送入口
     * {@code ChatClient.sendPrivateText(String, String)} 目前不接受引用参数，
     * 而私聊消息依赖客户端内部的重发与状态登记，绕过它自行发送会让"发送中/已送达"
     * 状态与超时重发全部失效，因此这里选择不支持，而不是偷偷降级成纯文本。
     * 收到对端带引用的消息仍然可以正常渲染引用块（见 {@link #onMessage(Message)}）。</p>
     *
     * @return 固定返回 false
     */
    @Override
    protected boolean isQuoteSupported() {
        return false;
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
        // 交给基类渲染：对端若带引用信息，气泡里会显示引用块
        appendIncoming(fromPeer ? peer : "我", content,
                fromPeer ? COLOR_OTHER : COLOR_SELF, message, false);
    }
}

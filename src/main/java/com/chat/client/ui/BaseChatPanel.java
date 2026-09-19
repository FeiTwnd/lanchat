package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.common.Constants;
import com.chat.common.Message;
import com.chat.util.DateUtil;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.time.LocalDateTime;

/**
 * 聊天面板抽象基类。
 *
 * <p>职责：统一实现“消息渲染 + 输入发送”这一聊天窗口的公共部分，
 * 私聊窗口与群聊窗口只需提供标题与发送策略即可复用全部界面逻辑。</p>
 *
 * <p>为什么用 {@code JTextPane} 而不是 {@code JTextArea}：聊天记录需要按消息来源
 * 使用不同颜色（本人、他人、系统通知）区分，只有 {@code StyledDocument} 才能做到
 * 同一控件内的差异化着色。</p>
 *
 * <p>继承价值：真正把“共性上移、差异下移”落到实处，
 * 避免私聊与群聊两份几乎一模一样的界面代码。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public abstract class BaseChatPanel extends JPanel implements ChatListener {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250113L;

    /** 界面统一字体，与 BaseUI 保持一致；本类继承 JPanel 而非 BaseUI，故需自行定义 */
    protected static final java.awt.Font FONT_NORMAL = new java.awt.Font("Microsoft YaHei",
            java.awt.Font.PLAIN, 13);

    /** 本人消息颜色 */
    protected static final Color COLOR_SELF = new Color(0x1E88E5);

    /** 他人消息颜色 */
    protected static final Color COLOR_OTHER = new Color(0x2E7D32);

    /** 系统通知颜色 */
    protected static final Color COLOR_SYSTEM = new Color(0x9E9E9E);

    /** 错误提示颜色 */
    protected static final Color COLOR_ERROR = new Color(0xC62828);

    /** 聊天记录显示区，支持富文本着色 */
    protected final javax.swing.JTextPane historyPane = new javax.swing.JTextPane();

    /** 输入框 */
    protected final JTextField inputField = new JTextField();

    /** 发送按钮 */
    protected final JButton sendButton = new JButton("发送");

    /** 清空按钮 */
    protected final JButton clearButton = new JButton("清空");

    /** 客户端引用 */
    protected final transient ChatClient client;

    /**
     * 构造聊天面板。
     *
     * @param client 客户端实例，供子类发送消息
     */
    protected BaseChatPanel(ChatClient client) {
        this.client = client;
        setLayout(new BorderLayout(4, 4));
        initHistoryPane();
        add(buildHistoryScroll(), BorderLayout.CENTER);
        add(buildInputPanel(), BorderLayout.SOUTH);
    }

    /**
     * 初始化聊天记录显示区。
     */
    private void initHistoryPane() {
        historyPane.setEditable(false);
        historyPane.setFont(FONT_NORMAL);
        historyPane.setBackground(Color.WHITE);
    }

    /**
     * 构建带滚动条的记录区。
     *
     * @return 滚动面板
     */
    private JScrollPane buildHistoryScroll() {
        JScrollPane scrollPane = new JScrollPane(historyPane);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setPreferredSize(new Dimension(560, 360));
        return scrollPane;
    }

    /**
     * 构建输入区。
     *
     * @return 输入面板
     */
    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(inputField, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(sendButton);
        buttons.add(clearButton);
        panel.add(buttons, BorderLayout.EAST);
        ActionListener sendAction = e -> sendCurrentInput();
        sendButton.addActionListener(sendAction);
        inputField.addActionListener(sendAction);
        clearButton.addActionListener(e -> clearHistory());
        return panel;
    }

    /**
     * 发送输入框中的内容。
     *
     * <p>空内容直接忽略，避免产生无意义的空消息占用带宽与聊天记录。</p>
     */
    protected void sendCurrentInput() {
        String text = inputField.getText();
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (doSend(text.trim())) {
            inputField.setText("");
        }
    }

    /**
     * 执行实际发送，由子类决定是私聊还是群聊。
     *
     * @param content 已去除首尾空白的正文
     * @return 发送成功返回 true
     */
    protected abstract boolean doSend(String content);

    /**
     * 获取输入框，供子类扩展（例如添加发送文件按钮）。
     *
     * @return 输入框组件
     */
    protected JTextField getInputField() {
        return inputField;
    }

    /**
     * 获取按钮所在面板的便捷方法，供子类追加自定义按钮。
     *
     * @return 南侧面板
     */
    protected JPanel southPanel() {
        return (JPanel) getComponent(getComponentCount() - 1);
    }

    /**
     * 向聊天记录追加一条普通文本。
     *
     * @param text  文本内容
     * @param color 文本颜色
     */
    public void appendLine(String text, Color color) {
        StyledDocument document = historyPane.getStyledDocument();
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, color);
        StyleConstants.setFontFamily(attributes, FONT_NORMAL.getFamily());
        try {
            document.insertString(document.getLength(), text + System.lineSeparator(), attributes);
            historyPane.setCaretPosition(document.getLength());
        } catch (BadLocationException e) {
            // 插入位置由文档长度决定，理论上不会越界；此处仅记录以免吞掉异常
            System.err.println("聊天记录插入失败: " + e.getMessage());
        }
    }

    /**
     * 追加一条带时间戳的消息。
     *
     * @param prefix  前缀（发送者或提示）
     * @param content 正文
     * @param color   颜色
     */
    public void appendMessage(String prefix, String content, Color color) {
        appendLine("[" + DateUtil.formatTime(LocalDateTime.now()) + "] " + prefix + ": " + content, color);
    }

    /**
     * 清空聊天记录显示区。
     */
    public void clearHistory() {
        historyPane.setText("");
    }

    /**
     * 获取窗口标题中使用的标识，由子类提供（如对方用户名或“群聊”）。
     *
     * @return 聊天对象名称
     */
    public abstract String getChatTarget();

    /**
     * 判断某个消息是否应由本窗口显示。
     *
     * @param message 消息
     * @return 应显示返回 true
     */
    public abstract boolean accepts(Message message);

    /**
     * 连接状态变化时的界面提示。
     *
     * @param connected 是否连接
     * @param reason    原因
     */
    @Override
    public void onConnectionChanged(boolean connected, String reason) {
        if (!connected) {
            appendLine("[系统] " + reason, new Color(0xC62828));
        }
    }

    /**
     * 默认的消息处理：把消息摘要以系统颜色追加，子类可覆写以获得更精细的渲染。
     *
     * @param message 消息
     */
    @Override
    public void onMessage(Message message) {
        if (accepts(message)) {
            appendMessage(message.getSender(), message.getSummary(), new Color(0x424242));
        }
    }

    /**
     * 生成界面统一的占位提示文本。
     *
     * @return 提示文本
     */
    protected String placeholder() {
        return "输入消息后回车或点击发送（" + Constants.APP_NAME + "）";
    }
}

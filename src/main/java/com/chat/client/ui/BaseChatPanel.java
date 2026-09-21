package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.client.ui.theme.Glyphs;
import com.chat.client.ui.theme.SkinButton;
import com.chat.client.ui.theme.Theme;
import com.chat.common.Constants;
import com.chat.common.Message;
import com.chat.common.TextMessage;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.AttributedCharacterIterator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天面板抽象基类。
 *
 * <p>职责：统一实现"消息渲染 + 输入发送 + 发送文件"这一聊天窗口的公共部分，
 * 私聊窗口与群聊窗口只需提供标题、发送目标与文件接收方策略即可复用全部界面逻辑。</p>
 *
 * <p>渲染模型：聊天记录由一个个 {@link MessageBubble} 组成，容器是纵向可滚动的消息列表。
 * 相比旧的"单个富文本区按行拼接"，气泡方案让"谁说的、什么时候、正文是什么"各自占据
 * 独立的组件，对齐方式与配色都变成组件属性，而不是靠字符串拼接和行内着色模拟。</p>
 *
 * <p>时间线策略：每条消息自带时间，但只在与上一条消息间隔超过 5 分钟时插入一条居中的
 * 时间分隔行，避免逐条重复时间造成视觉噪音。</p>
 *
 * <p>滚动策略：只有"插入前已经贴着底部"时才自动滚到底部。用户主动向上翻看历史时，
 * 新消息到达不会把他强行拉回底部。</p>
 *
 * <p>继承价值：真正把"共性上移、差异下移"落到实处，
 * 避免私聊与群聊两份几乎一模一样的界面代码。</p>
 *
 * @author Java 课程设计
 * @version 2.0
 */
public abstract class BaseChatPanel extends JPanel implements ChatListener {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250113L;

    /** 界面统一字体，保留给子类使用；新代码优先使用 {@link Theme#fontBase()} 以跟随主题 */
    protected static final Font FONT_NORMAL = new Font("Microsoft YaHei", Font.PLAIN, 13);

    /** 本人消息颜色 */
    protected static final Color COLOR_SELF = new Color(0x1E88E5);

    /** 他人消息颜色 */
    protected static final Color COLOR_OTHER = new Color(0x2E7D32);

    /** 系统通知颜色 */
    protected static final Color COLOR_SYSTEM = new Color(0x9E9E9E);

    /** 错误提示颜色 */
    protected static final Color COLOR_ERROR = new Color(0xC62828);

    /** 相邻消息时间间隔超过该值才插入时间分隔行 */
    private static final Duration TIME_SEPARATOR_GAP = Duration.ofMinutes(5);

    /** 输入区可见行数上限，超出部分交给滚动条 */
    private static final int MAX_INPUT_ROWS = 6;

    /** 消息列表宽度尚不可知时的兜底宽度，仅影响首帧气泡测量 */
    private static final int FALLBACK_LIST_WIDTH = 520;

    /** 判定"滚动条已经贴底"的像素容差，避免因取整误差漏判 */
    private static final int BOTTOM_TOLERANCE = 24;

    /**
     * 组合输入刚结束后忽略回车的时间窗（毫秒）。
     *
     * <p>部分平台的输入法在选词上屏之后才把这次回车键交给控件，此时组合状态已经结束，
     * 只能用一个极短的时间窗把紧接着到来的回车识别为"选词确认"而非"发送"。
     * 取值刻意很小，正常输入节奏下的"上屏后立刻回车发送"不会被误伤。</p>
     */
    private static final long COMPOSITION_GUARD_MILLIS = 80L;

    /** 消息行之间的垂直间距 */
    private static final int ROW_GAP = 8;

    /** 空状态卡片名 */
    private static final String CARD_EMPTY = "empty";

    /** 消息列表卡片名 */
    private static final String CARD_LIST = "list";

    /** 发送动作名 */
    private static final String ACTION_SEND = "lanchat-send";

    /** 换行动作名 */
    private static final String ACTION_NEWLINE = "lanchat-newline";

    /** @ 补全上移动作名 */
    private static final String ACTION_MENTION_UP = "lanchat-mention-up";

    /** @ 补全下移动作名 */
    private static final String ACTION_MENTION_DOWN = "lanchat-mention-down";

    /** @ 补全接受动作名 */
    private static final String ACTION_MENTION_TAB = "lanchat-mention-tab";

    /** @ 补全关闭动作名 */
    private static final String ACTION_MENTION_HIDE = "lanchat-mention-hide";

    /** 引用摘要的最大长度，超出部分截断，避免引用条把输入区挤成一行 */
    private static final int QUOTE_SUMMARY_LIMIT = 40;

    /** @ 补全候选窗口的宽度 */
    private static final int MENTION_WIDTH = 150;

    /** @ 补全候选窗口的可见行数 */
    private static final int MENTION_ROWS = 6;

    /** @ 补全候选的最大条数 */
    private static final int MENTION_MAX = 12;

    /** 表情面板的列数 */
    private static final int EMOJI_COLUMNS = 6;

    /** 截图临时文件所在目录名 */
    private static final String SHOT_DIR = "lanchat-shots";

    /**
     * 表情面板内容。
     *
     * <p>只用 Unicode 字符，不引入任何图片素材：这样既不需要资源文件，
     * 也不会把第三方表情包带进课程设计。渲染效果取决于系统是否安装了彩色表情字体。</p>
     */
    private static final String[] EMOJIS = {
            "😀", "😄", "😁", "😆", "😅", "😂",
            "🙂", "😉", "😊", "😍", "😘", "😜",
            "🤔", "😐", "😴", "😢", "😭", "😡",
            "👍", "👎", "👌", "🙏", "💪", "👏",
            "🎉", "🔥", "⭐", "✅", "❌", "❤"};

    /** 输入框，多行、自动换行，高度在 1 到 6 行之间自适应 */
    protected final JTextArea inputArea = new JTextArea(1, 20);

    /** 发送按钮 */
    protected final JButton sendButton = new SkinButton("发送", SkinButton.Kind.PRIMARY);

    /** 发送文件按钮：接收方由当前聊天窗口决定，因此不需要使用者再去选人 */
    protected final JButton fileButton = new SkinButton("发送文件", SkinButton.Kind.NORMAL);

    /** 清空按钮 */
    protected final JButton clearButton = new SkinButton("清空", SkinButton.Kind.NORMAL);

    /** 剪贴板图片发送按钮 */
    protected final JButton imageButton = new SkinButton("图片", SkinButton.Kind.NORMAL);

    /** 表情按钮 */
    protected final JButton emojiButton = new SkinButton("表情", SkinButton.Kind.NORMAL);

    /** 客户端引用 */
    protected final transient ChatClient client;

    /** 聊天记录滚动面板 */
    private final JScrollPane historyScroll;

    /** 输入区面板，同时作为 {@link #southPanel()} 的返回值 */
    private final JPanel inputPanel;

    /** 消息列表容器 */
    private final MessageList messageList = new MessageList();

    /** 空状态与消息列表之间的卡片布局 */
    private final CardLayout viewCards = new CardLayout();

    /** 承载空状态与消息列表的容器，直接作为滚动面板的视图 */
    private final JPanel viewportHost = new CardHost(viewCards);

    /** 消息条目模型，用于补拉历史时整体重建列表 */
    private final List<Entry> entries = new ArrayList<>();

    /**
     * 已渲染消息的指纹集合。
     *
     * <p>用并发集合是因为它可能被网络线程（实时消息）与事件分发线程（补拉历史）同时访问：
     * 前者在事件分发线程渲染之前就要写入，后者读取它来判断某条历史是否已经显示过。</p>
     */
    private final Set<String> renderedKeys = ConcurrentHashMap.newKeySet();

    /** 上一条消息的时间，用于判断是否需要插入时间分隔行 */
    private LocalDateTime lastMessageTime;

    /** 引用条，显示待发送的引用摘要 */
    private final JPanel quoteBar;

    /** 引用条上的摘要文本 */
    private final JLabel quoteLabel;

    /** @ 补全候选窗口 */
    private final JPopupMenu mentionPopup = new JPopupMenu();

    /** @ 补全候选列表 */
    private final JList<String> mentionList = new JList<>();

    /** 待发送引用的被引用消息标识，为 null 表示当前没有引用 */
    private String pendingQuoteId;

    /** 待发送引用的摘要 */
    private String pendingQuoteSummary;

    /** 输入法是否正处于候选组合中 */
    private boolean composing;

    /** 最近一次组合结束（上屏）的时刻 */
    private long compositionEndedAt;

    /**
     * 构造聊天面板。
     *
     * @param client 客户端实例，供子类发送消息
     */
    protected BaseChatPanel(ChatClient client) {
        this.client = client;
        setLayout(new BorderLayout(4, 4));
        setBackground(Theme.CARD);
        this.historyScroll = buildHistoryScroll();
        add(historyScroll, BorderLayout.CENTER);
        this.quoteLabel = new JLabel();
        this.quoteBar = buildQuoteBar(quoteLabel);
        this.inputPanel = buildInputPanel();
        add(inputPanel, BorderLayout.SOUTH);
        installFileDrop();
        updateEmptyState();
    }

    /**
     * 构建聊天记录区：空状态与消息列表共用同一个滚动视口。
     *
     * @return 滚动面板
     */
    private JScrollPane buildHistoryScroll() {
        viewportHost.setBackground(Theme.BG);
        viewportHost.add(buildEmptyPanel(), CARD_EMPTY);
        viewportHost.add(messageList, CARD_LIST);

        JScrollPane scrollPane = new JScrollPane(viewportHost);
        scrollPane.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setPreferredSize(new Dimension(560, 360));
        return scrollPane;
    }

    /**
     * 构建空状态占位面板。
     *
     * <p>用 GridBagLayout 包一个标签即可实现水平垂直双向居中，不需要手工计算坐标；
     * 该面板只在消息列表为空时显示。</p>
     *
     * @return 空状态面板
     */
    private JPanel buildEmptyPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Theme.BG);
        JLabel hint = new JLabel("还没有聊天记录，发送第一条消息吧");
        hint.setFont(Theme.fontBase());
        hint.setForeground(Theme.TEXT_WEAK);
        panel.add(hint);
        return panel;
    }

    /**
     * 构建输入区。
     *
     * @return 输入面板
     */
    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(Theme.CARD);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        inputArea.setFont(Theme.fontBase());
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        inputArea.setToolTipText(placeholder());
        panel.add(new InputScrollPane(inputArea), BorderLayout.CENTER);

        panel.add(quoteBar, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(imageButton);
        buttons.add(fileButton);
        buttons.add(emojiButton);
        buttons.add(sendButton);
        buttons.add(clearButton);
        panel.add(buttons, BorderLayout.EAST);

        sendButton.addActionListener(e -> sendCurrentInput());
        fileButton.setIcon(Glyphs.file(14, Theme.TEXT));
        fileButton.setIconTextGap(6);
        fileButton.addActionListener(e -> chooseAndSendFile());
        clearButton.addActionListener(e -> clearHistory());
        imageButton.addActionListener(e -> sendClipboardImage());
        emojiButton.addActionListener(e -> showEmojiPicker());

        installInputBindings();
        installCompositionWatcher();
        installMentionCompletion();
        // 文本区高度随内容行数变化，内容变了必须让父容器重新布局，
        // 否则输入区会停留在旧高度上把新行挤到看不见的位置
        inputArea.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void insertUpdate(DocumentEvent e) {
                inputArea.revalidate();
                refreshMentionPopup();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                inputArea.revalidate();
                refreshMentionPopup();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                inputArea.revalidate();
            }
        });
        return panel;
    }

    /**
     * 绑定输入快捷键。
     *
     * <p>回车与 Shift+回车都通过 {@code InputMap} 改写：多行文本框默认把回车用作换行，
     * 必须显式接管"回车"这个按键，同时给 Shift+回车补一条换行绑定，
     * 否则接管回车之后换行就没有入口了。</p>
     */
    private void installInputBindings() {
        InputMap keyMap = inputArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = inputArea.getActionMap();
        keyMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_SEND);
        keyMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), ACTION_NEWLINE);
        actionMap.put(ACTION_SEND, new AbstractAction() {

            @Override
            public void actionPerformed(ActionEvent e) {
                if (isComposingInput()) {
                    return;
                }
                // 候选窗打开时回车是"选中候选"而不是"发送"，否则补全根本无法用键盘确认
                if (mentionPopup.isVisible()) {
                    acceptMention();
                    return;
                }
                sendCurrentInput();
            }
        });
        actionMap.put(ACTION_NEWLINE, new AbstractAction() {

            @Override
            public void actionPerformed(ActionEvent e) {
                inputArea.replaceSelection("\n");
            }
        });
    }

    /**
     * 监听输入法组合状态。
     *
     * <p>中文输入法在选词阶段会把未上屏的候选串交给控件，此时按回车是"确认候选"而不是"发送"。
     * 只有注册了 {@link InputMethodListener}，控件才会收到组合文本变化事件，
     * 这里据此维护组合状态标记。</p>
     */
    private void installCompositionWatcher() {
        inputArea.addInputMethodListener(new InputMethodListener() {

            @Override
            public void inputMethodTextChanged(InputMethodEvent event) {
                int committed = event.getCommittedCharacterCount();
                int total = 0;
                AttributedCharacterIterator text = event.getText();
                if (text != null) {
                    total = text.getEndIndex() - text.getBeginIndex();
                }
                boolean nowComposing = total - committed > 0;
                if (composing && !nowComposing) {
                    compositionEndedAt = System.currentTimeMillis();
                }
                composing = nowComposing;
            }

            @Override
            public void caretPositionChanged(InputMethodEvent event) {
                // 光标位置变化与"是否组合中"无关，无需处理
            }
        });
    }

    /**
     * 判断当前回车是否应当被输入法占用而不发送消息。
     *
     * @return 正在组合候选，或组合刚刚结束应视为选词确认时返回 true
     */
    private boolean isComposingInput() {
        if (composing) {
            return true;
        }
        return System.currentTimeMillis() - compositionEndedAt < COMPOSITION_GUARD_MILLIS;
    }

    /**
     * 弹出文件选择框并把选中的文件交给子类发送。
     *
     * <p>文件发送入口放在聊天窗口内而不是主窗口的用户列表上：聊天窗口本来就知道
     * "我在跟谁聊"，接收方不必再由使用者选一次。</p>
     */
    protected void chooseAndSendFile() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("选择要发送的文件");
        chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file == null || !file.isFile()) {
            return;
        }
        sendFileTo(file);
    }

    /**
     * 执行实际的文件发送，由子类决定接收方。
     *
     * @param file 已选中的待发送文件
     */
    protected abstract void sendFileTo(File file);

    /**
     * 弹出确认框。
     *
     * <p>只允许在事件分发线程调用：本面板的按钮回调本身就运行在该线程上。</p>
     *
     * @param message 询问内容
     * @return 选择"是"返回 true
     */
    protected boolean askConfirm(String message) {
        return javax.swing.JOptionPane.showConfirmDialog(this, message, "请确认",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE) == javax.swing.JOptionPane.YES_OPTION;
    }

    /**
     * 发送输入框中的内容。
     *
     * <p>空内容直接忽略，避免产生无意义的空消息占用带宽与聊天记录；
     * 正文首尾空白（含 Shift+回车留下的换行）一并去除。</p>
     */
    protected void sendCurrentInput() {
        String text = inputArea.getText();
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String content = text.trim();
        boolean sent;
        if (pendingQuoteId == null) {
            sent = doSend(content);
        } else {
            sent = sendQuoted(content, pendingQuoteId, pendingQuoteSummary);
            if (!sent) {
                appendLine("[系统] 当前会话暂不支持引用发送，请先取消引用", COLOR_ERROR);
                return;
            }
        }
        if (sent) {
            inputArea.setText("");
            clearPendingQuote();
        }
    }

    /**
     * 发送带引用信息的消息。
     *
     * <p>默认返回 false 表示当前会话通道不支持引用：引用信息要随消息一起上线，
     * 必须由客户端提供带引用参数的发送方法，子类在具备该能力时覆写本方法。
     * 刻意不在基类里拼接正文，否则收端只能看到一段普通文本，引用就不再是结构化信息。</p>
     *
     * @param content      正文
     * @param quoteId      被引用消息的稳定标识
     * @param quoteSummary 被引用消息的摘要
     * @return 发送成功返回 true
     */
    protected boolean sendQuoted(String content, String quoteId, String quoteSummary) {
        return false;
    }

    /**
     * 当前会话是否支持引用发送。
     *
     * <p>只有支持时才给消息挂"引用"右键菜单，避免使用者点了却发不出去。</p>
     *
     * @return 支持返回 true
     */
    protected boolean isQuoteSupported() {
        return false;
    }

    /**
     * 获取待发送引用的被引用消息标识。
     *
     * @return 稳定消息标识，没有引用时为 null
     */
    protected String getPendingQuoteId() {
        return pendingQuoteId;
    }

    /**
     * 获取待发送引用的摘要。
     *
     * @return 摘要文本，没有引用时为 null
     */
    protected String getPendingQuoteSummary() {
        return pendingQuoteSummary;
    }

    /**
     * 取消当前引用。
     */
    protected void clearPendingQuote() {
        pendingQuoteId = null;
        pendingQuoteSummary = null;
        quoteLabel.setText("");
        quoteBar.setVisible(false);
        revalidate();
    }

    /**
     * 执行实际发送，由子类决定是私聊还是群聊。
     *
     * @param content 已去除首尾空白的正文
     * @return 发送成功返回 true
     */
    protected abstract boolean doSend(String content);

    /**
     * 获取多行输入框，供子类扩展（例如追加自定义按钮或提示文本）。
     *
     * @return 输入框组件
     */
    protected JTextArea getInputArea() {
        return inputArea;
    }

    /**
     * 获取南侧输入面板，供子类追加自定义按钮。
     *
     * @return 南侧面板
     */
    protected JPanel southPanel() {
        return inputPanel;
    }

    /**
     * 向聊天记录追加一条居中的系统提示。
     *
     * @param text  提示文本
     * @param color 文本颜色
     */
    public void appendLine(String text, Color color) {
        if (text == null) {
            return;
        }
        Entry entry = new Entry(null, text, false, true, null,
                color == null ? COLOR_SYSTEM : color, null, null, false);
        runOnEdt(() -> {
            boolean stick = isAtBottom();
            entries.add(entry);
            addRow(entry);
            updateEmptyState();
            scrollToBottomIf(stick);
        });
    }

    /**
     * 向聊天记录追加一条消息气泡。
     *
     * <p>签名中的 {@code color} 在气泡模型里承担"消息来源"的语义：
     * {@link #COLOR_SELF} 表示本人（靠右），{@link #COLOR_SYSTEM} 与 {@link #COLOR_ERROR}
     * 表示系统提示（居中、无气泡），其余一律按他人消息处理（靠左、带头像）。
     * 之所以继续用颜色传来源，是为了保持既有对外签名的语义不变，
     * 让并行的调用方不需要改动。</p>
     *
     * @param prefix  前缀（发送者或提示）
     * @param content 正文
     * @param color   来源颜色
     */
    public void appendMessage(String prefix, String content, Color color) {
        String text = content == null ? "" : content;
        appendEntry(new Entry(prefix, text, COLOR_SELF.equals(color),
                COLOR_SYSTEM.equals(color) || COLOR_ERROR.equals(color),
                LocalDateTime.now(), color, null, null, false));
    }

    /**
     * 把一条消息模型追加到聊天记录并滚动。
     *
     * @param entry 消息模型
     */
    private void appendEntry(Entry entry) {
        if (!entry.system()) {
            // 指纹在渲染之前就登记：网络线程先到、历史补拉后到的情况下，
            // 补拉逻辑必须能立刻看出这条消息已经出现过
            renderedKeys.add(messageKey(entry.prefix(), entry.content()));
        }
        runOnEdt(() -> {
            boolean stick = isAtBottom();
            entries.add(entry);
            addRow(entry);
            updateEmptyState();
            scrollToBottomIf(stick);
        });
    }

    /**
     * 在聊天记录最前面插入一条系统提示。
     *
     * <p>补拉历史记录时使用：这些内容比当前会话中已有的内容更早，
     * 只有插在最前面才能保持时间顺序。</p>
     *
     * @param text  文本内容
     * @param color 文本颜色
     */
    protected void prependLine(String text, Color color) {
        if (text == null) {
            return;
        }
        Entry entry = new Entry(null, text, false, true, null,
                color == null ? COLOR_SYSTEM : color, null, null, false);
        entries.add(0, entry);
        renderAll();
    }

    /**
     * 按时间升序把一批更早的消息插入到聊天记录最前面。
     *
     * <p>已经由实时通道渲染过的同一条消息会被跳过，避免同一条消息出现两次。
     * 补拉只发生在窗口刚创建时，因此这里直接整体重建列表：比逐条插入更简单，
     * 也不会出现"插入过程中滚动位置反复跳动"的问题。</p>
     *
     * @param history 历史消息，按时间升序
     * @return 实际渲染的条数
     */
    protected int prependHistory(List<HistoryEntry> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        List<Entry> prepared = new ArrayList<>();
        for (HistoryEntry item : history) {
            if (item == null || !renderedKeys.add(messageKey(item.who(), item.content()))) {
                continue;
            }
            prepared.add(new Entry(item.who(), item.content(), item.self(), false,
                    item.time(), COLOR_OTHER, null, null, false));
        }
        if (prepared.isEmpty()) {
            return 0;
        }
        entries.addAll(0, prepared);
        renderAll();
        scrollToBottom();
        return prepared.size();
    }

    /**
     * 清空聊天记录显示区，并恢复空状态占位。
     */
    public void clearHistory() {
        renderedKeys.clear();
        runOnEdt(() -> {
            entries.clear();
            lastMessageTime = null;
            messageList.removeAll();
            messageList.revalidate();
            messageList.repaint();
            updateEmptyState();
        });
    }

    /**
     * 构建引用条。
     *
     * @param label 摘要标签
     * @return 引用条面板，初始隐藏
     */
    private JPanel buildQuoteBar(JLabel label) {
        label.setFont(Theme.fontSmall());
        label.setForeground(Theme.TEXT);
        JButton cancel = new SkinButton("取消引用", SkinButton.Kind.GHOST);
        cancel.setFont(Theme.fontSmall());
        cancel.addActionListener(e -> clearPendingQuote());
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBackground(Theme.PRIMARY_LIGHT);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, Theme.PRIMARY),
                BorderFactory.createEmptyBorder(3, 8, 3, 2)));
        bar.add(label, BorderLayout.CENTER);
        bar.add(cancel, BorderLayout.EAST);
        bar.setVisible(false);
        return bar;
    }

    /**
     * 设置待发送的引用并显示引用条。
     *
     * @param messageId 被引用消息的稳定标识
     * @param content   被引用消息的正文
     */
    private void setPendingQuote(String messageId, String content) {
        pendingQuoteId = messageId;
        pendingQuoteSummary = abbreviate(content);
        quoteLabel.setText("引用：" + pendingQuoteSummary);
        quoteBar.setVisible(true);
        inputArea.requestFocusInWindow();
        revalidate();
    }

    /**
     * 把消息正文压缩成单行摘要。
     *
     * @param text 正文
     * @return 单行摘要
     */
    private static String abbreviate(String text) {
        String value = text == null ? "" : text.replace('\n', ' ').trim();
        return value.length() <= QUOTE_SUMMARY_LIMIT
                ? value : value.substring(0, QUOTE_SUMMARY_LIMIT) + "…";
    }

    /**
     * 构建消息右键菜单。
     *
     * <p>目前只有"引用"一项。引用需要被引用消息的稳定标识，而历史记录响应里不带该标识，
     * 因此只有实时收到的消息才有右键菜单，历史消息无法引用。</p>
     *
     * @param entry 消息模型
     * @return 右键菜单
     */
    private JPopupMenu buildQuoteMenu(Entry entry) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem quote = new JMenuItem("引用");
        quote.addActionListener(e -> setPendingQuote(entry.messageId(), entry.content()));
        menu.add(quote);
        return menu;
    }

    /**
     * 安装拖拽发送：把文件拖到聊天窗口即按当前会话的接收方发送。
     *
     * <p>处理器同时挂在面板与消息列表上：消息列表占据了聊天区的大部分面积，
     * 只挂在面板上时，鼠标位于列表上方会走"列表面板"的分发路径。</p>
     */
    private void installFileDrop() {
        TransferHandler handler = new FileDropHandler();
        setTransferHandler(handler);
        messageList.setTransferHandler(handler);
    }

    /**
     * 读取剪贴板图片，落地为临时 PNG 后走既有文件通道发送。
     *
     * <p>不新增二进制协议：图片与普通文件共用同一条传输链路，
     * 服务端与接收端都不需要为图片做任何额外适配。</p>
     */
    protected void sendClipboardImage() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable contents = clipboard.getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                appendLine("[系统] 剪贴板里没有图片，请先截图或复制一张图片", COLOR_SYSTEM);
                return;
            }
            Object data = contents.getTransferData(DataFlavor.imageFlavor);
            if (!(data instanceof Image)) {
                appendLine("[系统] 剪贴板内容不是图片", COLOR_SYSTEM);
                return;
            }
            File target = writeImageToTempFile((Image) data);
            appendLine("[系统] 已把剪贴板图片保存为 " + target.getName() + "，正在通过文件通道发送", COLOR_SYSTEM);
            sendFileTo(target);
        } catch (UnsupportedFlavorException | IOException e) {
            appendLine("[系统] 剪贴板图片发送失败：" + e.getMessage(), COLOR_ERROR);
        } catch (RuntimeException e) {
            // 无图形环境（例如无头运行）时取剪贴板会抛运行时异常，这里提示而不是让界面崩掉
            appendLine("[系统] 当前环境无法读取剪贴板图片", COLOR_ERROR);
        }
    }

    /**
     * 把图片写成临时 PNG 文件。
     *
     * <p>文件名带时间戳，避免同一秒内多次截图互相覆盖；文件登记 {@code deleteOnExit} 作为兜底回收：
     * 传输是异步的，发送方在读盘完成前删文件会让传输失败，因此不能在调用处立即删除。</p>
     *
     * @param image 剪贴板图片
     * @return 临时文件
     * @throws IOException 写盘失败时抛出
     */
    private static File writeImageToTempFile(Image image) throws IOException {
        BufferedImage buffer = new BufferedImage(
                Math.max(1, image.getWidth(null)), Math.max(1, image.getHeight(null)),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buffer.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        File dir = new File(System.getProperty("java.io.tmpdir"), SHOT_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + dir);
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        File target = new File(dir, "截图-" + stamp + ".png");
        for (int index = 2; target.exists() && index < 100; index++) {
            target = new File(dir, "截图-" + stamp + "-" + index + ".png");
        }
        ImageIO.write(buffer, "png", target);
        target.deleteOnExit();
        return target;
    }

    /**
     * 弹出表情面板，选中后插入到输入区光标处。
     */
    protected void showEmojiPicker() {
        JPopupMenu menu = new JPopupMenu();
        JPanel grid = new JPanel(new GridLayout(0, EMOJI_COLUMNS, 2, 2));
        grid.setBackground(Theme.CARD);
        grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        for (String emoji : EMOJIS) {
            JButton button = new JButton(emoji);
            button.setFont(emojiFont());
            button.setPreferredSize(new Dimension(36, 32));
            button.setFocusable(false);
            button.setBorder(BorderFactory.createEmptyBorder());
            button.setContentAreaFilled(false);
            button.addActionListener(e -> {
                insertEmoji(emoji);
                menu.setVisible(false);
                inputArea.requestFocusInWindow();
            });
            grid.add(button);
        }
        menu.add(grid);
        Dimension size = menu.getPreferredSize();
        // 输入区位于窗口底部，向上弹出才不会跑到屏幕外
        menu.show(emojiButton, 0, -Math.max(0, size.height));
    }

    /**
     * 选择能显示表情的字体。
     *
     * <p>主题字体是中文正文用字，通常不含彩色表情字形；直接用它画表情只会得到方框。
     * 这里按平台常见表情字体依次探测，都不可用时退回主题字体：此时表情字符仍能被插入并随消息发出，
     * 只是本机显示为缺字方框，接收端装了表情字体就能正常显示。</p>
     *
     * @return 表情字体
     */
    private static Font emojiFont() {
        String[] candidates = {"Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji",
                "Noto Emoji", "Symbola", "Segoe UI Symbol"};
        try {
            java.util.Set<String> available = new java.util.HashSet<>(java.util.Arrays.asList(
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                            .getAvailableFontFamilyNames()));
            for (String candidate : candidates) {
                if (available.contains(candidate)) {
                    return new Font(candidate, Font.PLAIN, 18);
                }
            }
        } catch (RuntimeException e) {
            System.err.println("表情字体探测失败: " + e.getMessage());
        }
        return Theme.font(18, Font.PLAIN);
    }

    /**
     * 把表情插入到输入区光标处。
     *
     * @param emoji 表情字符
     */
    protected void insertEmoji(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return;
        }
        inputArea.replaceSelection(emoji);
    }

    /**
     * 安装 @ 补全。
     *
     * <p>候选窗口不抢焦点，键盘事件仍由输入区处理，这样才能一边打字缩小候选范围、
     * 一边用上下键与回车/Tab 选中。为此上下键在候选窗打开时改为切换候选，
     * 关闭时交还给默认的光标移动动作。</p>
     */
    private void installMentionCompletion() {
        mentionList.setFont(Theme.fontBase());
        mentionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mentionList.setFocusable(false);
        mentionList.setVisibleRowCount(MENTION_ROWS);
        mentionPopup.setFocusable(false);
        mentionPopup.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        // 候选窗可能被"点击别处"关掉，关闭原因不止 hideMentionPopup 一条路径，
        // 因此把焦点遍历键的恢复绑在弹出菜单自身的关闭事件上，避免 Tab 永久失效
        mentionPopup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                // 无需处理：打开时的准备工作在 showMentionPopup 里完成
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                inputArea.setFocusTraversalKeysEnabled(true);
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                inputArea.setFocusTraversalKeysEnabled(true);
            }
        });
        JScrollPane scroll = new JScrollPane(mentionList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(MENTION_WIDTH, MENTION_ROWS * 22));
        mentionPopup.add(scroll);

        inputArea.addCaretListener(e -> refreshMentionPopup());
        inputArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                hideMentionPopup();
            }
        });

        InputMap keyMap = inputArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = inputArea.getActionMap();
        Action caretUp = actionMap.get(DefaultEditorKit.upAction);
        Action caretDown = actionMap.get(DefaultEditorKit.downAction);
        keyMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), ACTION_MENTION_UP);
        keyMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), ACTION_MENTION_DOWN);
        keyMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), ACTION_MENTION_TAB);
        keyMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ACTION_MENTION_HIDE);
        actionMap.put(ACTION_MENTION_UP, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (mentionPopup.isVisible()) {
                    moveMentionSelection(-1);
                } else if (caretUp != null) {
                    caretUp.actionPerformed(e);
                }
            }
        });
        actionMap.put(ACTION_MENTION_DOWN, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (mentionPopup.isVisible()) {
                    moveMentionSelection(1);
                } else if (caretDown != null) {
                    caretDown.actionPerformed(e);
                }
            }
        });
        actionMap.put(ACTION_MENTION_TAB, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (mentionPopup.isVisible()) {
                    acceptMention();
                } else {
                    // 补全没打开时 Tab 仍然承担"跳到下一个控件"的职责
                    inputArea.transferFocus();
                }
            }
        });
        actionMap.put(ACTION_MENTION_HIDE, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hideMentionPopup();
            }
        });
    }

    /**
     * 刷新 @ 补全候选窗口。
     */
    private void refreshMentionPopup() {
        if (inputArea.getWidth() <= 0 || composing) {
            hideMentionPopup();
            return;
        }
        MentionToken token = mentionTokenAt(inputArea.getText(), inputArea.getCaretPosition());
        List<String> candidates = token == null
                ? List.of() : matchCandidates(mentionCandidates(), token.prefix());
        if (candidates.isEmpty()) {
            hideMentionPopup();
            return;
        }
        mentionList.setListData(candidates.toArray(new String[0]));
        mentionList.setSelectedIndex(0);
        showMentionPopup();
    }

    /**
     * 弹出候选窗口。
     */
    private void showMentionPopup() {
        if (mentionPopup.isVisible()) {
            return;
        }
        try {
            Rectangle2D caret = inputArea.modelToView2D(inputArea.getCaretPosition());
            if (caret == null) {
                return;
            }
            // Tab 默认是焦点切换键，被焦点管理器先截走；补全打开期间临时让出来
            inputArea.setFocusTraversalKeysEnabled(false);
            mentionPopup.show(inputArea, (int) caret.getX(), (int) (caret.getY() + caret.getHeight()));
        } catch (BadLocationException e) {
            // 光标位置必然落在文本范围内，这里只是防御
            hideMentionPopup();
        }
    }

    /**
     * 关闭候选窗口。
     */
    private void hideMentionPopup() {
        if (mentionPopup.isVisible()) {
            mentionPopup.setVisible(false);
        }
    }

    /**
     * 移动候选选中项。
     *
     * @param delta 偏移量
     */
    private void moveMentionSelection(int delta) {
        int size = mentionList.getModel().getSize();
        if (size == 0) {
            return;
        }
        int index = (mentionList.getSelectedIndex() + delta + size) % size;
        mentionList.setSelectedIndex(index);
        mentionList.ensureIndexIsVisible(index);
    }

    /**
     * 用选中的候选替换光标前的 @ 片段。
     */
    private void acceptMention() {
        String name = mentionList.getSelectedValue();
        MentionToken token = name == null ? null
                : mentionTokenAt(inputArea.getText(), inputArea.getCaretPosition());
        if (token != null) {
            inputArea.select(token.start(), inputArea.getCaretPosition());
            inputArea.replaceSelection("@" + name + " ");
        }
        hideMentionPopup();
    }

    /**
     * @ 补全候选来源，默认没有候选。
     *
     * <p>只有群聊有明确的成员名单，因此由子类覆写提供；私聊保持默认的空列表，
     * 输入 @ 不会弹出任何窗口。</p>
     *
     * @return 候选名称列表
     */
    protected List<String> mentionCandidates() {
        return List.of();
    }

    /**
     * 在候选列表中按前缀筛选。
     *
     * <p>纯函数：不依赖界面状态，便于单独验算前缀匹配与前缀为空的边界。</p>
     *
     * @param candidates 全部候选
     * @param prefix     光标前已经输入的 @ 之后的内容
     * @return 命中的候选，最多 {@value #MENTION_MAX} 条
     */
    static List<String> matchCandidates(List<String> candidates, String prefix) {
        String needle = prefix == null ? "" : prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        if (candidates == null) {
            return result;
        }
        for (String name : candidates) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (needle.isEmpty() || name.toLowerCase().startsWith(needle)) {
                result.add(name);
                if (result.size() >= MENTION_MAX) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 取出光标前正在输入的 @ 片段。
     *
     * <p>纯函数：只有"@ 位于词首、且中间没有空白"时才认为处于补全语境，
     * 邮箱地址中间的 @ 不会误触发候选窗口。</p>
     *
     * @param text  输入框全文
     * @param caret 光标位置
     * @return @ 片段，不在补全语境时返回 null
     */
    static MentionToken mentionTokenAt(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int end = Math.max(0, Math.min(caret, text.length()));
        int at = -1;
        for (int index = end - 1; index >= 0; index--) {
            char current = text.charAt(index);
            if (current == '@') {
                at = index;
                break;
            }
            if (Character.isWhitespace(current)) {
                return null;
            }
        }
        if (at < 0 || (at > 0 && !Character.isWhitespace(text.charAt(at - 1)))) {
            return null;
        }
        return new MentionToken(at, text.substring(at + 1, end));
    }

    /**
     * 追加一条来自网络的真实消息。
     *
     * <p>与 {@link #appendMessage(String, String, Color)} 的区别：本方法额外接收原始消息对象，
     * 因而能拿到稳定标识（用于引用）与引用摘要（用于渲染引用块），
     * 以及是否被 @ 提及（用于高亮）。</p>
     *
     * @param prefix  发送者显示名
     * @param content 正文
     * @param color   来源颜色
     * @param message 原始消息对象，可为 null
     * @param mention 是否被 @ 提及
     */
    protected void appendIncoming(String prefix, String content, Color color, Message message,
                                  boolean mention) {
        String text = content == null ? "" : content;
        String messageId = message == null ? null : message.getMessageId();
        String quote = message instanceof TextMessage ? ((TextMessage) message).getQuoteSummary() : null;
        appendEntry(new Entry(prefix, text, COLOR_SELF.equals(color),
                COLOR_SYSTEM.equals(color) || COLOR_ERROR.equals(color),
                LocalDateTime.now(), color, messageId, quote, mention));
    }

    /**
     * 判断两条消息之间是否需要插入时间分隔行。
     *
     * <p>纯函数：结果完全由入参决定，便于按 5 分钟边界单独验算。</p>
     *
     * @param previous 上一条消息时间，为 null 表示这是第一条消息
     * @param current  当前消息时间，为 null 表示不参与判断
     * @return 需要插入分隔行返回 true
     */
    public static boolean needsSeparator(LocalDateTime previous, LocalDateTime current) {
        if (current == null) {
            return false;
        }
        if (previous == null) {
            return true;
        }
        return Duration.between(previous, current).abs().compareTo(TIME_SEPARATOR_GAP) > 0;
    }

    /**
     * 按当前模型重建整个消息列表。
     */
    private void renderAll() {
        messageList.removeAll();
        lastMessageTime = null;
        for (Entry entry : entries) {
            addRow(entry);
        }
        messageList.revalidate();
        messageList.repaint();
    }

    /**
     * 把一条消息模型渲染成界面组件，必要时先插入时间分隔行。
     *
     * @param entry 消息模型
     */
    private void addRow(Entry entry) {
        if (!entry.system() && needsSeparator(lastMessageTime, entry.time())) {
            LocalDateTime stamp = entry.time();
            addRowComponent(MessageBubble.timeSeparator(
                    MessageBubble.formatSmartTime(stamp, LocalDateTime.now())));
        }
        if (!entry.system()) {
            lastMessageTime = entry.time();
        }
        MessageBubble bubble;
        if (entry.system()) {
            bubble = MessageBubble.system(entry.content(), entry.color());
        } else if (entry.self()) {
            bubble = MessageBubble.self(entry.content(), entry.time());
        } else {
            bubble = MessageBubble.other(entry.prefix(), entry.content(), entry.time());
        }
        if (entry.quoteSummary() != null && !entry.quoteSummary().isEmpty()) {
            bubble.setQuoteText(entry.quoteSummary());
        }
        if (entry.highlight()) {
            bubble.setHighlighted(true);
        }
        if (isQuoteSupported() && entry.messageId() != null && !entry.messageId().isEmpty()) {
            bubble.setComponentPopupMenu(buildQuoteMenu(entry));
        }
        bubble.setAvailableWidth(messageList.getWidth() > 0
                ? messageList.getWidth() : FALLBACK_LIST_WIDTH);
        addRowComponent(bubble);
    }

    /**
     * 把组件加入消息列表并补上间距。
     *
     * @param component 消息行组件
     */
    private void addRowComponent(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        messageList.add(component);
        messageList.add(Box.createVerticalStrut(ROW_GAP));
    }

    /**
     * 依据消息条数切换空状态占位。
     */
    private void updateEmptyState() {
        viewCards.show(viewportHost, entries.isEmpty() ? CARD_EMPTY : CARD_LIST);
    }

    /**
     * 判断滚动条是否已经贴着底部。
     *
     * <p>必须在插入新内容之前调用：新内容一加入，最大滚动值变大，就再也判断不出
     * 用户此前是不是停在底部了。</p>
     *
     * @return 贴底返回 true
     */
    private boolean isAtBottom() {
        JScrollBar bar = historyScroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - BOTTOM_TOLERANCE;
    }

    /**
     * 按需滚动到底部。
     *
     * @param stick 插入前是否贴着底部
     */
    private void scrollToBottomIf(boolean stick) {
        if (stick) {
            scrollToBottom();
        }
    }

    /**
     * 滚动到底部。
     *
     * <p>延后到当前事件处理结束再滚动：此刻新组件尚未完成布局，滚动条的最大值还是旧值，
     * 立即设置会停在倒数第二屏的位置。</p>
     */
    private void scrollToBottom() {
        JScrollBar bar = historyScroll.getVerticalScrollBar();
        SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
    }

    /**
     * 在事件分发线程执行界面操作。
     *
     * <p>消息回调可能来自网络线程（见 {@link ChatListener} 的线程约束），
     * 而 Swing 组件只能在事件分发线程改动，因此这里统一兜底切换线程。</p>
     *
     * @param task 界面任务
     */
    private void runOnEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 生成消息指纹，用于历史补拉时的去重。
     *
     * @param who     发送者显示名
     * @param content 正文
     * @return 指纹字符串
     */
    private static String messageKey(String who, String content) {
        return (who == null ? "" : who) + '\u0000' + (content == null ? "" : content);
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
            appendLine("[系统] " + reason, COLOR_ERROR);
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
        return "输入消息后按回车发送，Shift+回车换行（" + Constants.APP_NAME + "）";
    }

    /**
     * 补拉历史记录时的单条数据。
     *
     * @param who     发送者显示名
     * @param content 正文
     * @param self    是否本人发送
     * @param time    消息时间
     */
    protected record HistoryEntry(String who, String content, boolean self, LocalDateTime time) {
    }

    /**
     * 消息模型条目。
     *
     * @param prefix       发送者显示名
     * @param content      正文
     * @param self         是否本人发送
     * @param system       是否系统提示
     * @param time         消息时间，系统提示为 null
     * @param color        系统提示文字颜色
     * @param messageId    跨端稳定消息标识，仅实时收到的消息有，用于引用
     * @param quoteSummary 被引用消息摘要，无引用时为 null
     * @param highlight    是否高亮（被 @ 提及）
     */
    private record Entry(String prefix, String content, boolean self, boolean system,
                         LocalDateTime time, Color color, String messageId,
                         String quoteSummary, boolean highlight) {
    }

    /**
     * 光标前正在输入的 @ 片段。
     *
     * @param start  {@code @} 字符所在下标
     * @param prefix {@code @} 与光标之间的内容
     */
    record MentionToken(int start, String prefix) {
    }

    /**
     * 文件拖拽处理器：接受文件列表，交给当前会话的接收方策略发送。
     *
     * <p>只认 {@code javaFileListFlavor}：文本、图片等其它拖拽内容直接拒绝（返回 false），
     * 由系统显示"不可放置"光标，不会弹出任何错误提示，也就不会打断正在进行的输入。</p>
     */
    private final class FileDropHandler extends TransferHandler {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20250122L;

        @Override
        public int getSourceActions(JComponent component) {
            return COPY;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                Object data = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (!(data instanceof List)) {
                    return false;
                }
                int count = 0;
                for (Object item : (List<?>) data) {
                    if (item instanceof File && ((File) item).isFile()) {
                        sendFileTo((File) item);
                        count++;
                    }
                }
                if (count == 0) {
                    appendLine("[系统] 拖入的内容里没有可发送的文件", COLOR_SYSTEM);
                    return false;
                }
                appendLine("[系统] 已接收拖入的 " + count + " 个文件，正在发起传输", COLOR_SYSTEM);
                return true;
            } catch (UnsupportedFlavorException | IOException e) {
                appendLine("[系统] 拖入的文件读取失败：" + e.getMessage(), COLOR_ERROR);
                return false;
            }
        }
    }

    /**
     * 可滚动的消息列表容器。
     *
     * <p>实现 {@link Scrollable} 并让宽度跟随视口：这样无论窗口多宽，消息行的宽度都等于
     * 视口宽度，靠右的气泡才有"贴住右边界"的参照物；同时高度不跟随视口，
     * 内容不足一屏时列表自然撑满，内容超过一屏时才出现滚动条。</p>
     *
     * <p>宽度变化时在这里统一下发到每条气泡，气泡据此重新测量正文折行宽度。</p>
     */
    private final class MessageList extends JPanel implements Scrollable {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20250119L;

        /** 上一次完成布局时的宽度，用于避免重复测量与反复重排 */
        private int laidOutWidth = -1;

        MessageList() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(Theme.BG);
            setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        }

        @Override
        public void doLayout() {
            int width = getWidth();
            if (width != laidOutWidth) {
                laidOutWidth = width;
                for (Component child : getComponents()) {
                    if (child instanceof MessageBubble) {
                        ((MessageBubble) child).setAvailableWidth(width);
                    }
                }
            }
            super.doLayout();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 120;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * 空状态与消息列表的卡片宿主。
     *
     * <p>宽度跟随视口是气泡自适应的前提：宿主不跟随视口，卡片就只能拿到自身首选宽度，
     * 窗口拉宽后气泡仍按旧宽度折行。</p>
     */
    private static final class CardHost extends JPanel implements Scrollable {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20250120L;

        /**
         * 构造卡片宿主。
         *
         * @param layout 卡片布局
         */
        CardHost(CardLayout layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 120;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * 输入区滚动面板：把可见高度限制在最大行数以内，超出部分交给滚动条。
     *
     * <p>为什么限制的是滚动面板而不是文本区：文本区的首选高度就是它的内容高度，
     * 一旦把文本区压小，滚动面板会认为内容已经放得下，既不再长高也不出现滚动条，
     * 超过上限的文字就彻底看不到了。限制外层高度、让内层保持真实高度才能正常滚动。</p>
     */
    private static final class InputScrollPane extends JScrollPane {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20250121L;

        /** 输入文本区 */
        private final JTextArea view;

        /** 文本区可见高度上限（6 行文本加内边距） */
        private final int maxViewHeight;

        /**
         * 构造输入区滚动面板。
         *
         * @param view 输入文本区
         */
        InputScrollPane(JTextArea view) {
            super(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            this.view = view;
            Insets insets = view.getInsets();
            int lineHeight = Math.max(1,
                    MessageBubble.wrappedTextHeight("汉\n汉", view.getFont(), 80) / 2);
            this.maxViewHeight = lineHeight * MAX_INPUT_ROWS + insets.top + insets.bottom;
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BORDER),
                    BorderFactory.createEmptyBorder(0, 2, 0, 2)));
        }

        /**
         * 首选尺寸直接取自文本区内容高度，并在这里截断到 6 行。
         *
         * <p>不调用 {@code super.getPreferredSize()}：{@code JScrollPane} 走的是
         * {@code getPreferredScrollableViewportSize()}，它对"随内容变高的文本框"并不刷新，
         * 实测会一直停在一行的高度上，输入区就永远长不高。文本区自己的首选高度是准确的，
         * 这里直接采用，再由外层截断，正好得到"最多 6 行、超出滚动"的效果。</p>
         *
         * @return 首选尺寸
         */
        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            Dimension content = view.getPreferredSize();
            int height = Math.min(content.height, maxViewHeight);
            return new Dimension(content.width + insets.left + insets.right,
                    height + insets.top + insets.bottom);
        }
    }
}

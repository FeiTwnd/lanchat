package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.client.ui.theme.Glyphs;
import com.chat.client.ui.theme.SkinButton;
import com.chat.client.ui.theme.Theme;
import com.chat.common.Constants;
import com.chat.common.Message;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.text.AttributedCharacterIterator;
import java.time.Duration;
import java.time.LocalDateTime;
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

    /** 输入框，多行、自动换行，高度在 1 到 6 行之间自适应 */
    protected final JTextArea inputArea = new JTextArea(1, 20);

    /** 发送按钮 */
    protected final JButton sendButton = new SkinButton("发送", SkinButton.Kind.PRIMARY);

    /** 发送文件按钮：接收方由当前聊天窗口决定，因此不需要使用者再去选人 */
    protected final JButton fileButton = new SkinButton("发送文件", SkinButton.Kind.NORMAL);

    /** 清空按钮 */
    protected final JButton clearButton = new SkinButton("清空", SkinButton.Kind.NORMAL);

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
        this.inputPanel = buildInputPanel();
        add(inputPanel, BorderLayout.SOUTH);
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

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(fileButton);
        buttons.add(sendButton);
        buttons.add(clearButton);
        panel.add(buttons, BorderLayout.EAST);

        sendButton.addActionListener(e -> sendCurrentInput());
        fileButton.setIcon(Glyphs.file(14, Theme.TEXT));
        fileButton.setIconTextGap(6);
        fileButton.addActionListener(e -> chooseAndSendFile());
        clearButton.addActionListener(e -> clearHistory());

        installInputBindings();
        installCompositionWatcher();
        // 文本区高度随内容行数变化，内容变了必须让父容器重新布局，
        // 否则输入区会停留在旧高度上把新行挤到看不见的位置
        inputArea.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void insertUpdate(DocumentEvent e) {
                inputArea.revalidate();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                inputArea.revalidate();
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
        if (doSend(text.trim())) {
            inputArea.setText("");
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
                color == null ? COLOR_SYSTEM : color);
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
        boolean self = COLOR_SELF.equals(color);
        boolean system = COLOR_SYSTEM.equals(color) || COLOR_ERROR.equals(color);
        Entry entry = new Entry(prefix, text, self, system, LocalDateTime.now(), color);
        if (!system) {
            // 指纹在渲染之前就登记：网络线程先到、历史补拉后到的情况下，
            // 补拉逻辑必须能立刻看出这条消息已经出现过
            renderedKeys.add(messageKey(prefix, text));
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
                color == null ? COLOR_SYSTEM : color);
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
                    item.time(), COLOR_OTHER));
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
     * @param prefix  发送者显示名
     * @param content 正文
     * @param self    是否本人发送
     * @param system  是否系统提示
     * @param time    消息时间，系统提示为 null
     * @param color   系统提示文字颜色
     */
    private record Entry(String prefix, String content, boolean self, boolean system,
                         LocalDateTime time, Color color) {
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

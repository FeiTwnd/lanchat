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
import javax.swing.Icon;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 聊天窗口面板的基类，负责消息列表展示、消息输入与发送、引用、撤回、提及补全和文件拖放。
 */
public abstract class BaseChatPanel extends JPanel implements ChatListener {
    private static final long serialVersionUID = 20250113L;

    protected static final Font FONT_NORMAL = new Font("Microsoft YaHei", Font.PLAIN, 13);

    protected static final Color COLOR_SELF = new Color(0x1E88E5);

    protected static final Color COLOR_OTHER = new Color(0x2E7D32);

    protected static final Color COLOR_SYSTEM = new Color(0x9E9E9E);

    protected static final Color COLOR_ERROR = new Color(0xC62828);

    /** 相邻消息超过该间隔时插入时间分隔条。 */
    private static final Duration TIME_SEPARATOR_GAP = Duration.ofMinutes(5);

    /** 输入框最多展开的行数。 */
    private static final int MAX_INPUT_ROWS = 6;

    private static final int FALLBACK_LIST_WIDTH = 520;

    /** 判定滚动条停在底部时允许的像素偏差。 */
    private static final int BOTTOM_TOLERANCE = 24;

    /** 输入法提交结束后忽略回车的毫秒数。 */
    private static final long COMPOSITION_GUARD_MILLIS = 80L;

    /** 相邻消息行之间的垂直间距，单位为像素。 */
    private static final int ROW_GAP = 8;

    private static final String CARD_EMPTY = "empty";

    private static final String CARD_LIST = "list";

    private static final String ACTION_SEND = "lanchat-send";

    private static final String ACTION_NEWLINE = "lanchat-newline";

    private static final String ACTION_MENTION_UP = "lanchat-mention-up";

    private static final String ACTION_MENTION_DOWN = "lanchat-mention-down";

    private static final String ACTION_MENTION_TAB = "lanchat-mention-tab";

    private static final String ACTION_MENTION_HIDE = "lanchat-mention-hide";

    /** 引用摘要保留的最大字符数。 */
    private static final int QUOTE_SUMMARY_LIMIT = 40;

    /** 提及候选弹窗的宽度，单位为像素。 */
    private static final int MENTION_WIDTH = 150;

    /** 提及候选列表的可见行数。 */
    private static final int MENTION_ROWS = 6;

    /** 提及候选的最大条数。 */
    private static final int MENTION_MAX = 12;

    /** 表情选择网格的列数。 */
    private static final int KAOMOJI_COLUMNS = 6;

    /** 剪贴板截图临时目录的名称。 */
    private static final String SHOT_DIR = "lanchat-shots";

    private static final String[] KAOMOJI = {
            "(^_^)", "(^-^)", "(*^_^*)", "(^o^)", "(T_T)", "(>_<)",
            "(￣▽￣)", "(⊙_⊙)", "(⊙▽⊙)", "(°▽°)", "(¬_¬)", "(＾▽＾)",
            "(ノ^_^)ノ", "(づ￣￣)づ", "(=^.^=)", "(・_・)", "＼(^o^)／", "(￣_￣)",
            "Orz", "OTZ", "☆彡", "(>﹏<)", "(＾＿＾)", "(°ー°)"};

    protected final JTextArea inputArea = new JTextArea(1, 20);

    protected final JButton sendButton = new SkinButton("发送", SkinButton.Kind.PRIMARY);

    protected final JButton fileButton = new SkinButton("发送文件", SkinButton.Kind.NORMAL);

    protected final JButton clearButton = new SkinButton("清空", SkinButton.Kind.NORMAL);

    protected final JButton imageButton = new SkinButton("图片", SkinButton.Kind.NORMAL);

    protected final JButton emojiButton = new SkinButton("表情", SkinButton.Kind.NORMAL);

    protected final transient ChatClient client;

    private transient RecallHandler recallHandler;

    private final JScrollPane historyScroll;

    private final JPanel inputPanel;

    private final MessageList messageList = new MessageList();

    private final CardLayout viewCards = new CardLayout();

    private final JPanel viewportHost = new CardHost(viewCards);

    private final List<Entry> entries = new ArrayList<>();

    private final Set<String> renderedKeys = ConcurrentHashMap.newKeySet();

    private final Map<String, MessageBubble> bubbleIndex = new LinkedHashMap<>();

    private final Map<String, Entry> entryIndex = new LinkedHashMap<>();

    private final Set<String> recalledIds = ConcurrentHashMap.newKeySet();

    private final Map<String, String> recallTexts = new ConcurrentHashMap<>();

    private boolean sendTargetAvailable = true;

    private transient Function<String, Icon> avatarProvider;

    private LocalDateTime lastMessageTime;

    private final JPanel quoteBar;

    private final JLabel quoteLabel;

    private final JPopupMenu mentionPopup = new JPopupMenu();

    private final JList<String> mentionList = new JList<>();

    private String pendingQuoteId;

    private String pendingQuoteSummary;

    private String pendingQuoteSender;

    private boolean composing;

    private long compositionEndedAt;

    /**
     * 创建面板并装配消息区、输入区和引用条。
     * @param client 聊天客户端
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

    private JPanel buildEmptyPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Theme.BG);
        JLabel hint = new JLabel("还没有聊天记录，发送第一条消息吧");
        hint.setFont(Theme.fontBase());
        hint.setForeground(Theme.TEXT_WEAK);
        panel.add(hint);
        return panel;
    }

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
        clearButton.addActionListener(e -> confirmClearHistory());
        imageButton.addActionListener(e -> showImageMenu());
        emojiButton.addActionListener(e -> showKaomojiPicker());

        installInputBindings();
        installCompositionWatcher();
        installMentionCompletion();

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

    /** 绑定回车发送与 Shift+回车换行。 */
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

    /** 跟踪输入法的组字状态，组字结束时记录时间。 */
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
            }
        });
    }

    /** 判断当前是否处于组字状态或提交后的短暂等待窗口内。 */
    private boolean isComposingInput() {
        if (composing) {
            return true;
        }
        return System.currentTimeMillis() - compositionEndedAt < COMPOSITION_GUARD_MILLIS;
    }

    /** 选择文件并发送到当前会话。 */
    protected void chooseAndSendFile() {
        if (!hasSendTarget()) {
            reportNoTarget();
            return;
        }
        File file = chooseFile("选择要发送的文件", null);
        if (file != null) {
            sendFileTo(file);
        }
    }

    /** 选择图片文件并发送到当前会话。 */
    protected void chooseAndSendImage() {
        if (!hasSendTarget()) {
            reportNoTarget();
            return;
        }
        File file = chooseFile("选择要发送的图片", Constants.IMAGE_EXTENSIONS);
        if (file != null) {
            sendFileTo(file);
        }
    }

    private File chooseFile(String title, String[] extensions) {
        File file = NativeFileDialog.open(this, title, null, extensions);
        return file != null && file.isFile() ? file : null;
    }

    /** 弹出图片来源菜单，可选本地文件或剪贴板。 */
    protected void showImageMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem fromFile = new JMenuItem("从文件选择…");
        fromFile.addActionListener(e -> chooseAndSendImage());
        JMenuItem fromClipboard = new JMenuItem("从剪贴板粘贴");
        fromClipboard.addActionListener(e -> sendClipboardImage());
        menu.add(fromFile);
        menu.add(fromClipboard);
        menu.show(imageButton, 0, imageButton.getHeight());
    }

    /**
     * 判断当前会话是否有可发送的目标。
     * @return 有可用目标时返回 true
     */
    protected boolean hasSendTarget() {
        return sendTargetAvailable;
    }

    /**
     * 返回无法发送文件时展示的说明。
     * @return 提示文案
     */
    protected String noTargetReason() {
        return "当前没有其他在线用户，无法发送文件";
    }

    /**
     * 设置当前会话是否可发送文件，并同步文件与图片按钮的提示。
     * @param available 是否可发送
     */
    public void setSendTargetAvailable(boolean available) {
        this.sendTargetAvailable = available;
        String tip = available ? null : noTargetReason();
        fileButton.setToolTipText(tip);
        imageButton.setToolTipText(tip);
    }

    /** 在消息区和弹窗中提示当前无法发送文件。 */
    protected void reportNoTarget() {
        String reason = noTargetReason();
        appendLine("[系统] " + reason, COLOR_ERROR);
        javax.swing.JOptionPane.showMessageDialog(this, reason, "无法发送",
                javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    /**
     * 把文件发送到当前会话。
     * @param file 待发送的文件
     */
    protected abstract void sendFileTo(File file);

    /**
     * 弹出确认对话框。
     * @param message 提示内容
     * @return 用户选择是时返回 true
     */
    protected boolean askConfirm(String message) {
        return javax.swing.JOptionPane.showConfirmDialog(this, message, "请确认",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE) == javax.swing.JOptionPane.YES_OPTION;
    }

    /** 读取输入框内容并发送，存在待发送引用时按引用方式发送。 */
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
     * 发送带引用的消息。
     * @param content 消息正文
     * @param quoteId 被引用消息的标识
     * @param quoteSummary 被引用消息的摘要
     * @return 发送成功时返回 true
     */
    protected boolean sendQuoted(String content, String quoteId, String quoteSummary) {
        return false;
    }

    /**
     * 判断当前会话是否支持引用发送。
     * @return 支持时返回 true
     */
    protected boolean isQuoteSupported() {
        return false;
    }

    /**
     * 设置撤回处理器。
     * @param handler 撤回处理器
     */
    public void setRecallHandler(RecallHandler handler) {
        this.recallHandler = handler;
    }

    /** 消息撤回的回调接口。 */
    public interface RecallHandler {
        /**
         * 判断消息是否仍可撤回。
         * @param messageId 消息标识
         * @return 可撤回时返回 true
         */
        boolean canRecall(String messageId);

        /**
         * 撤回指定消息。
         * @param messageId 消息标识
         */
        void recall(String messageId);

        /**
         * 记录消息原文，供撤回后重新编辑。
         * @param messageId 消息标识
         * @param content 消息原文
         * @param mine 是否为自己发送
         * @param time 发送时间
         */
        default void remember(String messageId, String content, boolean mine, LocalDateTime time) {
        }
    }

    protected String getPendingQuoteId() {
        return pendingQuoteId;
    }

    protected String getPendingQuoteSummary() {
        return pendingQuoteSummary;
    }

    protected String getPendingQuoteSender() {
        return pendingQuoteSender;
    }

    /**
     * 设置头像提供器。
     * @param provider 按用户名生成头像的函数
     */
    public void setAvatarProvider(Function<String, Icon> provider) {
        this.avatarProvider = provider;
    }

    /** 清除待发送的引用状态并隐藏引用条。 */
    protected void clearPendingQuote() {
        pendingQuoteId = null;
        pendingQuoteSummary = null;
        pendingQuoteSender = null;
        quoteLabel.setText("");
        quoteBar.setVisible(false);
        revalidate();
    }

    /**
     * 发送一条消息到当前会话。
     * @param content 消息正文
     * @return 发送成功时返回 true
     */
    protected abstract boolean doSend(String content);

    protected JTextArea getInputArea() {
        return inputArea;
    }

    /**
     * 返回底部输入区的容器。
     * @return 输入区面板
     */
    protected JPanel southPanel() {
        return inputPanel;
    }

    /**
     * 追加一行系统提示。
     * @param text 提示文本
     * @param color 文字颜色，为 null 时使用系统色
     */
    public void appendLine(String text, Color color) {
        if (text == null) {
            return;
        }
        Entry entry = new Entry(null, text, false, true, null,
                color == null ? COLOR_SYSTEM : color, null, null, null, false, null);
        runOnEdt(() -> {
            boolean stick = isAtBottom();
            entries.add(entry);
            addRow(entry);
            updateEmptyState();
            refreshAfterAppend();
            scrollToBottomIf(stick);
        });
    }

    public void appendMessage(String prefix, String content, Color color) {
        appendMessage(prefix, content, color, null);
    }

    public void appendMessage(String prefix, String content, Color color, String messageId) {
        appendMessage(prefix, content, color, messageId, null);
    }

    public void appendMessage(String prefix, String content, Color color, String messageId,
                              String quoteSummary) {
        appendMessage(prefix, content, color, messageId, quoteSummary, null);
    }

    public void appendMessage(String prefix, String content, Color color, String messageId,
                              String quoteSummary, String avatarUser) {
        appendMessage(prefix, content, color, messageId, quoteSummary, null, avatarUser);
    }

    /**
     * 追加一条消息气泡。
     * @param prefix 发送者名称
     * @param content 消息正文
     * @param color 气泡颜色
     * @param messageId 消息标识
     * @param quoteSummary 被引用消息的摘要
     * @param quoteSender 被引用消息的发送者
     * @param avatarUser 用于取头像的用户名
     */
    public void appendMessage(String prefix, String content, Color color, String messageId,
                              String quoteSummary, String quoteSender, String avatarUser) {
        appendEntry(new Entry(prefix, content == null ? "" : content, COLOR_SELF.equals(color),
                COLOR_SYSTEM.equals(color) || COLOR_ERROR.equals(color),
                LocalDateTime.now(), color,
                messageId == null || messageId.isEmpty() ? null : messageId,
                quoteSummary, quoteSender, false, avatarUser));
    }

    private void appendEntry(Entry entry) {
        if (!entry.system()) {
            renderedKeys.add(messageKey(entry.prefix(), entry.content()));
        }
        runOnEdt(() -> {
            boolean stick = isAtBottom();
            entries.add(entry);
            addRow(entry);
            updateEmptyState();
            refreshAfterAppend();
            scrollToBottomIf(stick);
        });
    }

    /** 在消息区顶部插入一行系统提示。 */
    protected void prependLine(String text, Color color) {
        if (text == null) {
            return;
        }
        Entry entry = new Entry(null, text, false, true, null,
                color == null ? COLOR_SYSTEM : color, null, null, null, false, null);
        entries.add(0, entry);
        renderAll();
    }

    /**
     * 把更早的消息插入消息列表顶部并跳过已显示的条目。
     * @param history 更早的消息列表
     * @return 实际插入的条数
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
                    item.time(), COLOR_OTHER, item.messageId(), null, null, false, null));
        }
        if (prepared.isEmpty()) {
            return 0;
        }
        entries.addAll(0, prepared);
        renderAll();
        scrollToBottom();
        return prepared.size();
    }

    /** 询问用户后清空当前窗口的聊天记录。 */
    protected void confirmClearHistory() {
        if (askConfirm("确定要清空当前聊天窗口的记录吗？\n\n"
                + "只清空本窗口显示的内容，服务器上保存的历史记录不受影响。")) {
            clearHistory();
        }
    }

    /** 清空当前窗口显示的全部消息与索引。 */
    public void clearHistory() {
        renderedKeys.clear();
        runOnEdt(() -> {
            entries.clear();
            bubbleIndex.clear();
            entryIndex.clear();
            recalledIds.clear();
            recallTexts.clear();
            lastMessageTime = null;
            messageList.removeAll();
            messageList.revalidate();
            messageList.repaint();
            updateEmptyState();
        });
    }

    /**
     * 把指定消息替换为撤回提示。
     * @param messageId 消息标识
     * @param newText 撤回提示文本
     * @return 找到并替换时返回 true
     */
    public boolean replaceMessage(String messageId, String newText) {
        MessageBubble bubble = messageId == null ? null : bubbleIndex.remove(messageId);
        if (bubble == null) {
            return false;
        }
        Entry entry = entryIndex.remove(messageId);
        recalledIds.add(messageId);
        recallTexts.put(messageId, newText == null ? "" : newText);
        runOnEdt(() -> swapWithNotice(bubble, entry, messageId));
        return true;
    }

    private void swapWithNotice(MessageBubble bubble, Entry entry, String messageId) {
        int index = messageList.getComponentZOrder(bubble);
        if (index < 0) {
            // 气泡不在当前列表中时只更新文字
            bubble.markRecalled(recallTexts.get(messageId));
            return;
        }
        // 用撤回提示替换原气泡
        JComponent notice = buildRecallNotice(entry, recallTexts.get(messageId));
        notice.setAlignmentX(Component.LEFT_ALIGNMENT);
        messageList.remove(index);
        messageList.add(notice, index);
        messageList.revalidate();
        messageList.repaint();
    }

    /**
     * 追加一条撤回提示。
     * @param text 提示文本
     */
    public void appendRecallNotice(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Entry entry = new Entry(null, text, false, true, null, COLOR_SYSTEM,
                null, null, null, false, null);
        runOnEdt(() -> {
            boolean stick = isAtBottom();
            entries.add(entry);
            addRow(entry);
            updateEmptyState();
            refreshAfterAppend();
            scrollToBottomIf(stick);
        });
    }

    /**
     * 滚动到指定消息并闪烁高亮。
     * @param messageId 消息标识
     * @return 找到消息时返回 true
     */
    public boolean locateMessage(String messageId) {
        MessageBubble bubble = messageId == null ? null : bubbleIndex.get(messageId);
        if (bubble == null) {
            return false;
        }
        bubble.scrollRectToVisible(new Rectangle(0, 0, bubble.getWidth(), bubble.getHeight()));
        bubble.flashHighlight();
        return true;
    }

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

    private void setPendingQuote(String messageId, String quoteSender, String content) {
        pendingQuoteId = messageId;
        pendingQuoteSender = quoteSender;
        pendingQuoteSummary = abbreviate(content);
        quoteLabel.setText((quoteSender == null || quoteSender.isEmpty()
                ? "引用：" : "引用 " + quoteSender + "：") + pendingQuoteSummary);
        quoteBar.setVisible(true);
        inputArea.requestFocusInWindow();
        revalidate();
    }

    private static String abbreviate(String text) {
        String value = text == null ? "" : text.replace('\n', ' ').trim();
        return value.length() <= QUOTE_SUMMARY_LIMIT
                ? value : value.substring(0, QUOTE_SUMMARY_LIMIT) + "…";
    }

    private JPopupMenu buildRowMenu(Entry entry) {
        String messageId = entry.messageId();
        if (messageId == null || messageId.isEmpty()) {
            return null;
        }
        JPopupMenu menu = new JPopupMenu();
        if (isQuoteSupported()) {
            JMenuItem quote = new JMenuItem("引用");

            quote.addActionListener(e -> setPendingQuote(messageId,
                    entry.self() ? client.getUsername() : entry.prefix(), entry.content()));
            menu.add(quote);
        }
        if (entry.self() && recallHandler != null) {
            JMenuItem recall = new JMenuItem("撤回");
            recall.addActionListener(e -> recallHandler.recall(messageId));

            menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
                    recall.setVisible(recallHandler.canRecall(messageId));
                }

                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {
                }

                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {
                }
            });
            menu.add(recall);
        }
        return menu.getComponentCount() == 0 ? null : menu;
    }

    private void installRowMenu(Component component, JPopupMenu menu) {
        if (component instanceof JComponent) {
            ((JComponent) component).setComponentPopupMenu(menu);
        }
        if (component instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) component).getComponents()) {
                installRowMenu(child, menu);
            }
        }
    }

    private void installFileDrop() {
        TransferHandler handler = new FileDropHandler();
        setTransferHandler(handler);
        messageList.setTransferHandler(handler);
    }

    /** 读取剪贴板图片，保存为临时文件后发送。 */
    protected void sendClipboardImage() {
        if (!hasSendTarget()) {
            reportNoTarget();
            return;
        }
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
            appendLine("[系统] 当前环境无法读取剪贴板图片", COLOR_ERROR);
        }
    }

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
        // 同名文件已存在时追加序号
        for (int index = 2; target.exists() && index < 100; index++) {
            target = new File(dir, "截图-" + stamp + "-" + index + ".png");
        }
        ImageIO.write(buffer, "png", target);
        target.deleteOnExit();
        return target;
    }

    /** 弹出表情选择面板。 */
    protected void showKaomojiPicker() {
        JPopupMenu menu = new JPopupMenu();
        JPanel grid = new JPanel(new GridLayout(0, KAOMOJI_COLUMNS, 2, 2));
        grid.setBackground(Theme.CARD);
        grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        for (String kaomoji : KAOMOJI) {
            JButton button = new JButton(kaomoji);

            button.setFont(Theme.font(13, Font.PLAIN));
            button.setMargin(new Insets(2, 4, 2, 4));
            button.setFocusable(false);
            button.setContentAreaFilled(false);
            button.addActionListener(e -> {
                insertKaomoji(kaomoji);
                menu.setVisible(false);
                inputArea.requestFocusInWindow();
            });
            grid.add(button);
        }
        menu.add(grid);
        Dimension size = menu.getPreferredSize();

        menu.show(emojiButton, 0, -Math.max(0, size.height));
    }

    /**
     * 在光标处插入表情文本。
     * @param kaomoji 表情文本
     */
    protected void insertKaomoji(String kaomoji) {
        if (kaomoji == null || kaomoji.isEmpty()) {
            return;
        }
        inputArea.replaceSelection(kaomoji);
    }

    /** 装配提及候选弹窗、快捷键与失焦隐藏。 */
    private void installMentionCompletion() {
        mentionList.setFont(Theme.fontBase());
        mentionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mentionList.setFocusable(false);
        mentionList.setVisibleRowCount(MENTION_ROWS);
        mentionPopup.setFocusable(false);
        mentionPopup.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

        mentionPopup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
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

    /** 根据光标左侧的文本刷新提及候选列表。 */
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

    /** 在光标下方显示提及候选弹窗。 */
    private void showMentionPopup() {
        if (mentionPopup.isVisible()) {
            return;
        }
        try {
            Rectangle2D caret = inputArea.modelToView2D(inputArea.getCaretPosition());
            if (caret == null) {
                return;
            }

            inputArea.setFocusTraversalKeysEnabled(false);
            mentionPopup.show(inputArea, (int) caret.getX(), (int) (caret.getY() + caret.getHeight()));
        } catch (BadLocationException e) {
            hideMentionPopup();
        }
    }

    private void hideMentionPopup() {
        if (mentionPopup.isVisible()) {
            mentionPopup.setVisible(false);
        }
    }

    private void moveMentionSelection(int delta) {
        int size = mentionList.getModel().getSize();
        if (size == 0) {
            return;
        }
        int index = (mentionList.getSelectedIndex() + delta + size) % size;
        mentionList.setSelectedIndex(index);
        mentionList.ensureIndexIsVisible(index);
    }

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
     * 返回可提及的用户列表。
     * @return 用户名列表
     */
    protected List<String> mentionCandidates() {
        return List.of();
    }

    /**
     * 按前缀筛选候选并限制返回数量。
     * @param candidates 全部候选
     * @param prefix 输入的前缀
     * @return 匹配的候选
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
     * 解析光标处的提及片段。
     * @param text 输入文本
     * @param caret 光标位置
     * @return 提及片段，不在提及上下文时返回 null
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
     * 追加一条收到的消息气泡。
     * @param prefix 发送者名称
     * @param content 消息正文
     * @param color 气泡颜色
     * @param message 原始消息
     * @param mention 是否提及当前用户
     */
    protected void appendIncoming(String prefix, String content, Color color, Message message,
                                  boolean mention) {
        String text = content == null ? "" : content;
        String messageId = message == null ? null : message.getMessageId();
        String quote = message instanceof TextMessage ? ((TextMessage) message).getQuoteSummary() : null;
        String quoteSender = message instanceof TextMessage
                ? ((TextMessage) message).getQuoteSender() : null;
        appendEntry(new Entry(prefix, text, COLOR_SELF.equals(color),
                COLOR_SYSTEM.equals(color) || COLOR_ERROR.equals(color),
                LocalDateTime.now(), color, messageId, quote, quoteSender, mention, null));
    }

    /**
     * 判断两条消息之间是否需要插入时间分隔条。
     * @param previous 上一条消息的时间
     * @param current 当前消息的时间
     * @return 需要分隔条时返回 true
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

    private void renderAll() {
        // 清空消息区后按条目顺序重建
        messageList.removeAll();
        bubbleIndex.clear();
        entryIndex.clear();
        lastMessageTime = null;
        for (Entry entry : entries) {
            addRow(entry);
        }
        messageList.revalidate();
        messageList.repaint();
    }

    private void addRow(Entry entry) {
        // 消息间隔超过阈值时插入时间分隔条
        if (!entry.system() && needsSeparator(lastMessageTime, entry.time())) {
            LocalDateTime stamp = entry.time();
            addRowComponent(MessageBubble.timeSeparator(
                    MessageBubble.formatSmartTime(stamp, LocalDateTime.now())));
        }
        if (!entry.system()) {
            lastMessageTime = entry.time();
        }
        // 已撤回的消息显示撤回提示
        if (isRecalled(entry)) {
            addRowComponent(buildRecallNotice(entry, recallTexts.get(entry.messageId())));
            return;
        }
        MessageBubble bubble;
        if (entry.system()) {
            bubble = MessageBubble.system(entry.content(), entry.color());
        } else if (entry.self()) {
            bubble = MessageBubble.self(entry.content(), entry.time(), avatarOf(entry));
        } else {
            bubble = MessageBubble.other(entry.prefix(), entry.content(), entry.time(), avatarOf(entry));
        }
        if (entry.quoteSummary() != null && !entry.quoteSummary().isEmpty()) {
            bubble.setQuoteText(entry.quoteSender(), entry.quoteSummary());
        }
        if (entry.highlight()) {
            bubble.setHighlighted(true);
        }
        JPopupMenu rowMenu = buildRowMenu(entry);
        if (rowMenu != null) {
            installRowMenu(bubble, rowMenu);
        }
        bubble.setAvailableWidth(messageList.getWidth() > 0
                ? messageList.getWidth() : FALLBACK_LIST_WIDTH);
        addRowComponent(bubble);
        rememberBubble(entry, bubble);
    }

    private void rememberBubble(Entry entry, MessageBubble bubble) {
        String messageId = entry.messageId();
        if (messageId == null || messageId.isEmpty()) {
            return;
        }
        bubbleIndex.put(messageId, bubble);
        entryIndex.put(messageId, entry);
        if (recallHandler != null) {
            recallHandler.remember(messageId, entry.content(), entry.self(), entry.time());
        }
    }

    private boolean isRecalled(Entry entry) {
        String messageId = entry.messageId();
        return messageId != null && !messageId.isEmpty() && recalledIds.contains(messageId);
    }

    private JComponent buildRecallNotice(Entry entry, String text) {
        boolean mine = entry != null && entry.self();
        String notice = text == null || text.isEmpty()
                ? (mine ? "你撤回了一条消息" : "对方撤回了一条消息") : text;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        row.setOpaque(false);
        JLabel message = new JLabel(notice);
        message.setFont(Theme.fontSmall());
        message.setForeground(Theme.TEXT_WEAK);
        row.add(message);
        if (mine && entry != null && entry.content() != null && !entry.content().isEmpty()) {
            row.add(buildReeditLink(entry.content()));
        }
        return row;
    }

    private JLabel buildReeditLink(String original) {
        JLabel link = new JLabel("重新编辑");
        link.setFont(Theme.fontSmall());
        link.setForeground(Theme.PRIMARY);
        link.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        link.setToolTipText("把这条消息的原文放回输入框");
        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                inputArea.setText(original);
                inputArea.setCaretPosition(inputArea.getDocument().getLength());
                inputArea.requestFocusInWindow();
            }
        });
        return link;
    }

    private Icon avatarOf(Entry entry) {
        if (avatarProvider == null) {
            return null;
        }
        String username = entry.avatarUser();
        if (username == null || username.isEmpty()) {
            username = entry.self() ? client.getUsername() : entry.prefix();
        }
        return username == null || username.isEmpty() ? null : avatarProvider.apply(username);
    }

    private void addRowComponent(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        messageList.add(component);
        messageList.add(Box.createVerticalStrut(ROW_GAP));
    }

    private void updateEmptyState() {
        viewCards.show(viewportHost, entries.isEmpty() ? CARD_EMPTY : CARD_LIST);
    }

    private boolean isAtBottom() {
        JScrollBar bar = historyScroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - BOTTOM_TOLERANCE;
    }

    private void scrollToBottomIf(boolean stick) {
        if (stick) {
            scrollToBottom();
        }
    }

    private void scrollToBottom() {
        JScrollBar bar = historyScroll.getVerticalScrollBar();
        // 布局完成后把滚动条移到底部
        SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
    }

    private void refreshAfterAppend() {
        messageList.revalidate();
        messageList.repaint();
    }

    /**
     * 在事件分发线程上执行任务，已在事件分发线程时直接运行。
     * @param task 待执行任务
     */
    private void runOnEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    private static String messageKey(String who, String content) {
        return (who == null ? "" : who) + '\u0000' + (content == null ? "" : content);
    }

    /**
     * 返回当前会话的标识。
     * @return 会话标识
     */
    public abstract String getChatTarget();

    /**
     * 判断消息是否属于当前会话。
     * @param message 消息
     * @return 属于当前会话时返回 true
     */
    public abstract boolean accepts(Message message);

    @Override
    public void onConnectionChanged(boolean connected, String reason) {
        if (!connected) {
            // 断线时在消息区追加断开原因
            appendLine("[系统] " + reason, COLOR_ERROR);
        }
    }

    @Override
    public void onMessage(Message message) {
        if (accepts(message)) {
            // 当前会话的消息实时追加到消息区
            appendMessage(message.getSender(), message.getSummary(), new Color(0x424242));
        }
    }

    protected String placeholder() {
        return "输入消息后按回车发送，Shift+回车换行（" + Constants.APP_NAME + "）";
    }

    /** 加载聊天记录时使用的消息条目。 */
    protected record HistoryEntry(String who, String content, boolean self, LocalDateTime time,
                                  String messageId) {
    }

    /** 消息区内部的消息条目。 */
    private record Entry(String prefix, String content, boolean self, boolean system,
                         LocalDateTime time, Color color, String messageId,
                         String quoteSummary, String quoteSender, boolean highlight,
                         String avatarUser) {
    }

    /** 输入框中的提及片段，包含 @ 的位置与其后的前缀。 */
    record MentionToken(int start, String prefix) {
    }

    /** 接收拖入文件的传输处理器。 */
    private final class FileDropHandler extends TransferHandler {
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
                // 逐个发送拖入的文件
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

    /** 消息区面板，纵向排列消息行，并在宽度变化时重新排版气泡。 */
    private final class MessageList extends JPanel implements Scrollable {
        private static final long serialVersionUID = 20250119L;

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

    /** 承载空状态与消息列表两张卡片的容器。 */
    private static final class CardHost extends JPanel implements Scrollable {
        private static final long serialVersionUID = 20250120L;

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

    /** 随内容增长但有高度上限的输入框滚动面板。 */
    private static final class InputScrollPane extends JScrollPane {
        private static final long serialVersionUID = 20250121L;

        private final JTextArea view;

        private final int maxViewHeight;

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

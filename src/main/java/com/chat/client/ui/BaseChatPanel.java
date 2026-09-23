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
import javax.swing.filechooser.FileNameExtensionFilter;
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
    private static final int KAOMOJI_COLUMNS = 6;

    /** 截图临时文件所在目录名 */
    private static final String SHOT_DIR = "lanchat-shots";

    /**
     * 表情面板内容：颜文字，4 行 × 6 列。
     *
     * <p>为什么不再用 Unicode 彩色 emoji：主题字体是中文正文字体（微软雅黑/苹方），
     * 这类字体不含彩色表情字形，选中后画出来是一个方框——使用者在真机上看到的就是
     * "选完什么都没出现"。要显示彩色 emoji 必须引入表情字体或表情图片，
     * 前者在没装该字体的机器上依旧是方框，后者会把第三方素材带进课程设计。</p>
     *
     * <p>因此改为拼字形的颜文字，字符集严格限定在 <b>GB2312 符号区</b>内
     * （ASCII、Latin-1 的 {@code ° ¬}、全角符号 {@code ＾ ￣ ＿ ／}、
     * 几何符号 {@code ⊙ ▽ ○ ● ★ ☆}、日文假名 {@code ノ ミ ツ}）——
     * 中文字体对这一区域的覆盖是完整的，任何一台装得动中文界面的机器都不会缺字，
     * 也就不需要再做字体探测。</p>
     */
    private static final String[] KAOMOJI = {
            "(^_^)", "(^-^)", "(*^_^*)", "(^o^)", "(T_T)", "(>_<)",
            "(￣▽￣)", "(⊙_⊙)", "(⊙▽⊙)", "(°▽°)", "(¬_¬)", "(＾▽＾)",
            "(ノ^_^)ノ", "(づ￣￣)づ", "(=^.^=)", "(・_・)", "＼(^o^)／", "(￣_￣)",
            "Orz", "OTZ", "☆彡", "(>﹏<)", "(＾＿＾)", "(°ー°)"};

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

    /** 撤回策略，由主窗口注入；未注入时消息气泡不出现"撤回"项 */
    private transient RecallHandler recallHandler;

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

    /**
     * 已渲染气泡的索引：消息标识 -> 气泡。
     *
     * <p>撤回要按标识改一条气泡、跳转要按标识定位一条气泡，两者都必须落到"确定的那一条"上。
     * 此前主窗口是靠"正文文本 + 是否本人发送"在组件树里找，同一会话里出现两条相同正文时
     * 会改错行；改成按标识索引后，这个问题从根上不成立。</p>
     *
     * <p>只在事件分发线程访问，因此用普通 {@link LinkedHashMap} 即可，
     * 用插入顺序也只是为了让遍历顺序与界面顺序一致，便于排查。</p>
     */
    private final Map<String, MessageBubble> bubbleIndex = new LinkedHashMap<>();

    /**
     * 已渲染消息的模型索引：消息标识 -> 消息模型。
     *
     * <p>撤回时要把气泡换成一条"撤回提示"，而提示里的"重新编辑"需要拿回原文，
     * 原文只存在于消息模型里——气泡本身记的是渲染后的样子。
     * 与 {@link #bubbleIndex} 同生同灭，因此两者一起登记、一起移除。</p>
     */
    private final Map<String, Entry> entryIndex = new LinkedHashMap<>();

    /**
     * 已撤回消息的标识集合。
     *
     * <p>记在这里而不是只替换组件，是因为消息列表在补拉历史（{@link #renderAll()}）时
     * 会按 {@link #entries} 整体重建：不记这一笔，撤回过的气泡会在窗口重建后
     * 带着原文复活——比不撤回更糟，使用者会以为撤回失败甚至内容泄露。</p>
     *
     * <p>集合与文本分开存：前者只回答"这条是不是撤回过"，后者是撤回后要展示的提示语
     * （本人撤回与对方撤回的措辞不同，由发起撤回的那一端决定）。</p>
     */
    private final Set<String> recalledIds = ConcurrentHashMap.newKeySet();

    /** 撤回提示文本：消息标识 -> 提示语 */
    private final Map<String, String> recallTexts = new ConcurrentHashMap<>();

    /** 是否存在可发送文件的接收方；由主窗口在用户列表刷新后写入 */
    private boolean sendTargetAvailable = true;

    /**
     * 头像提供者：用户名 -> 头像图标。
     *
     * <p>由主窗口注入。气泡需要的头像要带在线状态点与管理员环，而这两样都是"用户列表"的
     * 知识，面板拿不到也不应该反向依赖主窗口或用户树；把查询整体注入进来，
     * 面板就只需要知道自己要显示谁。未注入时按无自定义头像处理，回落到默认剪影。</p>
     */
    private transient Function<String, Icon> avatarProvider;

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

    /** 待发送引用的被引用者显示名，随引用一起发给对方 */
    private String pendingQuoteSender;

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
        clearButton.addActionListener(e -> confirmClearHistory());
        imageButton.addActionListener(e -> showImageMenu());
        emojiButton.addActionListener(e -> showKaomojiPicker());

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
     *
     * <p>无接收方的判断放在打开选择框<b>之前</b>：先让人翻遍文件夹挑好文件、
     * 关掉选择框才被告知"发不出去"，是纯粹的浪费——尤其群聊里没有其他人在线时，
     * 这个结果在点按钮的那一刻就已经确定了。</p>
     */
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

    /**
     * 弹出图片选择框，选中后走与普通文件相同的传输通道发送。
     *
     * <p>为什么图片不另开一条协议：本项目已有的文件通道已经处理了分片、确认、进度与落盘，
     * 图片只是"内容恰好是图片的文件"。再定义一套图片消息只会让服务端多一条需要维护的路径，
     * 而使用者的体验没有任何区别。</p>
     *
     * <p>过滤器按 {@link Constants#IMAGE_EXTENSIONS} 限定常见图片类型，同时保留
     * "所有文件"选项：使用者把 {@code .webp} 之类改名成 {@code .jpg} 时，
     * 一刀切地拦下反而更让人困惑；真正的类型判断在接收端由打开图片的程序负责。</p>
     */
    protected void chooseAndSendImage() {
        if (!hasSendTarget()) {
            reportNoTarget();
            return;
        }
        java.util.List<String> extensions = new ArrayList<>();
        for (String extension : Constants.IMAGE_EXTENSIONS) {
            extensions.add(extension);
        }
        String[] names = extensions.toArray(new String[0]);
        File file = chooseFile("选择要发送的图片",
                new FileNameExtensionFilter("图片文件 (" + String.join("、", names) + ")", names));
        if (file != null) {
            sendFileTo(file);
        }
    }

    /**
     * 弹出文件选择框。
     *
     * @param title  对话框标题
     * @param filter 文件类型过滤器；为 null 表示不限制类型
     * @return 选中的文件；取消或未选中文件时返回 null
     */
    private File chooseFile(String title, FileNameExtensionFilter filter) {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
        if (filter != null) {
            chooser.setFileFilter(filter);
        }
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File file = chooser.getSelectedFile();
        return file != null && file.isFile() ? file : null;
    }

    /**
     * 弹出"图片"按钮的二选一菜单。
     *
     * <p>本轮把"从文件夹选择图片"补成图片发送的主入口，但剪贴板入口必须保留：
     * 截图之后直接粘贴是更快的做法，砍掉它等于用一个新功能换掉一个旧功能。</p>
     */
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
     * 当前会话是否存在可发送文件的接收方。
     *
     * <p>默认取主窗口在用户列表刷新时写入的状态；群聊面板覆写为按在线名单实时判断。</p>
     *
     * @return 有接收方返回 true
     */
    protected boolean hasSendTarget() {
        return sendTargetAvailable;
    }

    /**
     * 无接收方时的原因描述。
     *
     * @return 中文原因
     */
    protected String noTargetReason() {
        return "当前没有其他在线用户，无法发送文件";
    }

    /**
     * 记录本次用户列表刷新后是否存在接收方。
     *
     * <p>状态只用于"点下去时的预检"与鼠标悬停提示，<b>不再置灰按钮</b>。
     * 置灰看起来是更体贴的提示，实际效果恰恰相反：灰按钮点下去不会有任何反馈，
     * 使用者只能得出"这个按钮坏了"或者"点了没反应"的结论——而真实原因
     * （对端离线 / 群里没有别人）本来一句话就能说清。保持可点，点下去由
     * {@link #reportNoTarget()} 把原因讲出来，是更好的交代。</p>
     *
     * @param available 是否存在接收方
     */
    public void setSendTargetAvailable(boolean available) {
        this.sendTargetAvailable = available;
        String tip = available ? null : noTargetReason();
        fileButton.setToolTipText(tip);
        imageButton.setToolTipText(tip);
    }

    /**
     * 报告"当前发不了文件"，并说明原因。
     *
     * <p>两条路同时给：聊天区留一行红字，另外弹一个提示框。只写聊天区是不够的——
     * 使用者点了按钮，目光还在按钮附近，聊天区即使已经自动滚到底也未必被注意到；
     * 只弹框也不行，聊天区里留不下这次操作失败的痕迹，翻记录时无从追溯。</p>
     */
    protected void reportNoTarget() {
        String reason = noTargetReason();
        appendLine("[系统] " + reason, COLOR_ERROR);
        javax.swing.JOptionPane.showMessageDialog(this, reason, "无法发送",
                javax.swing.JOptionPane.WARNING_MESSAGE);
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
     * 注入撤回策略。
     *
     * <p>由主窗口在创建聊天窗口时注入，面板不反向依赖主窗口：撤回的时限判断要读发送时刻，
     * 那份记录只有主窗口有，面板只负责把气泡右键菜单里的点击转交出去。</p>
     *
     * @param handler 撤回策略；传 null 表示本会话不提供撤回入口
     */
    public void setRecallHandler(RecallHandler handler) {
        this.recallHandler = handler;
    }

    /**
     * 撤回策略：把"能不能撤"与"执行撤回"两件事交给掌握发送状态的一方实现。
     */
    public interface RecallHandler {

        /**
         * 判断某条消息此刻是否可撤回。
         *
         * @param messageId 稳定消息标识
         * @return 可撤回返回 true
         */
        boolean canRecall(String messageId);

        /**
         * 请求撤回某条消息。
         *
         * @param messageId 稳定消息标识
         */
        void recall(String messageId);

        /**
         * 通知"界面上渲染出了一条带标识的消息"。
         *
         * <p>撤回的时限判断要读发送时刻，而发送时刻只有主窗口记录得到：
         * 面板渲染历史补拉的气泡时并不知道这条消息是什么时候真正发出的，
         * 它手上只有服务端给的发送时间。默认实现什么都不做，因此既有的两个方法
         * 实现类（只关心能不能撤、撤哪一条）不需要改动。</p>
         *
         * @param messageId 稳定消息标识
         * @param content   消息正文，供撤回候选列表指认"是哪一条"
         * @param mine      是否本人发送
         * @param time      消息时间；为 null 表示没有可用时刻
         */
        default void remember(String messageId, String content, boolean mine, LocalDateTime time) {
        }
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
     * 获取待发送引用的被引用者显示名。
     *
     * @return 被引用者显示名，没有引用时为 null
     */
    protected String getPendingQuoteSender() {
        return pendingQuoteSender;
    }

    /**
     * 注入头像提供者。
     *
     * @param provider 用户名 -> 头像图标；传 null 表示回落到默认头像
     */
    public void setAvatarProvider(Function<String, Icon> provider) {
        this.avatarProvider = provider;
    }

    /**
     * 取消当前引用。
     */
    protected void clearPendingQuote() {
        pendingQuoteId = null;
        pendingQuoteSummary = null;
        pendingQuoteSender = null;
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
                color == null ? COLOR_SYSTEM : color, null, null, null, false, null);
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
        appendMessage(prefix, content, color, null);
    }

    /**
     * 向聊天记录追加一条消息气泡，并带上稳定消息标识。
     *
     * <p>标识是"这条消息能被引用、能被撤回"的前提。本人发出的消息只有走带标识的重载
     * 才有右键菜单：本地回显时正文、时刻都由面板自己产生，标识却由发送通道分配，
     * 不由发送方回传的话，面板永远无从知道自己是哪一条。</p>
     *
     * @param prefix    前缀（发送者或提示）
     * @param content   正文
     * @param color     来源颜色
     * @param messageId 稳定消息标识，没有时传 null
     */
    public void appendMessage(String prefix, String content, Color color, String messageId) {
        appendMessage(prefix, content, color, messageId, null);
    }

    /**
     * 向聊天记录追加一条带引用的消息气泡。
     *
     * <p>本地回显必须走这个重载：引用块与正文一样属于"这一条消息长什么样"，
     * 少了它，发送方自己看不到自己发出的引用——对方却看得见，正是反馈里
     * "引用过后发送出去没有任何不同"的直接原因。</p>
     *
     * @param prefix       前缀（发送者或提示）
     * @param content      正文
     * @param color        来源颜色
     * @param messageId    稳定消息标识，没有时传 null
     * @param quoteSummary 被引用消息摘要，没有引用时传 null
     */
    public void appendMessage(String prefix, String content, Color color, String messageId,
                              String quoteSummary) {
        appendMessage(prefix, content, color, messageId, quoteSummary, null);
    }

    /**
     * 向聊天记录追加一条消息气泡，并显式指定头像归属者。
     *
     * <p>大多数情况下"气泡该显示谁的头像"可以由前缀推出来（本人看用户名、他人看发送者），
     * 唯一推不出来的是离线补投：那里的前缀带着"（离线消息）"后缀，按前缀查头像必然查不到。
     * 因此把归属者单独作为一个可空参数，只有这类特例才需要传。</p>
     *
     * @param prefix       前缀（发送者或提示）
     * @param content      正文
     * @param color        来源颜色
     * @param messageId    稳定消息标识，没有时传 null
     * @param quoteSummary 被引用消息摘要，没有引用时传 null
     * @param avatarUser   头像归属者用户名；为 null 时按前缀推断
     */
    public void appendMessage(String prefix, String content, Color color, String messageId,
                              String quoteSummary, String avatarUser) {
        appendMessage(prefix, content, color, messageId, quoteSummary, null, avatarUser);
    }

    /**
     * 向聊天记录追加一条消息气泡，并显式给出被引用者与头像归属者。
     *
     * <p>本地回显自己的引用消息时必须走这个重载：被引用者不在正文里，
     * 只有发送方手上有一份（他刚在右键菜单里选中的那一条），不显式传进来，
     * 自己看到的就是一个没有作者的引用块，而对方看到的却有作者。</p>
     *
     * @param prefix       前缀（发送者或提示）
     * @param content      正文
     * @param color        来源颜色
     * @param messageId    稳定消息标识，没有时传 null
     * @param quoteSummary 被引用消息摘要，没有引用时传 null
     * @param quoteSender  被引用消息的发送者显示名，可为 null
     * @param avatarUser   头像归属者用户名；为 null 时按前缀推断
     */
    public void appendMessage(String prefix, String content, Color color, String messageId,
                              String quoteSummary, String quoteSender, String avatarUser) {
        appendEntry(new Entry(prefix, content == null ? "" : content, COLOR_SELF.equals(color),
                COLOR_SYSTEM.equals(color) || COLOR_ERROR.equals(color),
                LocalDateTime.now(), color,
                messageId == null || messageId.isEmpty() ? null : messageId,
                quoteSummary, quoteSender, false, avatarUser));
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
                color == null ? COLOR_SYSTEM : color, null, null, null, false, null);
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

    /**
     * 询问之后清空聊天记录显示区。
     *
     * <p>"清空"是一个按下就无法撤销的动作：面板里的内容只存在于内存，
     * 清掉以后没有任何入口能把它们原样找回来。这种动作必须先问一句——
     * 按钮就在发送键旁边，误触一次，整段对话当场消失。</p>
     *
     * <p>询问文案里点明"只清本窗口、服务器上的记录不受影响"，
     * 是为了让人在犹豫时有个准确的判断依据，而不是靠猜。</p>
     */
    protected void confirmClearHistory() {
        if (askConfirm("确定要清空当前聊天窗口的记录吗？\n\n"
                + "只清空本窗口显示的内容，服务器上保存的历史记录不受影响。")) {
            clearHistory();
        }
    }

    /**
     * 清空聊天记录显示区，并恢复空状态占位。
     */
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
     * 按消息标识就地替换一条气泡的正文，用于撤回。
     *
     * <p>替换成功后该标识从索引里移除：撤回不可重复，留在索引里只会让下一次
     * 按标识定位又落到这条已经变成占位文案的气泡上。</p>
     *
     * @param messageId 稳定消息标识
     * @param newText   替换后的正文
     * @return 找到并替换返回 true；该标识不在本面板中时返回 false
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

    /**
     * 就地把一条气泡换成撤回提示行。
     *
     * <p>替换而不是"移除再追加"：撤回的提示必须留在原来那条消息的位置上，
     * 追加到末尾会让它看起来像一条刚刚发生的新事件，与时间线对不上。</p>
     *
     * @param bubble    待替换的气泡
     * @param entry     对应的消息模型，可为 null
     * @param messageId 消息标识
     */
    private void swapWithNotice(MessageBubble bubble, Entry entry, String messageId) {
        int index = messageList.getComponentZOrder(bubble);
        if (index < 0) {
            // 组件已经不在列表里（例如期间被清空过），退回到"改文字"这条老路上，
            // 至少让使用者看到撤回的结果，而不是点了撤回却毫无变化
            bubble.markRecalled(recallTexts.get(messageId));
            return;
        }
        JComponent notice = buildRecallNotice(entry, recallTexts.get(messageId));
        notice.setAlignmentX(Component.LEFT_ALIGNMENT);
        messageList.remove(index);
        messageList.add(notice, index);
        messageList.revalidate();
        messageList.repaint();
    }

    /**
     * 直接追加一行撤回提示。
     *
     * <p>给"找不到目标气泡"的降级路径使用：会话窗口是后开的、或者那条消息已经不在
     * 面板内存里时，至少把撤回这件事显示出来。与气泡替换的区别只是没有原位信息，
     * 因此落在末尾。</p>
     *
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
            scrollToBottomIf(stick);
        });
    }

    /**
     * 定位到某条消息并把视线引过去。
     *
     * <p>供"搜索/查询结果双击跳转"使用：滚动到该行并让其短暂闪烁，
     * 否则跳转后使用者面对满屏气泡根本不知道刚才是哪一条。</p>
     *
     * @param messageId 稳定消息标识
     * @return 定位成功返回 true；该消息不在本面板中时返回 false
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
     * @param messageId   被引用消息的稳定标识
     * @param quoteSender 被引用消息的发送者显示名，可为 null
     * @param content     被引用消息的正文
     */
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
     * <p>两项都要求消息带稳定标识：引用需要它来指代被引用的那一条，撤回需要它来向服务端
     * 指明撤哪一条。服务端的历史与搜索响应自本轮起也带上标识（行格式末列之前的
     * {@code messageId} 字段），因此补拉出来的历史消息同样有右键菜单；
     * 只有升级前写入的老记录没有标识，那些行依旧不出现菜单。</p>
     *
     * <p>"撤回"只在本人发出的消息上出现，且是否真的可撤回交给 {@link RecallHandler} 裁决：
     * 时限与发送时刻只有掌握发送状态的主窗口知道，面板不重复记一份，避免两处判断互相矛盾。</p>
     *
     * @param entry 消息模型
     * @return 右键菜单；没有任何可选项时返回 null
     */
    private JPopupMenu buildRowMenu(Entry entry) {
        String messageId = entry.messageId();
        if (messageId == null || messageId.isEmpty()) {
            return null;
        }
        JPopupMenu menu = new JPopupMenu();
        if (isQuoteSupported()) {
            JMenuItem quote = new JMenuItem("引用");
            // 本人消息的显示名是"我"，直接发出去对方会看到"我："——指的是发送方自己，
            // 于是这里改传用户名：跨端交流的内容不该带本机视角的称呼
            quote.addActionListener(e -> setPendingQuote(messageId,
                    entry.self() ? client.getUsername() : entry.prefix(), entry.content()));
            menu.add(quote);
        }
        if (entry.self() && recallHandler != null) {
            JMenuItem recall = new JMenuItem("撤回");
            recall.addActionListener(e -> recallHandler.recall(messageId));
            // 可撤回与否随时间流逝而变（2 分钟一到就不再可撤），也可能因为刚刚撤回成功而失效，
            // 因此每次弹出菜单时重新判定一次，而不是在气泡渲染时就定死
            menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {

                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
                    recall.setVisible(recallHandler.canRecall(messageId));
                }

                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {
                    // 无需处理：可见性每次弹出时重算
                }

                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {
                    // 无需处理：取消不改动菜单项
                }
            });
            menu.add(recall);
        }
        return menu.getComponentCount() == 0 ? null : menu;
    }

    /**
     * 把右键菜单安装到消息行及其全部子组件上。
     *
     * <p>必须递归安装：{@code setComponentPopupMenu} 不会被子组件继承，只装在气泡最外层容器上时，
     * 右键落在正文文本区或头像上会走该组件自己的分发路径，菜单根本不弹——使用者看到的
     * 就是"右键没反应"。</p>
     *
     * @param component 消息行组件
     * @param menu      右键菜单
     */
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
        // 预检与另外两个入口一致：剪贴板里有没有图片、接收方在不在，
        // 是两件互不相干的事，不能因为"这次是从剪贴板来的"就少查一样
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
     *
     * <p>按钮不设固定尺寸，交给 {@link GridLayout} 按最宽的那一条统一分配：
     * 颜文字的字符数在 4 到 9 之间不等，写死尺寸必然让长的那几条被裁成省略号，
     * 而"看着像乱码的表情"正是这一轮要修掉的问题。</p>
     */
    protected void showKaomojiPicker() {
        JPopupMenu menu = new JPopupMenu();
        JPanel grid = new JPanel(new GridLayout(0, KAOMOJI_COLUMNS, 2, 2));
        grid.setBackground(Theme.CARD);
        grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        for (String kaomoji : KAOMOJI) {
            JButton button = new JButton(kaomoji);
            // 用正文字体而不是表情字体：颜文字的每个字符都在中文字体的覆盖范围内，
            // 且与气泡正文同源，不会出现"面板里能看、发出去变方框"的落差
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
        // 输入区位于窗口底部，向上弹出才不会跑到屏幕外
        menu.show(emojiButton, 0, -Math.max(0, size.height));
    }

    /**
     * 把颜文字插入到输入区光标处。
     *
     * @param kaomoji 颜文字
     */
    protected void insertKaomoji(String kaomoji) {
        if (kaomoji == null || kaomoji.isEmpty()) {
            return;
        }
        inputArea.replaceSelection(kaomoji);
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
        String quoteSender = message instanceof TextMessage
                ? ((TextMessage) message).getQuoteSender() : null;
        appendEntry(new Entry(prefix, text, COLOR_SELF.equals(color),
                COLOR_SYSTEM.equals(color) || COLOR_ERROR.equals(color),
                LocalDateTime.now(), color, messageId, quote, quoteSender, mention, null));
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
        bubbleIndex.clear();
        entryIndex.clear();
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
        if (isRecalled(entry)) {
            // 撤回过的消息不再是气泡：按 QQ 的样式换成一行居中的"××撤回了一条消息"。
            // 走这条分支而不是"画个气泡再改文字"，是因为气泡的底色、对齐、头像都在
            // 表达"这是一条正常消息"，只换文字的话整条消息看上去仍然是发出去的样子
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

    /**
     * 登记气泡索引，并告知撤回策略"界面上多了一条带标识的消息"。
     *
     * <p>两条来源缺一不可：实时消息的标识在 {@code onMessageState(SENDING)} 阶段被登记过，
     * 但历史补拉出来的气泡没有经过那一步；反过来，历史补拉的气泡是走本方法渲染的。
     * 只依赖前一条路径，正是"撤回时而可用时而消失"的根因之一——
     * 关掉窗口再打开，历史里的本人消息右键就没有撤回项了。</p>
     *
     * @param entry  消息模型
     * @param bubble 渲染出的气泡
     */
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

    /**
     * 判断某条消息模型是否已被撤回。
     *
     * @param entry 消息模型
     * @return 已撤回返回 true
     */
    private boolean isRecalled(Entry entry) {
        String messageId = entry.messageId();
        return messageId != null && !messageId.isEmpty() && recalledIds.contains(messageId);
    }

    /**
     * 构建一行撤回提示。
     *
     * <p>样式对齐主流即时通讯软件：居中的浅灰小字，后面跟一个"重新编辑"。
     * 只在本人撤回时给出"重新编辑"——把对方撤回的内容重新编辑再发一遍毫无意义，
     * 而且原文对对方而言已经是"不该再出现"的东西。</p>
     *
     * @param entry 被撤回消息的模型，可为 null（拿不到时按对方撤回处理）
     * @param text  提示语，可为 null
     * @return 提示行组件
     */
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

    /**
     * 构建"重新编辑"链接。
     *
     * <p>点击后把原文放回输入框并聚焦：这是撤回之后最常见的一步——发现写错了字、
     * 想说给错的人，撤回之后紧接着就是改一改再发。原文只从界面内存里取，
     * 服务端已经不再回传已撤回消息的正文。</p>
     *
     * @param original 原文
     * @return 链接标签
     */
    private JLabel buildReeditLink(String original) {
        JLabel link = new JLabel("重新编辑");
        link.setFont(Theme.fontSmall());
        link.setForeground(Theme.PRIMARY);
        link.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        link.setToolTipText("把这条消息的原文放回输入框");
        link.addMouseListener(new java.awt.event.MouseAdapter() {

            /**
             * 点击后回填输入框。
             *
             * @param event 鼠标事件
             */
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                inputArea.setText(original);
                inputArea.setCaretPosition(inputArea.getDocument().getLength());
                inputArea.requestFocusInWindow();
            }
        });
        return link;
    }

    /**
     * 解析一条消息该显示谁的头像。
     *
     * <p>归属规则：气泡里的"昵称"在本项目里从来就是用户名（私聊传对端、群聊传发送者），
     * 本人的消息则用当前登录用户名；唯一推不出来的是离线补投，那里的前缀带
     * "（离线消息）"后缀，因此由调用方通过 {@link Entry#avatarUser()} 显式指定。</p>
     *
     * @param entry 消息模型
     * @return 头像图标；无法确定归属或未注入提供者时返回 null（由气泡回落到默认头像）
     */
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
     * @param who       发送者显示名
     * @param content   正文
     * @param self      是否本人发送
     * @param time      消息时间
     * @param messageId 跨端稳定消息标识；服务端在新版行格式里给出，旧版服务端或升级前的老记录为 null
     */
    protected record HistoryEntry(String who, String content, boolean self, LocalDateTime time,
                                  String messageId) {
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
     * @param messageId    跨端稳定消息标识，仅实时收到的消息有，用于引用与撤回
     * @param quoteSummary 被引用消息摘要，无引用时为 null
     * @param quoteSender  被引用消息的发送者显示名；本地回显自己发的引用时为空
     * @param highlight    是否高亮（被 @ 提及）
     * @param avatarUser   头像归属者用户名；为 null 时按前两者推断
     */
    private record Entry(String prefix, String content, boolean self, boolean system,
                         LocalDateTime time, Color color, String messageId,
                         String quoteSummary, String quoteSender, boolean highlight,
                         String avatarUser) {
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

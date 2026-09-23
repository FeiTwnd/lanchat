package com.chat.client.ui;

import com.chat.client.ui.theme.AvatarFactory;
import com.chat.client.ui.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单条消息的气泡组件。
 *
 * <p>职责：把"昵称 + 时间 + 正文"组织成一个可辨识来源的消息块。自己发送的靠右、
 * 他人发送的靠左并带头像、系统提示居中且不画气泡外框。一个消息条目对应一个本组件，
 * 因此列表容器只需按顺序纵向堆叠，不必再关心每条消息的内部排版。</p>
 *
 * <p>为什么正文用不可编辑的 {@code JTextArea} 而不是 {@code JLabel}：
 * {@code JLabel} 无法选中文本，聊天记录里"选中一段复制"是基本能力，
 * 而 {@code JTextArea} 只读模式下仍支持鼠标选择与 Ctrl+C，代价是需要自行控制换行宽度。</p>
 *
 * <p>头像由调用方查好后传入（见 {@code self}/{@code other} 的带 {@link Icon} 重载），
 * 不传时回落到 {@link AvatarFactory#avatar(boolean, boolean, int)} 的默认剪影。
 * 之所以不在本类里按昵称去查：实时在线状态与角色标记属于用户列表的数据，
 * 面板层拿不到也不应该反向依赖，由掌握这份数据的主窗口统一查好再传进来更直接。</p>
 *
 * <p>为后续功能预留的结构：</p>
 * <ul>
 *   <li>{@link #setStatusText(String)}：气泡底部一行的发送状态文字（发送中/已送达/对方离线/发送失败），
 *       由第 2 轮的 ACK 状态驱动；未设置时该行不占高度。</li>
 *   <li>{@link #setQuoteText(String, String)}：正文上方的引用块，按"谁：内容"展示被引用消息；
 *       未设置时该块不占高度。</li>
 *   <li>{@link #flashHighlight()}：搜索结果跳转过来时短暂闪烁，用于把视线引到目标那一条。</li>
 *   <li>撤回后的占位文案：复用正文文本槽位，把正文替换为 {@code [该消息已撤回]} 即可，
 *       无需新增组件，因此这里不再额外预留控件。</li>
 * </ul>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class MessageBubble extends JPanel {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20260920L;

    /** 消息来源，决定对齐方式与配色 */
    public enum Kind {
        /** 自己发送的消息，靠右显示 */
        SELF,
        /** 他人发送的消息，靠左显示并带头像 */
        OTHER,
        /** 系统提示，居中显示且不使用气泡外框 */
        SYSTEM
    }

    /** 气泡（含内边距）最大宽度占可用宽度的比例，避免长文本把窗口撑破 */
    private static final float BUBBLE_WIDTH_RATIO = 0.70f;

    /** 系统提示文字最大宽度占可用宽度的比例，略大于气泡以免长通知被挤成窄条 */
    private static final float SYSTEM_WIDTH_RATIO = 0.85f;

    /** 气泡最小宽度，保证极短消息仍有正常的圆角外观 */
    private static final int MIN_BUBBLE_WIDTH = 120;

    /**
     * 头像边长。
     *
     * <p>包内可见：聊天面板之外，主窗口在把"用户名 -> 头像"的查询交给面板时，
     * 也要用同一个边长取值，两处各写一个数字迟早会不一致。</p>
     */
    static final int AVATAR_SIZE = 32;

    /** 气泡内边距，同时决定正文折行宽度的扣减量 */
    private static final int BUBBLE_PADDING = 10;

    /** 头像与气泡之间的水平间距 */
    private static final int ROW_GAP = 8;

    /** 昵称行与正文之间的垂直间距 */
    private static final int HEADER_GAP = 4;

    /** 正文测量冗余，吸收文本视图取整差异，避免最后一行被固定高度裁掉 */
    private static final int HEIGHT_SLACK = 2;

    /** 宽度测量冗余：字体度量取整可能比实际绘制少一两个像素，留出余量避免最后一个字被折到下一行 */
    private static final int WIDTH_SLACK = 4;

    /** 被 @ 提及时的气泡底色（浅琥珀，与主题蓝形成对比又不刺眼） */
    private static final Color MENTION_BG = new Color(0xFFF6E0);

    /** 可用宽度尚不可知时的兜底值，仅影响首帧测量结果 */
    private static final int FALLBACK_AVAILABLE_WIDTH = 480;

    /** 定位闪烁的半周期时长（毫秒）：亮/灭各一次为一个周期 */
    private static final int FLASH_INTERVAL_MS = 220;

    /** 定位闪烁的亮灭翻转次数，取偶数以保证结束时落在"灭"上，再统一恢复原状态 */
    private static final int FLASH_TICKS = 6;

    /** 今天的时间格式 */
    private static final DateTimeFormatter TIME_TODAY = DateTimeFormatter.ofPattern("HH:mm");

    /** 昨天的时间格式 */
    private static final DateTimeFormatter TIME_YESTERDAY = DateTimeFormatter.ofPattern("'昨天' HH:mm");

    /** 本年内其他日期的时间格式 */
    private static final DateTimeFormatter TIME_THIS_YEAR = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 跨年日期的时间格式 */
    private static final DateTimeFormatter TIME_OTHER_YEAR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 仅用于文本排版测量的离屏文本区。
     *
     * <p>不让气泡正文自己参与测量：把固定尺寸写回真实组件会触发重排，测量又发生在重排过程中，
     * 容易互相触发形成反复校验；离屏组件不加入任何容器，测量过程对真实组件树完全无副作用。
     * 又因为它的换行、字体度量与真实正文完全同源，算出的高度才与绘制结果一致。</p>
     *
     * <p>线程约束：只在事件分发线程使用（本组件的所有测量都由界面回调触发）。</p>
     */
    private static final JTextArea MEASURER = createMeasurer();

    /** 消息来源 */
    private final Kind kind;

    /** 正文文本，保留原始内容以便宽度变化时重新测量 */
    private final String content;

    /** 正文文本区；系统提示同样使用它，以便长通知可以折行显示 */
    private final BodyArea body;

    /** 气泡容器；系统提示为 null（不画气泡） */
    private final JPanel bubblePane;

    /** 预留的发送状态文字槽位；系统提示为 null */
    private final JLabel statusLabel;

    /** 预留的引用块槽位；系统提示为 null */
    private final QuotePanel quotePanel;

    /** 当前可用宽度（消息列表的内容宽度），气泡宽度上限由它换算 */
    private int availableWidth = FALLBACK_AVAILABLE_WIDTH;

    /** 定位闪烁的计时器；只在闪烁期间非空，结束后不再重建 */
    private Timer flashTimer;

    /**
     * 构造一条自己发送的消息气泡。
     *
     * @param content 正文
     * @param time    消息时间，可为 null（此时头部不显示时间）
     * @return 气泡组件
     */
    public static MessageBubble self(String content, LocalDateTime time) {
        return self(content, time, null);
    }

    /**
     * 构造一条自己发送的消息气泡，并指定头像。
     *
     * @param content 正文
     * @param time    消息时间，可为 null（此时头部不显示时间）
     * @param avatar  头像图标；为 null 时使用默认头像
     * @return 气泡组件
     */
    public static MessageBubble self(String content, LocalDateTime time, Icon avatar) {
        return new MessageBubble(Kind.SELF, null, content, time, null, false, avatar);
    }

    /**
     * 构造一条他人发送的消息气泡。
     *
     * @param nickname 发送者昵称
     * @param content  正文
     * @param time     消息时间，可为 null（此时头部不显示时间）
     * @return 气泡组件
     */
    public static MessageBubble other(String nickname, String content, LocalDateTime time) {
        return other(nickname, content, time, null);
    }

    /**
     * 构造一条他人发送的消息气泡，并指定头像。
     *
     * <p>头像由调用方查好后传进来，而不是在这里按昵称去查：气泡只知道自己显示什么，
     * 在线状态与角色属于"用户列表"的知识，那是面板与主窗口的职责。</p>
     *
     * @param nickname 发送者昵称
     * @param content  正文
     * @param time     消息时间，可为 null（此时头部不显示时间）
     * @param avatar   头像图标；为 null 时使用默认头像
     * @return 气泡组件
     */
    public static MessageBubble other(String nickname, String content, LocalDateTime time,
                                      Icon avatar) {
        return new MessageBubble(Kind.OTHER, nickname, content, time, null, false, avatar);
    }

    /**
     * 构造一条居中的系统提示行。
     *
     * @param text  提示文本
     * @param color 文本颜色，为 null 时使用次要文字颜色
     * @return 提示组件
     */
    public static MessageBubble system(String text, Color color) {
        return new MessageBubble(Kind.SYSTEM, null, text, null, color, false, null);
    }

    /**
     * 构造一条居中的时间分隔行。
     *
     * <p>与系统提示的区别只在视觉：分隔行带浅色胶囊底，用于把"时间线"与"系统通知"这两类
     * 同为中心对齐的内容区分开，否则两种灰色居中文字混在一起无法区分。</p>
     *
     * @param text 时间文本
     * @return 分隔行组件
     */
    public static MessageBubble timeSeparator(String text) {
        return new MessageBubble(Kind.SYSTEM, null, text, null, Theme.TEXT_WEAK, true, null);
    }

    /**
     * 构造气泡组件。
     *
     * @param kind        消息来源
     * @param nickname    昵称，仅他人消息使用
     * @param content     正文
     * @param time        消息时间
     * @param systemColor 系统提示文字颜色
     * @param pill        系统提示是否使用胶囊底（时间分隔行）
     * @param avatar      头像图标，仅消息类气泡使用；为 null 时用默认头像
     */
    private MessageBubble(Kind kind, String nickname, String content, LocalDateTime time,
                          Color systemColor, boolean pill, Icon avatar) {
        super(new FlowLayout(alignmentOf(kind), ROW_GAP, 0));
        this.kind = kind;
        this.content = content == null ? "" : content;
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        BodyArea text = new BodyArea(this.content, kind == Kind.SELF ? Color.WHITE : Theme.TEXT);
        this.body = text;

        if (kind == Kind.SYSTEM) {
            this.bubblePane = null;
            this.statusLabel = null;
            this.quotePanel = null;
            if (systemColor != null) {
                text.setForeground(systemColor);
            }
            text.setFont(Theme.fontSmall());
            if (pill) {
                add(buildPill(text));
            } else {
                add(text);
            }
        } else {
            this.statusLabel = buildStatusLabel();
            this.quotePanel = new QuotePanel();
            JPanel contentPane = buildContentPane(nickname, time);
            this.bubblePane = new BubblePane(contentPane, kind);
            Icon icon = avatar != null ? avatar : AvatarFactory.avatar(true, false, AVATAR_SIZE);
            if (kind == Kind.OTHER) {
                add(new JLabel(icon));
                add(bubblePane);
            } else {
                add(bubblePane);
                add(new JLabel(icon));
            }
        }
        applyBodySize();
    }

    /**
     * 获取消息来源。
     *
     * @return 消息来源枚举
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 获取正文文本。
     *
     * @return 正文（永不为 null）
     */
    public String getContentText() {
        return content;
    }

    /**
     * 设置当前可用宽度（消息列表的内容宽度）。
     *
     * <p>宽度的唯一来源是消息列表容器：它会在自身尺寸变化时逐条通知气泡，
     * 气泡据此把宽度上限换算成 70% 并重新测量正文，从而实现"随窗口自适应"。</p>
     *
     * @param width 可用宽度（像素）
     */
    public void setAvailableWidth(int width) {
        int usable = Math.max(MIN_BUBBLE_WIDTH, width);
        if (usable == availableWidth) {
            return;
        }
        availableWidth = usable;
        applyBodySize();
    }

    /**
     * 把气泡正文改写成"已撤回"占位文案。
     *
     * <p>为什么由气泡自己做而不是让调用方去组件树里翻正文文本区：正文就装在本类内部，
     * "撤回后长什么样"（正文换成占位文字、颜色转灰、底部标注已撤回）是气泡自己的表现细节。
     * 调用方按内容去猜哪一条是目标，还会在同会话出现两条相同正文时改错行。</p>
     *
     * <p>自己的气泡是主色底白字，占位文案同样保持白色：换成灰色在这块深色底上几乎看不清，
     * 而"已撤回"恰恰是必须一眼看到的信息。</p>
     *
     * @param text 占位文案
     */
    public void markRecalled(String text) {
        body.setText(text == null ? "" : text);
        body.setForeground(kind == Kind.SELF ? Color.WHITE : Theme.TEXT_WEAK);
        setStatusText("已撤回");
        revalidate();
        repaint();
    }

    /**
     * 设置发送状态文字（预留槽位，本轮不接入协议）。
     *
     * @param text 状态文字；为空表示不显示
     */
    public void setStatusText(String text) {
        if (statusLabel == null) {
            return;
        }
        boolean show = text != null && !text.trim().isEmpty();
        statusLabel.setText(show ? text : "");
        statusLabel.setVisible(show);
        revalidate();
    }

    /**
     * 设置"被 @ 提及"的高亮状态。
     *
     * <p>只改气泡底色与描边，不改变消息文本本身：提醒是一种视觉强调，
     * 不应该篡改聊天记录的内容。</p>
     *
     * @param highlighted 是否高亮
     */
    public void setHighlighted(boolean highlighted) {
        if (bubblePane instanceof BubblePane) {
            ((BubblePane) bubblePane).setHighlighted(highlighted);
        }
    }

    /**
     * 是否处于"被提及"高亮态。
     *
     * @return 高亮返回 true
     */
    public boolean isHighlighted() {
        return bubblePane instanceof BubblePane && ((BubblePane) bubblePane).isHighlighted();
    }

    /**
     * 短暂闪烁高亮，用于把使用者的视线引到刚被定位到的那一条消息上。
     *
     * <p>用 {@link javax.swing.Timer} 驱动而不是自己开线程睡眠：本方法在事件分发线程上被调用，
     * 任何形式的阻塞（{@code Thread.sleep}）都会把整个界面卡住这段时间，闪烁也就无从谈起了。</p>
     *
     * <p>收尾时恢复调用前的高亮状态，而不是简单地熄灭：被 @ 提及的气泡本身就带着持久高亮，
     * 直接熄灭会把那个更重要的提示一并抹掉。</p>
     */
    public void flashHighlight() {
        if (!(bubblePane instanceof BubblePane)) {
            return;
        }
        if (flashTimer != null && flashTimer.isRunning()) {
            flashTimer.stop();
        }
        final boolean previous = isHighlighted();
        final int[] tick = {0};
        final boolean[] on = {false};
        flashTimer = new Timer(FLASH_INTERVAL_MS, event -> {
            tick[0]++;
            on[0] = !on[0];
            setHighlighted(on[0]);
            if (tick[0] >= FLASH_TICKS) {
                ((Timer) event.getSource()).stop();
                setHighlighted(previous);
            }
        });
        flashTimer.setInitialDelay(0);
        flashTimer.start();
    }

    /**
     * 设置引用块内容。
     *
     * @param text 引用摘要；为空表示不显示
     */
    public void setQuoteText(String text) {
        if (quotePanel != null) {
            quotePanel.setQuoteText(text);
        }
    }

    /**
     * 设置引用块内容（含被引用者）。
     *
     * @param who      被引用消息的发送者显示名，可为 null（此时只显示摘要）
     * @param summary  被引用消息的摘要；为空表示不显示整个引用块
     */
    public void setQuoteText(String who, String summary) {
        if (quotePanel != null) {
            quotePanel.setQuoteText(who, summary);
        }
    }

    /**
     * 按"今天/昨天/本年/跨年"四级规则格式化消息时间。
     *
     * <p>刻意做成静态纯函数：时间格式只在这一处定义，输入输出完全由参数决定，
     * 不依赖当前时间以外的任何状态，便于单独验算四级规则的边界（零点、跨月、跨年）。</p>
     *
     * @param time      消息时间，为 null 时返回空字符串
     * @param reference 参照时间（通常为当前时间），为 null 时取系统当前时间
     * @return 格式化后的中文时间文本
     */
    public static String formatSmartTime(LocalDateTime time, LocalDateTime reference) {
        if (time == null) {
            return "";
        }
        LocalDate today = (reference == null ? LocalDateTime.now() : reference).toLocalDate();
        LocalDate day = time.toLocalDate();
        if (day.equals(today)) {
            return TIME_TODAY.format(time);
        }
        if (day.equals(today.minusDays(1))) {
            return TIME_YESTERDAY.format(time);
        }
        if (day.getYear() == today.getYear()) {
            return TIME_THIS_YEAR.format(time);
        }
        return TIME_OTHER_YEAR.format(time);
    }

    /**
     * 测量文本在指定折行宽度下的渲染高度。
     *
     * <p>与气泡正文共用同一套测量实现，输入区据此推算自身最多显示 6 行的高度上限，
     * 避免两处各写一份换行估算而算出不同的行数。</p>
     *
     * @param text      文本，为 null 时按空串处理
     * @param font      字体
     * @param wrapWidth 折行宽度（像素）
     * @return 渲染高度（像素）
     */
    public static int wrappedTextHeight(String text, Font font, int wrapWidth) {
        return measureText(text, font, Math.max(1, wrapWidth)).height;
    }

    /**
     * 组装气泡内容：昵称行、预留引用块、正文、预留状态行。
     *
     * @param nickname 昵称，仅他人消息使用
     * @param time     消息时间
     * @return 内容面板
     */
    private JPanel buildContentPane(String nickname, LocalDateTime time) {
        JPanel contentPane = new JPanel();
        contentPane.setOpaque(false);
        contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
        contentPane.setBorder(BorderFactory.createEmptyBorder(
                BUBBLE_PADDING, BUBBLE_PADDING, BUBBLE_PADDING, BUBBLE_PADDING));
        contentPane.add(buildHeader(nickname, time));
        contentPane.add(quotePanel);
        contentPane.add(body);
        contentPane.add(statusLabel);
        return contentPane;
    }

    /**
     * 构建昵称与时间行。
     *
     * <p>自己的气泡不显示昵称：右侧位置本身已经表达了"这是我发的"，
     * 再写一遍"我"只是占用宽度。</p>
     *
     * @param nickname 昵称
     * @param time     消息时间
     * @return 昵称行面板
     */
    private JPanel buildHeader(String nickname, LocalDateTime time) {
        boolean self = kind == Kind.SELF;
        JPanel header = new JPanel(new FlowLayout(self ? FlowLayout.RIGHT : FlowLayout.LEFT, 6, 0));
        header.setOpaque(false);
        header.setAlignmentX(self ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, HEADER_GAP, 0));
        if (!self && nickname != null && !nickname.isEmpty()) {
            JLabel name = new JLabel(nickname);
            name.setFont(Theme.fontSmall());
            name.setForeground(Theme.PRIMARY_DARK);
            header.add(name);
        }
        JLabel stamp = new JLabel(formatSmartTime(time, LocalDateTime.now()));
        stamp.setFont(Theme.fontSmall());
        stamp.setForeground(self ? new Color(0xD8EFFB) : Theme.TEXT_WEAK);
        header.add(stamp);
        return header;
    }

    /**
     * 构建预留的发送状态标签。
     *
     * @return 状态标签，初始不可见（不占布局高度）
     */
    private JLabel buildStatusLabel() {
        JLabel label = new JLabel();
        label.setFont(Theme.fontSmall());
        label.setForeground(kind == Kind.SELF ? new Color(0xD8EFFB) : Theme.TEXT_WEAK);
        label.setAlignmentX(kind == Kind.SELF ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        label.setVisible(false);
        return label;
    }

    /**
     * 把时间文本包进浅色胶囊底。
     *
     * @param text 时间文本组件
     * @return 胶囊面板
     */
    private JPanel buildPill(JTextArea text) {
        PillPanel pill = new PillPanel();
        pill.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        pill.add(text, BorderLayout.CENTER);
        return pill;
    }

    /**
     * 按当前可用宽度重新测量正文尺寸。
     */
    private void applyBodySize() {
        if (body == null) {
            return;
        }
        int limit = kind == Kind.SYSTEM
                ? Math.max(60, Math.round(availableWidth * SYSTEM_WIDTH_RATIO))
                : Math.max(60, Math.round(availableWidth * BUBBLE_WIDTH_RATIO) - BUBBLE_PADDING * 2);
        Dimension measured = measureText(content, body.getFont(), limit);
        Dimension fixed = new Dimension(measured.width, measured.height + HEIGHT_SLACK);
        if (fixed.equals(body.getPreferredSize())) {
            return;
        }
        body.setPreferredSize(fixed);
        revalidate();
    }

    /**
     * 计算消息行在给定对齐方式下的布局方式。
     *
     * @param kind 消息来源
     * @return FlowLayout 的对齐常量
     */
    private static int alignmentOf(Kind kind) {
        switch (kind) {
            case SELF:
                return FlowLayout.RIGHT;
            case OTHER:
                return FlowLayout.LEFT;
            default:
                return FlowLayout.CENTER;
        }
    }

    /**
     * 创建离屏测量文本区。
     *
     * @return 已配置好换行参数的文本区
     */
    private static JTextArea createMeasurer() {
        JTextArea area = new JTextArea();
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(null);
        area.setMargin(new Insets(0, 0, 0, 0));
        return area;
    }

    /**
     * 在给定折行宽度下测量文本尺寸。
     *
     * <p>测量前必须先把离屏文本区的当前尺寸设为目标宽度：文本视图是按"当前宽度"计算折行高度的，
     * 不先设尺寸就只能得到不折行的单行高度。</p>
     *
     * <p>宽度另用字体度量单独算：折行文本视图在"不限宽"时返回的是视图宽度而不是文本自然宽度，
     * 直接拿它作气泡宽度会让所有气泡都撑到上限。</p>
     *
     * @param text      文本
     * @param font      字体
     * @param wrapWidth 折行宽度
     * @return 宽度取"自然宽度与折行上限的较小值"，高度为在该宽度下折行后的渲染高度
     */
    private static Dimension measureText(String text, Font font, int wrapWidth) {
        String value = text == null ? "" : text;
        MEASURER.setFont(font);
        MEASURER.setText(value);
        int width = Math.min(naturalWidth(value, font) + WIDTH_SLACK, wrapWidth);
        MEASURER.setSize(width, Short.MAX_VALUE);
        int height = MEASURER.getPreferredSize().height;
        return new Dimension(width, height);
    }

    /**
     * 计算文本不折行时的自然宽度。
     *
     * @param text 文本
     * @param font 字体
     * @return 最长一行所占像素宽
     */
    private static int naturalWidth(String text, Font font) {
        FontMetrics metrics = MEASURER.getFontMetrics(font);
        int width = 0;
        for (String line : text.split("\n", -1)) {
            width = Math.max(width, metrics.stringWidth(line));
        }
        return width;
    }

    /**
     * 气泡正文文本区：只读但可选中。
     *
     * <p>尺寸被显式固定为首选值，避免纵向 BoxLayout 把正文拉伸到整行宽度，
     * 那样气泡就失去了"刚好包住文字"的外观。</p>
     */
    private static final class BodyArea extends JTextArea {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20260921L;

        /**
         * 构造正文文本区。
         *
         * @param text       正文
         * @param foreground 文字颜色
         */
        BodyArea(String text, Color foreground) {
            super(text);
            setEditable(false);
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(false);
            setBorder(null);
            setMargin(new Insets(0, 0, 0, 0));
            setFont(Theme.fontBase());
            setForeground(foreground);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }
    }

    /**
     * 圆角气泡面板：只负责填充与描边，内容由外部传入。
     */
    private static final class BubblePane extends JPanel {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20260922L;

        /** 气泡归属，决定配色 */
        private final Kind kind;

        /** 是否处于"被提及"高亮态 */
        private boolean highlighted;

        /**
         * 构造气泡面板。
         *
         * @param content 内容面板
         * @param kind    气泡归属
         */
        BubblePane(JPanel content, Kind kind) {
            super(new BorderLayout());
            this.kind = kind;
            setOpaque(false);
            add(content, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1;
            int height = getHeight() - 1;
            int arc = 12;
            if (kind == Kind.SELF) {
                g2.setColor(Theme.PRIMARY);
                g2.fillRoundRect(0, 0, width, height, arc, arc);
            } else {
                g2.setColor(highlighted ? MENTION_BG : Theme.CARD);
                g2.fillRoundRect(0, 0, width, height, arc, arc);
                g2.setColor(highlighted ? Theme.ADMIN : Theme.BORDER);
                g2.setStroke(new BasicStroke(highlighted ? 1.6f : 1f));
                g2.drawRoundRect(0, 0, width, height, arc, arc);
            }
            g2.dispose();
            super.paintComponent(g);
        }

        /**
         * 是否高亮。
         *
         * @return 高亮返回 true
         */
        boolean isHighlighted() {
            return highlighted;
        }

        /**
         * 设置高亮状态。
         *
         * @param value 是否高亮
         */
        void setHighlighted(boolean value) {
            if (highlighted == value) {
                return;
            }
            highlighted = value;
            repaint();
        }
    }

    /**
     * 时间胶囊面板：为居中的时间文本画一层浅色圆角底。
     */
    private static final class PillPanel extends JPanel {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20260923L;

        /**
         * 构造胶囊面板。
         */
        PillPanel() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0xE4E9EF));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * 引用块：在主气泡正文上方展示被引用消息的"谁：内容"摘要。
     *
     * <p>为什么不用 {@code MatteBorder} 画左侧竖条：边框只能画直角矩形，
     * 而引用块本身是一张圆角卡片，直角竖条会从圆角处探出去；改为在
     * {@link #paintComponent} 里把圆角矩形当作裁剪区再画竖条，竖条自然跟着圆角收进去。</p>
     *
     * <p>内容一律经 {@link HtmlEscape#oneLine} 转义后再放进 HTML 标签：被引用的正文
     * 是使用者输入的，含 {@code <}、{@code &} 时若不转义，轻则本行显示错乱，
     * 重则把整个气泡的排版撑坏（Swing 的标签见到 {@code <html>} 就按富文本解析）。</p>
     */
    private static final class QuotePanel extends JPanel {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20260924L;

        /** 左侧竖条宽度 */
        private static final int BAR_WIDTH = 3;

        /** 摘要行的像素宽度预算：超出即截断为省略号，全文交给工具提示 */
        private static final int SUMMARY_WIDTH = 250;

        /** 截断标记 */
        private static final String ELLIPSIS = "…";

        /** 摘要标签 */
        private final JLabel label = new JLabel();

        QuotePanel() {
            super(new BorderLayout());
            setOpaque(false);
            label.setFont(Theme.fontSmall());
            label.setForeground(Theme.TEXT_WEAK);
            // 左边让开自绘的竖条，右侧与上下留出与卡片圆角相称的呼吸空间
            setBorder(BorderFactory.createEmptyBorder(3, BAR_WIDTH + 7, 4, 8));
            add(label, BorderLayout.CENTER);
            setVisible(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = Math.min(8, height);
            java.awt.Shape card = new java.awt.geom.RoundRectangle2D.Float(0, 0, width, height, arc, arc);
            g2.setColor(Theme.QUOTE_BG);
            g2.fill(card);
            // 竖条裁剪在圆角之内：左边两端的圆角会把竖条自然地收细，
            // 这正是它比"画一个直角矩形"更像成品界面的地方
            g2.clip(card);
            g2.setColor(Theme.PRIMARY);
            g2.fillRect(0, 0, BAR_WIDTH, height);
            g2.dispose();
            super.paintComponent(g);
        }

        /**
         * 设置引用摘要。
         *
         * @param text 摘要文本；为空表示隐藏整个引用块
         */
        void setQuoteText(String text) {
            setQuoteText(null, text);
        }

        /**
         * 设置引用摘要（含被引用者）。
         *
         * @param who     被引用消息的发送者显示名，可为 null（此时只显示摘要）
         * @param summary 被引用消息的摘要；为空表示隐藏整个引用块
         */
        void setQuoteText(String who, String summary) {
            if (summary == null || summary.trim().isEmpty()) {
                label.setText("");
                setToolTipText(null);
                setVisible(false);
                revalidate();
                return;
            }
            String head = who == null ? "" : who.trim();
            String plain = head.isEmpty() ? summary.trim() : head + "：" + summary.trim();
            // 工具提示也用 HTML 包裹：Swing 的工具提示同样会把以 <html> 开头的文本按富文本解析，
            // 一个叫 "<html>" 的昵称足以让提示框渲染错乱；转义后它只是一段普通文字。
            setToolTipText("<html>" + HtmlEscape.oneLine(plain) + "</html>");
            StringBuilder html = new StringBuilder("<html>");
            if (!head.isEmpty()) {
                html.append("<b>").append(HtmlEscape.oneLine(head)).append("</b>：");
            }
            html.append(HtmlEscape.oneLine(ellipsize(head, summary.trim())));
            label.setText(html.append("</html>").toString());
            setVisible(true);
            revalidate();
        }

        /**
         * 按像素宽度截断摘要，超出部分用省略号代替。
         *
         * <p>按字符数截断在中英文混排下长短悬殊（同样 20 个字符，汉字比字母宽近一倍），
         * 因此改为用字体度量测量真实像素宽度；发送者名字按加粗后的宽度预留，
         * 否则粗体比常规体宽出来的那一成会把摘要挤到卡片外。</p>
         *
         * @param head    发送者显示名，可为空
         * @param summary 摘要原文
         * @return 截断后的摘要
         */
        private String ellipsize(String head, String summary) {
            FontMetrics fm = label.getFontMetrics(label.getFont());
            int budget = SUMMARY_WIDTH;
            if (!head.isEmpty()) {
                FontMetrics boldFm = label.getFontMetrics(label.getFont().deriveFont(Font.BOLD));
                budget -= boldFm.stringWidth(head + "：");
            }
            int ellipsisWidth = fm.stringWidth(ELLIPSIS);
            if (budget <= ellipsisWidth) {
                return ELLIPSIS;
            }
            if (fm.stringWidth(summary) <= budget) {
                return summary;
            }
            StringBuilder sb = new StringBuilder();
            int width = 0;
            for (int i = 0; i < summary.length(); i++) {
                char c = summary.charAt(i);
                int charWidth = fm.charWidth(c);
                if (width + charWidth > budget - ellipsisWidth) {
                    break;
                }
                sb.append(c);
                width += charWidth;
            }
            // 截断点可能正好落在代理对中间，留下半个字符会渲染成方框，干脆一并去掉
            if (sb.length() > 0 && Character.isHighSurrogate(sb.charAt(sb.length() - 1))) {
                sb.setLength(sb.length() - 1);
            }
            return sb.append(ELLIPSIS).toString();
        }
    }
}

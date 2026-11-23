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
 * 聊天消息气泡，展示发送者、时间、正文、引用和撤回状态。
 */
public class MessageBubble extends JPanel {
    private static final long serialVersionUID = 20260920L;

    /** 消息类型，决定气泡的对齐方式与配色。 */
    public enum Kind {
        SELF,

        OTHER,

        SYSTEM
    }

    /** 气泡最大宽度占可用宽度的比例。 */
    private static final float BUBBLE_WIDTH_RATIO = 0.70f;

    /** 系统提示最大宽度占可用宽度的比例。 */
    private static final float SYSTEM_WIDTH_RATIO = 0.85f;

    /** 气泡的最小宽度，单位为像素。 */
    private static final int MIN_BUBBLE_WIDTH = 120;

    /** 头像边长，单位为像素。 */
    static final int AVATAR_SIZE = 32;

    /** 气泡内容的内边距，单位为像素。 */
    private static final int BUBBLE_PADDING = 10;

    private static final int ROW_GAP = 8;

    private static final int HEADER_GAP = 4;

    /** 测量正文高度时预留的像素余量。 */
    private static final int HEIGHT_SLACK = 2;

    /** 测量正文宽度时预留的像素余量。 */
    private static final int WIDTH_SLACK = 4;

    /** 提及当前用户时的气泡底色。 */
    private static final Color MENTION_BG = new Color(0xFFF6E0);

    private static final int FALLBACK_AVAILABLE_WIDTH = 480;

    /** 高亮闪烁的间隔，单位为毫秒。 */
    private static final int FLASH_INTERVAL_MS = 220;

    /** 高亮闪烁的次数。 */
    private static final int FLASH_TICKS = 6;

    private static final DateTimeFormatter TIME_TODAY = DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter TIME_YESTERDAY = DateTimeFormatter.ofPattern("'昨天' HH:mm");

    private static final DateTimeFormatter TIME_THIS_YEAR = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final DateTimeFormatter TIME_OTHER_YEAR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 测量文本尺寸时复用的文本域。 */
    private static final JTextArea MEASURER = createMeasurer();

    private final Kind kind;

    private final String content;

    private final BodyArea body;

    private final JPanel bubblePane;

    private final JLabel statusLabel;

    private final QuotePanel quotePanel;

    private int availableWidth = FALLBACK_AVAILABLE_WIDTH;

    private Timer flashTimer;

    public static MessageBubble self(String content, LocalDateTime time) {
        return self(content, time, null);
    }

    /**
     * 创建自己的消息气泡。
     * @param content 消息正文
     * @param time 发送时间
     * @param avatar 头像图标
     * @return 消息气泡
     */
    public static MessageBubble self(String content, LocalDateTime time, Icon avatar) {
        return new MessageBubble(Kind.SELF, null, content, time, null, false, avatar);
    }

    public static MessageBubble other(String nickname, String content, LocalDateTime time) {
        return other(nickname, content, time, null);
    }

    /**
     * 创建他人的消息气泡。
     * @param nickname 发送者名称
     * @param content 消息正文
     * @param time 发送时间
     * @param avatar 头像图标
     * @return 消息气泡
     */
    public static MessageBubble other(String nickname, String content, LocalDateTime time,
                                      Icon avatar) {
        return new MessageBubble(Kind.OTHER, nickname, content, time, null, false, avatar);
    }

    /**
     * 创建系统提示气泡。
     * @param text 提示文本
     * @param color 文字颜色
     * @return 消息气泡
     */
    public static MessageBubble system(String text, Color color) {
        return new MessageBubble(Kind.SYSTEM, null, text, null, color, false, null);
    }

    /**
     * 创建时间分隔条。
     * @param text 分隔条文字
     * @return 消息气泡
     */
    public static MessageBubble timeSeparator(String text) {
        return new MessageBubble(Kind.SYSTEM, null, text, null, Theme.TEXT_WEAK, true, null);
    }

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

    public Kind getKind() {
        return kind;
    }

    public String getContentText() {
        return content;
    }

    /**
     * 设置气泡可用的宽度并重新计算正文尺寸。
     * @param width 可用宽度，单位为像素
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
     * 把气泡内容替换为撤回提示。
     * @param text 提示文本
     */
    public void markRecalled(String text) {
        body.setText(text == null ? "" : text);
        body.setForeground(kind == Kind.SELF ? Color.WHITE : Theme.TEXT_WEAK);
        setStatusText("已撤回");
        revalidate();
        repaint();
    }

    /**
     * 设置气泡底部的状态文字，空文本时隐藏状态栏。
     * @param text 状态文字
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

    public void setHighlighted(boolean highlighted) {
        if (bubblePane instanceof BubblePane) {
            ((BubblePane) bubblePane).setHighlighted(highlighted);
        }
    }

    public boolean isHighlighted() {
        return bubblePane instanceof BubblePane && ((BubblePane) bubblePane).isHighlighted();
    }

    /** 让气泡高亮闪烁若干次后恢复原状态。 */
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
        // 交替切换高亮状态
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

    public void setQuoteText(String text) {
        if (quotePanel != null) {
            quotePanel.setQuoteText(text);
        }
    }

    /**
     * 设置气泡内的引用内容。
     * @param who 被引用消息的发送者
     * @param summary 被引用消息的摘要
     */
    public void setQuoteText(String who, String summary) {
        if (quotePanel != null) {
            quotePanel.setQuoteText(who, summary);
        }
    }

    /**
     * 按与参考时间的距离格式化时间文本。
     * @param time 待格式化的时间
     * @param reference 参考时间
     * @return 格式化后的时间文本
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
     * 计算文本在指定宽度下的换行高度。
     * @param text 文本
     * @param font 字体
     * @param wrapWidth 换行宽度
     * @return 高度，单位为像素
     */
    public static int wrappedTextHeight(String text, Font font, int wrapWidth) {
        return measureText(text, font, Math.max(1, wrapWidth)).height;
    }

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

    private JLabel buildStatusLabel() {
        JLabel label = new JLabel();
        label.setFont(Theme.fontSmall());
        label.setForeground(kind == Kind.SELF ? new Color(0xD8EFFB) : Theme.TEXT_WEAK);
        label.setAlignmentX(kind == Kind.SELF ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        label.setVisible(false);
        return label;
    }

    private JPanel buildPill(JTextArea text) {
        PillPanel pill = new PillPanel();
        pill.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        pill.add(text, BorderLayout.CENTER);
        return pill;
    }

    /** 按可用宽度计算正文尺寸并应用到正文区域。 */
    private void applyBodySize() {
        if (body == null) {
            return;
        }
        // 按气泡宽度比例计算正文可用宽度
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

    private static JTextArea createMeasurer() {
        JTextArea area = new JTextArea();
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(null);
        area.setMargin(new Insets(0, 0, 0, 0));
        return area;
    }

    private static Dimension measureText(String text, Font font, int wrapWidth) {
        String value = text == null ? "" : text;
        MEASURER.setFont(font);
        MEASURER.setText(value);
        // 宽度取自然宽度与换行宽度的较小值
        int width = Math.min(naturalWidth(value, font) + WIDTH_SLACK, wrapWidth);
        MEASURER.setSize(width, Short.MAX_VALUE);
        int height = MEASURER.getPreferredSize().height;
        return new Dimension(width, height);
    }

    private static int naturalWidth(String text, Font font) {
        FontMetrics metrics = MEASURER.getFontMetrics(font);
        int width = 0;
        for (String line : text.split("\n", -1)) {
            width = Math.max(width, metrics.stringWidth(line));
        }
        return width;
    }

    /** 只读、自动换行的消息正文区域。 */
    private static final class BodyArea extends JTextArea {
        private static final long serialVersionUID = 20260921L;

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

    /** 绘制圆角背景的气泡面板。 */
    private static final class BubblePane extends JPanel {
        private static final long serialVersionUID = 20260922L;

        private final Kind kind;

        private boolean highlighted;

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

        boolean isHighlighted() {
            return highlighted;
        }

        void setHighlighted(boolean value) {
            if (highlighted == value) {
                return;
            }
            highlighted = value;
            repaint();
        }
    }

    /** 绘制胶囊形背景的面板。 */
    private static final class PillPanel extends JPanel {
        private static final long serialVersionUID = 20260923L;

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

    /** 气泡内的引用条，左侧带竖线并单行省略摘要。 */
    private static final class QuotePanel extends JPanel {
        private static final long serialVersionUID = 20260924L;

        /** 左侧竖线的宽度，单位为像素。 */
        private static final int BAR_WIDTH = 3;

        /** 引用摘要的最大显示宽度，单位为像素。 */
        private static final int SUMMARY_WIDTH = 250;

        private static final String ELLIPSIS = "…";

        private final JLabel label = new JLabel();

        QuotePanel() {
            super(new BorderLayout());
            setOpaque(false);
            label.setFont(Theme.fontSmall());
            label.setForeground(Theme.TEXT_WEAK);

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

            g2.clip(card);
            g2.setColor(Theme.PRIMARY);
            g2.fillRect(0, 0, BAR_WIDTH, height);
            g2.dispose();
            super.paintComponent(g);
        }

        void setQuoteText(String text) {
            setQuoteText(null, text);
        }

        /**
         * 设置引用条显示的内容。
         * @param who 被引用消息的发送者
         * @param summary 被引用消息的摘要
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
         * 按可用宽度截断摘要并补省略号。
         * @param head 发送者前缀
         * @param summary 摘要
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

            if (sb.length() > 0 && Character.isHighSurrogate(sb.charAt(sb.length() - 1))) {
                sb.setLength(sb.length() - 1);
            }
            return sb.append(ELLIPSIS).toString();
        }
    }
}

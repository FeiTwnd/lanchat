package com.chat.client.ui.theme;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowEvent;

/**
 * 自定义标题栏。
 *
 * <p>职责：为无边框窗口提供标题展示、拖动移动、双击最大化、最小化与关闭。
 * 关闭动作分发的是标准 {@code WINDOW_CLOSING} 事件，因此各窗口既有的关闭逻辑
 * （服务器的停服确认、客户端断开连接、登录窗口的会话交接）都会被正常触发，
 * 不会被自定义按钮绕过。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class SkinTitleBar extends JPanel {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20260919L;

    /** 标题栏高度 */
    public static final int HEIGHT = 38;

    /** 所属窗口 */
    private final transient JFrame frame;

    /** 标题标签 */
    private final JLabel titleLabel;

    /** 是否处于最大化状态 */
    private transient boolean maximized;

    /** 最大化前的窗口位置与尺寸 */
    private transient Rectangle restoreBounds;

    /** 拖动起点（屏幕坐标） */
    private transient Point dragStart;

    /** 拖动开始时的窗口位置 */
    private transient Point windowStart;

    /**
     * 构造标题栏。
     *
     * @param frame 所属窗口
     * @param title 标题文本
     */
    public SkinTitleBar(JFrame frame, String title) {
        this.frame = frame;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(0, HEIGHT));
        setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, (HEIGHT - 22) / 2));
        left.setOpaque(false);
        JLabel logo = new JLabel(Glyphs.logo(20));
        titleLabel = new JLabel(title);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(Theme.fontTitle());
        left.add(logo);
        left.add(titleLabel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, (HEIGHT - 30) / 2));
        right.setOpaque(false);
        JButton minimize = new TitleButton(Glyphs.minimize(14, Color.WHITE), false);
        minimize.setToolTipText("最小化");
        minimize.addActionListener(e -> frame.setExtendedState(Frame.ICONIFIED));
        JButton close = new TitleButton(Glyphs.close(14, Color.WHITE), true);
        close.setToolTipText("关闭");
        close.addActionListener(e -> frame.dispatchEvent(
                new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        right.add(minimize);
        right.add(close);

        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);

        installDrag(left, logo, titleLabel, this);
    }

    /**
     * 更新标题文本。
     *
     * @param title 标题
     */
    public void setTitleText(String title) {
        titleLabel.setText(title == null ? "" : title);
    }

    /**
     * 绘制渐变底与底部细分隔线。
     *
     * @param g 画笔
     */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setPaint(new GradientPaint(0, 0, Theme.PRIMARY, getWidth(), HEIGHT, Theme.PRIMARY_DARK));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(new Color(0x00000022, true));
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
        g2.dispose();
        super.paintComponent(g);
    }

    /**
     * 为若干组件安装拖动与双击最大化行为。
     *
     * @param targets 可拖动的区域
     */
    private void installDrag(Component... targets) {
        for (Component target : targets) {
            target.addMouseListener(new MouseAdapter() {

                @Override
                public void mousePressed(MouseEvent e) {
                    dragStart = e.getLocationOnScreen();
                    windowStart = frame.getLocation();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        toggleMaximize();
                    }
                }
            });
            target.addMouseMotionListener(new MouseMotionAdapter() {

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragStart == null || windowStart == null) {
                        return;
                    }
                    Point now = e.getLocationOnScreen();
                    frame.setLocation(windowStart.x + now.x - dragStart.x,
                            windowStart.y + now.y - dragStart.y);
                }
            });
            target.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        }
    }

    /**
     * 在最大化与还原之间切换。
     */
    private void toggleMaximize() {
        if (maximized) {
            if (restoreBounds != null) {
                frame.setBounds(restoreBounds);
            }
            maximized = false;
            return;
        }
        restoreBounds = frame.getBounds();
        Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        frame.setBounds(screen);
        maximized = true;
    }

    /**
     * 标题栏按钮：悬停时显示背景，关闭按钮使用危险色。
     */
    private final class TitleButton extends JButton {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20260920L;

        /** 是否为关闭按钮 */
        private final boolean danger;

        /**
         * 构造按钮。
         *
         * @param icon   图标
         * @param danger 是否为关闭按钮
         */
        TitleButton(javax.swing.Icon icon, boolean danger) {
            super(icon);
            this.danger = danger;
            setPreferredSize(new Dimension(38, 30));
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setHorizontalAlignment(SwingConstants.CENTER);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        /**
         * 绘制悬停背景。
         *
         * @param g 画笔
         */
        @Override
        protected void paintComponent(Graphics g) {
            if (getModel().isRollover() || getModel().isPressed()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(danger ? Theme.DANGER : new Color(0xFFFFFF44, true));
                g2.fillRoundRect(4, 3, getWidth() - 8, getHeight() - 6, 8, 8);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}

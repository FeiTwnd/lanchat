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
 * 自绘的窗口标题栏，包含图标、标题、未读气泡以及最小化和关闭按钮，并支持拖动窗口与双击最大化。
 */
public class SkinTitleBar extends JPanel {
    private static final long serialVersionUID = 20260919L;

    /** 标题栏高度，单位像素。 */
    public static final int HEIGHT = 38;

    /** 未读气泡右边缘与标题栏右侧的距离，单位像素。 */
    private static final int BADGE_RIGHT_INSET = 76;

    private final transient JFrame frame;

    private final JLabel titleLabel;

    private transient boolean maximized;

    private transient Rectangle restoreBounds;

    private transient Point dragStart;

    private transient Point windowStart;

    private transient int badgeCount;

    /**
     * 创建标题栏并绑定所属窗口。
     *
     * @param frame 所在的窗口
     * @param title 初始标题文字
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
     * 修改标题文字。
     *
     * @param title 新的标题，null 按空文字处理
     */
    public void setTitleText(String title) {
        titleLabel.setText(title == null ? "" : title);
    }

    /**
     * 设置未读气泡显示的数量。
     *
     * @param count 未读数量，小于 0 时按 0 处理
     */
    public void setBadgeCount(int count) {
        if (badgeCount == count) {
            return;
        }
        badgeCount = Math.max(0, count);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 横向渐变底色与底部分隔线
        g2.setPaint(new GradientPaint(0, 0, Theme.PRIMARY, getWidth(), HEIGHT, Theme.PRIMARY_DARK));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(new Color(0x00000022, true));
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
        if (badgeCount > 0) {
            // 未读气泡画在右侧按钮区域左边
            Badge.paint(g2, getWidth() - BADGE_RIGHT_INSET, HEIGHT / 2, badgeCount);
        }
        g2.dispose();
        super.paintComponent(g);
    }

    /** 为给定组件安装拖动窗口与双击最大化的鼠标监听。 */
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

    /** 在最大化与还原状态之间切换窗口尺寸。 */
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

    /** 标题栏上的窗口控制按钮。 */
    private final class TitleButton extends JButton {
        private static final long serialVersionUID = 20260920L;

        private final boolean danger;

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

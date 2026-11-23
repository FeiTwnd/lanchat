package com.chat.client.ui;

import com.chat.client.ui.theme.Glyphs;
import com.chat.client.ui.theme.Theme;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 顶部断线提示横幅，显示重连进度和连接恢复提示，无提示时隐藏。
 */
public class DisconnectBanner extends JPanel {
    private static final long serialVersionUID = 20260925L;

    /** 横幅高度（像素） */
    private static final int HEIGHT = 30;

    private transient Color background = Theme.DANGER;

    private final JLabel label = new JLabel("", JLabel.CENTER);

    private final JLabel icon = new JLabel();

    public DisconnectBanner() {
        super(new BorderLayout());
        setOpaque(false);
        setPreferredSize(new Dimension(0, HEIGHT));
        label.setFont(Theme.fontBold());
        label.setForeground(Color.WHITE);
        icon.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 12, 0, 8));
        JPanel center = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        center.setOpaque(false);
        center.add(icon);
        center.add(label);
        add(center, BorderLayout.CENTER);
        setVisible(false);
    }

    /**
     * 显示重连中的横幅。
     *
     * @param text 提示文本，为 null 时使用默认文案
     */
    public void showReconnecting(String text) {
        show(text == null ? "与服务器的连接已断开，正在重连……" : text, Theme.DANGER,
                Glyphs.refresh(13, Color.WHITE));
    }

    /**
     * 显示连接已恢复的横幅。
     *
     * @param text 提示文本
     */
    public void showRestored(String text) {
        show(text, Theme.ONLINE, Glyphs.check(13, Color.WHITE));
    }

    /**
     * 隐藏横幅。
     */
    public void dismiss() {
        setVisible(false);
    }

    private void show(String text, Color color, javax.swing.Icon stateIcon) {
        this.background = color;
        label.setText(text);
        icon.setIcon(stateIcon);
        setVisible(true);
        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 铺满状态色后再绘制子组件
        g2.setColor(background);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}

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
 * 断线横幅：主窗口顶部的连接状态提示条。
 *
 * <p>职责：把"连接断了"这件事放在使用者一定会看到的位置。底部状态栏也会写连接状态，
 * 但它和"在线用户 N 人""撤回请求已发送"之类的日常信息共用一行，断线这种整条链路不可用的
 * 状态混在里面很容易被忽略；横幅用整条底色把这件事拎出来。</p>
 *
 * <p>两种形态：重连中用警示色并常驻，直到连上为止；恢复后换成成功色短暂提示一次
 * （由调用方计时隐藏），让使用者明确知道刚才那段时间的消息没有丢。</p>
 *
 * <p>默认隐藏。横幅本身不做任何重连判断，状态由 {@link ClientUI#onConnectionChanged} 驱动。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class DisconnectBanner extends JPanel {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20260925L;

    /** 横幅高度 */
    private static final int HEIGHT = 30;

    /** 底色 */
    private transient Color background = Theme.DANGER;

    /** 文案标签 */
    private final JLabel label = new JLabel("", JLabel.CENTER);

    /** 左侧状态图标 */
    private final JLabel icon = new JLabel();

    /**
     * 构造横幅。
     */
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
     * 显示"正在重连"横幅。
     *
     * @param text 提示文案，直接取连接回调带来的原因（其中已含重连次数）
     */
    public void showReconnecting(String text) {
        show(text == null ? "与服务器的连接已断开，正在重连……" : text, Theme.DANGER,
                Glyphs.refresh(13, Color.WHITE));
    }

    /**
     * 显示"连接已恢复"横幅。
     *
     * @param text 提示文案
     */
    public void showRestored(String text) {
        show(text, Theme.ONLINE, Glyphs.check(13, Color.WHITE));
    }

    /**
     * 隐藏横幅。
     *
     * <p>不叫 {@code hide()}：那会覆盖 {@code Component} 上同名的废弃方法，
     * 编译器会按"覆写已废弃 API"报警告，读代码的人也要多想一步到底调的是哪一个。</p>
     */
    public void dismiss() {
        setVisible(false);
    }

    /**
     * 按指定配色与图标显示横幅。
     *
     * @param text      文案
     * @param color     底色
     * @param stateIcon 左侧图标
     */
    private void show(String text, Color color, javax.swing.Icon stateIcon) {
        this.background = color;
        label.setText(text);
        icon.setIcon(stateIcon);
        setVisible(true);
        revalidate();
        repaint();
    }

    /**
     * 绘制整条底色。
     *
     * <p>不直接用 {@code setBackground}：本面板在无边框窗口里贴着卡片边缘，
     * 需要的是"整条实色横带"而不是随外观变化的组件底色。</p>
     *
     * @param g 画笔
     */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(background);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}

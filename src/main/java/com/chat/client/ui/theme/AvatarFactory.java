package com.chat.client.ui.theme;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 头像绘制工厂。
 *
 * <p>职责：用 Java2D 绘制圆形渐变头像，并在右下角叠加在线状态点、在边缘叠加管理员标记。
 * 名称只取首字（中文取一个字，英文取首字母），无需任何图片素材。</p>
 *
 * <p>绘制结果按"显示名 + 在线状态 + 管理员标记 + 尺寸"缓存，避免列表每次刷新都重绘。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class AvatarFactory {

    /** 头像缓存 */
    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    /** 私有构造，禁止实例化工具类 */
    private AvatarFactory() {
    }

    /**
     * 获取用户头像。
     *
     * @param displayName 显示名（昵称优先，空时使用用户名）
     * @param online      是否在线
     * @param admin       是否管理员
     * @param size        边长
     * @return 图标
     */
    public static Icon avatar(String displayName, boolean online, boolean admin, int size) {
        String name = displayName == null || displayName.trim().isEmpty() ? "?" : displayName.trim();
        String key = name + '|' + online + '|' + admin + '|' + size;
        return CACHE.computeIfAbsent(key, ignored -> draw(name, online, admin, size));
    }

    /**
     * 获取群聊头像：圆角底上三个白色人形剪影。
     *
     * @param size 边长
     * @return 图标
     */
    public static Icon groupAvatar(final int size) {
        String key = "group|" + size;
        return CACHE.computeIfAbsent(key, ignored -> new Icon() {

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                g2.setPaint(new GradientPaint(0, 0, Theme.PRIMARY, 0, size, Theme.PRIMARY_DARK));
                g2.fillRoundRect(0, 0, size - 1, size - 1, size / 3, size / 3);
                g2.setColor(Color.WHITE);
                int head = Math.max(3, size / 5);
                g2.fillOval(size / 2 - head / 2, (int) (size * 0.20), head, head);
                g2.fillArc(size / 2 - head, (int) (size * 0.46), head * 2, (int) (size * 0.46), 0, 180);
                int small = Math.max(2, size / 7);
                g2.fillOval((int) (size * 0.18), (int) (size * 0.30), small, small);
                g2.fillArc((int) (size * 0.10), (int) (size * 0.50), small * 2, small * 2, 0, 180);
                g2.fillOval((int) (size * 0.68), (int) (size * 0.30), small, small);
                g2.fillArc((int) (size * 0.60), (int) (size * 0.50), small * 2, small * 2, 0, 180);
                g2.dispose();
            }
        });
    }

    /**
     * 实际绘制头像。
     *
     * @param name   显示名
     * @param online 是否在线
     * @param admin  是否管理员
     * @param size   边长
     * @return 图标
     */
    private static Icon draw(final String name, final boolean online, final boolean admin, final int size) {
        return new Icon() {

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.translate(x, y);
                Color base = online ? Theme.avatarColor(name) : Theme.OFFLINE;
                g2.setPaint(new GradientPaint(0, 0, base.brighter(), 0, size, base.darker()));
                int arc = Math.max(6, size / 3);
                g2.fillRoundRect(0, 0, size - 1, size - 1, arc, arc);
                if (admin) {
                    g2.setColor(Theme.ADMIN);
                    g2.setStroke(new java.awt.BasicStroke(Math.max(1.5f, size / 14f)));
                    g2.drawRoundRect(1, 1, size - 3, size - 3, arc, arc);
                }
                g2.setColor(Color.WHITE);
                g2.setFont(Theme.font(Math.max(9, (int) (size * 0.46)), Font.BOLD));
                FontMetrics metrics = g2.getFontMetrics();
                String initial = new String(Character.toChars(name.codePointAt(0)));
                int textX = (size - metrics.stringWidth(initial)) / 2;
                int textY = (size - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.drawString(initial, textX, textY);
                paintStatusDot(g2, size, online);
                g2.dispose();
            }
        };
    }

    /**
     * 在右下角绘制在线状态点。
     *
     * @param g2     画笔
     * @param size   头像边长
     * @param online 是否在线
     */
    private static void paintStatusDot(Graphics2D g2, int size, boolean online) {
        int dot = Math.max(6, size / 3);
        int x = size - dot - 1;
        int y = size - dot - 1;
        g2.setColor(Theme.CARD);
        g2.fillOval(x - 2, y - 2, dot + 4, dot + 4);
        g2.setColor(online ? Theme.ONLINE : Theme.OFFLINE);
        g2.fillOval(x, y, dot, dot);
    }
}

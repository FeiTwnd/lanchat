package com.chat.client.ui.theme;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.Shape;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 头像绘制工厂。
 *
 * <p>职责：用 Java2D 绘制默认头像（圆底人形剪影），并在右下角叠加在线状态点、
 * 在边缘叠加管理员标记。所有用户共用同一个默认头像，不使用任何图片素材。</p>
 *
 * <p>为什么不再按用户名生成彩色首字头像：首字头像需要给每个名字稳定地分配底色，
 * 于是每家聊天软件都长得不一样，而渐变色在浅色列表里显得杂乱；默认头像只保留
 * "这里是一个人"这一个语义，在线状态交给状态点表达，视觉上更干净、也更容易维护。</p>
 *
 * <p>绘制结果按"账号、在线状态、管理员标记、尺寸"缓存，避免列表每次刷新都重绘。</p>
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
     * 获取默认头像。
     *
     * @param online 是否在线，决定底色深浅
     * @param admin  是否管理员，决定是否描边
     * @param size   边长
     * @return 图标
     */
    public static Icon avatar(boolean online, boolean admin, int size) {
        String key = "person|" + online + '|' + admin + '|' + size;
        return CACHE.computeIfAbsent(key, ignored -> draw(online, admin, size));
    }

    /**
     * 获取群聊头像：主色圆角底上三个白色人形剪影。
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
                g2.setColor(Theme.PRIMARY);
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
     * 实际绘制默认头像。
     *
     * <p>人形由"头部圆 + 肩部椭圆"两部分组成，绘制前把画布裁剪成外圆，
     * 肩部超出圆的部分会被自动裁掉，不需要手工计算交点。</p>
     *
     * @param online 是否在线
     * @param admin  是否管理员
     * @param size   边长
     * @return 图标
     */
    private static Icon draw(final boolean online, final boolean admin, final int size) {
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
                g2.translate(x, y);
                int diameter = size - 1;
                g2.setColor(online ? Theme.AVATAR_BG : Theme.AVATAR_BG_OFFLINE);
                g2.fillOval(0, 0, diameter, diameter);

                Shape outer = new Ellipse2D.Float(0, 0, diameter, diameter);
                Shape oldClip = g2.getClip();
                g2.clip(outer);
                g2.setColor(online ? Theme.AVATAR_FG : Theme.AVATAR_FG_OFFLINE);
                int head = Math.round(size * 0.30f);
                g2.fillOval((size - head) / 2, Math.round(size * 0.22f), head, head);
                int bodyWidth = Math.round(size * 0.60f);
                int bodyHeight = Math.round(size * 0.48f);
                g2.fillOval((size - bodyWidth) / 2, Math.round(size * 0.58f), bodyWidth, bodyHeight);
                g2.setClip(oldClip);

                if (admin) {
                    g2.setColor(Theme.ADMIN);
                    g2.setStroke(new BasicStroke(Math.max(1.5f, size / 14f)));
                    g2.drawOval(1, 1, size - 3, size - 3);
                }
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

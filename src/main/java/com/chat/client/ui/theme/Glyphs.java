package com.chat.client.ui.theme;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 矢量图标工厂。
 *
 * <p>职责：用 Java2D 运行时绘制界面所需的小图标，替代图片资源文件，
 * 好处是不依赖工作目录、不引入第三方素材、缩放不糊。</p>
 *
 * <p>所有图标都在 {@code [0, size)} 的正方形坐标系内绘制，绘制前已配置抗锯齿与描边样式，
 * 因此每个图标的绘制代码只需关注几何形状。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class Glyphs {

    /** 图标绘制回调 */
    public interface Painter {

        /**
         * 绘制图形。
         *
         * @param g    已配置抗锯齿与描边的画笔
         * @param size 图标边长（像素）
         */
        void paint(Graphics2D g, int size);
    }

    /** 私有构造，禁止实例化工具类 */
    private Glyphs() {
    }

    /**
     * 构造一个矢量图标。
     *
     * @param size    边长
     * @param color   颜色
     * @param painter 绘制回调
     * @return 图标对象
     */
    public static Icon of(final int size, final Color color, final Painter painter) {
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
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.translate(x, y);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(Math.max(1.4f, size / 8f),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                painter.paint(g2, size);
                g2.dispose();
            }
        };
    }

    /**
     * 应用徽标：圆角渐变方块内嵌一个对话气泡。
     *
     * @param size 边长
     * @return 图标
     */
    public static Icon logo(final int size) {
        return of(size, Color.WHITE, (g, s) -> {
            g.setPaint(new GradientPaint(0, 0, Theme.PRIMARY, s, s, Theme.PRIMARY_DARK));
            g.fillRoundRect(0, 0, s - 1, s - 1, Math.max(4, s / 3), Math.max(4, s / 3));
            g.setColor(Color.WHITE);
            int margin = Math.max(2, s / 5);
            int bubbleHeight = Math.max(4, (int) (s * 0.40));
            g.fillRoundRect(margin, margin, s - 2 * margin, bubbleHeight,
                    Math.max(2, s / 6), Math.max(2, s / 6));
            int tailX = margin + Math.max(1, s / 8);
            g.fillPolygon(new int[]{tailX, tailX + Math.max(2, s / 6), tailX},
                    new int[]{margin + bubbleHeight - 1, margin + bubbleHeight - 1,
                            Math.min(s - margin, margin + bubbleHeight + Math.max(2, s / 6))}, 3);
        });
    }

    /**
     * 最小化图标。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon minimize(final int size, final Color color) {
        return of(size, color, (g, s) -> g.drawLine((int) (s * 0.24), (int) (s * 0.56),
                (int) (s * 0.76), (int) (s * 0.56)));
    }

    /**
     * 关闭图标。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon close(final int size, final Color color) {
        return of(size, color, (g, s) -> {
            g.drawLine((int) (s * 0.28), (int) (s * 0.28), (int) (s * 0.72), (int) (s * 0.72));
            g.drawLine((int) (s * 0.72), (int) (s * 0.28), (int) (s * 0.28), (int) (s * 0.72));
        });
    }

    /**
     * 刷新图标：缺口圆环加箭头。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon refresh(final int size, final Color color) {
        return of(size, color, (g, s) -> {
            int pad = Math.max(2, s / 6);
            g.drawArc(pad, pad, s - 2 * pad, s - 2 * pad, 40, 280);
            g.fillPolygon(new int[]{(int) (s * 0.72), (int) (s * 0.86), (int) (s * 0.76)},
                    new int[]{(int) (s * 0.20), (int) (s * 0.30), (int) (s * 0.42)}, 3);
        });
    }

    /**
     * 搜索图标：放大镜。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon search(final int size, final Color color) {
        return of(size, color, (g, s) -> {
            int d = (int) (s * 0.52);
            g.drawOval((int) (s * 0.16), (int) (s * 0.14), d, d);
            g.drawLine((int) (s * 0.62), (int) (s * 0.62), (int) (s * 0.82), (int) (s * 0.82));
        });
    }

    /**
     * 群聊图标：两个重叠气泡。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon group(final int size, final Color color) {
        return of(size, color, (g, s) -> {
            g.drawRoundRect((int) (s * 0.10), (int) (s * 0.16),
                    (int) (s * 0.52), (int) (s * 0.34), s / 6, s / 6);
            g.drawRoundRect((int) (s * 0.38), (int) (s * 0.44),
                    (int) (s * 0.52), (int) (s * 0.34), s / 6, s / 6);
        });
    }

    /**
     * 文件图标：文档加折角。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon file(final int size, final Color color) {
        return of(size, color, (g, s) -> {
            int left = (int) (s * 0.22);
            int top = (int) (s * 0.14);
            int right = (int) (s * 0.72);
            int bottom = (int) (s * 0.86);
            g.drawLine(left, top, (int) (s * 0.54), top);
            g.drawLine((int) (s * 0.54), top, right, (int) (s * 0.40));
            g.drawLine(right, (int) (s * 0.40), right, bottom);
            g.drawLine(right, bottom, left, bottom);
            g.drawLine(left, bottom, left, top);
            g.drawLine((int) (s * 0.36), (int) (s * 0.58), (int) (s * 0.60), (int) (s * 0.58));
            g.drawLine((int) (s * 0.36), (int) (s * 0.72), (int) (s * 0.60), (int) (s * 0.72));
        });
    }

    /**
     * 历史记录图标：表盘。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon history(final int size, final Color color) {
        return of(size, color, (g, s) -> {
            int pad = Math.max(2, s / 7);
            g.drawOval(pad, pad, s - 2 * pad, s - 2 * pad);
            g.drawLine(s / 2, (int) (s * 0.30), s / 2, s / 2);
            g.drawLine(s / 2, s / 2, (int) (s * 0.66), (int) (s * 0.60));
        });
    }

    /**
     * 导出图标：托盘加下箭头。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon export(final int size, final Color color) {
        return of(size, color, (g, s) -> {
            g.drawLine(s / 2, (int) (s * 0.16), s / 2, (int) (s * 0.58));
            g.drawLine((int) (s * 0.34), (int) (s * 0.44), s / 2, (int) (s * 0.60));
            g.drawLine((int) (s * 0.66), (int) (s * 0.44), s / 2, (int) (s * 0.60));
            g.drawLine((int) (s * 0.20), (int) (s * 0.72), (int) (s * 0.20), (int) (s * 0.84));
            g.drawLine((int) (s * 0.20), (int) (s * 0.84), (int) (s * 0.80), (int) (s * 0.84));
            g.drawLine((int) (s * 0.80), (int) (s * 0.84), (int) (s * 0.80), (int) (s * 0.72));
        });
    }

    /**
     * 用户图标。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon user(final int size, final Color color) {
        return of(size, color, (g, s) -> {
            int d = (int) (s * 0.34);
            g.drawOval((s - d) / 2, (int) (s * 0.16), d, d);
            g.drawArc((int) (s * 0.20), (int) (s * 0.52), (int) (s * 0.60), (int) (s * 0.44),
                    0, 180);
        });
    }

    /**
     * 密码锁图标。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon lock(final int size, final Color color) {
        return of(size, color, (g, s) -> {
            g.drawArc((int) (s * 0.32), (int) (s * 0.16), (int) (s * 0.36), (int) (s * 0.40),
                    0, 180);
            g.drawRoundRect((int) (s * 0.24), (int) (s * 0.44),
                    (int) (s * 0.52), (int) (s * 0.40), s / 6, s / 6);
        });
    }

    /**
     * 删除图标：垃圾桶。
     *
     * @param size  边长
     * @param color 颜色
     * @return 图标
     */
    public static Icon trash(final int size, final Color color) {
        return of(size, color, (g, s) -> {
            g.drawLine((int) (s * 0.18), (int) (s * 0.30), (int) (s * 0.82), (int) (s * 0.30));
            g.drawLine((int) (s * 0.40), (int) (s * 0.30), (int) (s * 0.40), (int) (s * 0.20));
            g.drawLine((int) (s * 0.60), (int) (s * 0.30), (int) (s * 0.60), (int) (s * 0.20));
            g.drawLine((int) (s * 0.40), (int) (s * 0.20), (int) (s * 0.60), (int) (s * 0.20));
            g.drawLine((int) (s * 0.28), (int) (s * 0.30), (int) (s * 0.34), (int) (s * 0.84));
            g.drawLine((int) (s * 0.72), (int) (s * 0.30), (int) (s * 0.66), (int) (s * 0.84));
            g.drawLine((int) (s * 0.34), (int) (s * 0.84), (int) (s * 0.66), (int) (s * 0.84));
        });
    }
}

package com.chat.client.ui.theme;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 绘制未读数量气泡。
 */
public final class Badge {
    /** 气泡内文字的字号。 */
    private static final int FONT_SIZE = 11;

    private static final Color BACKGROUND = Theme.DANGER;

    private static final Color FOREGROUND = Color.WHITE;

    /** 气泡的最小直径，单位像素。 */
    private static final int MIN_DIAMETER = 16;

    /** 文字与气泡左右边缘之间的间距，单位像素。 */
    private static final int PADDING = 5;

    /** 气泡显示的最大数字，超出时显示为 99+。 */
    private static final int MAX_DISPLAY = 99;

    /** 预取的字体度量，用于测量文字宽度。 */
    private static final FontMetrics METRICS = probeMetrics();

    private Badge() {
    }

    /**
     * 返回气泡的宽度，数量不大于 0 时返回 0。
     *
     * @param count 未读数量
     * @return 气泡宽度，单位像素
     */
    public static int widthOf(int count) {
        if (count <= 0) {
            return 0;
        }
        return Math.max(MIN_DIAMETER, METRICS.stringWidth(textOf(count)) + PADDING * 2);
    }

    /**
     * 返回气泡的高度，数量不大于 0 时返回 0。
     *
     * @param count 未读数量
     * @return 气泡高度，单位像素
     */
    public static int heightOf(int count) {
        return count <= 0 ? 0 : MIN_DIAMETER;
    }

    /**
     * 在指定位置绘制未读气泡。
     *
     * @param g 绘图上下文
     * @param rightEdge 气泡右边缘的横坐标
     * @param centerY 气泡垂直中心的纵坐标
     * @param count 未读数量，不大于 0 时不绘制
     */
    public static void paint(Graphics2D g, int rightEdge, int centerY, int count) {
        if (count <= 0) {
            return;
        }
        String text = textOf(count);
        int width = Math.max(MIN_DIAMETER, METRICS.stringWidth(text) + PADDING * 2);
        int height = MIN_DIAMETER;
        int x = rightEdge - width;
        int y = centerY - height / 2;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BACKGROUND);
        g.fillRoundRect(x, y, width, height, height, height);

        g.setColor(FOREGROUND);
        g.setFont(Theme.font(FONT_SIZE, Font.BOLD));
        int textX = x + (width - METRICS.stringWidth(text)) / 2;

        int textY = y + (height - METRICS.getHeight()) / 2 + METRICS.getAscent();
        g.drawString(text, textX, textY);
    }

    /** 返回气泡内显示的文字，超过上限时带上加号。 */
    private static String textOf(int count) {
        return count > MAX_DISPLAY ? MAX_DISPLAY + "+" : String.valueOf(count);
    }

    /** 创建临时图像以取得气泡字体度量。 */
    private static FontMetrics probeMetrics() {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        try {
            return g.getFontMetrics(Theme.font(FONT_SIZE, Font.BOLD));
        } finally {
            g.dispose();
        }
    }
}

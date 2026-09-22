package com.chat.client.ui.theme;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 未读角标绘制工具。
 *
 * <p>职责：把"未读 N 条"这一件事画成统一的红色胶囊，供好友树的行内角标与主窗口标题栏的
 * 合计角标共用。两处若各画一遍，圆角半径、内边距与字号迟早会走样，而它们出现在同一个窗口里，
 * 不一致一眼就能看出来。</p>
 *
 * <p>只提供静态绘制，不持有业务状态：角标画成什么样由本类决定，未读数据始终由调用方保管，
 * 这样刷新用户列表重建树时不会把计数连同节点一起丢掉。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class Badge {

    /** 角标字号 */
    private static final int FONT_SIZE = 11;

    /** 角标背景色：与错误提示、撤回入口同色系，保持"需要注意"的语义一致 */
    private static final Color BACKGROUND = Theme.DANGER;

    /** 角标文字色 */
    private static final Color FOREGROUND = Color.WHITE;

    /** 最小直径：一位数时画成正圆，比胶囊更规整 */
    private static final int MIN_DIAMETER = 16;

    /** 文字与胶囊边缘的水平内边距 */
    private static final int PADDING = 5;

    /** 数字超过该值后显示为 "99+"，避免角标被长数字撑得过宽 */
    private static final int MAX_DISPLAY = 99;

    /** 字体度量缓存：宽度计算发生在树的行渲染里，每次现开图像取度量会拖慢滚动 */
    private static final FontMetrics METRICS = probeMetrics();

    /** 私有构造，禁止实例化工具类 */
    private Badge() {
    }

    /**
     * 计算角标需要的宽度。
     *
     * @param count 未读条数
     * @return 宽度（像素）；无需绘制时返回 0
     */
    public static int widthOf(int count) {
        if (count <= 0) {
            return 0;
        }
        return Math.max(MIN_DIAMETER, METRICS.stringWidth(textOf(count)) + PADDING * 2);
    }

    /**
     * 计算角标需要的高度。
     *
     * @param count 未读条数
     * @return 高度（像素）；无需绘制时返回 0
     */
    public static int heightOf(int count) {
        return count <= 0 ? 0 : MIN_DIAMETER;
    }

    /**
     * 绘制角标。
     *
     * <p>以右上角为锚点绘制：调用方只需要说"角标右边缘该停在哪"。好友树的行右端与标题栏的
     * 按钮区左侧都是这么定位的——用右边缘比用左边缘省事，因为宽度会随数字位数变化。</p>
     *
     * @param g         画笔（调用方负责创建与释放）
     * @param rightEdge 角标右边缘的横坐标
     * @param centerY   角标垂直中心线的纵坐标
     * @param count     未读条数，小于等于 0 时不绘制
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
        // 用字体度量定位基线而不是写死偏移：不同平台的中文字体度量不同，
        // 写死 "+4" 之类的偏移换一台机器就会偏
        int textY = y + (height - METRICS.getHeight()) / 2 + METRICS.getAscent();
        g.drawString(text, textX, textY);
    }

    /**
     * 把未读条数转换成角标文字。
     *
     * @param count 未读条数
     * @return 角标文字
     */
    private static String textOf(int count) {
        return count > MAX_DISPLAY ? MAX_DISPLAY + "+" : String.valueOf(count);
    }

    /**
     * 取一次本类字体的度量并缓存。
     *
     * @return 字体度量
     */
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

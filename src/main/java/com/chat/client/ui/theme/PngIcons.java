package com.chat.client.ui.theme;

import com.chat.common.Constants;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 主窗口功能入口的 PNG 图标加载器。
 *
 * <p>为什么要有它：本项目的界面在此之前全部由代码绘制（{@link Glyphs} 画矢量图形），
 * 不依赖任何图片资源，走到哪台机器都是同一副样子。主窗口的四个入口改为使用者提供的
 * PNG 素材后，"图片放在哪、找不到怎么办"就成了一件必须有人负责的事——放在各调用点
 * 就是四份重复的兜底逻辑。</p>
 *
 * <p>查找顺序：先类路径 {@code /image/<名称>.png}，再项目根目录下的 {@code image/} 目录。
 * 前者服务于"打包进 jar"的场合，后者服务于"直接跑源码目录"的场合——本项目属于后者，
 * 但把两种都覆盖上，换运行方式时不需要改代码。</p>
 *
 * <p>找不到图片时返回调用方给的兜底图标而不是抛异常：一枚按钮的图标缺失不该让主窗口
 * 打不开，退回 {@link Glyphs} 的矢量图形至少还能用。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class PngIcons {

    /** 日志记录器：图标缺失属于"能继续跑但要知道"的问题，只留日志不打扰使用者 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".ui.theme.PngIcons");

    /** 图片目录：项目根目录下的 image/ */
    private static final String IMAGE_DIR = Constants.ROOT_DIR + File.separator + "image";

    /** 已按原始尺寸解出的图片缓存：同名图片只读一次磁盘 */
    private static final Map<String, BufferedImage> SOURCES = new ConcurrentHashMap<>();

    /** 确认缺失的图片名：缺失是常态（用户可以只放几张），不必每次都去试一遍磁盘 */
    private static final Map<String, Boolean> MISSING = new ConcurrentHashMap<>();

    /** 私有构造，禁止实例化工具类 */
    private PngIcons() {
    }

    /**
     * 按名称加载一张图标。
     *
     * @param name     图片文件名（不含扩展名），如 {@code 搜索}
     * @param side     期望的显示边长（像素），图片按短边等比缩放后居中放入正方形
     * @param fallback 兜底图标；图片缺失或读取失败时返回它，可为 null
     * @return 图标
     */
    public static Icon load(String name, int side, Icon fallback) {
        BufferedImage source = read(name);
        if (source == null) {
            return fallback;
        }
        return new ImageIcon(scaleToSquare(source, side));
    }

    /**
     * 读取原始图片，优先类路径、其次项目目录。
     *
     * @param name 图片文件名（不含扩展名）
     * @return 图片；找不到或读取失败返回 null
     */
    private static BufferedImage read(String name) {
        if (name == null || name.isEmpty() || MISSING.containsKey(name)) {
            return null;
        }
        BufferedImage cached = SOURCES.get(name);
        if (cached != null) {
            return cached;
        }
        BufferedImage loaded = readFromClasspath(name);
        if (loaded == null) {
            loaded = readFromFile(name);
        }
        if (loaded == null) {
            MISSING.put(name, Boolean.TRUE);
            LOGGER.info(() -> "未找到图标图片，将使用内置矢量图标: " + name);
            return null;
        }
        SOURCES.put(name, loaded);
        return loaded;
    }

    /**
     * 从类路径读取图片。
     *
     * @param name 图片文件名（不含扩展名）
     * @return 图片；不在类路径上或读取失败返回 null
     */
    private static BufferedImage readFromClasspath(String name) {
        String resource = "/image/" + name + ".png";
        try (InputStream in = PngIcons.class.getResourceAsStream(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "读取类路径图标失败: " + resource, e);
            return null;
        }
    }

    /**
     * 从项目根目录下的 image/ 目录读取图片。
     *
     * @param name 图片文件名（不含扩展名）
     * @return 图片；文件不在或读取失败返回 null
     */
    private static BufferedImage readFromFile(String name) {
        File file = new File(IMAGE_DIR, name + ".png");
        if (!file.isFile()) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "读取图标文件失败: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 把图片按短边等比缩放进一个正方形容器并居中。
     *
     * <p>刻意不拉伸成正方形：素材的长宽比未必一致（本项目的四张图里就有一张 68×64），
     * 强行拉伸会把图标压变形。</p>
     *
     * @param source 原始图片
     * @param side   容器边长
     * @return 缩放后的正方形图片
     */
    private static BufferedImage scaleToSquare(BufferedImage source, int side) {
        int target = Math.max(1, side);
        BufferedImage result = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = result.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double ratio = Math.min((double) target / source.getWidth(),
                    (double) target / source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
            int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
            g2.drawImage(source, (target - width) / 2, (target - height) / 2, width, height, null);
        } finally {
            g2.dispose();
        }
        return result;
    }
}

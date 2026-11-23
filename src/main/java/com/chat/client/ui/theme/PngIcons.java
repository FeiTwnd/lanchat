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
 * 从类路径或 image 目录加载 PNG 图标，并缩放为正方形。
 */
public final class PngIcons {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".ui.theme.PngIcons");

    /** 外部图标目录。 */
    private static final String IMAGE_DIR = Constants.ROOT_DIR + File.separator + "image";

    /** 已加载的图片缓存。 */
    private static final Map<String, BufferedImage> SOURCES = new ConcurrentHashMap<>();

    /** 记录读取失败的图标名，避免重复访问磁盘。 */
    private static final Map<String, Boolean> MISSING = new ConcurrentHashMap<>();

    private PngIcons() {
    }

    /**
     * 加载指定图标并缩放到给定边长。
     *
     * @param name 图标名，不含扩展名
     * @param side 目标边长，单位像素
     * @param fallback 图标缺失时返回的替代图标
     * @return 加载到的图标，缺失时为 fallback
     */
    public static Icon load(String name, int side, Icon fallback) {
        BufferedImage source = read(name);
        if (source == null) {
            return fallback;
        }
        return new ImageIcon(scaleToSquare(source, side));
    }

    /** 依次从类路径与外部目录读取图片，读取失败时记录并返回 null。 */
    private static BufferedImage read(String name) {
        if (name == null || name.isEmpty() || MISSING.containsKey(name)) {
            return null;
        }
        BufferedImage cached = SOURCES.get(name);
        if (cached != null) {
            return cached;
        }
        // 先查类路径，再查外部图标目录
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

    /** 从类路径的 image 目录读取图片。 */
    private static BufferedImage readFromClasspath(String name) {
        String resource = "/image/" + name + ".png";
        try (InputStream in = PngIcons.class.getResourceAsStream(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "读取类路径图标失败: " + resource, e);
            return null;
        }
    }

    /** 从工作目录下的 image 目录读取图片。 */
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

    /** 把图片等比缩放到指定边长的方形画布中并居中。 */
    private static BufferedImage scaleToSquare(BufferedImage source, int side) {
        int target = Math.max(1, side);
        BufferedImage result = new BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = result.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // 取长边方向的缩放比例，保证图片完整落在画布内
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

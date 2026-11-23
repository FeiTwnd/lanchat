package com.chat.client.ui.theme;

import com.chat.common.Constants;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成并缓存头像图标，支持默认剪影、自定义图片、管理员标记与在线状态点。
 */
public final class AvatarFactory {
    /** 图标缓存，键由用户名、在线状态、管理员标记与尺寸组成。 */
    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    /** 按尺寸缩放后的头像图片缓存。 */
    private static final Map<String, Image> SCALED = new ConcurrentHashMap<>();

    /** 用户名到已解码头像图片的映射。 */
    private static final Map<String, Image> AVATARS = new ConcurrentHashMap<>();

    /** 用户名到原始 Base64 头像数据的映射，用于判断内容是否变化。 */
    private static final Map<String, String> AVATAR_SOURCES = new ConcurrentHashMap<>();

    private AvatarFactory() {
    }

    /**
     * 生成默认的人物剪影头像。
     *
     * @param online 是否在线
     * @param admin 是否为管理员
     * @param size 图标边长，单位像素
     * @return 对应状态的头像图标
     */
    public static Icon avatar(boolean online, boolean admin, int size) {
        String key = "person|" + online + '|' + admin + '|' + size;
        return CACHE.computeIfAbsent(key, ignored -> draw(online, admin, size));
    }

    /**
     * 生成用户头像，缓存有该用户的图片时使用图片，否则使用默认剪影。
     *
     * @param username 用户名，为空时返回默认剪影
     * @param online 是否在线
     * @param admin 是否为管理员
     * @param size 图标边长，单位像素
     * @return 用户头像图标
     */
    public static Icon avatar(String username, boolean online, boolean admin, int size) {
        if (username == null || username.isEmpty()) {
            return avatar(online, admin, size);
        }
        String key = "user|" + username + '|' + online + '|' + admin + '|' + size;

        return CACHE.computeIfAbsent(key, ignored -> drawUser(username, null, online, admin, size));
    }

    /**
     * 缓存用户的 Base64 头像数据并清除其原图标。
     *
     * @param username 用户名
     * @param base64 头像图片的 Base64 文本，为空时删除该用户的头像
     */
    public static void putAvatar(String username, String base64) {
        if (username == null) {
            return;
        }
        if (base64 == null || base64.isEmpty()) {
            AVATARS.remove(username);
            AVATAR_SOURCES.remove(username);
            invalidate(username);
            return;
        }
        if (base64.equals(AVATAR_SOURCES.get(username))) {
            // 头像内容没有变化，沿用已有缓存
            return;
        }
        Image image = decode(base64);

        AVATAR_SOURCES.put(username, base64);
        if (image == null) {
            AVATARS.remove(username);
        } else {
            AVATARS.put(username, image);
        }
        invalidate(username);
    }

    /** 清除指定用户在所有尺寸下的缩放缓存。 */
    private static void invalidate(String username) {
        String prefix = "user|" + username + '|';
        SCALED.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** 把 Base64 文本解码为图片，数据非法或超出限制时返回 null。 */
    private static Image decode(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }

        if (base64.length() > Constants.AVATAR_MAX_LENGTH) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            if (isOversized(raw)) {
                return null;
            }
            return ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IllegalArgumentException | java.io.IOException e) {
            return null;
        }
    }

    /** 读取图片尺寸，判断是否超过边长与像素总量限制。 */
    private static boolean isOversized(byte[] raw) {
        // 只读图片头获取尺寸，不做完整解码
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > Constants.AVATAR_IMAGE_MAX_SIDE || height > Constants.AVATAR_IMAGE_MAX_SIDE) {
                    return true;
                }

                return (long) width * height > Constants.AVATAR_MAX_PIXELS;
            } finally {
                reader.dispose();
            }
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 按给定的 Base64 内容生成预览头像，解码失败时退回默认剪影。
     *
     * @param base64 头像图片的 Base64 文本
     * @param online 是否在线
     * @param admin 是否为管理员
     * @param size 图标边长，单位像素
     * @return 预览头像图标
     */
    public static Icon previewAvatar(String base64, boolean online, boolean admin, int size) {
        Image image = decode(base64);
        if (image == null) {
            return avatar(online, admin, size);
        }
        return drawUser(null, image, online, admin, size);
    }

    /** 清空全部已缓存的自定义头像，保留默认剪影缓存。 */
    public static void clearAvatars() {
        AVATARS.clear();
        AVATAR_SOURCES.clear();

        SCALED.clear();
        CACHE.keySet().removeIf(key -> key.startsWith("user|"));
    }

    /** 为用户或给定图片创建头像图标，每次绘制时叠加状态点与管理员圆环。 */
    private static Icon drawUser(final String username, final Image fixed, final boolean online,
                                 final boolean admin, final int size) {
        final Image preview = fixed == null ? null : scale(fixed, size);
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
                // 优先绘制固定图片，否则取该用户已缓存的缩放图
                Image image = fixed != null
                        ? preview
                        : scaledImage(username, online, admin, size);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                int diameter = size - 1;

                if (image != null) {
                    Shape outer = new Ellipse2D.Float(0, 0, diameter, diameter);
                    Shape oldClip = g2.getClip();
                    g2.clip(outer);

                    g2.drawImage(image, 0, 0, null);
                    g2.setClip(oldClip);

                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(Math.max(1f, size / 20f)));
                    g2.drawOval(0, 0, diameter, diameter);
                } else {
                    paintSilhouette(g2, size, online);
                }

                if (admin) {
                    paintAdminRing(g2, size);
                }
                paintStatusDot(g2, size, online);
                g2.dispose();
            }
        };
    }

    /** 返回该用户按指定尺寸缩放后的头像图片，未缓存图片时返回 null。 */
    private static Image scaledImage(String username, boolean online, boolean admin, int size) {
        Image source = AVATARS.get(username);
        if (source == null) {
            return null;
        }
        String key = "user|" + username + '|' + online + '|' + admin + '|' + size;
        return SCALED.computeIfAbsent(key, ignored -> scale(source, size));
    }

    /** 把图片等比缩放为指定边长的方形图像。 */
    private static Image scale(Image source, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        g2.drawImage(source, 0, 0, size, size, null);
        g2.dispose();
        return scaled;
    }

    /**
     * 生成群聊头像，用圆角方块和三个人物轮廓表示群组。
     *
     * @param size 图标边长，单位像素
     * @return 群聊头像图标
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
                paintSilhouette(g2, size, online);
                if (admin) {
                    paintAdminRing(g2, size);
                }
                paintStatusDot(g2, size, online);
                g2.dispose();
            }
        };
    }

    private static void paintSilhouette(Graphics2D g2, int size, boolean online) {
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
    }

    private static void paintAdminRing(Graphics2D g2, int size) {
        g2.setColor(Theme.ADMIN);
        g2.setStroke(new BasicStroke(Math.max(1.5f, size / 14f)));
        g2.drawOval(1, 1, size - 3, size - 3);
    }

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

package com.chat.client.ui.theme;

import com.chat.common.Constants;

import javax.imageio.ImageIO;
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
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 头像绘制工厂。
 *
 * <p>职责：绘制用户头像——未设置自定义头像时用 Java2D 画默认人形剪影，
 * 已设置时绘制用户上传的图片；两种情况都在右下角叠加在线状态点、
 * 在边缘叠加管理员标记，因此位置与配色完全一致。</p>
 *
 * <p>为什么不再按用户名生成彩色首字头像：首字头像需要给每个名字稳定地分配底色，
 * 于是每家聊天软件都长得不一样，而渐变色在浅色列表里显得杂乱；默认头像只保留
 * "这里是一个人"这一个语义，在线状态交给状态点表达，视觉上更干净、也更容易维护。</p>
 *
 * <p>绘制结果按"账号、在线状态、管理员标记、尺寸"缓存，避免列表每次刷新都重绘。</p>
 *
 * <p>线程约束：自定义头像表是静态的（头像属于公共资料，与登录会话无关），
 * 用 {@link ConcurrentHashMap} 保证多线程读写安全。界面线程读取即可，
 * 登记与清理由收到用户列表的一方负责。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class AvatarFactory {

    /** 头像缓存 */
    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    /**
     * 自定义头像表：用户名 -> 已解码的图片。
     *
     * <p>只存解码结果不存 Base64：Base64 每次绘制都要解一遍，而头像会在
     * 用户树、资料卡、每个聊天气泡上各画一次，解码开销会被放大很多倍。</p>
     */
    private static final Map<String, Image> AVATARS = new ConcurrentHashMap<>();

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
     * 获取指定用户的头像：有自定义头像则画自定义图片，否则回落到默认剪影。
     *
     * <p>返回的图标在<b>每次绘制时</b>才去 {@link #AVATARS} 里取当前图片，
     * 而不是在创建时就把图片抓在手里。这一点是有意为之：头像会在用户树、资料卡、
     * 每一条聊天气泡上各出现一次，图片换了以后那些图标对象早已被各自的标签持有，
     * 逐个去找出来替换既繁琐又容易漏（漏掉的那一处就永远停着旧头像）；
     * 让绘制去取当前值，换完头像只要重画一次窗口，所有出现位置自然全部更新。</p>
     *
     * @param username 用户名，为空或没有自定义头像时使用默认头像
     * @param online   是否在线，决定状态点颜色与默认头像底色深浅
     * @param admin    是否管理员，决定是否描边
     * @param size     边长
     * @return 图标
     */
    public static Icon avatar(String username, boolean online, boolean admin, int size) {
        if (username == null || username.isEmpty()) {
            return avatar(online, admin, size);
        }
        String key = "user|" + username + '|' + online + '|' + admin + '|' + size;
        // 缓存键里不含图片内容：绘制时才取图，同一个键对应的图标始终画当前图片，
        // 若把内容摘要拼进键里，静态缓存会随每次换头像无限增长
        return CACHE.computeIfAbsent(key, ignored -> drawUser(username, null, online, admin, size));
    }

    /**
     * 登记某个用户的自定义头像。
     *
     * <p>Base64 为空表示该用户未设置头像（或已恢复默认），此时只做登记清理。
     * 解码失败同样按未设置处理：显示一张默认头像，好过让整个列表渲染抛异常。</p>
     *
     * @param username 用户名
     * @param base64   头像 PNG 的 Base64 文本，可为 null
     */
    public static void putAvatar(String username, String base64) {
        if (username == null) {
            return;
        }
        if (base64 == null || base64.isEmpty()) {
            AVATARS.remove(username);
            return;
        }
        Image image = decode(base64);
        if (image == null) {
            // 解码失败按"未设置"处理：显示默认头像好过让整个列表渲染抛异常
            AVATARS.remove(username);
            return;
        }
        AVATARS.put(username, image);
    }

    /**
     * 解码头像图片，<b>不登记</b>到任何表里。
     *
     * <p>抽出来供两条路径共用：{@link #putAvatar(String, String)} 用它落库登记，
     * {@link #previewAvatar(String, boolean, boolean, int)} 用它画一张只属于调用方的预览图。
     * 两处各写一遍解码，迟早会出现"预览能显示、保存后却失败"这类只差一个校验的落差。</p>
     *
     * @param base64 头像 PNG 的 Base64 文本
     * @return 解码后的图片；内容为空、超长或格式非法时返回 null
     */
    private static Image decode(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        // 防御性上限：报文本该已被服务端校验过，但客户端不该假设收到的数据一定合规，
        // 否则一个超大报文会在解码这一步把客户端拖死
        if (base64.length() > Constants.AVATAR_MAX_LENGTH) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            return ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IllegalArgumentException | java.io.IOException e) {
            return null;
        }
    }

    /**
     * 画一张"仅本处可见"的头像预览。
     *
     * <p>为什么需要它：设置窗口要在使用者点"保存"<b>之前</b>把选好的图片显示出来，
     * 而 {@link #putAvatar(String, String)} 改的是全局头像表——预览一旦走那条路，
     * 主窗口、用户列表、每一条聊天气泡都会立刻换成新头像，等于"没点保存也已经生效"，
     * 想放弃都退不回去。因此预览拿到的图标自己攥着那张图片，不写进任何共享状态。</p>
     *
     * @param base64 头像 PNG 的 Base64 文本；为 null 或空串表示预览"恢复默认"
     * @param online 是否在线，决定状态点颜色与默认头像底色深浅
     * @param admin  是否管理员，决定是否描边
     * @param size   边长
     * @return 预览图标
     */
    public static Icon previewAvatar(String base64, boolean online, boolean admin, int size) {
        Image image = decode(base64);
        if (image == null) {
            // 没有可画的图片（含"恢复默认"）时回落到默认剪影：这条路径不读全局头像表，
            // 因此正好画出"清除自定义头像之后"的样子
            return avatar(online, admin, size);
        }
        return drawUser(null, image, online, admin, size);
    }

    /**
     * 清空全部已登记的自定义头像。
     *
     * <p>退出登录时调用：下一个登录的可能是另一个人，留着上一位用户看到的头像
     * 既浪费内存，也会在新列表到达之前的一瞬间显示错误的人。</p>
     */
    public static void clearAvatars() {
        AVATARS.clear();
        CACHE.keySet().removeIf(key -> key.startsWith("user|"));
    }

    /**
     * 绘制某个用户的头像：有自定义图片就画图片，没有就画默认剪影。
     *
     * <p>两种情况共用同一个图标对象，是因为"有没有自定义头像"随时会变
     * （使用者刚在设置里换了一张），而图标一旦被标签持有就不会再被替换。
     * 把判断挪到每次绘制时做，两种形态之间的切换就不需要任何通知机制。</p>
     *
     * <p>画自定义图片时裁成圆形并加一圈细白边：圆形与默认头像保持一致的视觉轮廓，
     * 白边是为了让深色照片不与浅色卡片背景糊在一起。</p>
     *
     * @param username 用户名；{@code fixed} 非空时不会用到，可传 null
     * @param fixed    固定要画的图片；为 null 表示每次绘制时去全局头像表取
     * @param online   是否在线
     * @param admin    是否管理员
     * @param size     边长
     * @return 图标
     */
    private static Icon drawUser(final String username, final Image fixed, final boolean online,
                                 final boolean admin, final int size) {
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
                // 固定图片只属于这一个图标对象（预览用），没有时才去查全局头像表：
                // 三元表达式短路，fixed 非空时不会走到 get(null)
                Image image = fixed != null ? fixed : AVATARS.get(username);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // 双线性插值：头像从 96 缩到 34 这类尺寸时，默认的最近邻会出现明显锯齿
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.translate(x, y);
                int diameter = size - 1;

                if (image != null) {
                    // 圆形裁切只服务于自定义图片；默认剪影由 paintSilhouette 自己裁，
                    // 外面再套一层同一个椭圆会把边缘的抗锯齿覆盖率算两遍
                    // （覆盖率相乘），圆边上那一圈像素会淡到近乎消失
                    Shape outer = new Ellipse2D.Float(0, 0, diameter, diameter);
                    Shape oldClip = g2.getClip();
                    g2.clip(outer);
                    // 宽高都画成 size，由上传方保证图片是正方形（客户端已居中裁切）
                    g2.drawImage(image, 0, 0, size, size, null);
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
     * 绘制默认头像（已知在线与角色，但不知道是谁）。
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
                paintSilhouette(g2, size, online);
                if (admin) {
                    paintAdminRing(g2, size);
                }
                paintStatusDot(g2, size, online);
                g2.dispose();
            }
        };
    }

    /**
     * 在画布原点处绘制默认人形剪影（含底色圆）。
     *
     * <p>人形由"头部圆 + 肩部椭圆"两部分组成，绘制前把画布裁剪成外圆，
     * 肩部超出圆的部分会被自动裁掉，不需要手工计算交点。</p>
     *
     * <p>抽成共用方法：自定义头像取不到图片时走的也是这条路径，
     * 两处各画一遍的话，默认头像的形状一改就会出现两种剪影。</p>
     *
     * @param g2     画笔，调用前必须已平移到头像左上角
     * @param size   边长
     * @param online 是否在线，决定底色深浅
     */
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

    /**
     * 在头像边缘绘制管理员标记环。
     *
     * <p>抽成共用方法是为了让默认头像与自定义头像的管理员标记完全一致：
     * 两处各画一遍的话，改了一处忘了另一处，同一个管理员在不同界面上就会长得不一样。</p>
     *
     * @param g2   画笔
     * @param size 头像边长
     */
    private static void paintAdminRing(Graphics2D g2, int size) {
        g2.setColor(Theme.ADMIN);
        g2.setStroke(new BasicStroke(Math.max(1.5f, size / 14f)));
        g2.drawOval(1, 1, size - 3, size - 3);
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

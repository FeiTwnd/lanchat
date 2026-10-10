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
 * <p>绘制结果按"账号、在线状态、管理员标记、尺寸"缓存，避免列表每次刷新都重绘；
 * 自定义头像的<b>缩放结果</b>同样按这四个维度缓存，因此绘制路径上不会对原图做插值缩放。</p>
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
     * 缩放结果缓存：用户名、在线状态、管理员标记、尺寸 -> 已缩放到目标尺寸的图片。
     *
     * <p>为什么必须缓存：{@link Icon#paintIcon} 会被 Swing 反复调用（滚动、悬停、
     * 展开树节点都会触发重绘），若每次绘制都用原图插值缩放，一张 4096×4096 的图
     * 每次重绘就要上百毫秒，界面会持续卡顿。缓存后每个组合只缩放一次。</p>
     *
     * <p>容量天然受限：条目数上限是"在线的自定义头像用户数 × 出现过的尺寸组合数"，
     * 用户清除头像或换头像时由 {@link #invalidate(String)} 精确删除对应条目，
     * 因此不需要额外做淘汰策略。</p>
     */
    private static final Map<String, Image> SCALED = new ConcurrentHashMap<>();

    /**
     * 自定义头像表：用户名 -> 已解码的图片。
     *
     * <p>只存解码结果不存 Base64：Base64 每次绘制都要解一遍，而头像会在
     * 用户树、资料卡、每个聊天气泡上各画一次，解码开销会被放大很多倍。</p>
     */
    private static final Map<String, Image> AVATARS = new ConcurrentHashMap<>();

    /**
     * 每个用户名最近一次登记的 Base64 原文，用于判断头像内容有没有变。
     *
     * <p>用原文而不是哈希：判重的目的就是"内容完全一致时跳过解码"，原文比较不会
     * 出现哈希碰撞导致的"新头像被当成旧头像而永远显示不出来"；而且这段文本本来
     * 就在用户列表报文里，多留一份引用的内存代价可以忽略。</p>
     */
    private static final Map<String, String> AVATAR_SOURCES = new ConcurrentHashMap<>();

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
        // 若把内容摘要拼进键里，静态缓存会随每次换头像无限增长。
        // 缩放结果另有一份缓存（SCALED），它按内容变化失效，见 putAvatar
        return CACHE.computeIfAbsent(key, ignored -> drawUser(username, null, online, admin, size));
    }

    /**
     * 登记某个用户的自定义头像。
     *
     * <p>Base64 为空表示该用户未设置头像（或已恢复默认），此时连同判重记录一起清理：
     * 否则用户清除头像后又设回同一张图，内容判重会误判成"没变"而把新头像挡掉。
     * 解码失败同样按未设置处理：显示一张默认头像，好过让整个列表渲染抛异常。</p>
     *
     * <p>内容没变时直接返回，不重新解码。用户列表是广播下发的，只要有人上线、
     * 下线或改名，所有在线用户都会收到一次完整列表，其中绝大多数头像与上次完全相同；
     * 逐次重解码等于把每个客户端都拖进无谓的图片解码里，这正是此前界面卡顿的根源之一。</p>
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
            AVATAR_SOURCES.remove(username);
            invalidate(username);
            return;
        }
        if (base64.equals(AVATAR_SOURCES.get(username))) {
            // 与上次登记的内容完全一致（含上次也解码失败的情况）：跳过解码，也无需让缓存失效
            return;
        }
        Image image = decode(base64);
        // 无论解码成功与否都记下这份内容：失败时也记，避免同样的坏数据在下一次广播里被反复重试
        AVATAR_SOURCES.put(username, base64);
        if (image == null) {
            // 解码失败按"未设置"处理：显示默认头像好过让整个列表渲染抛异常
            AVATARS.remove(username);
        } else {
            AVATARS.put(username, image);
        }
        invalidate(username);
    }

    /**
     * 让某个用户已缓存的缩放结果失效。
     *
     * <p>换头像或清除头像时必须调用：缩放缓存按用户名缓存的是"旧图片缩出来的结果"，
     * 不清掉的话，绘制路径会一直画缓存里的旧图，界面上看不到任何变化。</p>
     *
     * @param username 用户名
     */
    private static void invalidate(String username) {
        String prefix = "user|" + username + '|';
        SCALED.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 解码头像图片，<b>不登记</b>到任何表里。
     *
     * <p>抽出来供两条路径共用：{@link #putAvatar(String, String)} 用它落库登记，
     * {@link #previewAvatar(String, boolean, boolean, int)} 用它画一张只属于调用方的预览图。
     * 两处各写一遍解码，迟早会出现"预览能显示、保存后却失败"这类只差一个校验的落差。</p>
     *
     * <p>解码前先用 {@link #isOversized(byte[])} 只读文件头拿宽高：服务端已经拦了超限头像，
     * 但客户端不该假设收到的数据一定合规——服务端被改坏、或有人绕过服务端直接伪造广播报文时，
     * 真正把 1600 万像素的图解进内存的就是这一步。</p>
     *
     * @param base64 头像 PNG 的 Base64 文本
     * @return 解码后的图片；内容为空、超长、超限或格式非法时返回 null
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
            if (isOversized(raw)) {
                return null;
            }
            return ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IllegalArgumentException | java.io.IOException e) {
            return null;
        }
    }

    /**
     * 只读图片头判断尺寸是否超限，<b>不解码像素</b>。
     *
     * <p>为什么要走 {@link ImageReader} 而不是先 {@code ImageIO.read} 再量宽高：
     * 解码才会真正分配像素内存，一张 4096×4096 的 1 位色深 PNG 压缩后只有两千多字节，
     * 却要解出 1600 万像素；先解码再判断等于先把风险承担下来。{@code getWidth/getHeight}
     * 只需要解析文件头（PNG 的 IHDR），代价与图片面积无关。</p>
     *
     * <p>尺寸读不出来时返回 false，交给后面的正常解码路径去报错：这里的职责只是"提前拦超限"，
     * 不是格式校验，格式非法的数据由 {@code ImageIO.read} 返回 null 处理。</p>
     *
     * @param raw 图片原始字节
     * @return 边长或像素总量超过服务端上限时返回 true
     */
    private static boolean isOversized(byte[] raw) {
        // 用内存缓存流而不是 ImageIO.createImageInputStream：后者对 InputStream 可能会
        // 落到临时文件的缓存实现上，头像每次登记都产生磁盘 IO 不值得；头像报文本来就不大
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
                // 与 AvatarCodec 服务端校验同款判断：边长合规但铺满面积的图同样要拦，
                // 用 long 相乘防止溢出绕过上限
                return (long) width * height > Constants.AVATAR_MAX_PIXELS;
            } finally {
                reader.dispose();
            }
        } catch (java.io.IOException | RuntimeException e) {
            return false;
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
        AVATAR_SOURCES.clear();
        // 缩放缓存与图标缓存都按 "user|" 前缀区分于默认头像、群头像的条目，
        // 一次性清掉，避免留下上一位登录者看到过的图片
        SCALED.clear();
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
     * <p>绘制用的图片必须是已缩放到目标边长的：{@link Icon#paintIcon} 会被反复调用，
     * 在绘制路径上做插值缩放等于每次重绘都付一遍缩放开销。取缩放结果由
     * {@link #scaledImage(String, boolean, boolean, int)} 负责，预览图的缩放
     * 在创建图标时一次做完。</p>
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
        // 预览图只属于这一个图标对象，且不会随全局头像表变化，因此在创建时就缩放好：
        // 预览标签同样会因为悬停、布局变化反复重绘，把缩放留在绘制里等于每次重绘都缩放一遍
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
                // 自定义头像每次绘制都重新取缩放结果：换头像时只要让缓存失效，
                // fixed 非空（预览）则用创建时就缩放好的那一张
                Image image = fixed != null
                        ? preview
                        : scaledImage(username, online, admin, size);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                int diameter = size - 1;

                if (image != null) {
                    // 圆形裁切只服务于自定义图片；默认剪影由 paintSilhouette 自己裁，
                    // 外面再套一层同一个椭圆会把边缘的抗锯齿覆盖率算两遍
                    // （覆盖率相乘），圆边上那一圈像素会淡到近乎消失
                    Shape outer = new Ellipse2D.Float(0, 0, diameter, diameter);
                    Shape oldClip = g2.getClip();
                    g2.clip(outer);
                    // 图片已经是 size 见方（缩放见 scaledImage），这里按原始尺寸 1:1 画出，
                    // 绘制路径上不做插值缩放
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

    /**
     * 取某个用户已按目标尺寸缩放好的头像图片。
     *
     * <p>缓存键把在线状态、管理员标记与尺寸一起编进来：缩放结果的像素其实只由原图与尺寸决定，
     * 但键结构与 {@link #avatar(String, boolean, boolean, int)} 的图标缓存保持一致，
     * 换头像或清除头像时就能用同一个 "user|用户名|" 前缀把两类缓存一起清掉，不会漏。
     * 多出的状态组合只是几个小缩略图的引用，条目数上限仍是"用户数 × 尺寸数 × 状态组合"，
     * 用户数本身有限，因此不构成内存风险。</p>
     *
     * @param username 用户名
     * @param online   是否在线
     * @param admin    是否管理员
     * @param size     边长
     * @return 已缩放到 {@code size} 见方的图片；该用户没有自定义头像时返回 null
     */
    private static Image scaledImage(String username, boolean online, boolean admin, int size) {
        Image source = AVATARS.get(username);
        if (source == null) {
            return null;
        }
        String key = "user|" + username + '|' + online + '|' + admin + '|' + size;
        return SCALED.computeIfAbsent(key, ignored -> scale(source, size));
    }

    /**
     * 把头像原图缩放到指定边长。
     *
     * <p>用双线性插值：96 缩到 34 这类尺寸下默认的最近邻会出现明显锯齿。
     * 画到一张带透明通道的 {@link BufferedImage} 上而不是直接画到目标画笔，
     * 是为了让结果能被缓存下来复用。</p>
     *
     * @param source 原图
     * @param size   目标边长
     * @return 缩放后的图片
     */
    private static Image scale(Image source, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // 宽高都画成 size，由上传方保证图片是正方形（客户端已居中裁切）
        g2.drawImage(source, 0, 0, size, size, null);
        g2.dispose();
        return scaled;
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

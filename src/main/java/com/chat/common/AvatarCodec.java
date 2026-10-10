package com.chat.common;

import java.util.Base64;

/**
 * 头像编解码与校验。
 *
 * <p>职责：把客户端上传的头像 Base64 文本校验成"可安全存库、可安全广播"的形态。
 * 客户端负责把用户选择的任意图片裁切缩放成 {@link Constants#AVATAR_SIDE} 见方的 PNG，
 * 本类负责在服务端复核。</p>
 *
 * <p><b>为什么只查 PNG 文件头，而不是解码图片</b>：校验运行在服务端，
 * 而解码图片需要 AWT/{@code ImageIO}——{@code ImageIO.read} 会先把整张图解成
 * {@code BufferedImage} 再谈缩放，一张精心构造的小体积高压缩图片即可在解码时
 * 膨胀出数百 MB 内存，足以打垮服务端。头像的全部用途只是"显示成一个小圆圈"，
 * 因此只读文件头拿到宽高做范围检查即可，不需要、也不应该把它真正解出来。</p>
 *
 * <p>代价是本类不做像素级校验：它只读文件头，因此不做 CRC 校验、不解析 IDAT 的压缩内容，
 * 一段结构完整但内容是垃圾的 PNG 仍会通过；但这样的数据最终只是客户端画不出来的一张图，
 * 危害不超出"这个人的头像坏了"。</p>
 *
 * <p><b>结构完整性检查的意义</b>：只读文件头不等于"只看 24 字节"。若只校验魔数、块长与
 * IHDR 类型，一段 24 字节的手工拼接数据就能冒充 PNG 通过校验并进入广播报文——
 * 它连 IHDR 的 CRC 都不完整，根本不可能解码成图。因此本类还要求数据够长（IHDR 块含
 * CRC 至少 33 字节）且确实存在 IDAT 图像数据块：用极小的代价把"纯文件头"这类
 * 伪造输入挡在库和广播之外。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class AvatarCodec {

    /** PNG 文件签名（魔数）：89 50 4E 47 0D 0A 1A 0A */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * 校验所需的最小字节数：8 字节魔数 + 4 字节块长度 + 4 字节块类型
     * + 4 字节宽 + 4 字节高。
     *
     * <p>这是"能读出宽高"的下限，不代表数据可以算作有效的 PNG，
     * 结构完整性另由 {@link #PNG_MIN_LENGTH} 与 IDAT 检查把关。</p>
     */
    private static final int HEADER_SIZE = 24;

    /**
     * 结构完整的 PNG 至少要有这么多字节：8 字节魔数 + 4 字节块长度 + 4 字节块类型
     * + 13 字节 IHDR 正文 + 4 字节 IHDR 的 CRC。
     *
     * <p>刚好 24 字节的数据连 IHDR 的 CRC 都没写完，用它冒充 PNG 成本极低，
     * 因此不足这个长度的一律拒绝。</p>
     */
    private static final int PNG_MIN_LENGTH = 33;

    /** IHDR（图像头）块的正文长度固定为 13：宽、高、位深、颜色类型等共 13 字节 */
    private static final int IHDR_DATA_LENGTH = 13;

    /** PNG 魔数之后第一个数据块的类型标识，必须是 IHDR（图像头） */
    private static final String IHDR = "IHDR";

    /**
     * IDAT（图像数据）块类型标识。
     *
     * <p>PNG 规定 IHDR 之后必须至少有一个 IDAT，否则这张图没有任何像素可画；
     * 只有文件头的数据正是最常见的伪造形态，因此存在性必须检查。</p>
     */
    private static final byte[] IDAT = {'I', 'D', 'A', 'T'};

    /** 私有构造，禁止实例化工具类 */
    private AvatarCodec() {
    }

    /**
     * 校验头像 Base64 文本。
     *
     * <p>空值与空串视为合法，含义是"未设置头像"，界面回落到默认剪影。
     * 这不是把非法输入放过，而是因为"清空头像"本身就是本类要支持的正常操作——
     * 调用方无需为此另开一条代码路径。</p>
     *
     * @param base64 头像 Base64 文本，可为 null
     * @return 合法返回 null；非法返回可直接回执给客户端的中文原因
     */
    public static String check(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        if (base64.length() > Constants.AVATAR_MAX_LENGTH) {
            return "头像过大，最多 " + Constants.AVATAR_MAX_LENGTH + " 字节";
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return "头像数据不是合法的 Base64 编码";
        }
        if (raw.length < HEADER_SIZE) {
            return "头像数据过短，不是有效的 PNG 图片";
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (raw[i] != PNG_MAGIC[i]) {
                return "头像只支持 PNG 格式的图片";
            }
        }
        // 紧跟魔数的必须是 IHDR，否则说明这不是一张结构正常的 PNG。
        // 块长也必须等于 13：IHDR 的正文长度是规范写死的，手工拼接的文件头往往
        // 只把类型改成 IHDR 而块长留着 0，这两项合起来才算"图像头成立"
        if (readInt(raw, 8) != IHDR_DATA_LENGTH
                || !IHDR.equals(new String(raw, 12, 4, Constants.CHARSET))) {
            return "头像数据缺少 PNG 图像头";
        }
        if (raw.length < PNG_MIN_LENGTH) {
            return "头像数据不完整，不是有效的 PNG 图片";
        }
        // 只有文件头、没有任何 IDAT 图像数据的数据同样是伪造的：它解码不出任何像素，
        // 却会占用广播带宽并让每个客户端白跑一次解码
        if (!contains(raw, IDAT)) {
            return "头像数据缺少 PNG 图像数据";
        }
        int width = readInt(raw, 16);
        int height = readInt(raw, 20);
        if (width <= 0 || height <= 0) {
            return "头像图片尺寸无效";
        }
        if (width > Constants.AVATAR_IMAGE_MAX_SIDE || height > Constants.AVATAR_IMAGE_MAX_SIDE) {
            return "头像图片过大，边长不能超过 " + Constants.AVATAR_IMAGE_MAX_SIDE + " 像素";
        }
        // 边长合规不等于安全：4096×4096 的 1 位色深 PNG 只要两千多字节就能表达
        // 1600 万像素，客户端每次解码都要为它付出上百毫秒，所以必须单独限制像素总量。
        // 用 long 相乘是防止两个大数相乘溢出成负数而绕过上限
        if ((long) width * height > Constants.AVATAR_MAX_PIXELS) {
            return "头像像素过多，最多 " + Constants.AVATAR_MAX_PIXELS + " 像素";
        }
        return null;
    }

    /**
     * 判断头像 Base64 文本是否合法。
     *
     * @param base64 头像 Base64 文本，可为 null
     * @return 合法（含表示"未设置"的空值）返回 true
     */
    public static boolean isValid(String base64) {
        return check(base64) == null;
    }

    /**
     * 从 PNG 数据中读出指定偏移处的 4 字节大端整数。
     *
     * <p>PNG 多字节整数一律为大端序（网络字节序），与 Java 的默认大端一致，
     * 因此可以直接按位拼接。</p>
     *
     * @param data   数据
     * @param offset 起始偏移
     * @return 读出的整数
     */
    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * 判断数据中是否出现过指定的 4 字节块类型签名。
     *
     * <p>只做一次线性扫描，不逐块解析 PNG 结构：IDAT 可以被拆成多个块、位置也不固定，
     * 要准确走到它得把长度与 CRC 都解析一遍，那已经不是"只读文件头"的代价了。
     * 本类要挡的是"只有文件头"的伪造数据，一次存在性扫描就足够。</p>
     *
     * @param data      数据
     * @param signature 4 字节块类型签名
     * @return 出现过返回 true
     */
    private static boolean contains(byte[] data, byte[] signature) {
        for (int i = 0; i <= data.length - signature.length; i++) {
            int matched = 0;
            while (matched < signature.length && data[i + matched] == signature[matched]) {
                matched++;
            }
            if (matched == signature.length) {
                return true;
            }
        }
        return false;
    }
}

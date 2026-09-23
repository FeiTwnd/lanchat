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
 * <p>代价是本类不做像素级校验：伪造的文件头可以通过校验，但它既不会被执行也不会被
 * 服务端解码，最终只是客户端画不出来的一个图片，危害不超出"这个人的头像坏了"。</p>
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
     */
    private static final int HEADER_SIZE = 24;

    /** PNG 魔数之后第一个数据块的类型标识，必须是 IHDR（图像头） */
    private static final String IHDR = "IHDR";

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
        // 紧跟魔数的必须是 IHDR，否则说明这不是一张结构正常的 PNG
        if (!IHDR.equals(new String(raw, 12, 4, Constants.CHARSET))) {
            return "头像数据缺少 PNG 图像头";
        }
        int width = readInt(raw, 16);
        int height = readInt(raw, 20);
        if (width <= 0 || height <= 0) {
            return "头像图片尺寸无效";
        }
        if (width > Constants.AVATAR_IMAGE_MAX_SIDE || height > Constants.AVATAR_IMAGE_MAX_SIDE) {
            return "头像图片过大，边长不能超过 " + Constants.AVATAR_IMAGE_MAX_SIDE + " 像素";
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
}

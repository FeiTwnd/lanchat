package com.chat.common;

import java.util.Base64;

/**
 * 头像 Base64 数据的校验工具，按长度、PNG 文件头和图像尺寸判断数据是否可用。
 */
public final class AvatarCodec {
    /** PNG 文件签名 */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** 读取图像宽高所需的最小头部长度 */
    private static final int HEADER_SIZE = 24;

    /** 一个 PNG 文件的最小字节数：签名加 IHDR 块 */
    private static final int PNG_MIN_LENGTH = 33;

    /** IHDR 块数据段的固定长度 */
    private static final int IHDR_DATA_LENGTH = 13;

    /** 图像头块的类型标识 */
    private static final String IHDR = "IHDR";

    /** 图像数据块的类型标识 */
    private static final byte[] IDAT = {'I', 'D', 'A', 'T'};

    private AvatarCodec() {
    }

    /**
     * 检查头像 Base64 数据是否是可用的 PNG 图片。
     *
     * @param base64 头像的 Base64 文本
     * @return 错误说明，数据合法时返回 null
     */
    public static String check(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        // 校验编码长度上限
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
        // 校验 PNG 文件签名
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (raw[i] != PNG_MAGIC[i]) {
                return "头像只支持 PNG 格式的图片";
            }
        }

        // 校验 IHDR 块与图像头
        if (readInt(raw, 8) != IHDR_DATA_LENGTH
                || !IHDR.equals(new String(raw, 12, 4, Constants.CHARSET))) {
            return "头像数据缺少 PNG 图像头";
        }
        if (raw.length < PNG_MIN_LENGTH) {
            return "头像数据不完整，不是有效的 PNG 图片";
        }

        // 校验图像数据块
        if (!contains(raw, IDAT)) {
            return "头像数据缺少 PNG 图像数据";
        }
        // 校验图像边长与像素总数
        int width = readInt(raw, 16);
        int height = readInt(raw, 20);
        if (width <= 0 || height <= 0) {
            return "头像图片尺寸无效";
        }
        if (width > Constants.AVATAR_IMAGE_MAX_SIDE || height > Constants.AVATAR_IMAGE_MAX_SIDE) {
            return "头像图片过大，边长不能超过 " + Constants.AVATAR_IMAGE_MAX_SIDE + " 像素";
        }

        if ((long) width * height > Constants.AVATAR_MAX_PIXELS) {
            return "头像像素过多，最多 " + Constants.AVATAR_MAX_PIXELS + " 像素";
        }
        return null;
    }

    /**
     * 判断头像数据是否通过校验。
     *
     * @param base64 头像的 Base64 文本
     * @return 通过校验时返回 true
     */
    public static boolean isValid(String base64) {
        return check(base64) == null;
    }

    /** 按大端序读取 4 字节整数。 */
    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

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

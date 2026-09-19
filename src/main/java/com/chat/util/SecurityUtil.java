package com.chat.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 密码安全工具类。
 *
 * <p>职责：提供“加盐 SHA-256 散列”与“校验”两项能力，保证用户密码永不以明文形式落盘。</p>
 *
 * <p>为什么必须加盐：单纯对密码做 SHA-256 会被彩虹表秒破，加入随机盐后，
 * 相同密码在不同账号下散列值不同，攻击者必须为每个账号单独爆破。</p>
 *
 * <p>为什么不引入 BCrypt：题目禁止使用第三方框架，且 JDK 自带
 * {@link MessageDigest} 已能满足课程设计的强度要求。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class SecurityUtil {

    /** 盐值字节长度 */
    private static final int SALT_LENGTH = 16;

    /** 散列算法名称 */
    private static final String ALGORITHM = "SHA-256";

    /** 密码与盐的连接符，同时用于持久化文件的字段分隔 */
    private static final String CREDENTIAL_SEPARATOR = ":";

    /** 安全随机数生成器，用于产生不可预测的盐 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 十六进制编解码器（JDK 17+ 提供，避免手写位运算） */
    private static final HexFormat HEX = HexFormat.of();

    /** 私有构造，禁止实例化工具类 */
    private SecurityUtil() {
    }

    /**
     * 生成随机盐（十六进制字符串）。
     *
     * @return 32 个字符的十六进制盐值
     */
    public static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return HEX.formatHex(salt);
    }

    /**
     * 使用指定盐对密码做 SHA-256 散列。
     *
     * <p>计算方式：{@code SHA-256(盐的原始字节 || 密码字节)}，
     * 先放盐可避免长度扩展攻击对短密码的削弱。</p>
     *
     * @param password 明文密码，不允许为 null
     * @param salt     十六进制盐值，不允许为 null
     * @return 64 个字符的十六进制小写散列值
     * @throws IllegalArgumentException 当参数为 null 或盐值非法时抛出
     */
    public static String hashPassword(String password, String salt) {
        if (password == null) {
            throw new IllegalArgumentException("密码不能为 null");
        }
        if (salt == null || salt.isEmpty()) {
            throw new IllegalArgumentException("盐值不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            digest.update(HEX.parseHex(salt));
            digest.update(password.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，走到这里说明运行环境被裁剪，属于不可恢复错误
            throw new IllegalStateException("当前 JVM 不支持 " + ALGORITHM + " 算法", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("盐值不是合法的十六进制字符串", e);
        }
    }

    /**
     * 生成存储格式的凭据串（盐 + 冒号 + 散列）。
     *
     * @param password 明文密码
     * @return 形如 {@code 3f2a...:9b1c...} 的凭据串
     */
    public static String encode(String password) {
        String salt = generateSalt();
        return salt + CREDENTIAL_SEPARATOR + hashPassword(password, salt);
    }

    /**
     * 校验明文密码与存储凭据是否匹配。
     *
     * <p>使用常量时间比较，避免通过响应时间差异侧信道推断散列前缀。</p>
     *
     * @param rawPassword 用户输入的明文密码
     * @param salt        存储的盐值
     * @param hash        存储的散列值
     * @return 匹配返回 true；任一参数为空时返回 false
     */
    public static boolean verifyPassword(String rawPassword, String salt, String hash) {
        if (rawPassword == null || salt == null || hash == null) {
            return false;
        }
        String actual = hashPassword(rawPassword, salt);
        return constantTimeEquals(actual, hash);
    }

    /**
     * 解析存储凭据串，返回其中的盐值部分。
     *
     * @param credential 形如 {@code salt:hash} 的凭据串
     * @return 盐值；格式非法时返回 null
     */
    public static String extractSalt(String credential) {
        if (credential == null) {
            return null;
        }
        int index = credential.indexOf(CREDENTIAL_SEPARATOR);
        if (index <= 0) {
            return null;
        }
        return credential.substring(0, index);
    }

    /**
     * 解析存储凭据串，返回其中的散列部分。
     *
     * @param credential 形如 {@code salt:hash} 的凭据串
     * @return 散列值；格式非法时返回 null
     */
    public static String extractHash(String credential) {
        if (credential == null) {
            return null;
        }
        int index = credential.indexOf(CREDENTIAL_SEPARATOR);
        if (index <= 0 || index == credential.length() - 1) {
            return null;
        }
        return credential.substring(index + 1);
    }

    /**
     * 计算字节数组的 SHA-256 校验和，用于文件完整性校验。
     *
     * @param data 待计算的数据
     * @return 十六进制小写校验和
     * @throws IllegalArgumentException 当 data 为 null 时抛出
     */
    public static String sha256Hex(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("待校验数据不能为 null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HEX.formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 " + ALGORITHM + " 算法", e);
        }
    }

    /**
     * 常量时间字符串比较。
     *
     * @param left  左值
     * @param right 右值
     * @return 内容相同返回 true
     */
    private static boolean constantTimeEquals(String left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length(); i++) {
            diff |= left.charAt(i) ^ right.charAt(i);
        }
        return diff == 0;
    }
}

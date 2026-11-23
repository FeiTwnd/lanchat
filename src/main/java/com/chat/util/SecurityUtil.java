package com.chat.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 密码散列与校验工具，使用加盐 PBKDF2 和 SHA-256。
 */
public final class SecurityUtil {
    /** 盐值字节数。 */
    private static final int SALT_LENGTH = 16;

    private static final String ALGORITHM = "SHA-256";

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    /** PBKDF2 散列串前缀。 */
    private static final String PBKDF2_PREFIX = "pbkdf2$";

    /** PBKDF2 迭代次数。 */
    private static final int PBKDF2_ITERATIONS = 120_000;

    /** PBKDF2 派生密钥长度，单位为位。 */
    private static final int PBKDF2_KEY_LENGTH_BITS = 256;

    /** SHA-256 十六进制散列的长度。 */
    private static final int LEGACY_HASH_LENGTH = 64;

    private static final String CREDENTIAL_SEPARATOR = ":";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final HexFormat HEX = HexFormat.of();

    private SecurityUtil() {
    }

    /**
     * 生成随机盐值。
     *
     * @return 十六进制盐值文本
     */
    public static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return HEX.formatHex(salt);
    }

    /**
     * 用指定盐值计算密码散列。
     *
     * @param password 明文密码
     * @param salt 十六进制盐值
     * @return 带算法前缀与迭代次数的散列文本
     */
    public static String hashPassword(String password, String salt) {
        if (password == null) {
            throw new IllegalArgumentException("密码不能为 null");
        }
        if (salt == null || salt.isEmpty()) {
            throw new IllegalArgumentException("盐值不能为空");
        }
        return pbkdf2Hash(password, salt, PBKDF2_ITERATIONS);
    }

    /**
     * 生成随机盐值并拼出凭证文本。
     *
     * @param password 明文密码
     * @return 盐值与散列以冒号拼接的凭证文本
     */
    public static String encode(String password) {
        String salt = generateSalt();
        return salt + CREDENTIAL_SEPARATOR + hashPassword(password, salt);
    }

    /**
     * 校验密码是否与散列匹配。
     *
     * @param rawPassword 明文密码
     * @param salt 十六进制盐值
     * @param hash 已存储的散列
     * @return 匹配返回 true
     */
    public static boolean verifyPassword(String rawPassword, String salt, String hash) {
        if (rawPassword == null || salt == null || hash == null) {
            return false;
        }
        // PBKDF2 散列按串内记录的迭代次数重算
        if (hash.startsWith(PBKDF2_PREFIX)) {
            int iterations = parseIterations(hash);
            if (iterations <= 0) {
                return false;
            }
            return constantTimeEquals(pbkdf2Hash(rawPassword, salt, iterations), hash);
        }
        return constantTimeEquals(legacySha256Hex(rawPassword, salt), hash);
    }

    /**
     * 判断散列是否为 64 位十六进制 SHA-256 格式。
     *
     * @param hash 散列文本
     * @return 是 SHA-256 十六进制格式返回 true
     */
    public static boolean isLegacyHash(String hash) {
        return hash != null && !hash.isEmpty()
                && !hash.startsWith(PBKDF2_PREFIX)
                && hash.length() == LEGACY_HASH_LENGTH;
    }

    /**
     * 从凭证文本中取出盐值。
     *
     * @param credential 冒号分隔的凭证文本
     * @return 盐值，格式不符时返回 null
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
     * 从凭证文本中取出散列。
     *
     * @param credential 冒号分隔的凭证文本
     * @return 散列，格式不符时返回 null
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
     * 计算字节数组的 SHA-256 摘要。
     *
     * @param data 待计算数据
     * @return 十六进制摘要文本
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

    private static String pbkdf2Hash(String password, String salt, int iterations) {
        byte[] saltBytes;
        try {
            saltBytes = HEX.parseHex(salt);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("盐值不是合法的十六进制字符串", e);
        }
        // 盐值为十六进制文本，先解码为字节
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, iterations,
                PBKDF2_KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            byte[] derived = factory.generateSecret(spec).getEncoded();
            // 散列串由前缀、迭代次数与十六进制散列组成
            return PBKDF2_PREFIX + iterations + "$" + HEX.formatHex(derived);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("当前 JVM 不支持 " + PBKDF2_ALGORITHM + " 算法", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String legacySha256Hex(String password, String salt) {
        byte[] saltBytes;
        try {
            saltBytes = HEX.parseHex(salt);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("盐值不是合法的十六进制字符串", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            // 摘要按盐值在前、密码在后计算
            digest.update(saltBytes);
            digest.update(password.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 " + ALGORITHM + " 算法", e);
        }
    }

    private static int parseIterations(String hash) {
        int start = PBKDF2_PREFIX.length();
        int end = hash.indexOf('$', start);
        if (end <= start) {
            return -1;
        }
        try {
            int iterations = Integer.parseInt(hash.substring(start, end));
            return iterations > 0 ? iterations : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

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

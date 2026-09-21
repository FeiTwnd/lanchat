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
 * 密码安全工具类。
 *
 * <p>职责：提供“口令散列”与“校验”两项能力，保证用户密码永不以明文形式落盘。</p>
 *
 * <p>为什么必须从单轮加盐 SHA-256 升级为 PBKDF2：SHA-256 是为“快”设计的通用摘要算法，
 * 单张消费级显卡每秒可以尝试上亿次，加盐只能阻止彩虹表、无法阻止逐账号爆破。
 * PBKDF2WithHmacSHA256 把迭代次数（本项目 12 万次）作为人为的时间成本写进算法，
 * 每次尝试都要重复 12 万轮 HMAC 计算，单次尝试成本因此提高数个数量级，
 * 同一张显卡每秒能试的组合从“上亿”降到“几千”，爆破成本超出账号价值。</p>
 *
 * <p>为什么仍兼容历史散列：升级前注册的账号，库里存的是单轮加盐 SHA-256（64 位十六进制）。
 * 若直接拒绝这类散列，所有既有账号会在升级后集体失效。因此校验时按格式分流：
 * 新散列走 PBKDF2，旧散列走历史算法，登录成功后再由业务层透明重算并回写。</p>
 *
 * <p>为什么不引入 BCrypt/Argon2：题目禁止使用第三方依赖，JDK 自带的
 * {@link SecretKeyFactory} 提供的 PBKDF2 已能满足课程设计的强度要求。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class SecurityUtil {

    /** 盐值字节长度 */
    private static final int SALT_LENGTH = 16;

    /** 摘要算法名称，仅用于文件校验与历史散列校验 */
    private static final String ALGORITHM = "SHA-256";

    /** 口令散列算法名称（PBKDF2 的 JDK 自带实现） */
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 口令散列存储格式前缀，用于与历史单轮散列区分 */
    private static final String PBKDF2_PREFIX = "pbkdf2$";

    /**
     * 口令散列迭代次数。
     *
     * <p>取值 12 万是“抗爆破强度”与“登录响应时间”的折中：本机单次散列约几十毫秒，
     * 用户几乎无感，而攻击者要付出的总时间被放大数个数量级。</p>
     */
    private static final int PBKDF2_ITERATIONS = 120_000;

    /** 派生密钥长度（位），256 位与 SHA-256 的安全强度对齐 */
    private static final int PBKDF2_KEY_LENGTH_BITS = 256;

    /** 历史散列格式的长度：SHA-256 的十六进制表示 */
    private static final int LEGACY_HASH_LENGTH = 64;

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
     * 使用指定盐对密码做 PBKDF2 散列。
     *
     * <p>计算方式：以盐的原始字节作为 PBKDF2 的盐，对密码迭代 12 万次后派生 256 位密钥，
     * 最终格式为 {@code pbkdf2$120000$<小写十六进制>}。把迭代次数写进结果，
     * 是为了将来提高迭代数时旧散列仍能被正确校验，不必一次性重置全部口令。</p>
     *
     * @param password 明文密码，不允许为 null
     * @param salt     十六进制盐值，不允许为 null
     * @return 形如 {@code pbkdf2$120000$9b1c...} 的散列值
     * @throws IllegalArgumentException 当参数为 null 或盐值非法时抛出
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
     * 生成存储格式的凭据串（盐 + 冒号 + 散列）。
     *
     * @param password 明文密码
     * @return 形如 {@code 3f2a...:pbkdf2$120000$9b1c...} 的凭据串
     */
    public static String encode(String password) {
        String salt = generateSalt();
        return salt + CREDENTIAL_SEPARATOR + hashPassword(password, salt);
    }

    /**
     * 校验明文密码与存储凭据是否匹配。
     *
     * <p>按散列格式分流：{@code pbkdf2$} 开头的按其中记录的迭代数重新派生后比较；
     * 其余按历史算法（先盐字节后密码字节的单轮 SHA-256）比较，
     * 保证升级前注册的账号仍能正常登录，登录成功后由业务层透明升级散列。</p>
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
     * 判断散列是否为升级前的历史格式。
     *
     * <p>供登录成功后的透明升级使用：历史账号的口令在用户下次登录时被重新散列，
     * 既不需要强制改密，也不会让旧账号失效。</p>
     *
     * @param hash 存储的散列值
     * @return 非空、不以 {@code pbkdf2$} 开头且长度为 64 时返回 true
     */
    public static boolean isLegacyHash(String hash) {
        return hash != null && !hash.isEmpty()
                && !hash.startsWith(PBKDF2_PREFIX)
                && hash.length() == LEGACY_HASH_LENGTH;
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
     * <p>刻意保持单轮 SHA-256：文件校验关心的是“内容是否被改动”，
     * 需要的是快速比对而非抗爆破，套用 PBKDF2 只会让大文件校验慢到不可用。</p>
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
     * 按 PBKDF2 派生口令散列。
     *
     * @param password   明文密码
     * @param salt       十六进制盐值
     * @param iterations 迭代次数
     * @return 形如 {@code pbkdf2$迭代数$十六进制} 的散列值
     * @throws IllegalArgumentException 当盐值不是合法十六进制串时抛出
     * @throws IllegalStateException    当前 JVM 不支持 PBKDF2 时抛出
     */
    private static String pbkdf2Hash(String password, String salt, int iterations) {
        byte[] saltBytes;
        try {
            saltBytes = HEX.parseHex(salt);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("盐值不是合法的十六进制字符串", e);
        }
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, iterations,
                PBKDF2_KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            byte[] derived = factory.generateSecret(spec).getEncoded();
            return PBKDF2_PREFIX + iterations + "$" + HEX.formatHex(derived);
        } catch (GeneralSecurityException e) {
            // PBKDF2 是 JDK 必须支持的算法，走到这里说明运行环境被裁剪，属于不可恢复错误
            throw new IllegalStateException("当前 JVM 不支持 " + PBKDF2_ALGORITHM + " 算法", e);
        } finally {
            // 及时清空口令副本，减少明文口令在内存中的驻留时间
            spec.clearPassword();
        }
    }

    /**
     * 计算历史格式的散列：{@code SHA-256(盐的原始字节 || 密码字节)}。
     *
     * <p>先放盐可避免长度扩展攻击对短密码的削弱。该算法只用于校验升级前的旧散列，
     * 新口令一律走 {@link #hashPassword(String, String)}。</p>
     *
     * @param password 明文密码
     * @param salt     十六进制盐值
     * @return 64 个字符的十六进制小写散列值
     * @throws IllegalArgumentException 当盐值不是合法十六进制串时抛出
     * @throws IllegalStateException    当前 JVM 不支持 SHA-256 时抛出
     */
    private static String legacySha256Hex(String password, String salt) {
        byte[] saltBytes;
        try {
            saltBytes = HEX.parseHex(salt);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("盐值不是合法的十六进制字符串", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            digest.update(saltBytes);
            digest.update(password.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 " + ALGORITHM + " 算法", e);
        }
    }

    /**
     * 从 PBKDF2 散列中解析迭代次数。
     *
     * @param hash 形如 {@code pbkdf2$120000$十六进制} 的散列值
     * @return 迭代次数；格式非法或数值非正时返回 -1
     */
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

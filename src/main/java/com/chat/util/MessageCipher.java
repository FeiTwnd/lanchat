package com.chat.util;

import com.chat.common.Config;
import com.chat.common.Constants;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 聊天记录存储加密工具（对称加密）。
 *
 * <p>职责：对写入数据库的消息正文做加密、读出时解密，使聊天记录在数据库中以密文形态存放。
 * 使用者即使直接查看 {@code chat_message.content} 列，也只能看到 Base64 密文，
 * 无法从中读出聊天内容。</p>
 *
 * <p>算法选择：</p>
 * <ul>
 *   <li>{@code AES/GCM/NoPadding}——GCM 模式自带完整性校验，密文被篡改会在解密时直接失败，
 *       比 AES-CBC 更适合"只存不管"的持久化场景；</li>
 *   <li>每次加密随机生成 12 字节初始向量（IV），相同明文两次加密结果不同，
 *       攻击者无法通过比对密文判断两条消息内容是否相同；</li>
 *   <li>密钥由配置口令经 {@code PBKDF2WithHmacSHA256} 派生，避免把口令直接当密钥使用；
 *       全部来自 JDK 自带实现，不引入任何第三方加密库。</li>
 * </ul>
 *
 * <p>兼容与降级策略：</p>
 * <ul>
 *   <li>密文带固定前缀 {@code enc:v1:}，读取时若没有该前缀则按历史明文处理，
 *       因此"加密前写入的旧记录"依然可读；</li>
 *   <li>口令变更后旧记录无法解密，此时返回占位文本并记录警告，而不是抛异常让界面崩溃。</li>
 * </ul>
 *
 * <p>边界说明：本类只保护"落盘后的聊天正文"，不改变网络传输方式——协议仍是
 * Java 原生对象序列化的明文 TCP 报文，局域网内抓包可见，详见 README 的安全说明。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class MessageCipher {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".MessageCipher");

    /** 密文前缀：用于区分"已加密"与"历史明文" */
    private static final String PREFIX = "enc:v1:";

    /** 加密变换：AES 的 GCM 模式，无需填充 */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 密钥派生算法 */
    private static final String KEY_ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 初始向量长度（字节），12 字节是 GCM 的推荐值 */
    private static final int IV_LENGTH = 12;

    /** 认证标签长度（位），128 位提供完整强度 */
    private static final int TAG_LENGTH_BITS = 128;

    /** 密钥派生迭代次数：兼顾口令强度与启动耗时 */
    private static final int ITERATIONS = 65_536;

    /** 派生密钥长度（位） */
    private static final int KEY_LENGTH_BITS = 256;

    /**
     * 密钥派生使用的固定盐。
     *
     * <p>必须是固定值而不是随机值：密钥要在每次进程启动时重新派生并解出历史记录，
     * 若盐每次随机，重启后就再也解不开之前写入的密文。</p>
     */
    private static final byte[] SALT = "LANChat.chat_message.v1".getBytes(StandardCharsets.UTF_8);

    /** 解密失败时的占位文本 */
    private static final String UNDECRYPTABLE = "[该消息无法解密，可能更换了加密口令]";

    /** 随机数生成器，用于生成不可预测的初始向量 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Base64 编码器 */
    private static final Base64.Encoder ENCODER = Base64.getEncoder();

    /** Base64 解码器 */
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    /** 派生后的密钥，类加载时按配置口令派生一次，避免每条记录都重复计算 */
    private static final SecretKeySpec KEY = deriveKey(Config.messageSecret());

    /** 私有构造，禁止实例化工具类 */
    private MessageCipher() {
    }

    /**
     * 由口令派生 AES 密钥。
     *
     * @param secret 配置中的加密口令
     * @return 256 位 AES 密钥
     * @throws IllegalStateException 当前 JVM 不支持所需算法时抛出（JDK 必须支持，属于不可恢复错误）
     */
    private static SecretKeySpec deriveKey(String secret) {
        char[] password = (secret == null ? "" : secret).toCharArray();
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
            byte[] key = factory.generateSecret(
                    new PBEKeySpec(password, SALT, ITERATIONS, KEY_LENGTH_BITS)).getEncoded();
            return new SecretKeySpec(key, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("初始化消息加密密钥失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加密消息正文。
     *
     * @param plain 明文正文，可为 null
     * @return 形如 {@code enc:v1:Base64(IV||密文)} 的字符串；入参为 null 或空串时原样返回
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + ENCODER.encodeToString(combined);
        } catch (GeneralSecurityException e) {
            // 加密失败宁可放弃这条记录的内容，也不能把明文写进数据库
            LOGGER.log(Level.SEVERE, "消息加密失败，该条记录正文将不落库", e);
            return null;
        }
    }

    /**
     * 解密数据库中的消息正文。
     *
     * @param stored 数据库列值，可能为 null、历史明文或带前缀的密文
     * @return 明文正文；历史明文原样返回；解密失败返回占位提示
     */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            // 无前缀说明是加密之前写入的历史明文，保持可读
            return stored;
        }
        try {
            byte[] combined = DECODER.decode(stored.substring(PREFIX.length()));
            if (combined.length <= IV_LENGTH) {
                LOGGER.warning("密文长度非法，按不可解密处理");
                return UNDECRYPTABLE;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, KEY,
                    new GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "消息解密失败，可能是加密口令已变更: " + e.getMessage(), e);
            return UNDECRYPTABLE;
        }
    }

    /**
     * 判断某个存储值是否为本工具产出的密文。
     *
     * @param stored 数据库列值
     * @return 是密文返回 true
     */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }
}

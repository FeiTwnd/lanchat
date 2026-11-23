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
 * 聊天消息正文的加解密工具，采用 AES-GCM 与 PBKDF2 派生密钥。
 */
public final class MessageCipher {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".MessageCipher");

    /** 密文前缀，用于识别已加密内容。 */
    private static final String PREFIX = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final String KEY_ALGORITHM = "PBKDF2WithHmacSHA256";

    /** GCM 初始向量长度，单位为字节。 */
    private static final int IV_LENGTH = 12;

    /** GCM 认证标签长度，单位为位。 */
    private static final int TAG_LENGTH_BITS = 128;

    /** PBKDF2 迭代次数。 */
    private static final int ITERATIONS = 65_536;

    /** 派生密钥长度，单位为位。 */
    private static final int KEY_LENGTH_BITS = 256;

    /** 密钥派生使用的固定盐。 */
    private static final byte[] SALT = "LANChat.chat_message.v1".getBytes(StandardCharsets.UTF_8);

    /** 解密失败时返回的替代文本。 */
    private static final String UNDECRYPTABLE = "[该消息无法解密，可能更换了加密口令]";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder ENCODER = Base64.getEncoder();

    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private static volatile SecretKeySpec cachedKey;

    private MessageCipher() {
    }

    private static SecretKeySpec secretKey() {
        SecretKeySpec key = cachedKey;
        if (key == null) {
            synchronized (MessageCipher.class) {
                key = cachedKey;
                if (key == null) {
                    key = deriveKey(Config.messageSecret());
                    cachedKey = key;
                }
            }
        }
        return key;
    }

    private static SecretKeySpec deriveKey(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("未配置消息加密口令，请在 config/chat.properties 中设置 "
                    + "security.message.secret 后重试；注意口令变更后此前写入的历史密文将无法解密");
        }
        char[] password = secret.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, SALT, ITERATIONS, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
            byte[] key = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("初始化消息加密密钥失败: " + e.getMessage(), e);
        } finally {
            // 清除口令字符副本
            spec.clearPassword();
        }
    }

    /**
     * 加密消息正文。
     *
     * @param plain 明文
     * @return 带前缀的 Base64 密文，加密失败时返回 null
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            // 每次加密生成随机 IV
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + ENCODER.encodeToString(combined);
        } catch (GeneralSecurityException e) {
            LOGGER.log(Level.SEVERE, "消息加密失败，该条记录正文将不落库", e);
            return null;
        }
    }

    /**
     * 解密消息正文。
     *
     * @param stored 带前缀的密文
     * @return 明文；无前缀时原样返回，解密失败时返回提示文本
     */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] combined = DECODER.decode(stored.substring(PREFIX.length()));
            if (combined.length <= IV_LENGTH) {
                LOGGER.warning("密文长度非法，按不可解密处理");
                return UNDECRYPTABLE;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                    new GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "消息解密失败，可能是加密口令已变更: " + e.getMessage(), e);
            return UNDECRYPTABLE;
        }
    }

    /**
     * 判断文本是否为加密格式。
     *
     * @param stored 待判断文本
     * @return 带加密前缀返回 true
     */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }
}

package com.chat.test;

import com.chat.util.SecurityUtil;

/**
 * 覆盖盐值生成、密码散列、密码校验与 SHA-256 校验和。
 */
public class SecurityUtilTest {
    /** 验证盐值长度固定为 32 且两次生成不同。 */
    @Test("盐值生成：长度 32 且两次生成不同")
    public void testGenerateSalt() {
        String first = SecurityUtil.generateSalt();
        String second = SecurityUtil.generateSalt();
        TestRunner.assertEquals(32, first.length(), "盐值应为 16 字节即 32 个十六进制字符");
        TestRunner.assertNotEquals(first, second, "两次生成的盐值不应相同");
    }

    /** 验证相同密码搭配不同盐得到不同散列。 */
    @Test("散列一致性：相同密码 + 不同盐 结果不同")
    public void testHashWithDifferentSalt() {
        String salt1 = SecurityUtil.generateSalt();
        String salt2 = SecurityUtil.generateSalt();
        String hash1 = SecurityUtil.hashPassword("123456", salt1);
        String hash2 = SecurityUtil.hashPassword("123456", salt2);
        TestRunner.assertTrue(hash1.startsWith("pbkdf2$120000$"),
                "散列应为 pbkdf2$迭代数$十六进制 格式，且迭代数为 120000");
        TestRunner.assertEquals(78, hash1.length(),
                "PBKDF2 散列应为 pbkdf2$120000$ 前缀加 64 位十六进制，共 78 字符");
        TestRunner.assertNotEquals(hash1, hash2, "加盐后相同密码的散列必须不同");
    }

    /** 验证相同密码与相同盐始终得到同一散列。 */
    @Test("散列可复现：相同密码 + 相同盐 结果一致")
    public void testHashDeterministic() {
        String salt = SecurityUtil.generateSalt();
        TestRunner.assertEquals(SecurityUtil.hashPassword("abc123", salt),
                SecurityUtil.hashPassword("abc123", salt), "相同输入必须得到相同散列");
    }

    /** 验证正确密码通过校验，大小写不同或错误的密码被拒绝。 */
    @Test("密码校验：正确通过、错误拒绝")
    public void testVerifyPassword() {
        String salt = SecurityUtil.generateSalt();
        String hash = SecurityUtil.hashPassword("MyPass_2025", salt);
        TestRunner.assertTrue(SecurityUtil.verifyPassword("MyPass_2025", salt, hash),
                "正确密码应校验通过");
        TestRunner.assertFalse(SecurityUtil.verifyPassword("mypass_2025", salt, hash),
                "大小写不同的密码应校验失败");
        TestRunner.assertFalse(SecurityUtil.verifyPassword("MyPass_2026", salt, hash),
                "错误密码应校验失败");
    }

    /** 验证密码、盐或散列任一为空时校验返回 false。 */
    @Test("边界处理：空密码或空凭据返回 false")
    public void testNullSafety() {
        TestRunner.assertFalse(SecurityUtil.verifyPassword(null, "s", "h"), "空密码应返回 false");
        TestRunner.assertFalse(SecurityUtil.verifyPassword("p", null, "h"), "空盐应返回 false");
        TestRunner.assertFalse(SecurityUtil.verifyPassword("p", "s", null), "空散列应返回 false");
    }

    /** 验证编码凭据可解析出盐与散列，非法格式解析为 null。 */
    @Test("凭据解析：encode 后可取出盐与散列")
    public void testEncodeAndParse() {
        String credential = SecurityUtil.encode("123456");
        String salt = SecurityUtil.extractSalt(credential);
        String hash = SecurityUtil.extractHash(credential);
        TestRunner.assertNotNull(salt, "应能解析出盐值");
        TestRunner.assertNotNull(hash, "应能解析出散列");
        TestRunner.assertTrue(SecurityUtil.verifyPassword("123456", salt, hash),
                "解析出的盐与散列应能通过校验");
        TestRunner.assertNull(SecurityUtil.extractSalt("非法格式"), "非法格式应返回 null");
        TestRunner.assertNull(SecurityUtil.extractHash("非法格式"), "非法格式应返回 null");
    }

    /** 验证相同数据的 SHA-256 结果可复现，不同数据结果不同。 */
    @Test("文件校验：字节数组 SHA-256 可复现且长度正确")
    public void testSha256Hex() {
        byte[] data = "局域网聊天程序".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String first = SecurityUtil.sha256Hex(data);
        String second = SecurityUtil.sha256Hex(data);
        TestRunner.assertEquals(64, first.length(), "校验和应为 64 个字符");
        TestRunner.assertEquals(first, second, "相同数据校验和必须一致");
        TestRunner.assertNotEquals(first, SecurityUtil.sha256Hex("另一份数据".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)), "不同数据校验和应不同");
    }
}

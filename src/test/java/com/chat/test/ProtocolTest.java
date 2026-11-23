package com.chat.test;

import com.chat.common.AvatarCodec;
import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.Message;
import com.chat.common.MessageFilter;
import com.chat.common.MessageType;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.common.UserListCodec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 覆盖序列化过滤器限额、头像校验、用户列表编解码与消息字段往返。
 */
public class ProtocolTest {
    /** 验证 maxbytes 为累计上限，线上过滤器能读完同一报文。 */
    @Test("协议过滤器：maxbytes 是累计字节")
    public void testMaxBytesIsCumulative() throws Exception {
        String onlyBytesTight = pattern("maxbytes=4194304");
        byte[] stream = chunkStream(70);

        int readBefore = readAll(stream, ObjectInputFilter.Config.createFilter(onlyBytesTight));
        String reasonBefore = lastStopReason;
        int readAfter = readAll(stream, MessageFilter.forServerInput());
        String reasonAfter = lastStopReason;
        System.out.println("        证据：同一段 " + stream.length / 1024 + " KB 报文，"
                + "maxbytes=4MiB 读出 " + readBefore + " 条后停止（" + reasonBefore + "），"
                + "线上每条消息独立预算读出 " + readAfter + " 条后停止（" + reasonAfter + "）");

        TestRunner.assertTrue(readBefore < 70,
                "4MiB 累计上限下应当被拒绝，实际却读完了全部报文");

        TestRunner.assertTrue(readBefore >= 50,
                "拒绝应当发生在读满 4MiB 之后，实际只读出 " + readBefore + " 条");
        TestRunner.assertEquals(70, readAfter, "线上过滤器应能读完同一段报文的全部 70 条");
    }

    /** 验证 maxrefs 为累计引用上限，线上过滤器能读完 300 条文本报文。 */
    @Test("协议过滤器：maxrefs 是累计引用数")
    public void testMaxRefsIsCumulative() throws Exception {
        String onlyRefsTight = pattern("maxrefs=1000");
        byte[] stream = textStream(300);

        int readBefore = readAll(stream, ObjectInputFilter.Config.createFilter(onlyRefsTight));
        String reasonBefore = lastStopReason;
        int readAfter = readAll(stream, MessageFilter.forServerInput());
        String reasonAfter = lastStopReason;
        System.out.println("        证据：同一段 " + stream.length + " 字节 / 300 条纯文本报文，"
                + "maxrefs=1000 读出 " + readBefore + " 条后停止（" + reasonBefore + "），"
                + "线上每条消息独立预算读出 " + readAfter + " 条后停止（" + reasonAfter + "）");

        TestRunner.assertTrue(readBefore < 300,
                "maxrefs=1000 下应当在累计引用耗尽后被拒绝，实际却读完了全部报文");
        TestRunner.assertTrue(readBefore < 120,
                "按每条约 15 个引用估算，1000 个引用应当只够七十几条，实际读出 " + readBefore + " 条");

        TestRunner.assertTrue(readBefore > 30, "拒绝不应早到不合理的程度，实际 " + readBefore + " 条");

        TestRunner.assertEquals(300, readAfter,
                "线上过滤器应能读完这 300 条纯文本报文（正是修复前会掉线的规模）");
        TestRunner.assertNotNull(reasonAfter, "停止原因应为非 null");
    }

    /** 构造测试用过滤器表达式，替换指定参数的取值。 */
    private String pattern(String override) {
        String key = override.substring(0, override.indexOf('='));
        List<String> parts = new ArrayList<>();
        for (String part : new String[]{"maxdepth=8", "maxrefs=50000000", "maxbytes=1073741824",
                "maxarray=65536"}) {
            parts.add(part.startsWith(key + "=") ? override : part);
        }
        return "com.chat.common.*;java.lang.*;java.time.*;java.util.*;!*;" + String.join(";", parts);
    }

    /** 生成指定条数的文本消息序列化流。 */
    private byte[] textStream(int count) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            for (int i = 0; i < count; i++) {
                out.writeObject(ChatMessageFactory.text("alice", "bob", "在吗 " + i,
                        MessageType.TEXT_PRIVATE));
                out.reset();
            }
            out.flush();
        }
        return buffer.toByteArray();
    }

    /** 验证不在白名单内的 java.io.File 无法被反序列化。 */
    @Test("协议过滤器：非白名单类型仍被拒绝")
    public void testFilterStillBlocksForeignType() throws Exception {
        Serializable file = new java.io.File("C:/windows/system32/calc.exe");
        int read = readAll(serialize(file), ChatMessageFactory.serializationFilter());
        TestRunner.assertEquals(0, read, "java.io.File 不在白名单内，不应被反序列化出来");
    }

    /** 验证超过 maxarray 的字节数组被拒绝。 */
    @Test("协议过滤器：超过 maxarray 的数组仍被拒绝")
    public void testFilterStillLimitsArrayLength() throws Exception {
        FileMessage oversize = ChatMessageFactory.file("alice", "bob", MessageType.FILE_CHUNK);
        oversize.setTransferId("t-oversize");

        oversize.setData(new byte[65537]);
        int read = readAll(serialize(oversize), ChatMessageFactory.serializationFilter());
        TestRunner.assertEquals(0, read, "超过 maxarray 的字节数组应被拒绝");
    }

    /** 验证尺寸合规的 PNG 通过头像校验，空值视为未设置。 */
    @Test("头像校验：合法 PNG 通过，空值表示未设置")
    public void testAvatarCodecAcceptsValidPng() {
        TestRunner.assertNull(AvatarCodec.check(pngBase64(Constants.AVATAR_SIDE, Constants.AVATAR_SIDE)),
                "96×96 的 PNG 应通过校验");
        TestRunner.assertNull(AvatarCodec.check(null), "null 表示未设置头像，应通过");
        TestRunner.assertNull(AvatarCodec.check(""), "空串表示未设置头像，应通过");
        TestRunner.assertTrue(AvatarCodec.isValid(""), "isValid 应对空值返回 true");
    }

    /** 验证非 PNG、截断、超限、伪文件头与非法 Base64 的头像均被拒绝。 */
    @Test("头像校验：非 PNG、截断、像素超限、伪文件头与非法 Base64 均被拒绝")
    public void testAvatarCodecRejectsBadInput() {
        byte[] jpeg = new byte[64];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        String jpegBase64 = java.util.Base64.getEncoder().encodeToString(jpeg);
        TestRunner.assertNotNull(AvatarCodec.check(jpegBase64), "非 PNG 数据应被拒绝");

        String truncated = java.util.Base64.getEncoder().encodeToString(
                Arrays.copyOf(java.util.Base64.getDecoder().decode(
                        pngBase64(Constants.AVATAR_SIDE, Constants.AVATAR_SIDE)), 16));
        TestRunner.assertNotNull(AvatarCodec.check(truncated), "不足 24 字节的数据应被拒绝");

        TestRunner.assertNotNull(AvatarCodec.check(
                        pngBase64(Constants.AVATAR_IMAGE_MAX_SIDE + 1, 128)),
                "超过源图边长上限的图片应被拒绝");

        TestRunner.assertNotNull(AvatarCodec.check(pngBase64(512, 512)),
                "边长合规但像素总量超过上限的图片应被拒绝");
        TestRunner.assertNotNull(AvatarCodec.check("这不是 Base64!!"), "非法 Base64 应被拒绝");
        TestRunner.assertNotNull(AvatarCodec.check("A".repeat(Constants.AVATAR_MAX_LENGTH + 4)),
                "超过长度上限的文本应被拒绝");

        byte[] noIhdr = new byte[40];
        System.arraycopy(java.util.Base64.getDecoder().decode(
                pngBase64(Constants.AVATAR_SIDE, Constants.AVATAR_SIDE)), 0, noIhdr, 0, 8);
        System.arraycopy("JUNK".getBytes(Constants.CHARSET), 0, noIhdr, 12, 4);
        TestRunner.assertNotNull(AvatarCodec.check(
                        java.util.Base64.getEncoder().encodeToString(noIhdr)),
                "缺少 IHDR 图像头的数据应被拒绝");

        byte[] fakeHeader = new byte[24];
        System.arraycopy(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A},
                0, fakeHeader, 0, 8);
        fakeHeader[11] = 13;
        System.arraycopy("IHDR".getBytes(Constants.CHARSET), 0, fakeHeader, 12, 4);
        fakeHeader[19] = (byte) Constants.AVATAR_SIDE;
        fakeHeader[23] = (byte) Constants.AVATAR_SIDE;
        TestRunner.assertNotNull(AvatarCodec.check(
                        java.util.Base64.getEncoder().encodeToString(fakeHeader)),
                "24 字节的伪 PNG 头（IHDR 块与 CRC 都不完整）应被拒绝");

        byte[] fakeNoData = Arrays.copyOf(fakeHeader, 36);
        TestRunner.assertNotNull(AvatarCodec.check(
                        java.util.Base64.getEncoder().encodeToString(fakeNoData)),
                "只有文件头、没有 IDAT 图像数据的数据应被拒绝");
    }

    /** 验证四列编码中的头像可往返，三列格式解析为未设置头像。 */
    @Test("用户列表：头像列可往返，三列格式仍可解析")
    public void testUserListCodecAvatarColumn() {
        User alice = new User("alice", "爱丽丝");
        String avatar = pngBase64(Constants.AVATAR_SIDE, Constants.AVATAR_SIDE);
        alice.setAvatar(avatar);
        User bob = new User("bob", "鲍勃");
        bob.setRole(Constants.ROLE_ADMIN);

        String encoded = UserListCodec.encode(List.of(alice, bob));
        TestRunner.assertEquals(4, encoded.split("\n")[0].split("\\|", -1).length,
                "编码结果应为 用户名|昵称|角色|头像 四列");
        List<User> decoded = UserListCodec.decode(encoded);
        TestRunner.assertEquals(avatar, decoded.get(0).getAvatar(), "头像应被完整还原");
        TestRunner.assertNull(decoded.get(1).getAvatar(), "未设置头像的用户应为 null");

        List<User> legacy = UserListCodec.decode("carol|卡罗尔|USER");
        TestRunner.assertEquals(1, legacy.size(), "三列格式应能解析");
        TestRunner.assertEquals("卡罗尔", legacy.get(0).getNickname(), "三列格式的昵称应正确");
        TestRunner.assertNull(legacy.get(0).getAvatar(), "三列格式没有头像列，应视为未设置");

        User tricky = new User("dave", "a|b");
        TestRunner.assertEquals(4, UserListCodec.encode(List.of(tricky))
                .split("\\|", -1).length, "昵称中的竖线应被替换，列数不变");
    }

    /** 验证文本消息的引用标识、摘要与发送者可完整往返，未引用时为空串。 */
    @Test("文本消息：引用信息可完整往返且不引用时为空串")
    public void testQuoteRoundTrip() throws Exception {
        TextMessage quoted = new TextMessage("alice", "bob", "同意", "msg-1", "今晚聚餐吗", "bob");
        List<Message> restored = deserializeAll(serialize(quoted));
        TestRunner.assertEquals(1, restored.size(), "应还原出一条消息");
        TextMessage back = (TextMessage) restored.get(0);
        TestRunner.assertEquals("msg-1", back.getQuoteId(), "被引用消息标识应一致");
        TestRunner.assertEquals("今晚聚餐吗", back.getQuoteSummary(), "引用摘要应一致");
        TestRunner.assertEquals("bob", back.getQuoteSender(), "被引用者应一致");

        TextMessage plain = new TextMessage("alice", "bob", "在的");
        TestRunner.assertEquals("", plain.getQuoteId(), "未引用时标识应为空串");
        TestRunner.assertEquals("", plain.getQuoteSummary(), "未引用时摘要应为空串");
        TestRunner.assertEquals("", plain.getQuoteSender(), "未引用时被引用者应为空串");

        TextMessage legacy = (TextMessage) deserializeAll(serialize(plain)).get(0);
        TestRunner.assertEquals("", legacy.getQuoteSender(), "反序列化后的 getter 不应返回 null");
    }

    /** 验证消息类型可按枚举名反查，未知名称回退为 SYSTEM。 */
    @Test("消息类型：PROFILE_RESULT 可按名反查且全部类型都能往返")
    public void testProfileResultType() {
        TestRunner.assertEquals(MessageType.PROFILE_RESULT, MessageType.fromName("PROFILE_RESULT"),
                "PROFILE_RESULT 应能按枚举名反查");
        TestRunner.assertEquals(MessageType.SYSTEM, MessageType.fromName("不存在的类型"),
                "未知名称应回退为 SYSTEM");
        for (MessageType type : MessageType.values()) {
            TestRunner.assertEquals(type, MessageType.fromName(type.name()),
                    type.name() + " 应能按名反查回自身");
        }
        TestRunner.assertEquals(3, Constants.PROFILE_RESULT_FIELD_COUNT, "回执正文应为三段");
        TestRunner.assertTrue(Constants.IMAGE_EXTENSIONS.length >= 3, "图片扩展名清单不应为空");
    }

    /** 生成指定条数的文件分块消息序列化流。 */
    private byte[] chunkStream(int count) throws IOException {
        byte[] payload = new byte[Constants.FILE_CHUNK_SIZE];
        Arrays.fill(payload, (byte) 0x5A);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            for (int i = 0; i < count; i++) {
                FileMessage chunk = ChatMessageFactory.file("alice", "bob", MessageType.FILE_CHUNK);
                chunk.setTransferId("transfer-1");
                chunk.setChunkIndex(i);
                chunk.setTotalChunks(count);
                chunk.setData(payload);
                out.writeObject(chunk);
                out.reset();
            }
            out.flush();
        }
        return buffer.toByteArray();
    }

    /** 最近一次读取停止的原因。 */
    private String lastStopReason = "";

    /** 逐条反序列化直到异常或流结束，返回读出的条数。 */
    private int readAll(byte[] data, ObjectInputFilter filter) throws IOException {
        int read = 0;
        lastStopReason = "读到流末尾";
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            in.setObjectInputFilter(filter);
            while (true) {
                // 每条消息读取前重置过滤器预算
                if (filter instanceof MessageFilter perMessage) {
                    perMessage.beginMessage();
                }
                in.readObject();
                read++;
            }
        } catch (IOException | ClassNotFoundException e) {
            lastStopReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            return read;
        }
    }

    private List<Message> deserializeAll(byte[] data) throws Exception {
        List<Message> messages = new ArrayList<>();
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            in.setObjectInputFilter(ChatMessageFactory.serializationFilter());
            while (true) {
                messages.add((Message) in.readObject());
            }
        } catch (java.io.EOFException e) {
            return messages;
        }
    }

    private byte[] serialize(Serializable value) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(value);
            out.flush();
        }
        return buffer.toByteArray();
    }

    /** 生成指定尺寸的透明 PNG 并编码为 Base64。 */
    private String pngBase64(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", buffer)) {
                throw new IllegalStateException("运行环境缺少 PNG 编码器");
            }
        } catch (IOException e) {
            throw new IllegalStateException("生成测试用 PNG 失败", e);
        }
        return java.util.Base64.getEncoder().encodeToString(buffer.toByteArray());
    }
}

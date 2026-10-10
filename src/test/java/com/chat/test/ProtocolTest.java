package com.chat.test;

import com.chat.common.AvatarCodec;
import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.Message;
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
 * 协议层单元测试。
 *
 * <p>覆盖点：反序列化过滤器的边界（尤其是缺陷 D-22 的 {@code maxbytes} 累计语义）、
 * 随报文上线的第 4 项扩展（头像列、引用发送者），以及头像校验器的拒绝路径。</p>
 *
 * <p>为什么单独成类而不是塞进 {@link MessageServiceTest}：这里全部是"不碰数据库、
 * 不碰网络、只跟字节流打交道"的用例，与既有两个测试类的关注点不同；
 * 放在一起会让"哪些用例需要 MySQL"这条边界变得含糊。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class ProtocolTest {

    /**
     * 缺陷 D-22 的复现：{@code maxbytes} 绑定的是连接上累计已读字节，而不是单条消息。
     *
     * <p>只改 {@code maxbytes} 一个取值做对照，其余限度与线上一致——
     * 这样读出的条数差异只可能来自 {@code maxbytes}。</p>
     *
     * @throws Exception 序列化失败时抛出
     */
    @Test("协议过滤器：maxbytes 是累计字节（D-22 复现与修复验证）")
    public void testMaxBytesIsCumulative() throws Exception {
        String onlyBytesTight = pattern("maxbytes=4194304");
        byte[] stream = chunkStream(70);

        int readBefore = readAll(stream, ObjectInputFilter.Config.createFilter(onlyBytesTight));
        String reasonBefore = lastStopReason;
        int readAfter = readAll(stream, ChatMessageFactory.serializationFilter());
        String reasonAfter = lastStopReason;
        System.out.println("        证据：同一段 " + stream.length / 1024 + " KB 报文，"
                + "maxbytes=4MiB 读出 " + readBefore + " 条后停止（" + reasonBefore + "），"
                + "线上 maxbytes=1GiB 读出 " + readAfter + " 条后停止（" + reasonAfter + "）");

        TestRunner.assertTrue(readBefore < 70,
                "4MiB 累计上限下应当被拒绝，实际却读完了全部报文");
        // 单块 64KB，4MiB 大约装得下 60 多块；下界取 50 是为了断言"拒绝发生在中途"
        // 这一事实，而不是把具体条数写死成易碎的数字
        TestRunner.assertTrue(readBefore >= 50,
                "拒绝应当发生在读满 4MiB 之后，实际只读出 " + readBefore + " 条");
        TestRunner.assertEquals(70, readAfter, "线上过滤器应能读完同一段报文的全部 70 条");
    }

    /**
     * 缺陷 D-23 的复现：{@code maxrefs} 同样是连接级累计量，且比 {@code maxbytes} 更容易撞满。
     *
     * <p>关键前提是"每发一条消息都 {@code reset()}"——线上客户端与服务端都是这么写的，
     * 而重置会让类描述符在每条消息里重发一遍，引用消耗随之翻倍。
     * 本用例按同一方式造流，因此它复现的正是真实使用中的行为。</p>
     *
     * @throws Exception 序列化失败时抛出
     */
    @Test("协议过滤器：maxrefs 是累计引用数（D-23 复现与修复验证）")
    public void testMaxRefsIsCumulative() throws Exception {
        String onlyRefsTight = pattern("maxrefs=1000");
        byte[] stream = textStream(300);

        int readBefore = readAll(stream, ObjectInputFilter.Config.createFilter(onlyRefsTight));
        String reasonBefore = lastStopReason;
        int readAfter = readAll(stream, ChatMessageFactory.serializationFilter());
        String reasonAfter = lastStopReason;
        System.out.println("        证据：同一段 " + stream.length + " 字节 / 300 条纯文本报文，"
                + "maxrefs=1000 读出 " + readBefore + " 条后停止（" + reasonBefore + "），"
                + "线上 maxrefs=50000000 读出 " + readAfter + " 条后停止（" + reasonAfter + "）");

        TestRunner.assertTrue(readBefore < 300,
                "maxrefs=1000 下应当在累计引用耗尽后被拒绝，实际却读完了全部报文");
        TestRunner.assertTrue(readBefore < 120,
                "按实测的每条约 15 个引用，1000 个引用应当只够七十几条，实际读出 " + readBefore + " 条");
        // 这条断言就是 D-23 的用户可见后果：正常聊天发到第 66 条左右，连接开始拒绝一切消息
        TestRunner.assertTrue(readBefore > 30, "拒绝不应早到不合理的程度，实际 " + readBefore + " 条");

        TestRunner.assertEquals(300, readAfter,
                "线上过滤器应能读完这 300 条纯文本报文（正是修复前会掉线的规模）");
        TestRunner.assertNotNull(reasonAfter, "停止原因应为非 null");
    }

    /**
     * 拼出只改动某一处的过滤器串，其余限度与线上保持一致。
     *
     * <p>这样写而不是把整串复制一遍：限度值将来再调整时，本测试不会因为
     * "复制品与线上不同步"而变成一条永远通过的假证据。</p>
     *
     * @param override 形如 {@code maxbytes=4194304} 的覆盖项
     * @return 过滤器串
     */
    private String pattern(String override) {
        String key = override.substring(0, override.indexOf('='));
        List<String> parts = new ArrayList<>();
        for (String part : new String[]{"maxdepth=8", "maxrefs=50000000", "maxbytes=1073741824",
                "maxarray=65536"}) {
            parts.add(part.startsWith(key + "=") ? override : part);
        }
        return "com.chat.common.*;java.lang.*;java.time.*;java.util.*;!*;" + String.join(";", parts);
    }

    /**
     * 拼出一段纯文本消息的字节流，每条之后 {@code reset()}（与线上发送路径一致）。
     *
     * @param count 条数
     * @return 序列化字节
     * @throws IOException 序列化失败时抛出
     */
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

    /**
     * 放宽 {@code maxbytes} 之后，类名白名单仍然拦截非协议类型。
     *
     * <p>这条用例是 D-22 修复的安全侧证明：把容量上限抬高不等于把防护打开，
     * 真正拦 gadget 链的是类名白名单与 {@code maxdepth}/{@code maxrefs}。</p>
     */
    @Test("协议过滤器：非白名单类型仍被拒绝")
    public void testFilterStillBlocksForeignType() throws Exception {
        Serializable file = new java.io.File("C:/windows/system32/calc.exe");
        int read = readAll(serialize(file), ChatMessageFactory.serializationFilter());
        TestRunner.assertEquals(0, read, "java.io.File 不在白名单内，不应被反序列化出来");
    }

    /**
     * {@code maxarray} 仍然限制单个数组的长度。
     */
    @Test("协议过滤器：超过 maxarray 的数组仍被拒绝")
    public void testFilterStillLimitsArrayLength() throws Exception {
        FileMessage oversize = ChatMessageFactory.file("alice", "bob", MessageType.FILE_CHUNK);
        oversize.setTransferId("t-oversize");
        // 比 maxarray=65536 多 1 字节
        oversize.setData(new byte[65537]);
        int read = readAll(serialize(oversize), ChatMessageFactory.serializationFilter());
        TestRunner.assertEquals(0, read, "超过 maxarray 的字节数组应被拒绝");
    }

    /**
     * 合法的 96×96 PNG 头应通过头像校验。
     */
    @Test("头像校验：合法 PNG 通过，空值表示未设置")
    public void testAvatarCodecAcceptsValidPng() {
        TestRunner.assertNull(AvatarCodec.check(pngBase64(Constants.AVATAR_SIDE, Constants.AVATAR_SIDE)),
                "96×96 的 PNG 应通过校验");
        TestRunner.assertNull(AvatarCodec.check(null), "null 表示未设置头像，应通过");
        TestRunner.assertNull(AvatarCodec.check(""), "空串表示未设置头像，应通过");
        TestRunner.assertTrue(AvatarCodec.isValid(""), "isValid 应对空值返回 true");
    }

    /**
     * 头像校验的拒绝路径逐条覆盖。
     */
    @Test("头像校验：非 PNG、截断、像素超限、伪文件头与非法 Base64 均被拒绝")
    public void testAvatarCodecRejectsBadInput() {
        // 用 JPEG 魔数开头：能通过 Base64 解码，但过不了 PNG 签名
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
        // 边长都在上限内、只有像素总量超限：512×512 = 262144 > AVATAR_MAX_PIXELS，
        // 这张图在只查边长的旧校验下会被放行，正是本次修复要挡的"小体积、大像素"图
        TestRunner.assertNotNull(AvatarCodec.check(pngBase64(512, 512)),
                "边长合规但像素总量超过上限的图片应被拒绝");
        TestRunner.assertNotNull(AvatarCodec.check("这不是 Base64!!"), "非法 Base64 应被拒绝");
        TestRunner.assertNotNull(AvatarCodec.check("A".repeat(Constants.AVATAR_MAX_LENGTH + 4)),
                "超过长度上限的文本应被拒绝");

        // 截断到 12 字节后仍不足 HEADER_SIZE，但先撞上"过短"分支；
        // 这里单独造一段够长、却没有 IHDR 的数据，验证图像头这一条分支
        byte[] noIhdr = new byte[40];
        System.arraycopy(java.util.Base64.getDecoder().decode(
                pngBase64(Constants.AVATAR_SIDE, Constants.AVATAR_SIDE)), 0, noIhdr, 0, 8);
        System.arraycopy("JUNK".getBytes(Constants.CHARSET), 0, noIhdr, 12, 4);
        TestRunner.assertNotNull(AvatarCodec.check(
                        java.util.Base64.getEncoder().encodeToString(noIhdr)),
                "缺少 IHDR 图像头的数据应被拒绝");

        // 手工拼的"纯文件头"：魔数 + 块长 13 + IHDR + 96×96，没有 CRC 也没有 IDAT
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

        // 补到 36 字节后长度这一关能过，但数据里仍没有 IDAT 图像数据，必须照样拒绝
        byte[] fakeNoData = Arrays.copyOf(fakeHeader, 36);
        TestRunner.assertNotNull(AvatarCodec.check(
                        java.util.Base64.getEncoder().encodeToString(fakeNoData)),
                "只有文件头、没有 IDAT 图像数据的数据应被拒绝");
    }

    /**
     * 用户列表的第 4 列（头像）可往返，且旧的三列格式仍能解析。
     */
    @Test("用户列表：头像列可往返且兼容旧的三列格式")
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

        // 旧客户端/旧服务端的三列格式：缺列时按"未设置头像"处理，不能解析失败
        List<User> legacy = UserListCodec.decode("carol|卡罗尔|USER");
        TestRunner.assertEquals(1, legacy.size(), "三列的旧格式应能解析");
        TestRunner.assertEquals("卡罗尔", legacy.get(0).getNickname(), "旧格式的昵称应正确");
        TestRunner.assertNull(legacy.get(0).getAvatar(), "旧格式没有头像列，应视为未设置");

        // 昵称里的竖线必须被替换掉，否则会挤出一列来
        User tricky = new User("dave", "a|b");
        TestRunner.assertEquals(4, UserListCodec.encode(List.of(tricky))
                .split("\\|", -1).length, "昵称中的竖线应被替换，列数不变");
    }

    /**
     * 引用信息（标识、摘要、被引用者）随消息往返。
     */
    @Test("文本消息：引用信息可完整往返且不引用时为空串")
    public void testQuoteRoundTrip() throws Exception {
        TextMessage quoted = new TextMessage("alice", "bob", "同意", "msg-1", "今晚聚餐吗", "bob");
        List<Message> restored = deserializeAll(serialize(quoted));
        TestRunner.assertEquals(1, restored.size(), "应还原出一条消息");
        TextMessage back = (TextMessage) restored.get(0);
        TestRunner.assertEquals("msg-1", back.getQuoteId(), "被引用消息标识应一致");
        TestRunner.assertEquals("今晚聚餐吗", back.getQuoteSummary(), "引用摘要应一致");
        TestRunner.assertEquals("bob", back.getQuoteSender(), "被引用者应一致");

        // 不引用时三个 getter 一律返回空串，调用方无需判空
        TextMessage plain = new TextMessage("alice", "bob", "在的");
        TestRunner.assertEquals("", plain.getQuoteId(), "未引用时标识应为空串");
        TestRunner.assertEquals("", plain.getQuoteSummary(), "未引用时摘要应为空串");
        TestRunner.assertEquals("", plain.getQuoteSender(), "未引用时被引用者应为空串");
        // 兼容性：反序列化旧对端发来的"没有 quoteSender 字段"的对象时该字段为 null，
        // getter 必须把它归一成空串
        TextMessage legacy = (TextMessage) deserializeAll(serialize(plain)).get(0);
        TestRunner.assertEquals("", legacy.getQuoteSender(), "反序列化后的 getter 不应返回 null");
    }

    /**
     * 新增的资料变更回执类型可被名字反查，且不影响既有类型的反查。
     */
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

    /**
     * 拼出一段包含若干条文件数据块报文的字节流。
     *
     * <p>每条报文后调用 {@code reset()}，与线上发送路径一致
     * （{@code ClientHandler.send} 每条都 reset）：若不重置引用表，
     * 相同内容的数据块会被写成引用而几乎不占字节，也就复现不出累计字节的问题。</p>
     *
     * @param count 报文条数
     * @return 序列化字节
     * @throws IOException 序列化失败时抛出
     */
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

    /** 上一次 {@link #readAll} 停止读取的原因，用于把证据打印出来 */
    private String lastStopReason = "";

    /**
     * 用指定过滤器反复读到流结束，返回成功读出的对象个数。
     *
     * @param data   序列化字节
     * @param filter 反序列化过滤器
     * @return 成功读出的对象个数；中途被拒绝时返回已经读出的个数
     * @throws IOException 建流失败时抛出
     */
    private int readAll(byte[] data, ObjectInputFilter filter) throws IOException {
        int read = 0;
        lastStopReason = "读到流末尾";
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            in.setObjectInputFilter(filter);
            while (true) {
                in.readObject();
                read++;
            }
        } catch (IOException | ClassNotFoundException e) {
            // 被过滤器拒绝会以 ObjectStreamException（IOException 的子类）抛出，
            // 消息形如 "filter status: REJECTED"；这里把它当作"读取到此为止"的信号，
            // 并把异常原文记下来——正是它区分了"被容量上限拒绝"与"报文本身有问题"
            lastStopReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            return read;
        }
    }

    /**
     * 反序列化一段字节流中的全部对象，用于断言往返结果。
     *
     * @param data 序列化字节
     * @return 对象列表
     * @throws Exception 反序列化失败时抛出
     */
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

    /**
     * 序列化一个对象。
     *
     * @param value 对象
     * @return 序列化字节
     * @throws IOException 序列化失败时抛出
     */
    private byte[] serialize(Serializable value) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(value);
            out.flush();
        }
        return buffer.toByteArray();
    }

    /**
     * 造一张真实的 PNG 图片数据（Base64）。
     *
     * <p>为什么必须用 {@code ImageIO} 真编码，而不是手工拼一段"魔数 + IHDR + 宽高"的字节：
     * 客户端上传的本来就是 {@code ImageIO} 编码出来的 PNG，手工拼的数据只有文件头、
     * 既没有 IHDR 的 CRC 也没有 IDAT 图像数据，根本解码不出任何像素。拿它当"合法 PNG"
     * 会让夹具与真实客户端行为脱节，也会让 {@link AvatarCodec} 的结构完整性校验
     * （长度下限、块长、IDAT 存在性）失去验证对象。需要伪造或截断数据的用例，
     * 请在那个用例里自行构造字节，不要退回手工拼接。</p>
     *
     * @param width  宽
     * @param height 高
     * @return Base64 文本
     */
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

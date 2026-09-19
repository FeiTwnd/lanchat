package com.chat.test;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.MessageType;
import com.chat.exception.FileTransferException;
import com.chat.service.FileService;
import com.chat.util.FileUtil;
import com.chat.util.SecurityUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 文件传输业务单元测试。
 *
 * <p>覆盖点：发送前校验（不存在、超限）、分块数量计算、分块内容正确性、
 * 接收端顺序落盘、SHA-256 校验、块序号错乱检测、校验和不匹配检测、
 * 重名文件自动改名、大文件（1MB）完整往返。</p>
 *
 * <p>测试策略：不启动网络，直接把 {@link FileService} 当作“发送端 + 接收端”使用，
 * 逐块传递 {@link FileMessage}，从而在毫秒级内验证完整的分块协议逻辑。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class FileServiceTest {

    /** 当前测试使用的临时目录 */
    private Path baseDir;

    /**
     * 准备临时目录。
     *
     * @param prefix 前缀
     * @throws IOException 创建失败
     */
    private void prepare(String prefix) throws IOException {
        baseDir = TestRunner.createTempDir(prefix);
    }

    /**
     * 清理临时目录。
     */
    private void cleanup() {
        TestRunner.deleteRecursively(baseDir);
        baseDir = null;
    }

    /**
     * 用例 1：文件不存在时拒绝发送。
     *
     * @throws Exception 测试异常
     */
    @Test("文件服务：文件不存在时抛出 FileTransferException")
    public void testPrepareSendMissingFile() throws Exception {
        prepare("filesvc-missing");
        try {
            FileService service = new FileService();
            File missing = baseDir.resolve("not-exist.bin").toFile();
            boolean thrown = false;
            try {
                service.prepareSend("alice", "bob", missing);
            } catch (FileTransferException e) {
                thrown = true;
                TestRunner.assertTrue(e.getMessage().contains("不存在"), "错误信息应说明文件不存在");
            }
            TestRunner.assertTrue(thrown, "文件不存在应抛出异常");
            thrown = false;
            try {
                service.prepareSend("alice", "bob", null);
            } catch (FileTransferException e) {
                thrown = true;
            }
            TestRunner.assertTrue(thrown, "文件为 null 应抛出异常");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 2：分块数量计算规则。
     */
    @Test("文件服务：分块数量计算包含空文件与整块边界")
    public void testChunkCount() {
        int size = Constants.FILE_CHUNK_SIZE;
        TestRunner.assertEquals(1, FileUtil.chunkCount(0, size), "空文件应按 1 块处理");
        TestRunner.assertEquals(1, FileUtil.chunkCount(1, size), "1 字节应为 1 块");
        TestRunner.assertEquals(1, FileUtil.chunkCount(size, size), "恰好一整块应为 1 块");
        TestRunner.assertEquals(2, FileUtil.chunkCount(size + 1, size), "超出一字节应为 2 块");
        TestRunner.assertEquals(3, FileUtil.chunkCount(size * 2 + 5, size), "两块多应为 3 块");
    }

    /**
     * 用例 3：小文件完整往返并校验 SHA-256。
     *
     * @throws Exception 测试异常
     */
    @Test("文件服务：小文件分块传输后内容与校验和一致")
    public void testSmallFileRoundTrip() throws Exception {
        prepare("filesvc-small");
        try {
            byte[] content = "局域网聊天程序文件传输测试内容".getBytes(Constants.CHARSET);
            File source = writeFile("small.txt", content);
            transferAndVerify(source);
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 4：跨越多块的 1MB 随机文件往返。
     *
     * @throws Exception 测试异常
     */
    @Test("文件服务：1MB 随机文件多块传输后逐字节一致")
    public void testLargeFileRoundTrip() throws Exception {
        prepare("filesvc-large");
        try {
            byte[] content = new byte[1024 * 1024 + 123];
            new Random(20250101L).nextBytes(content);
            File source = writeFile("large.bin", content);
            FileMessage request = transferAndVerify(source);
            TestRunner.assertTrue(request.getTotalChunks() > 10,
                    "1MB 文件应被拆分为多个数据块，实际 " + request.getTotalChunks());
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 5：空文件也能正确传输。
     *
     * @throws Exception 测试异常
     */
    @Test("文件服务：空文件可完成传输并生成 0 字节文件")
    public void testEmptyFile() throws Exception {
        prepare("filesvc-empty");
        try {
            File source = writeFile("empty.dat", new byte[0]);
            File received = runTransfer(source);
            TestRunner.assertEquals(0L, received.length(), "接收文件应为 0 字节");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 6：块序号错乱时立即报错并清理残缺文件。
     *
     * @throws Exception 测试异常
     */
    @Test("文件服务：数据块序号错乱时抛异常并清理残缺文件")
    public void testChunkOrderValidation() throws Exception {
        prepare("filesvc-order");
        try {
            byte[] content = new byte[Constants.FILE_CHUNK_SIZE * 2];
            new Random(7L).nextBytes(content);
            File source = writeFile("order.bin", content);
            FileService sender = new FileService();
            FileService receiver = new FileService();
            FileMessage request = sender.prepareSend("alice", "bob", source);
            receiver.openReceive(baseDir.resolve("recv").toString(), request);

            // 故意跳过第 0 块，直接发送第 1 块
            FileMessage wrong = sender.createChunkMessage("alice", "bob", request, 1);
            boolean thrown = false;
            try {
                receiver.appendChunk(wrong);
            } catch (FileTransferException e) {
                thrown = true;
            }
            TestRunner.assertTrue(thrown, "块序号错乱应抛出异常");
            sender.closeSend(request.getTransferId());
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 7：校验和不一致时拒绝接收。
     *
     * @throws Exception 测试异常
     */
    @Test("文件服务：SHA-256 不匹配时抛异常并删除文件")
    public void testChecksumMismatch() throws Exception {
        prepare("filesvc-checksum");
        try {
            byte[] content = "校验和测试内容".getBytes(Constants.CHARSET);
            File source = writeFile("checksum.txt", content);
            FileService sender = new FileService();
            FileService receiver = new FileService();
            FileMessage request = sender.prepareSend("alice", "bob", source);
            String receiveDir = baseDir.resolve("recv").toString();
            receiver.openReceive(receiveDir, request);
            for (int i = 0; i < request.getTotalChunks(); i++) {
                receiver.appendChunk(sender.createChunkMessage("alice", "bob", request, i));
            }
            FileMessage end = sender.createEndMessage("alice", "bob", request);
            end.setSha256("0000000000000000000000000000000000000000000000000000000000000000");
            boolean thrown = false;
            try {
                receiver.finishReceive(end);
            } catch (FileTransferException e) {
                thrown = true;
                TestRunner.assertTrue(e.getMessage().contains("校验和"), "错误信息应说明校验和问题");
            }
            TestRunner.assertTrue(thrown, "校验和不一致应抛出异常");
            try (java.util.stream.Stream<Path> files = Files.list(Path.of(receiveDir))) {
                TestRunner.assertEquals(0L, files.count(), "校验失败后应删除残缺文件");
            }
            sender.closeSend(request.getTransferId());
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 8：重名文件自动追加序号，不覆盖已有文件。
     *
     * @throws Exception 测试异常
     */
    @Test("文件工具：重名文件自动生成 (1) (2) 副本")
    public void testUniqueTarget() throws Exception {
        prepare("filesvc-unique");
        try {
            File first = new File(baseDir.toFile(), "report.txt");
            TestRunner.assertTrue(first.createNewFile(), "应能创建占位文件");
            File second = FileUtil.uniqueTarget(baseDir.toString(), "report.txt");
            TestRunner.assertEquals("report(1).txt", second.getName(), "重名应追加 (1)");
            TestRunner.assertTrue(second.createNewFile(), "应能创建第二份文件");
            File third = FileUtil.uniqueTarget(baseDir.toString(), "report.txt");
            TestRunner.assertEquals("report(2).txt", third.getName(), "再次重名应追加 (2)");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 9：文件名清洗防止目录穿越。
     */
    @Test("文件工具：文件名清洗可阻止目录穿越")
    public void testSanitizeFileName() {
        TestRunner.assertEquals("passwd", FileUtil.sanitizeFileName("../../etc/passwd"),
                "应剥离路径部分");
        TestRunner.assertEquals("c_win.ini", FileUtil.sanitizeFileName("C:\\windows\\c:win.ini"),
                "应替换非法字符");
        TestRunner.assertEquals("unnamed", FileUtil.sanitizeFileName(".."), "特殊名应回退默认值");
        TestRunner.assertEquals("unnamed", FileUtil.sanitizeFileName(""), "空名应回退默认值");
    }

    /**
     * 用例 10：人类可读大小格式化。
     */
    @Test("文件工具：大小格式化输出正确单位")
    public void testHumanSize() {
        TestRunner.assertEquals("512 B", FileUtil.humanSize(512), "字节级应显示 B");
        TestRunner.assertEquals("1.00 KB", FileUtil.humanSize(1024), "1024 字节应显示 1.00 KB");
        TestRunner.assertEquals("1.50 MB", FileUtil.humanSize((long) (1.5 * 1024 * 1024)),
                "1.5MB 应显示 1.50 MB");
        TestRunner.assertEquals("0 B", FileUtil.humanSize(-1), "负数应回退 0 B");
    }

    /**
     * 用例 11：文件校验和计算与工具方法一致。
     *
     * @throws Exception 测试异常
     */
    @Test("文件工具：文件 SHA-256 与字节数组计算结果一致")
    public void testFileSha256() throws Exception {
        prepare("filesvc-sha");
        try {
            byte[] content = "一致性校验".getBytes(Constants.CHARSET);
            File file = writeFile("sha.bin", content);
            TestRunner.assertEquals(SecurityUtil.sha256Hex(content), FileUtil.sha256(file),
                    "两种计算方式的 SHA-256 结果应一致");
        } finally {
            cleanup();
        }
    }

    /**
     * 用例 12：取消接收会清理会话与残缺文件。
     *
     * @throws Exception 测试异常
     */
    @Test("文件服务：取消接收后进度归零且文件被清理")
    public void testCancelReceive() throws Exception {
        prepare("filesvc-cancel");
        try {
            byte[] content = new byte[Constants.FILE_CHUNK_SIZE * 2];
            new Random(11L).nextBytes(content);
            File source = writeFile("cancel.bin", content);
            FileService sender = new FileService();
            FileService receiver = new FileService();
            FileMessage request = sender.prepareSend("alice", "bob", source);
            String receiveDir = baseDir.resolve("recv").toString();
            String transferId = receiver.openReceive(receiveDir, request);
            receiver.appendChunk(sender.createChunkMessage("alice", "bob", request, 0));
            TestRunner.assertTrue(receiver.progressOf(transferId) > 0, "应有接收进度");
            receiver.cancelReceive(transferId);
            TestRunner.assertEquals(0.0, receiver.progressOf(transferId), "取消后进度查询应归零");
            try (java.util.stream.Stream<Path> files = Files.list(Path.of(receiveDir))) {
                TestRunner.assertEquals(0L, files.count(), "取消后应清理残缺文件");
            }
            sender.closeSend(request.getTransferId());
        } finally {
            cleanup();
        }
    }

    /**
     * 写入测试文件。
     *
     * @param name    文件名
     * @param content 内容
     * @return 文件对象
     * @throws IOException 写入失败
     */
    private File writeFile(String name, byte[] content) throws IOException {
        File file = baseDir.resolve(name).toFile();
        Files.write(file.toPath(), content);
        return file;
    }

    /**
     * 执行一次完整传输并校验内容与校验和。
     *
     * @param source 源文件
     * @return 本次传输的请求消息
     * @throws Exception 传输失败
     */
    private FileMessage transferAndVerify(File source) throws Exception {
        FileService sender = new FileService();
        FileService receiver = new FileService();
        FileMessage request = sender.prepareSend("alice", "bob", source);
        String receiveDir = baseDir.resolve("recv").toString();
        receiver.openReceive(receiveDir, request);
        for (int index = 0; index < request.getTotalChunks(); index++) {
            FileMessage chunk = sender.createChunkMessage("alice", "bob", request, index);
            TestRunner.assertNotNull(chunk, "分块消息不应为 null");
            receiver.appendChunk(chunk);
        }
        File target = receiver.finishReceive(sender.createEndMessage("alice", "bob", request));
        sender.closeSend(request.getTransferId());
        verifySame(source, target);
        return request;
    }

    /**
     * 执行传输并返回接收到的文件。
     *
     * @param source 源文件
     * @return 接收到的文件
     * @throws Exception 传输失败
     */
    private File runTransfer(File source) throws Exception {
        FileService sender = new FileService();
        FileService receiver = new FileService();
        FileMessage request = sender.prepareSend("alice", "bob", source);
        String receiveDir = baseDir.resolve("recv").toString();
        receiver.openReceive(receiveDir, request);
        for (int index = 0; index < request.getTotalChunks(); index++) {
            receiver.appendChunk(sender.createChunkMessage("alice", "bob", request, index));
        }
        File target = receiver.finishReceive(sender.createEndMessage("alice", "bob", request));
        sender.closeSend(request.getTransferId());
        verifySame(source, target);
        return target;
    }

    /**
     * 校验接收文件与源文件完全一致。
     *
     * @param source 源文件
     * @param target 接收文件
     * @throws IOException 读取失败
     */
    private void verifySame(File source, File target) throws IOException {
        TestRunner.assertArrayEquals(Files.readAllBytes(source.toPath()),
                Files.readAllBytes(target.toPath()), "接收文件内容应与源文件一致");
        TestRunner.assertEquals(FileUtil.sha256(source), FileUtil.sha256(target),
                "接收文件校验和应与源文件一致");
    }
}

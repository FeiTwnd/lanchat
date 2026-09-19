package com.chat.util;

import com.chat.common.Constants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 文件工具类。
 *
 * <p>职责：封装目录创建、大小格式化、SHA-256 计算、重名文件规避等重复性文件操作，
 * 让业务层只关注“传什么文件”，而不是“怎么开流、怎么关流”。</p>
 *
 * <p>资源管理：所有涉及流的方法一律使用 try-with-resources（或由调用方负责），
 * 满足课程对“资源必须关闭”的要求。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class FileUtil {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".FileUtil");

    /** 大小单位进位基数 */
    private static final long UNIT_BASE = 1024L;

    /** 私有构造，禁止实例化工具类 */
    private FileUtil() {
    }

    /**
     * 确保目录存在，不存在则递归创建。
     *
     * @param dir 目录路径
     * @return 目录对应的 {@link File} 对象
     * @throws IOException 创建失败时抛出
     */
    public static File ensureDir(String dir) throws IOException {
        Path path = Paths.get(dir);
        Files.createDirectories(path);
        return path.toFile();
    }

    /**
     * 把字节数转换为人类可读的大小文本。
     *
     * @param bytes 字节数
     * @return 形如 {@code 1.20 MB} 的字符串；负数返回 {@code 0 B}
     */
    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "0 B";
        }
        if (bytes < UNIT_BASE) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unitIndex = -1;
        while (value >= UNIT_BASE && unitIndex < units.length - 1) {
            value /= UNIT_BASE;
            unitIndex++;
        }
        return String.format("%.2f %s", value, units[unitIndex]);
    }

    /**
     * 计算文件的 SHA-256 校验和。
     *
     * <p>采用流式摘要（{@link DigestInputStream}），避免把整个大文件读入内存。</p>
     *
     * @param file 目标文件
     * @return 十六进制小写校验和
     * @throws IOException 读取失败时抛出
     * @throws IllegalArgumentException 当 file 为 null 或不存在时抛出
     */
    public static String sha256(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("待校验文件不存在: " + file);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file.toPath());
                 DigestInputStream digestIn = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[Constants.FILE_CHUNK_SIZE];
                while (digestIn.read(buffer) != -1) {
                    // 读取过程即为摘要计算过程，此处无需额外处理
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256 算法", e);
        }
    }

    /**
     * 计算分块序号对应的字节偏移量。
     *
     * @param chunkIndex 分块序号（从 0 开始）
     * @param chunkSize  分块大小
     * @return 文件中的起始偏移字节数
     */
    public static long offsetOf(int chunkIndex, int chunkSize) {
        return (long) chunkIndex * chunkSize;
    }

    /**
     * 根据文件大小计算分块总数。
     *
     * <p>空文件也视为 1 块，保证接收端至少收到一个结束信号，
     * 否则长度为 0 的文件会永远等不到 FILE_END 之外的分块。</p>
     *
     * @param fileSize  文件字节数
     * @param chunkSize 分块大小
     * @return 分块总数
     */
    public static int chunkCount(long fileSize, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("分块大小必须为正数");
        }
        if (fileSize <= 0) {
            return 1;
        }
        return (int) ((fileSize + chunkSize - 1) / chunkSize);
    }

    /**
     * 规避重名：若目标文件已存在，则追加 (1)、(2) 等序号。
     *
     * <p>避免接收文件时静默覆盖用户已有文件，属于典型的数据安全考量。</p>
     *
     * @param dir      目标目录路径
     * @param fileName 原始文件名
     * @return 可安全写入的文件对象
     */
    public static File uniqueTarget(String dir, String fileName) {
        String safeName = sanitizeFileName(fileName);
        File target = new File(dir, safeName);
        if (!target.exists()) {
            return target;
        }
        String base = safeName;
        String suffix = "";
        int dot = safeName.lastIndexOf('.');
        if (dot > 0) {
            base = safeName.substring(0, dot);
            suffix = safeName.substring(dot);
        }
        for (int index = 1; index < Integer.MAX_VALUE; index++) {
            File candidate = new File(dir, base + "(" + index + ")" + suffix);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return target;
    }

    /**
     * 清洗文件名，剔除路径分隔符与 Windows 非法字符。
     *
     * <p>防止对端构造 {@code ../../etc/passwd} 这类文件名造成目录穿越写入。</p>
     *
     * @param fileName 原始文件名
     * @return 安全的纯文件名
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "unnamed";
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "unnamed";
        }
        return name;
    }

    /**
     * 复制输入流到输出流，用于文件落盘。
     *
     * @param in  输入流
     * @param out 输出流
     * @return 实际复制的字节数
     * @throws IOException 读写失败时抛出
     */
    public static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[Constants.FILE_CHUNK_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }
        out.flush();
        return total;
    }

    /**
     * 删除文件，失败时仅记录日志不抛异常。
     *
     * <p>用于清理传输失败产生的残缺文件，此时“删除失败”不应再中断主流程。</p>
     *
     * @param file 待删除文件
     * @return 删除成功或文件本就不存在时返回 true
     */
    public static boolean deleteQuietly(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        try {
            return Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "临时文件删除失败: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * 获取文件扩展名（小写，不含点）。
     *
     * @param fileName 文件名
     * @return 扩展名；无扩展名时返回空字符串
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}

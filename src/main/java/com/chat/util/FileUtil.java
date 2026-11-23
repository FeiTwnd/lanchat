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
 * 文件与路径操作工具，提供目录创建、容量格式化、摘要计算和分块辅助方法。
 */
public final class FileUtil {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".FileUtil");

    /** 容量换算基数，1 KB 等于 1024 字节。 */
    private static final long UNIT_BASE = 1024L;

    private FileUtil() {
    }

    /**
     * 创建目录，已存在时直接返回。
     *
     * @param dir 目录路径
     * @return 目录对应的文件对象
     * @throws IOException 创建失败时抛出
     */
    public static File ensureDir(String dir) throws IOException {
        Path path = Paths.get(dir);
        Files.createDirectories(path);
        return path.toFile();
    }

    /**
     * 把字节数格式化为带单位的可读文本。
     *
     * @param bytes 字节数
     * @return 形如 12 B 或 1.50 MB 的文本
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
     * 计算文件的 SHA-256 摘要。
     *
     * @param file 待计算的文件
     * @return 十六进制摘要文本
     * @throws IOException 文件不存在或读取失败时抛出
     */
    public static String sha256(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("待校验文件不存在: " + file);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file.toPath());
                 DigestInputStream digestIn = new DigestInputStream(in, digest)) {
                // 分块读入以更新摘要
                byte[] buffer = new byte[Constants.FILE_CHUNK_SIZE];
                while (digestIn.read(buffer) != -1) {
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256 算法", e);
        }
    }

    /**
     * 计算分块在文件中的起始偏移。
     *
     * @param chunkIndex 分块序号，从 0 开始
     * @param chunkSize 每块字节数
     * @return 起始字节偏移
     */
    public static long offsetOf(int chunkIndex, int chunkSize) {
        return (long) chunkIndex * chunkSize;
    }

    /**
     * 计算文件需要划分的块数。
     *
     * @param fileSize 文件字节数
     * @param chunkSize 每块字节数
     * @return 分块总数，空文件按 1 块计
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
     * 在目录下生成不重名的目标文件，同名时追加序号。
     *
     * @param dir 目标目录
     * @param fileName 原始文件名
     * @return 可用的目标文件
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
        // 同名文件存在时按 (n) 递增序号
        for (int index = 1; index < Integer.MAX_VALUE; index++) {
            File candidate = new File(dir, base + "(" + index + ")" + suffix);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return target;
    }

    /**
     * 清理文件名中的路径和非法字符。
     *
     * @param fileName 原始文件名
     * @return 可安全落盘的文件名
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
        // 去掉路径部分并替换常见非法字符
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "unnamed";
        }
        return name;
    }

    /**
     * 把输入流完整复制到输出流。
     *
     * @param in 输入流
     * @param out 输出流
     * @return 复制的字节数
     * @throws IOException 读取或写入失败时抛出
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
     * 删除文件，失败时只记录日志。
     *
     * @param file 待删除文件
     * @return 文件不存在或删除成功返回 true
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
     * 取文件名的扩展名。
     *
     * @param fileName 文件名
     * @return 小写扩展名，没有扩展名时返回空串
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

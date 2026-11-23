package com.chat.service;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.MessageType;
import com.chat.exception.FileTransferException;
import com.chat.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 文件传输业务服务，负责发送端分块读取与接收端分块落盘、完整性校验。
 */
public class FileService {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".FileService");

    private final Map<String, ReceivingFile> receivingFiles = new ConcurrentHashMap<>();

    private final Map<String, SendingFile> sendingFiles = new ConcurrentHashMap<>();

    /** 单文件大小上限（字节）。 */
    private final long maxFileSize = com.chat.common.Config.maxFileSize();

    public FileService() {
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    private String maxFileSizeText() {
        return FileUtil.humanSize(maxFileSize);
    }

    /**
     * 校验待发送文件并生成文件请求消息。
     *
     * @param sender 发送者用户名
     * @param receiver 接收者用户名
     * @param file 待发送文件
     * @return 文件请求消息
     * @throws FileTransferException 文件无效、超过大小上限或元信息构造失败时抛出
     */
    public FileMessage prepareSend(String sender, String receiver, File file) throws FileTransferException {
        if (file == null) {
            throw new FileTransferException("请选择要发送的文件");
        }
        if (!file.isFile()) {
            throw new FileTransferException("文件不存在或不是普通文件: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new FileTransferException("文件不可读: " + file.getAbsolutePath());
        }
        if (file.length() > maxFileSize) {
            throw new FileTransferException("文件超过大小上限 " + maxFileSizeText()
                    + "，当前 " + FileUtil.humanSize(file.length()));
        }
        try {
            // 填充文件元信息并计算 SHA-256
            FileMessage request = ChatMessageFactory.file(sender, receiver, MessageType.FILE_REQUEST);
            request.setTransferId(UUID.randomUUID().toString());
            request.setFileName(safeFileName(file.getName()));
            request.setFileSize(file.length());
            request.setTotalChunks(FileUtil.chunkCount(file.length(), Constants.FILE_CHUNK_SIZE));
            request.setSha256(FileUtil.sha256(file));
            String reason = request.failReason();
            if (reason != null) {
                // 元信息校验未通过
                throw new FileTransferException("无法发送文件：" + reason);
            }
            // 登记发送会话
            sendingFiles.put(request.getTransferId(), new SendingFile(file));
            LOGGER.info(() -> "准备发送文件 " + file.getName() + "（"
                    + FileUtil.humanSize(file.length()) + "，" + request.getTotalChunks() + " 块）");
            return request;
        } catch (IOException e) {
            throw new FileTransferException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取指定序号的数据块并生成数据块消息。
     *
     * @param sender 发送者用户名
     * @param receiver 接收者用户名
     * @param request 文件请求消息
     * @param chunkIndex 数据块序号，从 0 开始
     * @return 数据块消息，序号越界时返回 null
     * @throws FileTransferException 发送会话不存在或读取失败时抛出
     */
    public FileMessage createChunkMessage(String sender, String receiver, FileMessage request, int chunkIndex)
            throws FileTransferException {
        if (request == null || request.getTransferId() == null) {
            throw new FileTransferException("传输会话不存在");
        }
        SendingFile sending = sendingFiles.get(request.getTransferId());
        if (sending == null) {
            throw new FileTransferException("传输会话已关闭: " + request.getTransferId(), request.getTransferId());
        }
        if (chunkIndex < 0 || chunkIndex >= request.getTotalChunks()) {
            return null;
        }
        try {
            byte[] data = sending.readChunk(chunkIndex);
            FileMessage chunk = ChatMessageFactory.file(sender, receiver, MessageType.FILE_CHUNK);
            chunk.setTransferId(request.getTransferId());
            chunk.setFileName(request.getFileName());
            chunk.setFileSize(request.getFileSize());
            chunk.setTotalChunks(request.getTotalChunks());
            chunk.setSha256(request.getSha256());
            chunk.setChunkIndex(chunkIndex);
            chunk.setData(data);
            return chunk;
        } catch (IOException e) {
            throw new FileTransferException("读取数据块 " + chunkIndex + " 失败: " + e.getMessage(),
                    request.getTransferId());
        }
    }

    /**
     * 生成文件传输结束消息。
     *
     * @param sender 发送者用户名
     * @param receiver 接收者用户名
     * @param request 文件请求消息
     * @return 结束消息
     */
    public FileMessage createEndMessage(String sender, String receiver, FileMessage request) {
        FileMessage end = ChatMessageFactory.file(sender, receiver, MessageType.FILE_END);
        end.setTransferId(request.getTransferId());
        end.setFileName(request.getFileName());
        end.setFileSize(request.getFileSize());
        end.setTotalChunks(request.getTotalChunks());
        end.setSha256(request.getSha256());
        return end;
    }

    /**
     * 关闭发送会话并释放文件句柄。
     *
     * @param transferId 传输编号
     */
    public void closeSend(String transferId) {
        SendingFile sending = transferId == null ? null : sendingFiles.remove(transferId);
        if (sending != null) {
            sending.close();
        }
    }

    /**
     * 校验请求并创建接收会话与目标文件。
     *
     * @param receiverDir 接收目录，空值使用默认目录
     * @param request 文件请求消息
     * @return 传输编号
     * @throws FileTransferException 请求非法或目标文件创建失败时抛出
     */
    public String openReceive(String receiverDir, FileMessage request) throws FileTransferException {
        validateRequest(request);
        try {
            String dir = (receiverDir == null || receiverDir.trim().isEmpty())
                    ? Constants.RECEIVED_DIR : receiverDir;
            FileUtil.ensureDir(dir);
            // 创建唯一的接收文件
            File target = FileUtil.uniqueTarget(dir, safeFileName(request.getFileName()));
            ReceivingFile receiving = new ReceivingFile(request.getTransferId(), target,
                    request.getTotalChunks(), request.getFileSize());
            // 登记接收会话
            receivingFiles.put(request.getTransferId(), receiving);
            LOGGER.info(() -> "开始接收文件，保存到 " + target.getAbsolutePath());
            return request.getTransferId();
        } catch (IOException e) {
            throw new FileTransferException("创建接收文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将数据块按序写入接收文件。
     *
     * @param chunk 数据块消息
     * @return 已接收的字节数
     * @throws FileTransferException 会话不存在、块序号越界或写入失败时抛出
     */
    public long appendChunk(FileMessage chunk) throws FileTransferException {
        if (chunk == null || chunk.getTransferId() == null) {
            throw new FileTransferException("无效的数据块");
        }
        ReceivingFile receiving = receivingFiles.get(chunk.getTransferId());
        if (receiving == null) {
            throw new FileTransferException("接收会话不存在: " + chunk.getTransferId(), chunk.getTransferId());
        }
        int index = chunk.getChunkIndex();
        if (index < 0 || index >= receiving.getTotalChunks()) {
            // 块序号越界时取消整个接收
            cancelReceive(chunk.getTransferId());
            throw new FileTransferException("数据块序号越界：允许 0 - " + (receiving.getTotalChunks() - 1)
                    + "，实际 " + index, chunk.getTransferId());
        }
        try {
            return receiving.append(index, chunk.getData());
        } catch (IOException e) {
            cancelReceive(chunk.getTransferId());
            throw new FileTransferException("数据块写入失败: " + e.getMessage(), chunk.getTransferId());
        }
    }

    /**
     * 结束接收，校验块数、字节数与 SHA-256。
     *
     * @param end 传输结束消息
     * @return 接收完成的文件
     * @throws FileTransferException 会话不存在、落盘失败或校验失败时抛出
     */
    public File finishReceive(FileMessage end) throws FileTransferException {
        if (end == null || end.getTransferId() == null) {
            throw new FileTransferException("无效的传输结束消息");
        }
        ReceivingFile receiving = receivingFiles.remove(end.getTransferId());
        if (receiving == null) {
            throw new FileTransferException("接收会话不存在: " + end.getTransferId(), end.getTransferId());
        }
        try {
            File target = receiving.finish(end.getSha256());
            LOGGER.info(() -> "文件接收完成并通过校验: " + target.getAbsolutePath());
            return target;
        } catch (IOException e) {
            receiving.discard();
            throw new FileTransferException("文件落盘失败: " + e.getMessage(), end.getTransferId());
        } catch (FileTransferException e) {
            // 校验失败时删除残缺文件
            receiving.discard();
            throw e;
        }
    }

    /**
     * 取消接收并删除残缺文件。
     *
     * @param transferId 传输编号
     */
    public void cancelReceive(String transferId) {
        ReceivingFile receiving = transferId == null ? null : receivingFiles.remove(transferId);
        if (receiving != null) {
            receiving.abort();
            LOGGER.warning(() -> "接收已取消，残缺文件已清理: " + receiving.getTarget().getName());
        }
    }

    /**
     * 查询接收进度。
     *
     * @param transferId 传输编号
     * @return 进度比例，取值 0.0 到 1.0；会话不存在返回 0.0
     */
    public double progressOf(String transferId) {
        ReceivingFile receiving = transferId == null ? null : receivingFiles.get(transferId);
        return receiving == null ? 0.0 : receiving.progress();
    }

    /**
     * 查询已接收的字节数。
     *
     * @param transferId 传输编号
     * @return 已接收字节数；会话不存在返回 0
     */
    public long receivedBytesOf(String transferId) {
        ReceivingFile receiving = transferId == null ? null : receivingFiles.get(transferId);
        return receiving == null ? 0L : receiving.getReceivedBytes();
    }

    /**
     * 校验文件请求的必填字段、元信息与大小上限。
     *
     * @param request 文件请求消息
     * @throws FileTransferException 请求为 null、元信息非法或超过大小上限时抛出
     */
    private void validateRequest(FileMessage request) throws FileTransferException {
        if (request == null) {
            throw new FileTransferException("无效的文件传输请求");
        }
        String reason = request.failReason();
        if (reason != null) {
            throw new FileTransferException("对方发送的文件元信息非法：" + reason);
        }
        if (request.getFileSize() > maxFileSize) {
            throw new FileTransferException("对方发送的文件超过大小上限 " + maxFileSizeText());
        }
    }

    /**
     * 过滤文件名并检查长度、路径成分与控制字符。
     *
     * @param rawName 原始文件名
     * @return 处理后的文件名
     * @throws FileTransferException 文件名过长或包含非法字符时抛出
     */
    private static String safeFileName(String rawName) throws FileTransferException {
        String safe = FileUtil.sanitizeFileName(rawName);
        if (safe.length() > FileMessage.MAX_FILE_NAME_LENGTH) {
            throw new FileTransferException("文件名过长，最多 " + FileMessage.MAX_FILE_NAME_LENGTH + " 个字符");
        }
        // 拒绝路径穿越与路径分隔符
        if (safe.contains("..") || safe.indexOf('/') >= 0 || safe.indexOf('\\') >= 0) {
            throw new FileTransferException("文件名包含非法路径成分: " + rawName);
        }
        for (int i = 0; i < safe.length(); i++) {
            if (Character.isISOControl(safe.charAt(i))) {
                throw new FileTransferException("文件名包含控制字符: " + rawName);
            }
        }
        return safe;
    }

    /**
     * 发送会话，持有按块随机读取的文件句柄。
     */
    private static final class SendingFile {
        private final File file;

        private final RandomAccessFile accessor;

        private SendingFile(File file) throws IOException {
            this.file = file;
            this.accessor = new RandomAccessFile(file, "r");
        }

        private byte[] readChunk(int chunkIndex) throws IOException {
            // 按块序号计算偏移与长度
            long offset = FileUtil.offsetOf(chunkIndex, Constants.FILE_CHUNK_SIZE);
            int length = (int) Math.min(Constants.FILE_CHUNK_SIZE, file.length() - offset);
            if (length < 0) {
                length = 0;
            }
            byte[] buffer = new byte[length];
            synchronized (accessor) {
                accessor.seek(offset);
                accessor.readFully(buffer);
            }
            return buffer;
        }

        private void close() {
            try {
                accessor.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "发送文件句柄关闭失败", e);
            }
        }
    }

    /**
     * 接收会话，按序写入目标文件并记录进度。
     */
    private static final class ReceivingFile {
        private final String transferId;

        private final File target;

        private final OutputStream output;

        private final int totalChunks;

        private final long expectedSize;

        private long receivedBytes;

        private int expectedChunk;

        private final Object lock = new Object();

        private ReceivingFile(String transferId, File target, int totalChunks, long expectedSize) throws IOException {
            this.transferId = transferId;
            this.target = target;
            this.totalChunks = totalChunks;
            this.expectedSize = expectedSize;
            this.output = Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }

        private long append(int chunkIndex, byte[] data) throws IOException, FileTransferException {
            synchronized (lock) {
                // 数据块必须按序号连续到达
                if (chunkIndex != expectedChunk) {
                    throw new FileTransferException("数据块序号错乱：期望 " + expectedChunk + "，实际 " + chunkIndex,
                            transferId);
                }
                if (data != null && data.length > 0) {
                    output.write(data);
                    receivedBytes += data.length;
                }
                expectedChunk++;
                return receivedBytes;
            }
        }

        private File finish(String expectedSha256) throws IOException, FileTransferException {
            synchronized (lock) {
                output.flush();
                output.close();
                // 校验块数与总字节数
                if (expectedChunk != totalChunks) {
                    throw new FileTransferException("接收到的块数不足：期望 " + totalChunks + "，实际 " + expectedChunk,
                            transferId);
                }
                if (receivedBytes != expectedSize) {
                    throw new FileTransferException("接收字节数与声明不符：声明 " + expectedSize
                            + "，实际 " + receivedBytes, transferId);
                }
                // 请求带校验和时比对 SHA-256
                if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                    String actual = FileUtil.sha256(target);
                    if (!actual.equalsIgnoreCase(expectedSha256)) {
                        throw new FileTransferException("文件校验和不一致，传输可能被截断", transferId);
                    }
                }
                return target;
            }
        }

        private void abort() {
            synchronized (lock) {
                closeOutputQuietly();
                FileUtil.deleteQuietly(target);
            }
        }

        private void discard() {
            synchronized (lock) {
                closeOutputQuietly();
                boolean removed = FileUtil.deleteQuietly(target);
                if (!removed) {
                    LOGGER.warning(() -> "残缺文件删除失败，请手动清理: " + target.getAbsolutePath());
                }
            }
        }

        private void closeOutputQuietly() {
            try {
                output.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "接收流关闭异常（忽略）", e);
            }
        }

        private double progress() {
            synchronized (lock) {
                if (expectedSize <= 0) {
                    return 0.0;
                }
                return Math.min(1.0, (double) receivedBytes / expectedSize);
            }
        }

        private long getReceivedBytes() {
            synchronized (lock) {
                return receivedBytes;
            }
        }

        private int getTotalChunks() {
            return totalChunks;
        }

        private File getTarget() {
            return target;
        }
    }
}

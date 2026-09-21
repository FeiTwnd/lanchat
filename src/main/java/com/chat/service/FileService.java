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
 * 文件传输业务服务。
 *
 * <p>职责：文件发送端的“分块读取”与接收端的“分块落盘 + 完整性校验”，
 * 是文件传输模块的核心业务逻辑，GUI 与网络层都只调用本类。</p>
 *
 * <p>分块协议：</p>
 * <ol>
 *   <li>发送端调用 {@link #prepareSend(File)} 生成 FILE_REQUEST 消息（含大小、块数、SHA-256）；</li>
 *   <li>接收端同意后，发送端循环调用 {@link #createChunkMessage} 投递数据块；</li>
 *   <li>接收端调用 {@link #appendChunk} 顺序落盘，并通过 {@link #progressOf} 读取进度；</li>
 *   <li>全部块收完后调用 {@link #finishReceive}，此时才做 SHA-256 比对，防止“收到一半就报成功”。</li>
 * </ol>
 *
 * <p>并发设计：接收会话使用 {@link ConcurrentHashMap} 管理，支持同一时刻多个对端并发传文件；
 * 单个会话内部使用显式锁保证“写文件 + 更新进度”的原子性。发送端使用
 * {@link RandomAccessFile} 支持按块随机定位读取，避免为每个块重新打开文件。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class FileService {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".FileService");

    /** 接收会话表：传输编号 -> 会话对象 */
    private final Map<String, ReceivingFile> receivingFiles = new ConcurrentHashMap<>();

    /** 发送会话表：传输编号 -> 发送句柄 */
    private final Map<String, SendingFile> sendingFiles = new ConcurrentHashMap<>();

    /** 单文件大小上限（字节），读取自配置文件，缺省为内置默认值 */
    private final long maxFileSize = com.chat.common.Config.maxFileSize();

    /**
     * 构造文件服务。
     */
    public FileService() {
        // 上限在构造时固化，避免每条消息都读取配置
    }

    /**
     * 获取当前生效的单文件大小上限。
     *
     * @return 字节数
     */
    public long getMaxFileSize() {
        return maxFileSize;
    }

    /**
     * 把字节上限转换为可读文本，用于错误提示。
     *
     * @return 形如 {@code 200.00 MB} 的文本
     */
    private String maxFileSizeText() {
        return FileUtil.humanSize(maxFileSize);
    }

    /**
     * 生成文件传输请求消息。
     *
     * <p>业务校验：文件必须存在、是普通文件、可读且体积不超过
     * {@link Constants#MAX_FILE_SIZE}（实际取配置项 {@code file.max.size} 的生效值）；
     * 任一不满足直接抛出 {@link FileTransferException}，
     * 让调用方明确得到“为什么发不出去”。空文件允许发送，按 1 个空块传输，
     * 与 {@link com.chat.common.FileMessage#failReason()} 的块数口径保持一致。</p>
     *
     * @param sender   发送者用户名
     * @param receiver 接收者用户名
     * @param file     待发送文件
     * @return 已填充元信息的 FILE_REQUEST 消息
     * @throws FileTransferException 文件不合法或读取失败时抛出
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
            FileMessage request = ChatMessageFactory.file(sender, receiver, MessageType.FILE_REQUEST);
            request.setTransferId(UUID.randomUUID().toString());
            request.setFileName(safeFileName(file.getName()));
            request.setFileSize(file.length());
            request.setTotalChunks(FileUtil.chunkCount(file.length(), Constants.FILE_CHUNK_SIZE));
            request.setSha256(FileUtil.sha256(file));
            String reason = request.failReason();
            if (reason != null) {
                // 读出文件后才可能发现元信息自相矛盾，这里提前失败，避免把非法请求发给对端
                throw new FileTransferException("无法发送文件：" + reason);
            }
            sendingFiles.put(request.getTransferId(), new SendingFile(file));
            LOGGER.info(() -> "准备发送文件 " + file.getName() + "（"
                    + FileUtil.humanSize(file.length()) + "，" + request.getTotalChunks() + " 块）");
            return request;
        } catch (IOException e) {
            throw new FileTransferException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按块序号构造数据块消息。
     *
     * @param sender     发送者用户名
     * @param receiver   接收者用户名
     * @param request    原始请求消息（提供传输编号与文件信息）
     * @param chunkIndex 块序号，从 0 开始
     * @return FILE_CHUNK 消息；当块序号超出范围时返回 null 表示已发送完毕
     * @throws FileTransferException 读取失败时抛出
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
     * 构造传输结束消息。
     *
     * @param sender   发送者用户名
     * @param receiver 接收者用户名
     * @param request  原始请求消息
     * @return FILE_END 消息
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
     * 创建接收会话，准备落盘。
     *
     * <p>目标目录不存在时自动创建；文件重名时自动追加序号，绝不覆盖已有文件。</p>
     *
     * @param receiverDir 保存目录
     * @param request     文件请求消息
     * @return 接收会话编号
     * @throws FileTransferException 创建失败时抛出
     */
    public String openReceive(String receiverDir, FileMessage request) throws FileTransferException {
        validateRequest(request);
        try {
            String dir = (receiverDir == null || receiverDir.trim().isEmpty())
                    ? Constants.RECEIVED_DIR : receiverDir;
            FileUtil.ensureDir(dir);
            File target = FileUtil.uniqueTarget(dir, safeFileName(request.getFileName()));
            ReceivingFile receiving = new ReceivingFile(request.getTransferId(), target,
                    request.getTotalChunks(), request.getFileSize());
            receivingFiles.put(request.getTransferId(), receiving);
            LOGGER.info(() -> "开始接收文件，保存到 " + target.getAbsolutePath());
            return request.getTransferId();
        } catch (IOException e) {
            throw new FileTransferException("创建接收文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 接收一个数据块并顺序落盘。
     *
     * <p>严格校验块序号连续性：若收到的块序号与期望不符，说明网络乱序或对端实现有误，
     * 立即抛异常并清理残缺文件，绝不写入错位数据。</p>
     *
     * <p>在连续性之前先做范围校验：声明总块数为 N 时，序号只允许落在 {@code [0, N)}，
     * 否则说明对端在请求之外伪造了数据块，必须当场拒绝。</p>
     *
     * @param chunk FILE_CHUNK 消息
     * @return 当前累计接收字节数
     * @throws FileTransferException 会话不存在、块序号越界或错乱、写盘失败时抛出
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
     * 结束接收：关闭文件并校验 SHA-256。
     *
     * @param end FILE_END 消息
     * @return 保存成功的文件对象
     * @throws FileTransferException 块数不足、校验和比对失败或文件缺失时抛出
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
            // 块数不足或校验和不一致都意味着文件不完整，必须删除残缺文件，
            // 否则用户会在接收目录看到一个“看起来正常但内容损坏”的文件
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
     * 获取接收进度（0.0 - 1.0）。
     *
     * @param transferId 传输编号
     * @return 进度比例；会话不存在时返回 0
     */
    public double progressOf(String transferId) {
        ReceivingFile receiving = transferId == null ? null : receivingFiles.get(transferId);
        return receiving == null ? 0.0 : receiving.progress();
    }

    /**
     * 获取已接收字节数。
     *
     * @param transferId 传输编号
     * @return 已接收字节数；会话不存在时返回 0
     */
    public long receivedBytesOf(String transferId) {
        ReceivingFile receiving = transferId == null ? null : receivingFiles.get(transferId);
        return receiving == null ? 0L : receiving.getReceivedBytes();
    }

    /**
     * 校验文件请求消息的合法性。
     *
     * <p>结构性规则（文件名、块数、SHA-256 格式）统一委托给
     * {@link FileMessage#failReason()}，本方法只补充“体积必须大于 0”与依赖配置的上限判断，
     * 保证与文本通道解析使用同一套规则、且校验规则不在两处重复实现。</p>
     *
     * @param request 请求消息
     * @throws FileTransferException 请求为空、元信息非法、体积为 0 或超过大小上限时抛出
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
     * 清洗并校验文件名，得到可安全落盘的纯文件名。
     *
     * <p>先清洗再校验的顺序是刻意为之：清洗剔除路径部分，校验负责拒绝
     * {@code ..} 这类清洗后仍保留危险语义的名字，两级防线不能相互替代。</p>
     *
     * @param rawName 原始文件名
     * @return 不含路径的安全文件名
     * @throws FileTransferException 名字为空、过长或含危险内容时抛出
     */
    private static String safeFileName(String rawName) throws FileTransferException {
        String safe = FileUtil.sanitizeFileName(rawName);
        if (safe.length() > FileMessage.MAX_FILE_NAME_LENGTH) {
            throw new FileTransferException("文件名过长，最多 " + FileMessage.MAX_FILE_NAME_LENGTH + " 个字符");
        }
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
     * 发送端句柄：持有文件并支持按块随机读取。
     *
     * <p>使用 {@link RandomAccessFile} 而非普通输入流，是为了在多线程场景下
     * 仍能按块序号精确定位，避免“必须顺序发送”这一隐性约束。</p>
     */
    private static final class SendingFile {

        /** 待发送文件 */
        private final File file;

        /** 随机访问句柄 */
        private final RandomAccessFile accessor;

        /**
         * 打开文件句柄。
         *
         * @param file 待发送文件
         * @throws IOException 打开失败时抛出
         */
        private SendingFile(File file) throws IOException {
            this.file = file;
            this.accessor = new RandomAccessFile(file, "r");
        }

        /**
         * 读取指定块的数据。
         *
         * @param chunkIndex 块序号
         * @return 块字节数组，最后一块可能不足 {@link Constants#FILE_CHUNK_SIZE}
         * @throws IOException 读取失败时抛出
         */
        private byte[] readChunk(int chunkIndex) throws IOException {
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

        /**
         * 关闭句柄，失败仅记录日志。
         */
        private void close() {
            try {
                accessor.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "发送文件句柄关闭失败", e);
            }
        }
    }

    /**
     * 接收端会话：顺序写入目标文件并维护进度。
     */    private static final class ReceivingFile {

        /** 传输编号 */
        private final String transferId;

        /** 目标文件 */
        private final File target;

        /** 输出流，随会话生命周期持有 */
        private final OutputStream output;

        /** 期望的总块数 */
        private final int totalChunks;

        /** 期望的文件总字节数 */
        private final long expectedSize;

        /** 已接收字节数 */
        private long receivedBytes;

        /** 下一个期望的块序号 */
        private int expectedChunk;

        /** 会话内部锁 */
        private final Object lock = new Object();

        /**
         * 创建目标文件并打开输出流。
         *
         * @param transferId   传输编号
         * @param target       目标文件
         * @param totalChunks  总块数
         * @param expectedSize 文件总字节数
         * @throws IOException 创建失败时抛出
         */
        private ReceivingFile(String transferId, File target, int totalChunks, long expectedSize) throws IOException {
            this.transferId = transferId;
            this.target = target;
            this.totalChunks = totalChunks;
            this.expectedSize = expectedSize;
            this.output = Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }

        /**
         * 追加一个数据块。
         *
         * @param chunkIndex 块序号
         * @param data       块数据
         * @return 累计接收字节数
         * @throws IOException              写盘失败时抛出
         * @throws FileTransferException    块序号错乱时抛出
         */
        private long append(int chunkIndex, byte[] data) throws IOException, FileTransferException {
            synchronized (lock) {
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

        /**
         * 关闭文件并校验完整性。
         *
         * @param expectedSha256 期望的 SHA-256，可为 null 表示跳过校验
         * @return 保存成功的文件
         * @throws IOException             关闭失败时抛出
         * @throws FileTransferException   块数或校验和不匹配时抛出
         */
        private File finish(String expectedSha256) throws IOException, FileTransferException {
            synchronized (lock) {
                output.flush();
                output.close();
                if (expectedChunk != totalChunks) {
                    throw new FileTransferException("接收到的块数不足：期望 " + totalChunks + "，实际 " + expectedChunk,
                            transferId);
                }
                if (receivedBytes != expectedSize) {
                    throw new FileTransferException("接收字节数与声明不符：声明 " + expectedSize
                            + "，实际 " + receivedBytes, transferId);
                }
                if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                    String actual = FileUtil.sha256(target);
                    if (!actual.equalsIgnoreCase(expectedSha256)) {
                        throw new FileTransferException("文件校验和不一致，传输可能被截断", transferId);
                    }
                }
                return target;
            }
        }

        /**
         * 中止接收：关闭流并删除残缺文件。
         */
        private void abort() {
            synchronized (lock) {
                closeOutputQuietly();
                FileUtil.deleteQuietly(target);
            }
        }

        /**
         * 丢弃残缺文件：关闭流并删除，用于校验失败等失败的收尾处理。
         */
        private void discard() {
            synchronized (lock) {
                closeOutputQuietly();
                boolean removed = FileUtil.deleteQuietly(target);
                if (!removed) {
                    LOGGER.warning(() -> "残缺文件删除失败，请手动清理: " + target.getAbsolutePath());
                }
            }
        }

        /**
         * 静默关闭输出流，关闭失败不影响后续清理动作。
         */
        private void closeOutputQuietly() {
            try {
                output.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "接收流关闭异常（忽略）", e);
            }
        }

        /**
         * 计算接收进度。
         *
         * @return 0.0 - 1.0 的进度比例
         */
        private double progress() {
            synchronized (lock) {
                if (expectedSize <= 0) {
                    return 0.0;
                }
                return Math.min(1.0, (double) receivedBytes / expectedSize);
            }
        }

        /**
         * 获取已接收字节数。
         *
         * @return 已接收字节数
         */
        private long getReceivedBytes() {
            synchronized (lock) {
                return receivedBytes;
            }
        }

        /**
         * 获取期望的总块数，用于判断对端发来的块序号是否越界。
         *
         * @return 总块数
         */
        private int getTotalChunks() {
            return totalChunks;
        }

        /**
         * 获取目标文件。
         *
         * @return 目标文件
         */
        private File getTarget() {
            return target;
        }
    }
}

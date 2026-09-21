package com.chat.common;

/**
 * 文件消息（元信息 + 数据块）。
 *
 * <p>职责：一条 {@code FileMessage} 既可承载“文件传输请求”的全部元信息
 * （文件编号、文件名、大小、分块总数、SHA-256 校验和），也可承载某个具体数据块
 * （块序号 + 字节数组），由 {@link MessageType} 区分当前处于哪个阶段：</p>
 *
 * <ul>
 *   <li>{@link MessageType#FILE_REQUEST} 发送方发起请求（accept=false）</li>
 *   <li>{@link MessageType#FILE_ACCEPT} / {@link MessageType#FILE_REJECT} 接收方应答</li>
 *   <li>{@link MessageType#FILE_CHUNK} 传输数据块</li>
 *   <li>{@link MessageType#FILE_END} 传输结束</li>
 *   <li>{@link MessageType#FILE_RESULT} 结果回执（success 字段起效）</li>
 * </ul>
 *
 * <p>为什么一个类而不是五个：五个类共享完全相同的九个字段，拆开后每次协议微调都要改五处，
 * 属于典型的过度设计；用一个类 + 类型枚举反而更清晰、更易维护。</p>
 *
 * <p>元信息校验：对端发来的元信息全部属于不可信输入，因此把校验规则集中在本类
 * （{@link #failReason()}），让“文本通道解析”与
 * “业务层建会话”共用同一份规则，避免两处校验口径不一致而留下绕过路径。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class FileMessage extends Message {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250105L;

    /** 文件名长度上限：255 个字符，与主流文件系统（ext4/NTFS）的单段名上限保持一致 */
    public static final int MAX_FILE_NAME_LENGTH = 255;

    /**
     * 分块总数上限：1000000 块。
     *
     * <p>该值远大于按 {@link Constants#FILE_CHUNK_SIZE}（64KB）与默认 200MB 上限算出的块数（3200 块），
     * 属于与配置无关的防御性天花板：即使有人把 {@code file.max.size} 配成极大值，接收端也不会
     * 因为“块数”被放大而预先分配资源或长时间空等。</p>
     */
    public static final int MAX_TOTAL_CHUNKS = 1_000_000;

    /** SHA-256 十六进制摘要长度：32 字节固定输出 64 个十六进制字符 */
    public static final int SHA256_HEX_LENGTH = 64;

    /** 文件传输编号，由发送方生成，用于同时传输多个文件时区分会话 */
    private String transferId;

    /** 文件名（不含路径），接收方以此为默认保存名 */
    private String fileName;

    /** 文件总字节数 */
    private long fileSize;

    /** 分块总数，接收方据此显示进度百分比 */
    private int totalChunks;

    /** 当前数据块的序号，从 0 开始 */
    private int chunkIndex;

    /** 当前数据块的字节内容；请求阶段为 null */
    private byte[] data;

    /** 发送方计算的文件 SHA-256 校验和，用于接收方完整性校验 */
    private String sha256;

    /** 接收方是否同意接收；拒绝时携带拒绝原因 */
    private boolean accepted;

    /** 附加说明文本：拒绝原因、传输结果描述等 */
    private String message;

    /**
     * 构造文件消息骨架。
     *
     * @param sender   发送者用户名
     * @param receiver 接收者用户名
     */
    public FileMessage(String sender, String receiver) {
        setSender(sender);
        setReceiver(receiver == null ? "" : receiver);
    }

    /**
     * 获取传输会话编号。
     *
     * @return 传输编号
     */
    public String getTransferId() {
        return transferId;
    }

    /**
     * 设置传输会话编号。
     *
     * @param transferId 传输编号，建议使用 UUID
     */
    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    /**
     * 获取文件名。
     *
     * @return 不含路径的文件名
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * 设置文件名。
     *
     * @param fileName 不含路径的文件名
     */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /**
     * 获取文件总字节数。
     *
     * @return 文件大小（字节）
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * 设置文件总字节数。
     *
     * @param fileSize 文件大小（字节）
     */
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    /**
     * 获取分块总数。
     *
     * @return 分块数量
     */
    public int getTotalChunks() {
        return totalChunks;
    }

    /**
     * 设置分块总数。
     *
     * @param totalChunks 分块数量
     */
    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    /**
     * 获取当前分块序号。
     *
     * @return 从 0 开始的分块序号
     */
    public int getChunkIndex() {
        return chunkIndex;
    }

    /**
     * 设置当前分块序号。
     *
     * @param chunkIndex 从 0 开始的分块序号
     */
    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    /**
     * 获取当前分块数据。
     *
     * @return 字节数组；请求阶段返回 null
     */
    public byte[] getData() {
        return data;
    }

    /**
     * 设置当前分块数据。
     *
     * @param data 分块字节内容
     */
    public void setData(byte[] data) {
        this.data = data;
    }

    /**
     * 获取文件 SHA-256 校验和。
     *
     * @return 十六进制小写校验和
     */
    public String getSha256() {
        return sha256;
    }

    /**
     * 设置文件 SHA-256 校验和。
     *
     * @param sha256 十六进制小写校验和
     */
    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    /**
     * 是否被接收方接受。
     *
     * @return 同意接收返回 true
     */
    public boolean isAccepted() {
        return accepted;
    }

    /**
     * 设置接收意向。
     *
     * @param accepted true 表示同意接收
     */
    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    /**
     * 获取附加说明文本。
     *
     * @return 拒绝原因或传输结果描述，可能为 null
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置附加说明文本。
     *
     * @param message 拒绝原因或结果描述
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 给出元信息中第一处非法字段的中文原因。
     *
     * <p>返回原因而不是只返回布尔值，是为了让网络层、业务层都能直接拼出可读提示，
     * 不必各自复述一遍判断顺序；调用方用 {@code != null} 判断即为"是否合法"，
     * 因此不再另外提供只返回布尔值的重载。</p>
     *
     * @return 中文失败原因；全部合法时返回 null
     */
    public String failReason() {
        if (transferId == null || transferId.trim().isEmpty()) {
            return "文件传输编号为空";
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            return "文件名为空";
        }
        if (fileName.length() > MAX_FILE_NAME_LENGTH) {
            return "文件名过长，最多 " + MAX_FILE_NAME_LENGTH + " 个字符";
        }
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            return "文件名不得包含路径分隔符";
        }
        if (fileName.contains("..")) {
            return "文件名不得包含 ..";
        }
        for (int i = 0; i < fileName.length(); i++) {
            // 控制字符（含制表、换行、NUL）在界面上不可见，却可能被用于伪造日志或界面文本，一律拒绝
            if (Character.isISOControl(fileName.charAt(i))) {
                return "文件名含非法控制字符";
            }
        }
        if (fileSize < 0) {
            // 只拒绝负数：0 字节的空文件是合法传输对象，按 1 个空块发送即可，
            // 若一并拒绝会让"发送空文件"这一正常操作变成不可用
            return "文件大小不能为负数";
        }
        if (totalChunks <= 0) {
            return "分块总数必须大于 0";
        }
        if (totalChunks > MAX_TOTAL_CHUNKS) {
            return "分块总数超过上限 " + MAX_TOTAL_CHUNKS;
        }
        // 块数必须与文件大小精确自洽：既不能少（尾部数据会丢失、文件残缺），
        // 也不能多（接收方会按声明的块数空等永远不来的数据块，属于资源耗尽的利用点）。
        // 口径与 FileUtil.chunkCount 保持一致：0 字节的空文件按 1 个空块传输。
        long expectedChunks = fileSize == 0 ? 1
                : (fileSize + Constants.FILE_CHUNK_SIZE - 1) / Constants.FILE_CHUNK_SIZE;
        if (totalChunks != expectedChunks) {
            return "分块总数与文件大小不匹配：应为 " + expectedChunks + "，实际 " + totalChunks;
        }
        if (sha256 == null || sha256.trim().isEmpty()) {
            return "SHA-256 校验和为空";
        }
        if (sha256.length() != SHA256_HEX_LENGTH) {
            return "SHA-256 校验和长度应为 " + SHA256_HEX_LENGTH + " 位十六进制";
        }
        for (int i = 0; i < sha256.length(); i++) {
            if (Character.digit(sha256.charAt(i), 16) < 0) {
                return "SHA-256 校验和含非十六进制字符";
            }
        }
        return null;
    }

    /**
     * 获取摘要：文件名 + 人类可读大小，界面只需调用本方法即可展示。
     *
     * @return 形如 {@code 报告.pdf (1.20 MB)} 的摘要
     */
    @Override
    public String getSummary() {
        if (fileName == null) {
            return "(文件消息)";
        }
        return fileName + " (" + com.chat.util.FileUtil.humanSize(fileSize) + ")";
    }
}

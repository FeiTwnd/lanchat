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
 * @author Java 课程设计
 * @version 1.0
 */
public class FileMessage extends Message {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250105L;

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

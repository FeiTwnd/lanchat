package com.chat.common;

/**
 * 承载文件传输请求、分块数据与校验信息的消息。
 */
public class FileMessage extends Message {
    private static final long serialVersionUID = 20250105L;

    /** 文件名最大字符数 */
    public static final int MAX_FILE_NAME_LENGTH = 255;

    /** 分块总数上限 */
    public static final int MAX_TOTAL_CHUNKS = 1_000_000;

    /** SHA-256 十六进制校验和的长度 */
    public static final int SHA256_HEX_LENGTH = 64;

    private String transferId;

    private String fileName;

    /** 文件总字节数 */
    private long fileSize;

    private int totalChunks;

    /** 当前分块序号，从 0 开始 */
    private int chunkIndex;

    /** 当前分块的数据 */
    private byte[] data;

    /** 文件内容的 SHA-256 校验和 */
    private String sha256;

    /** 接收方是否已同意接收 */
    private boolean accepted;

    /** 附加说明文本 */
    private String message;

    /**
     * 创建文件消息并设置收发双方。
     *
     * @param sender 发送者
     * @param receiver 接收者，为空表示广播
     */
    public FileMessage(String sender, String receiver) {
        setSender(sender);
        setReceiver(receiver == null ? "" : receiver);
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 检查文件传输字段是否完整合法。
     *
     * @return 错误说明，校验通过时返回 null
     */
    public String failReason() {
        // 校验传输编号与文件名
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
            if (Character.isISOControl(fileName.charAt(i))) {
                return "文件名含非法控制字符";
            }
        }
        // 校验文件大小与分块数
        if (fileSize < 0) {
            return "文件大小不能为负数";
        }
        if (totalChunks <= 0) {
            return "分块总数必须大于 0";
        }
        if (totalChunks > MAX_TOTAL_CHUNKS) {
            return "分块总数超过上限 " + MAX_TOTAL_CHUNKS;
        }

        // 分块总数应与文件大小一致
        long expectedChunks = fileSize == 0 ? 1
                : (fileSize + Constants.FILE_CHUNK_SIZE - 1) / Constants.FILE_CHUNK_SIZE;
        if (totalChunks != expectedChunks) {
            return "分块总数与文件大小不匹配：应为 " + expectedChunks + "，实际 " + totalChunks;
        }
        // 校验 SHA-256 校验和
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

    @Override
    public String getSummary() {
        if (fileName == null) {
            return "(文件消息)";
        }
        return fileName + " (" + com.chat.util.FileUtil.humanSize(fileSize) + ")";
    }
}

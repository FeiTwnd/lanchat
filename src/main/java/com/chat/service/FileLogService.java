package com.chat.service;

import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.MessageType;
import com.chat.dao.JdbcFileLogDao;
import com.chat.exception.ChatException;

import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 文件传输日志服务，负责把一次文件传输的结果写入审计记录。
 */
public class FileLogService {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".FileLogService");

    public static final String RESULT_SUCCESS = "SUCCESS";

    public static final String RESULT_REJECTED = "REJECTED";

    public static final String RESULT_FAILED = "FAILED";

    private final JdbcFileLogDao fileLogDao;

    public FileLogService() {
        this(JdbcFileLogDao.fromConfig());
    }

    /**
     * 使用指定的日志存储创建服务。
     *
     * @param fileLogDao 文件传输日志存储
     */
    public FileLogService(JdbcFileLogDao fileLogDao) {
        if (fileLogDao == null) {
            throw new IllegalArgumentException("JdbcFileLogDao 不能为 null");
        }
        this.fileLogDao = fileLogDao;
    }

    public boolean isStorageAvailable() {
        return fileLogDao.isAvailable();
    }

    public String storageFailureReason() {
        return fileLogDao.failureReason();
    }

    /**
     * 记录一次已结束的文件传输。
     *
     * @param result 传输结果消息
     * @param fileSender 发送者用户名
     * @param fileReceiver 接收者用户名
     * @return 写入成功返回 true，存储不可用或写入失败返回 false
     */
    public boolean record(FileMessage result, String fileSender, String fileReceiver) {
        String outcome = outcomeOf(result);
        String remark = result.getMessage() == null ? "" : result.getMessage().trim();
        try {
            boolean saved = fileLogDao.append(result.getTransferId(), fileSender, fileReceiver,
                    result.getFileName(), result.getFileSize(), result.getSha256(), outcome, remark,
                    result.getTimestamp() == null ? LocalDateTime.now() : result.getTimestamp());
            if (!saved) {
                LOGGER.warning(() -> "文件传输日志未写入: " + fileSender + " -> " + fileReceiver);
            }
            return saved;
        } catch (ChatException e) {
            LOGGER.log(Level.WARNING, "文件传输日志写入失败: " + e.getMessage(), e);
            return false;
        }
    }

    private String outcomeOf(FileMessage result) {
        if (result.getType() == MessageType.FILE_REJECT) {
            return RESULT_REJECTED;
        }
        return result.isAccepted() ? RESULT_SUCCESS : RESULT_FAILED;
    }
}

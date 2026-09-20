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
 * 文件传输日志服务。
 *
 * <p>职责：把一次已结束的文件传输按"审计"口径写入 {@code chat_file_log}——
 * 传输编号、收发双方、文件元信息、结果与结果说明。
 * 与聊天记录的区别：聊天记录面向界面展示（一次传输一条消息），
 * 本表面向统计与追溯（带 SUCCESS/REJECTED/FAILED 结果，可按结果统计成功率）。</p>
 *
 * <p>设计要点：这是旁路功能，写入失败只记日志并返回 false，绝不阻断传输本身——
 * 文件是否送达取决于收发两端，不该因为审计表写不进去就让使用者以为文件没传到。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class FileLogService {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".FileLogService");

    /** 传输结果：成功 */
    public static final String RESULT_SUCCESS = "SUCCESS";

    /** 传输结果：对方拒绝接收 */
    public static final String RESULT_REJECTED = "REJECTED";

    /** 传输结果：传输失败 */
    public static final String RESULT_FAILED = "FAILED";

    /** 文件传输日志存储 */
    private final JdbcFileLogDao fileLogDao;

    /**
     * 默认构造：使用数据库存储。
     */
    public FileLogService() {
        this(JdbcFileLogDao.fromConfig());
    }

    /**
     * 指定 DAO 构造服务，便于测试。
     *
     * @param fileLogDao 文件传输日志存储
     * @throws IllegalArgumentException 当 fileLogDao 为 null 时抛出
     */
    public FileLogService(JdbcFileLogDao fileLogDao) {
        if (fileLogDao == null) {
            throw new IllegalArgumentException("JdbcFileLogDao 不能为 null");
        }
        this.fileLogDao = fileLogDao;
    }

    /**
     * 判断日志存储是否可用。
     *
     * @return 可用返回 true
     */
    public boolean isStorageAvailable() {
        return fileLogDao.isAvailable();
    }

    /**
     * 获取存储不可用的原因。
     *
     * @return 原因描述；可用时返回空字符串
     */
    public String storageFailureReason() {
        return fileLogDao.failureReason();
    }

    /**
     * 记录一次已结束的文件传输。
     *
     * @param result       结果消息（{@code FILE_RESULT} 或 {@code FILE_REJECT}），提供传输编号与文件元信息
     * @param fileSender   文件发送者用户名
     * @param fileReceiver 文件接收者用户名
     * @return 写入成功返回 true；存储不可用或写入失败返回 false
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

    /**
     * 由结果消息推导审计结果取值。
     *
     * @param result 结果消息
     * @return {@link #RESULT_SUCCESS}、{@link #RESULT_REJECTED} 或 {@link #RESULT_FAILED}
     */
    private String outcomeOf(FileMessage result) {
        if (result.getType() == MessageType.FILE_REJECT) {
            return RESULT_REJECTED;
        }
        return result.isAccepted() ? RESULT_SUCCESS : RESULT_FAILED;
    }
}

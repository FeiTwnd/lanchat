package com.chat.service;

import com.chat.common.Constants;
import com.chat.common.Message;
import com.chat.common.Result;
import com.chat.dao.JdbcMessageDao;
import com.chat.dao.MessageDao;
import com.chat.exception.ChatException;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 消息业务服务。
 *
 * <p>职责：聊天消息的保存（数据库）、检索与导出。检索支持“用户名 + 时间范围”两种条件的任意组合，
 * 以及按关键字全文模糊检索。</p>
 *
 * <p>设计要点：所有时间范围参数在进入 DAO 之前统一补全边界值
 * （起始日补 00:00:00，结束日补 23:59:59），避免用户输入“2025-01-01”这类纯日期时漏掉当天记录。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class MessageService {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".MessageService");

    /** 消息持久化实现 */
    private final MessageDao messageDao;

    /**
     * 默认构造：使用数据库存储（本项目唯一的存储方式）。
     */
    public MessageService() {
        this(JdbcMessageDao.fromConfig());
    }

    /**
     * 指定 DAO 构造服务，便于测试。
     *
     * @param messageDao 消息持久化实现
     * @throws IllegalArgumentException 当 messageDao 为 null 时抛出
     */
    public MessageService(MessageDao messageDao) {
        if (messageDao == null) {
            throw new IllegalArgumentException("MessageDao 不能为 null");
        }
        this.messageDao = messageDao;
    }

    /**
     * 判断聊天记录存储是否可用。
     *
     * @return 可用返回 true
     */
    public boolean isStorageAvailable() {
        return messageDao instanceof JdbcMessageDao && ((JdbcMessageDao) messageDao).isAvailable();
    }

    /**
     * 获取聊天记录存储不可用的原因。
     *
     * @return 原因描述；可用时返回空字符串
     */
    public String storageFailureReason() {
        return messageDao instanceof JdbcMessageDao ? ((JdbcMessageDao) messageDao).failureReason() : "";
    }

    /**
     * 保存一条消息到历史记录。
     *
     * <p>历史记录属于旁路功能：保存失败不应该阻断消息的正常转发，
     * 因此本方法返回 {@link Result} 而非抛异常，调用方记录失败原因后可继续业务。</p>
     *
     * @param message 待保存消息
     * @return 保存结果
     */
    public Result<Boolean> saveMessage(Message message) {
        if (message == null) {
            return Result.fail("消息不能为空");
        }
        try {
            boolean saved = messageDao.append(message);
            return saved ? Result.ok("已保存", Boolean.TRUE) : Result.fail("消息保存失败");
        } catch (ChatException e) {
            LOGGER.log(Level.WARNING, "消息保存失败: " + e.getMessage(), e);
            return Result.fail("消息保存失败: " + e.getMessage());
        }
    }

    /**
     * 查询聊天记录。
     *
     * @param username 用户名，null 或空表示查询全部
     * @param fromDate 起始日期（含），可为 null
     * @param toDate   结束日期（含），可为 null
     * @return 成功时携带按时间升序排列的消息列表
     */
    public Result<List<Message>> queryHistory(String username, LocalDate fromDate, LocalDate toDate) {
        LocalDateTime from = fromDate == null ? null : fromDate.atStartOfDay();
        LocalDateTime to = toDate == null ? null : toDate.atTime(LocalTime.MAX);
        return queryHistory(username, from, to);
    }

    /**
     * 查询聊天记录（精确时间）。
     *
     * @param username 用户名，null 或空表示查询全部
     * @param from     起始时间（含），可为 null
     * @param to       结束时间（含），可为 null
     * @return 成功时携带消息列表
     */
    public Result<List<Message>> queryHistory(String username, LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            return Result.fail("起始时间不能晚于结束时间");
        }
        try {
            String target = (username == null || username.trim().isEmpty()) ? null : username.trim();
            List<Message> messages = messageDao.query(target, from, to);
            LOGGER.info(() -> "历史记录查询命中 " + messages.size() + " 条");
            return Result.ok("查询成功，共 " + messages.size() + " 条", messages);
        } catch (ChatException e) {
            return Result.fail("查询失败: " + e.getMessage());
        }
    }

    /**
     * 按关键字检索全部聊天记录。
     *
     * @param keyword 关键字
     * @return 成功时携带命中列表
     */
    public Result<List<Message>> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Result.fail("关键字不能为空");
        }
        try {
            List<Message> messages = messageDao.search(keyword.trim());
            return Result.ok("命中 " + messages.size() + " 条记录", messages);
        } catch (ChatException e) {
            return Result.fail("检索失败: " + e.getMessage());
        }
    }

    /**
     * 把消息列表导出到指定文件。
     *
     * <p>只负责"渲染 + 落盘"，不含"查哪条记录"的决策：界面上的导出由服务端查库、
     * 客户端落盘（见 {@code ClientHandler.handleExportRequest}），本方法则是服务端本地
     * 导出文件这一能力的入口。</p>
     *
     * @param messages 待导出消息
     * @param target   目标文件
     * @return 成功时携带导出文件对象
     */
    public Result<File> exportMessages(List<Message> messages, File target) {
        if (target == null) {
            return Result.fail("导出目标文件不能为空");
        }
        if (messages == null || messages.isEmpty()) {
            return Result.fail("没有可导出的记录");
        }
        try {
            int count = messageDao.exportTo(messages, target);
            LOGGER.info(() -> "聊天记录已导出 " + count + " 条到 " + target.getAbsolutePath());
            return Result.ok("已导出 " + count + " 条记录", target);
        } catch (ChatException e) {
            return Result.fail("导出失败: " + e.getMessage());
        } catch (java.io.IOException e) {
            LOGGER.log(Level.WARNING, "导出写文件失败", e);
            return Result.fail("导出失败: " + e.getMessage());
        }
    }

    /**
     * 统计历史消息总数。
     *
     * @return 消息条数
     */
    public long count() {
        try {
            return messageDao.count();
        } catch (ChatException e) {
            LOGGER.warning("消息统计失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 生成历史记录查询的默认起始时间（最近 7 天）。
     *
     * <p>供界面初始化日期选择器使用，避免默认查询全部记录造成界面卡顿。</p>
     *
     * @return 7 天前的 00:00:00
     */
    public static LocalDateTime defaultFromTime() {
        return LocalDateTime.now().minusDays(7).with(LocalTime.MIN);
    }
}

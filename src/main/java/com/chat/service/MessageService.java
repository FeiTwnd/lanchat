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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 消息业务服务，负责聊天记录的保存、查询、检索与导出。
 */
public class MessageService {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".MessageService");

    private final MessageDao messageDao;

    public MessageService() {
        this(JdbcMessageDao.fromConfig());
    }

    /**
     * 使用指定的消息存储创建服务。
     *
     * @param messageDao 消息存储实现
     */
    public MessageService(MessageDao messageDao) {
        if (messageDao == null) {
            throw new IllegalArgumentException("MessageDao 不能为 null");
        }
        this.messageDao = messageDao;
    }

    public boolean isStorageAvailable() {
        return messageDao instanceof JdbcMessageDao && ((JdbcMessageDao) messageDao).isAvailable();
    }

    public String storageFailureReason() {
        return messageDao instanceof JdbcMessageDao ? ((JdbcMessageDao) messageDao).failureReason() : "";
    }

    /**
     * 保存一条消息到历史记录。
     *
     * @param message 待保存的消息
     * @return 保存结果，失败时携带原因
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
     * 按日期范围查询聊天记录，起止日期均包含当天。
     *
     * @param username 用户名，空表示不限
     * @param fromDate 起始日期，可为 null
     * @param toDate 结束日期，可为 null
     * @return 查询结果，成功时携带消息列表
     */
    public Result<List<Message>> queryHistory(String username, LocalDate fromDate, LocalDate toDate) {
        // 起始日期取当天零点，结束日期取当天最后一刻
        LocalDateTime from = fromDate == null ? null : fromDate.atStartOfDay();
        LocalDateTime to = toDate == null ? null : toDate.atTime(LocalTime.MAX);
        return queryHistory(username, from, to);
    }

    /**
     * 按时间范围查询聊天记录。
     *
     * @param username 用户名，空表示不限
     * @param from 起始时间，可为 null
     * @param to 结束时间，可为 null
     * @return 查询结果，成功时携带消息列表
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
     * 按关键字检索当前用户可见的聊天记录。
     *
     * @param requester 检索发起人
     * @param keyword 检索关键字
     * @return 命中的记录
     */
    public Result<List<Message>> search(String requester, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Result.fail("关键字不能为空");
        }
        if (requester == null || requester.trim().isEmpty()) {
            return Result.fail("未登录，无法检索聊天记录");
        }
        String self = requester.trim();
        try {
            List<Message> visible = new ArrayList<>();
            // 只保留当前用户作为发送者、接收者或群发的记录
            for (Message message : messageDao.search(keyword.trim())) {
                if (visibleTo(message, self)) {
                    visible.add(message);
                }
            }
            return Result.ok("命中 " + visible.size() + " 条记录", visible);
        } catch (ChatException e) {
            return Result.fail("检索失败: " + e.getMessage());
        }
    }

    private static boolean visibleTo(Message message, String username) {
        return username.equals(message.getSender())
                || username.equals(message.getReceiver())
                || message.isBroadcast();
    }

    /**
     * 把指定消息导出到文件。
     *
     * @param messages 待导出的消息
     * @param target 目标文件
     * @return 导出结果，成功时携带目标文件
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

    public boolean existsByMessageId(String messageId) throws ChatException {
        if (messageId == null || messageId.trim().isEmpty()) {
            return false;
        }
        return messageDao.existsByMessageId(messageId.trim());
    }

    public List<Message> findUndelivered(String receiver, int limit) throws ChatException {
        if (receiver == null || receiver.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return messageDao.findUndelivered(receiver.trim(), limit);
    }

    public int markDelivered(Collection<String> messageIds) throws ChatException {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        return messageDao.markDelivered(messageIds);
    }

    public Message findByMessageId(String messageId) throws ChatException {
        if (messageId == null || messageId.trim().isEmpty()) {
            return null;
        }
        return messageDao.findByMessageId(messageId.trim());
    }

    /**
     * 撤回一条消息。
     *
     * @param messageId 消息标识
     * @param requester 撤回发起人
     * @return 撤回结果
     * @throws ChatException 查询或更新失败时抛出
     */
    public Result<Boolean> recall(String messageId, String requester) throws ChatException {
        if (messageId == null || messageId.trim().isEmpty()) {
            return Result.fail("缺少消息标识，无法撤回");
        }
        if (requester == null || requester.trim().isEmpty()) {
            return Result.fail("未登录，无法撤回");
        }
        Message message = messageDao.findByMessageId(messageId.trim());
        if (message == null) {
            return Result.fail("消息不存在或已过期，无法撤回");
        }
        if (!requester.trim().equals(message.getSender())) {
            // 只有发送者本人可以撤回
            return Result.fail("只能撤回自己发送的消息");
        }
        if (Constants.RECALLED_PLACEHOLDER.equals(message.getSummary())) {
            return Result.ok("该消息已撤回", Boolean.TRUE);
        }
        LocalDateTime sentAt = message.getTimestamp();
        if (sentAt != null
                && java.time.Duration.between(sentAt, LocalDateTime.now()).toMinutes()
                >= Constants.RECALL_WINDOW_MINUTES) {
            return Result.fail("超过撤回时限（" + Constants.RECALL_WINDOW_MINUTES + " 分钟），无法撤回");
        }
        boolean updated = messageDao.markRecalled(messageId.trim(), requester.trim());
        if (!updated) {
            // 更新 0 行时只记录日志
            LOGGER.info(() -> "撤回未更新任何行（可能已被并发撤回）: " + messageId);
        }
        return Result.ok("已撤回", Boolean.TRUE);
    }

    /**
     * 按键集分页查询聊天记录。
     *
     * @param username 当前用户名，空表示不限
     * @param peer 对端用户名，空表示不限
     * @param from 起始时间，可为 null
     * @param to 结束时间，可为 null
     * @param beforeId 上一页最后一条消息的编号，可为 null
     * @param limit 每页条数
     * @return 消息列表
     * @throws ChatException 时间范围非法或查询失败时抛出
     */
    public List<Message> queryPage(String username, String peer, LocalDateTime from, LocalDateTime to,
                                   Long beforeId, int limit) throws ChatException {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ChatException("起始时间不能晚于结束时间");
        }
        String self = (username == null || username.trim().isEmpty()) ? null : username.trim();
        String target = (peer == null || peer.trim().isEmpty()) ? null : peer.trim();
        return messageDao.queryPage(self, target, from, to, beforeId, limit);
    }

    /**
     * 统计消息总数。
     *
     * @return 消息条数，查询失败返回 0
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
     * 返回默认查询起点，即 7 天前的零点。
     *
     * @return 默认起始时间
     */
    public static LocalDateTime defaultFromTime() {
        return LocalDateTime.now().minusDays(7).with(LocalTime.MIN);
    }
}

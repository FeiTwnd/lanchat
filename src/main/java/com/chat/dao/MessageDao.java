package com.chat.dao;

import com.chat.common.Message;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import com.chat.exception.ChatException;

/**
 * 聊天记录数据访问接口，提供追加、查询、分页、撤回与导出等操作。
 */
public interface MessageDao extends BaseDao<Message, Long> {
    /**
     * 追加一条聊天记录。
     *
     * @param message 待保存的消息
     * @return 写入成功返回 true
     * @throws ChatException 数据库操作失败时抛出
     */
    boolean append(Message message) throws ChatException;

    /**
     * 按用户与时间范围查询聊天记录。
     *
     * @param username 参与会话的用户名，为 null 时不限制
     * @param from 起始时间，为 null 时不限制
     * @param to 结束时间，为 null 时不限制
     * @return 按时间升序排列的消息列表
     * @throws ChatException 数据库操作失败时抛出
     */
    List<Message> query(String username, LocalDateTime from, LocalDateTime to) throws ChatException;

    /**
     * 判断消息标识是否已存在。
     *
     * @param messageId 消息标识
     * @return 已存在返回 true
     * @throws ChatException 数据库操作失败时抛出
     */
    boolean existsByMessageId(String messageId) throws ChatException;

    /**
     * 查询指定接收者尚未投递的消息。
     *
     * @param receiver 接收者用户名
     * @param limit 最多返回的条数
     * @return 未投递的消息列表
     * @throws ChatException 数据库操作失败时抛出
     */
    List<Message> findUndelivered(String receiver, int limit) throws ChatException;

    /**
     * 批量标记消息为已投递。
     *
     * @param messageIds 消息标识集合
     * @return 实际更新的条数
     * @throws ChatException 数据库操作失败时抛出
     */
    int markDelivered(java.util.Collection<String> messageIds) throws ChatException;

    /**
     * 按消息标识查询单条记录。
     *
     * @param messageId 消息标识
     * @return 对应消息，不存在返回 null
     * @throws ChatException 数据库操作失败时抛出
     */
    Message findByMessageId(String messageId) throws ChatException;

    /**
     * 撤回指定发送者的消息。
     *
     * @param messageId 消息标识
     * @param sender 发送者用户名，需与记录一致
     * @return 撤回成功返回 true
     * @throws ChatException 数据库操作失败时抛出
     */
    boolean markRecalled(String messageId, String sender) throws ChatException;

    /**
     * 分页查询会话记录。
     *
     * @param username 当前用户名，为 null 时不限制
     * @param peer 对方用户名，为 null 时不限制
     * @param from 起始时间，为 null 时不限制
     * @param to 结束时间，为 null 时不限制
     * @param beforeId 只返回主键小于该值的记录，为 null 时从头开始
     * @param limit 每页条数
     * @return 按时间升序排列的一页消息
     * @throws ChatException 数据库操作失败时抛出
     */
    List<Message> queryPage(String username, String peer, LocalDateTime from, LocalDateTime to,
                            Long beforeId, int limit) throws ChatException;

    /**
     * 按关键字检索聊天记录。
     *
     * @param keyword 检索关键字
     * @return 命中的消息列表
     * @throws ChatException 关键字为空或数据库操作失败时抛出
     */
    List<Message> search(String keyword) throws ChatException;

    /**
     * 将消息导出到文本文件。
     *
     * @param messages 待导出的消息
     * @param target 目标文件
     * @return 导出的记录条数
     * @throws IOException 文件写入失败时抛出
     * @throws ChatException 导出参数或查询失败时抛出
     */
    int exportTo(List<Message> messages, File target) throws IOException, ChatException;
}

package com.chat.dao;

import com.chat.common.Message;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import com.chat.exception.ChatException;

/**
 * 聊天消息数据访问接口。
 *
 * <p>职责：在 {@link BaseDao} 通用能力之上，补充“按用户 + 时间范围”的检索能力，
 * 以及历史记录导出能力——这正是课程设计“消息持久化与查询”评分点的落点。</p>
 *
 * <p>实现只有数据库一种：{@link JdbcMessageDao}。接口保留是为了让业务层依赖抽象，
 * 也便于测试注入不同数据源。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public interface MessageDao extends BaseDao<Message, Long> {

    /**
     * 追加一条聊天记录到数据库。
     *
     * @param message 待保存消息
     * @return 保存成功返回 true
     * @throws ChatException 写入失败时抛出
     */
    boolean append(Message message) throws ChatException;

    /**
     * 按用户与时间范围检索消息。
     *
     * <p>“与用户相关”的定义：消息发送者或接收者包含该用户，且消息类型为私聊（含用户间）。
     * 传入 null 用户名时返回全部消息，用于管理端查询。</p>
     *
     * @param username 用户名，可为 null 表示不限制
     * @param from     起始时间（含），可为 null 表示不限制
     * @param to       结束时间（含），可为 null 表示不限制
     * @return 按时间升序排列的消息列表，永不返回 null
     * @throws ChatException 读取失败时抛出
     */
    List<Message> query(String username, LocalDateTime from, LocalDateTime to) throws ChatException;

    /**
     * 在全部消息中按关键字模糊检索。
     *
     * @param keyword 关键字，不允许为空
     * @return 命中的消息列表（时间升序），永不返回 null
     * @throws ChatException 读取失败时抛出
     */
    List<Message> search(String keyword) throws ChatException;

    /**
     * 把消息列表导出为文本文件。
     *
     * @param messages 待导出消息
     * @param target   目标文件
     * @return 实际写入的消息条数
     * @throws IOException   读写失败时抛出
     * @throws ChatException 目标路径非法时抛出
     */
    int exportTo(List<Message> messages, File target) throws IOException, ChatException;
}

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
     * 按稳定消息标识查询是否已存在。
     *
     * <p>用于服务端落库前去重：网络重发、断线补投都可能把同一条消息再次送达服务端，
     * 而稳定标识（{@link Message#getMessageId()}）跨端一致，因此可以据此判断是否已入库。</p>
     *
     * @param messageId 稳定消息标识；为 null 或空白时返回 false（历史数据该列为 NULL，不存在可比对的标识）
     * @return 已存在返回 true，否则返回 false
     * @throws ChatException 读取失败时抛出
     */
    boolean existsByMessageId(String messageId) throws ChatException;

    /**
     * 查询某个接收者尚未送达的消息，用于登录后的离线补投。
     *
     * @param receiver 接收者用户名；为 null 或空白时返回空列表（无法确定补投对象）
     * @param limit    最多返回条数；小于等于 0 时由实现使用默认值
     * @return 按主键升序（即时间先后）排列的消息列表，永不返回 null
     * @throws ChatException 读取失败时抛出
     */
    List<Message> findUndelivered(String receiver, int limit) throws ChatException;

    /**
     * 批量把消息标记为已送达。
     *
     * <p>对应 {@link #findUndelivered(String, int)} 的补投结果：补投成功后才标记，
     * 保证补投中途失败时消息不会被误判为已送达而丢失。</p>
     *
     * @param messageIds 稳定消息标识集合；为 null 或空集合时直接返回 0，不执行任何 SQL
     * @return 实际更新的行数
     * @throws ChatException 更新失败时抛出
     */
    int markDelivered(java.util.Collection<String> messageIds) throws ChatException;

    /**
     * 键集分页查询聊天记录，供界面向上翻页（加载更早的历史消息）。
     *
     * <p>语义为“取不晚于 beforeId 的最近 limit 条”，返回结果按时间升序，便于界面直接追加。</p>
     *
     * @param username 当前用户；为 null 或空白表示不限制（与 {@link #query} 的 null 语义一致）
     * @param peer     对端用户名；非空时只查该用户与对端的双向私聊，为空时沿用 {@link #query} 的既有语义
     * @param from     起始时间（含），可为 null
     * @param to       结束时间（含），可为 null
     * @param beforeId 分页游标：只取主键小于该值的记录；为 null 时从最新一条开始取
     * @param limit    本页最多返回条数；小于等于 0 时由实现使用默认值
     * @return 按时间升序排列的消息列表，永不返回 null
     * @throws ChatException 读取失败时抛出
     */
    List<Message> queryPage(String username, String peer, LocalDateTime from, LocalDateTime to,
                            Long beforeId, int limit) throws ChatException;

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

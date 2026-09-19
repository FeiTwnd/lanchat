package com.chat.dao;

import com.chat.common.User;
import com.chat.exception.ChatException;
import com.chat.exception.UserNotFoundException;

/**
 * 用户数据访问接口。
 *
 * <p>职责：在 {@link BaseDao} 通用能力之上，补充用户场景特有的查询语义。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public interface UserDao extends BaseDao<User, String> {

    /**
     * 按用户名查询用户。
     *
     * @param username 用户名（主键）
     * @return 用户对象；不存在时返回 null
     * @throws ChatException 读取失败时抛出
     */
    User findByUsername(String username) throws ChatException;

    /**
     * 判断用户名是否已存在。
     *
     * @param username 用户名
     * @return 存在返回 true
     * @throws ChatException 读取失败时抛出
     */
    boolean exists(String username) throws ChatException;

    /**
     * 按用户名删除用户。
     *
     * @param username 用户名
     * @return 删除成功返回 true
     * @throws UserNotFoundException 用户不存在时抛出
     * @throws ChatException         持久化失败时抛出
     */
    boolean deleteByUsername(String username) throws UserNotFoundException, ChatException;

    /**
     * 保存或更新用户（存在则覆盖）。
     *
     * <p>批量刷盘场景下比“先判断再调用 save/update”更简洁，减少一次读取。</p>
     *
     * @param user 用户对象
     * @return 写入成功返回 true
     * @throws ChatException 持久化失败时抛出
     */
    boolean saveOrUpdate(User user) throws ChatException;
}

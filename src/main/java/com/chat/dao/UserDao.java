package com.chat.dao;

import com.chat.common.User;
import com.chat.exception.ChatException;
import com.chat.exception.UserNotFoundException;

/**
 * 用户数据访问接口，按用户名维护账号记录。
 */
public interface UserDao extends BaseDao<User, String> {
    /**
     * 按用户名查询用户。
     *
     * @param username 用户名
     * @return 对应用户，不存在返回 null
     * @throws ChatException 数据库操作失败时抛出
     */
    User findByUsername(String username) throws ChatException;

    /**
     * 判断用户名是否已存在。
     *
     * @param username 用户名
     * @return 已存在返回 true
     * @throws ChatException 数据库操作失败时抛出
     */
    boolean exists(String username) throws ChatException;

    /**
     * 按用户名删除用户。
     *
     * @param username 用户名
     * @return 删除成功返回 true
     * @throws UserNotFoundException 用户不存在时抛出
     * @throws ChatException 数据库操作失败时抛出
     */
    boolean deleteByUsername(String username) throws UserNotFoundException, ChatException;

    /**
     * 保存用户，用户名已存在时改为更新。
     *
     * @param user 用户对象
     * @return 写入成功返回 true
     * @throws ChatException 数据库操作失败时抛出
     */
    boolean saveOrUpdate(User user) throws ChatException;
}

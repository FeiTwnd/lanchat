package com.chat.dao;

import com.chat.exception.ChatException;

import java.util.List;

/**
 * 通用数据访问接口，定义实体的保存、更新、删除、查询与计数操作。
 *
 * @param <T> 实体类型
 * @param <ID> 主键类型
 */
public interface BaseDao<T, ID> {
    /**
     * 保存实体。
     *
     * @param entity 待保存实体
     * @return 保存成功返回 true
     * @throws ChatException 数据库操作失败时抛出
     */
    boolean save(T entity) throws ChatException;

    /**
     * 更新实体。
     *
     * @param entity 待更新实体
     * @return 更新成功返回 true
     * @throws ChatException 数据库操作失败时抛出
     */
    boolean update(T entity) throws ChatException;

    /**
     * 按主键删除实体。
     *
     * @param id 主键
     * @return 删除成功返回 true
     * @throws ChatException 数据库操作失败时抛出
     */
    boolean deleteById(ID id) throws ChatException;

    /**
     * 按主键查询实体。
     *
     * @param id 主键
     * @return 查到的实体，不存在返回 null
     * @throws ChatException 数据库操作失败时抛出
     */
    T findById(ID id) throws ChatException;

    /**
     * 查询全部实体。
     *
     * @return 实体列表
     * @throws ChatException 数据库操作失败时抛出
     */
    List<T> findAll() throws ChatException;

    /**
     * 统计实体总数。
     *
     * @return 记录条数
     * @throws ChatException 数据库操作失败时抛出
     */
    long count() throws ChatException;
}

package com.chat.dao;

import com.chat.exception.ChatException;

import java.util.List;

/**
 * 通用数据访问接口（泛型）。
 *
 * <p>职责：抽象出“增、删、改、查”四类最基本的持久化操作，让业务层只依赖接口而非具体存储实现。
 * 这是 DAO 模式与泛型的核心落地点：{@code BaseDao<User, String>} 与 {@code BaseDao<Message, Long>}
 * 在同一套方法签名下工作，编译期即可校验主键与实体类型。</p>
 *
 * <p>为什么保留接口而 Service 层不设接口：本系统的 DAO 确实存在两套实现（文件与 JDBC），
 * 接口能带来真实收益；而 Service 层只有唯一实现，再加接口属于冗余抽象。</p>
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 * @author Java 课程设计
 * @version 1.0
 */
public interface BaseDao<T, ID> {

    /**
     * 新增实体。
     *
     * @param entity 待新增实体
     * @return 新增成功返回 true
     * @throws ChatException 持久化失败时抛出
     */
    boolean save(T entity) throws ChatException;

    /**
     * 更新实体。
     *
     * @param entity 待更新实体，主键必须已存在
     * @return 更新成功返回 true；主键不存在返回 false
     * @throws ChatException 持久化失败时抛出
     */
    boolean update(T entity) throws ChatException;

    /**
     * 按主键删除实体。
     *
     * @param id 主键
     * @return 删除成功返回 true；主键不存在返回 false
     * @throws ChatException 持久化失败时抛出
     */
    boolean deleteById(ID id) throws ChatException;

    /**
     * 按主键查询实体。
     *
     * @param id 主键
     * @return 实体对象；不存在时返回 null
     * @throws ChatException 读取失败时抛出
     */
    T findById(ID id) throws ChatException;

    /**
     * 查询全部实体。
     *
     * @return 实体列表，永不返回 null
     * @throws ChatException 读取失败时抛出
     */
    List<T> findAll() throws ChatException;

    /**
     * 统计实体总数。
     *
     * @return 记录条数
     * @throws ChatException 读取失败时抛出
     */
    long count() throws ChatException;
}

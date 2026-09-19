package com.chat.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体类。
 *
 * <p>职责：描述一个账号的全部持久化信息（用户名、昵称、密码散列、盐、角色、注册与登录时间），
 * 同时作为在线用户列表的载体在网络上传输（此时不携带敏感字段）。</p>
 *
 * <p>封装说明：所有字段私有，通过 getter/setter 访问；密码相关字段在序列化到客户端前
 * 必须由 {@link #clearCredential()} 清空，避免散列与盐随在线用户列表广播出去。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class User implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250106L;

    /** 登录用户名，全局唯一，作为持久化主键 */
    private String username;

    /** 显示昵称，可重复，仅用于界面展示 */
    private String nickname;

    /** 密码散列值（十六进制），格式为 {@code salt:hash} 中的 hash 部分 */
    private String passwordHash;

    /** 密码盐值（十六进制），与散列分开存储以抵御彩虹表攻击 */
    private String salt;

    /** 角色：{@link Constants#ROLE_ADMIN} 或 {@link Constants#ROLE_USER} */
    private String role;

    /** 注册时间 */
    private LocalDateTime createTime;

    /** 最近一次登录时间 */
    private LocalDateTime lastLoginTime;

    /** 是否在线（运行期状态，不持久化） */
    private boolean online;

    /**
     * 无参构造，供文件反序列化与测试使用。
     */
    public User() {
    }

    /**
     * 构造用户。
     *
     * @param username 用户名
     * @param nickname 昵称
     */
    public User(String username, String nickname) {
        this.username = username;
        this.nickname = nickname;
        this.role = Constants.ROLE_USER;
        this.createTime = LocalDateTime.now();
    }

    /**
     * 获取用户名。
     *
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * 设置用户名。
     *
     * @param username 用户名
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * 获取昵称。
     *
     * @return 昵称
     */
    public String getNickname() {
        return nickname;
    }

    /**
     * 设置昵称。
     *
     * @param nickname 昵称
     */
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * 获取密码散列。
     *
     * @return 十六进制散列值，可能为 null（凭据已被清理）
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * 设置密码散列。
     *
     * @param passwordHash 十六进制散列值
     */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * 获取密码盐。
     *
     * @return 十六进制盐值，可能为 null（凭据已被清理）
     */
    public String getSalt() {
        return salt;
    }

    /**
     * 设置密码盐。
     *
     * @param salt 十六进制盐值
     */
    public void setSalt(String salt) {
        this.salt = salt;
    }

    /**
     * 获取角色。
     *
     * @return {@link Constants#ROLE_ADMIN} 或 {@link Constants#ROLE_USER}
     */
    public String getRole() {
        return role;
    }

    /**
     * 设置角色。
     *
     * @param role 角色标识
     */
    public void setRole(String role) {
        this.role = role;
    }

    /**
     * 判断是否为管理员。
     *
     * @return 角色为 ADMIN 时返回 true
     */
    public boolean isAdmin() {
        return Constants.ROLE_ADMIN.equalsIgnoreCase(role);
    }

    /**
     * 获取注册时间。
     *
     * @return 注册时间
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置注册时间。
     *
     * @param createTime 注册时间
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    /**
     * 获取最近登录时间。
     *
     * @return 最近登录时间，从未登录时为 null
     */
    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    /**
     * 设置最近登录时间。
     *
     * @param lastLoginTime 最近登录时间
     */
    public void setLastLoginTime(LocalDateTime lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    /**
     * 判断是否在线。
     *
     * @return 在线返回 true
     */
    public boolean isOnline() {
        return online;
    }

    /**
     * 设置在线状态。
     *
     * @param online 在线状态
     */
    public void setOnline(boolean online) {
        this.online = online;
    }

    /**
     * 清理密码凭据，用于把用户对象发送给客户端之前脱敏。
     *
     * <p>把自己从“持久化实体”临时降级为“公共资料对象”，避免散列与盐经网络泄露。</p>
     */
    public void clearCredential() {
        this.passwordHash = null;
        this.salt = null;
    }

    /**
     * 生成界面展示名：昵称（用户名）。
     *
     * @return 形如 {@code 张三(alice)} 的展示名
     */
    public String getDisplayName() {
        if (nickname == null || nickname.isEmpty()) {
            return username;
        }
        return nickname + "(" + username + ")";
    }

    /**
     * 输出用户摘要，刻意不包含密码散列与盐，防止凭据进入日志。
     *
     * @return 形如 {@code User{username=alice, nickname=张三, role=USER, online=true}} 的字符串
     */
    @Override
    public String toString() {
        return "User{username=" + username + ", nickname=" + nickname
                + ", role=" + role + ", online=" + online + "}";
    }

    /**
     * 以用户名为唯一标识判断相等性，便于放入集合去重。
     *
     * @param obj 比较对象
     * @return 用户名相同则返回 true
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof User)) {
            return false;
        }
        User other = (User) obj;
        return username != null && username.equals(other.username);
    }

    /**
     * 计算散列码。
     *
     * @return 基于用户名的散列码，与 {@link #equals(Object)} 保持一致
     */
    @Override
    public int hashCode() {
        return username == null ? 0 : username.hashCode();
    }
}

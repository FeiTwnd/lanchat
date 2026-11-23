package com.chat.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户账号信息，包含昵称、角色、头像与在线状态。
 */
public class User implements Serializable {
    private static final long serialVersionUID = 20250106L;

    private String username;

    private String nickname;

    /** 密码哈希值 */
    private String passwordHash;

    /** 密码哈希使用的盐值 */
    private String salt;

    private String role;

    private LocalDateTime createTime;

    private LocalDateTime lastLoginTime;

    private boolean online;

    /** Base64 编码的 PNG 头像 */
    private String avatar;

    public User() {
    }

    /**
     * 创建普通用户并记录创建时间。
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    /**
     * 判断用户是否为管理员。
     *
     * @return 管理员时返回 true
     */
    public boolean isAdmin() {
        return Constants.ROLE_ADMIN.equalsIgnoreCase(role);
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(LocalDateTime lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    /** 清空密码哈希与盐值。 */
    public void clearCredential() {
        this.passwordHash = null;
        this.salt = null;
    }

    public String getAvatar() {
        return avatar;
    }

    /**
     * 设置头像数据，空字符串按未设置处理。
     *
     * @param avatar Base64 编码的 PNG 头像
     */
    public void setAvatar(String avatar) {
        this.avatar = avatar == null || avatar.isEmpty() ? null : avatar;
    }

    /**
     * 判断用户是否设置头像。
     *
     * @return 已设置头像时返回 true
     */
    public boolean hasAvatar() {
        return avatar != null && !avatar.isEmpty();
    }

    /**
     * 返回昵称与用户名的展示文本。
     *
     * @return 昵称为空时返回用户名
     */
    public String getDisplayName() {
        if (nickname == null || nickname.isEmpty()) {
            return username;
        }
        return nickname + "(" + username + ")";
    }

    @Override
    public String toString() {
        return "User{username=" + username + ", nickname=" + nickname
                + ", role=" + role + ", online=" + online + "}";
    }

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

    @Override
    public int hashCode() {
        return username == null ? 0 : username.hashCode();
    }
}

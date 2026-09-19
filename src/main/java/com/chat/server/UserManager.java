package com.chat.server;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.common.UserListCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 在线用户管理器。
 *
 * <p>职责：维护“用户名 -> 连接处理器”的映射，提供广播、私聊定向投递、
 * 在线列表快照查询、重复登录判定等能力，是服务器端唯一持有在线状态的组件。</p>
 *
 * <p>线程安全设计：</p>
 * <ul>
 *   <li>{@link ConcurrentHashMap} 作为在线表，读操作（查询、广播）无锁；</li>
 *   <li>{@link CopyOnWriteArrayList} 保存观察者，遍历通知时无需加锁，
 *       避免“通知过程中观察者注销”引发 {@code ConcurrentModificationException}；</li>
 *   <li>{@link #register} 使用 {@code putIfAbsent} 保证同一用户名并发登录时只有一个成功。</li>
 * </ul>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class UserManager {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".UserManager");

    /** 在线用户表：用户名 -> 连接处理器 */
    private final Map<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();

    /** 在线用户资料表：用户名 -> 脱敏用户对象，用于快速构造列表快照 */
    private final Map<String, User> userProfiles = new ConcurrentHashMap<>();

    /** 服务器端观察者列表（主要供 ServerUI 刷新界面） */
    private final List<ServerObserver> observers = new CopyOnWriteArrayList<>();

    /**
     * 注册一个已登录用户。
     *
     * @param user    用户对象（应在调用前完成脱敏）
     * @param handler 该用户的连接处理器
     * @return 注册成功返回 true；用户名已在线返回 false
     */
    public boolean register(User user, ClientHandler handler) {
        if (user == null || handler == null) {
            return false;
        }
        ClientHandler existing = onlineUsers.putIfAbsent(user.getUsername(), handler);
        if (existing != null) {
            LOGGER.warning(() -> "重复登录被拒绝: " + user.getUsername());
            return false;
        }
        user.setOnline(true);
        userProfiles.put(user.getUsername(), user);
        notifyObservers(ServerObserver.EventType.USER_ONLINE, user.getDisplayName() + " 已上线");
        LOGGER.info(() -> "用户上线: " + user.getUsername() + "，当前在线 " + onlineUsers.size() + " 人");
        return true;
    }

    /**
     * 注销用户（下线）。
     *
     * @param username 用户名
     * @return 确实移除了在线记录返回 true
     */
    public boolean unregister(String username) {
        if (username == null) {
            return false;
        }
        ClientHandler removed = onlineUsers.remove(username);
        User profile = userProfiles.remove(username);
        if (removed == null) {
            return false;
        }
        if (profile != null) {
            profile.setOnline(false);
        }
        notifyObservers(ServerObserver.EventType.USER_OFFLINE, username + " 已下线");
        LOGGER.info(() -> "用户下线: " + username + "，当前在线 " + onlineUsers.size() + " 人");
        return true;
    }

    /**
     * 按用户名获取连接处理器。
     *
     * @param username 用户名
     * @return 连接处理器；用户不在线时返回 null
     */
    public ClientHandler get(String username) {
        return username == null ? null : onlineUsers.get(username);
    }

    /**
     * 判断用户是否在线。
     *
     * @param username 用户名
     * @return 在线返回 true
     */
    public boolean isOnline(String username) {
        return username != null && onlineUsers.containsKey(username);
    }

    /**
     * 获取在线用户名集合。
     *
     * @return 用户名集合的副本，可安全遍历
     */
    public Set<String> onlineNames() {
        return Set.copyOf(onlineUsers.keySet());
    }

    /**
     * 获取连接处理器集合。
     *
     * @return 连接处理器集合副本
     */
    public Collection<ClientHandler> handlers() {
        return new ArrayList<>(onlineUsers.values());
    }

    /**
     * 获取在线用户资料快照（已脱敏）。
     *
     * @return 用户列表，按用户名排序
     */
    public List<User> onlineUsers() {
        List<User> users = new ArrayList<>(userProfiles.values());
        users.sort((a, b) -> a.getUsername().compareTo(b.getUsername()));
        return users;
    }

    /**
     * 在线人数。
     *
     * @return 在线用户数量
     */
    public int size() {
        return onlineUsers.size();
    }

    /**
     * 向指定用户发送消息。
     *
     * @param username 用户名
     * @param message  消息
     * @return 投递成功返回 true；用户不在线返回 false
     */
    public boolean sendTo(String username, Message message) {
        ClientHandler handler = get(username);
        if (handler == null) {
            return false;
        }
        return handler.send(message);
    }

    /**
     * 向所有在线用户广播消息。
     *
     * @param message 消息
     * @return 成功投递的连接数
     */
    public int broadcast(Message message) {
        int count = 0;
        for (ClientHandler handler : handlers()) {
            if (handler.send(message)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 广播一条系统通知。
     *
     * @param content 通知内容
     * @return 成功投递的连接数
     */
    public int broadcastSystem(String content) {
        SystemMessage message = ChatMessageFactory.system("", content);
        return broadcast(message);
    }

    /**
     * 构造在线用户列表消息并广播，使所有客户端界面保持一致。
     */
    public void broadcastUserList() {
        TextMessage message = ChatMessageFactory.text(Constants.SYSTEM_SENDER, "",
                UserListCodec.encode(onlineUsers()), MessageType.TEXT_GROUP);
        message.setType(MessageType.USER_LIST);
        broadcast(message);
    }

    /**
     * 向所有在线用户广播一条普通消息。
     *
     * @param message 已由服务器校验过的群聊消息
     * @return 成功投递的连接数
     */
    public int broadcastText(Message message) {
        return broadcast(message);
    }

    /**
     * 注册观察者。
     *
     * @param observer 观察者
     */
    public void addObserver(ServerObserver observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    /**
     * 注销观察者。
     *
     * @param observer 观察者
     */
    public void removeObserver(ServerObserver observer) {
        observers.remove(observer);
    }

    /**
     * 通知所有观察者。
     *
     * <p>单个观察者抛异常不影响其它观察者，保证界面异常不会波及服务器主流程。</p>
     *
     * @param type    事件类型
     * @param content 事件描述
     */
    public void notifyObservers(ServerObserver.EventType type, String content) {
        for (ServerObserver observer : observers) {
            try {
                observer.onEvent(type, content);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "服务器观察者回调异常，已忽略", e);
            }
        }
    }

    /**
     * 清空全部在线状态，用于服务器停止时善后。
     */
    public void clear() {
        onlineUsers.clear();
        userProfiles.clear();
        LOGGER.info("在线用户表已清空");
    }
}

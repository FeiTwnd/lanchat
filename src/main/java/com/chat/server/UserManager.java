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
 * 在线用户管理器，维护在线用户表与用户资料，负责消息投递和用户列表广播。
 */
public class UserManager {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".UserManager");

    /** 在线用户表，键为用户名，值为对应的连接处理器。 */
    private final Map<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();

    private final Map<String, User> userProfiles = new ConcurrentHashMap<>();

    /** 虚拟成员用户名集合，不计入在线人数。 */
    private final Set<String> virtualUsers = ConcurrentHashMap.newKeySet();

    private final List<ServerObserver> observers = new CopyOnWriteArrayList<>();

    /**
     * 注册上线用户。
     *
     * @param user 用户
     * @param handler 连接处理器
     * @return 注册成功返回 true，用户名已在线时返回 false
     */
    public boolean register(User user, ClientHandler handler) {
        if (user == null || handler == null) {
            return false;
        }
        // 同一用户名只允许一个连接
        ClientHandler existing = onlineUsers.putIfAbsent(user.getUsername(), handler);
        if (existing != null) {
            LOGGER.warning(() -> "重复登录被拒绝: " + user.getUsername());
            return false;
        }

        virtualUsers.remove(user.getUsername());
        user.setOnline(true);
        // 记录用户资料并通知观察者
        userProfiles.put(user.getUsername(), user);
        notifyObservers(ServerObserver.EventType.USER_ONLINE, user.getDisplayName() + " 已上线");
        LOGGER.info(() -> "用户上线: " + user.getUsername() + "，当前在线 " + onlineUsers.size() + " 人");
        return true;
    }

    /**
     * 注册不占用连接的虚拟成员，例如智能体小助手。
     *
     * @param user 用户
     */
    public void registerVirtualUser(User user) {
        if (user == null || user.getUsername() == null || user.getUsername().isEmpty()) {
            return;
        }
        user.setOnline(true);
        userProfiles.put(user.getUsername(), user);
        virtualUsers.add(user.getUsername());
        LOGGER.info(() -> "虚拟成员已注册: " + user.getDisplayName());
    }

    /**
     * 将用户从在线表移除。
     *
     * @param username 用户名
     * @return 用户原先在线并移除成功返回 true
     */
    public boolean unregister(String username) {
        if (username == null) {
            return false;
        }
        ClientHandler removed = onlineUsers.remove(username);
        User profile = userProfiles.remove(username);
        virtualUsers.remove(username);
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
     * 按用户名获取在线连接。
     *
     * @param username 用户名
     * @return 对应连接，用户不在线时返回 null
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
     * 返回在线用户名集合。
     *
     * @return 用户名集合的快照
     */
    public Set<String> onlineNames() {
        return Set.copyOf(onlineUsers.keySet());
    }

    /**
     * 返回全部在线连接。
     *
     * @return 连接列表的快照
     */
    public Collection<ClientHandler> handlers() {
        return new ArrayList<>(onlineUsers.values());
    }

    /**
     * 返回在线用户资料，按用户名排序。
     *
     * @return 用户资料列表
     */
    public List<User> onlineUsers() {
        List<User> users = new ArrayList<>(userProfiles.values());
        users.sort((a, b) -> a.getUsername().compareTo(b.getUsername()));
        return users;
    }

    /**
     * 更新在线用户的资料。
     *
     * @param user 用户资料
     */
    public void updateProfile(User user) {
        if (user == null || user.getUsername() == null) {
            return;
        }
        if (!onlineUsers.containsKey(user.getUsername())) {
            return;
        }
        user.setOnline(true);
        userProfiles.put(user.getUsername(), user);
        LOGGER.info(() -> "在线用户资料已更新: " + user.getUsername());
    }

    /**
     * 返回非虚拟成员的在线人数。
     *
     * @return 在线人数
     */
    public int size() {
        int count = 0;
        for (String name : userProfiles.keySet()) {
            if (!virtualUsers.contains(name)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 向指定用户发送消息。
     *
     * @param username 用户名
     * @param message 消息
     * @return 发送成功返回 true，用户不在线或发送失败返回 false
     */
    public boolean sendTo(String username, Message message) {
        ClientHandler handler = get(username);
        if (handler == null) {
            return false;
        }
        return handler.send(message);
    }

    /**
     * 向全部在线用户广播消息。
     *
     * @param message 消息
     * @return 发送成功的连接数
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
     * 广播系统消息。
     *
     * @param content 消息内容
     * @return 发送成功的连接数
     */
    public int broadcastSystem(String content) {
        SystemMessage message = ChatMessageFactory.system("", content);
        return broadcast(message);
    }

    /**
     * 向全部在线用户广播用户列表。
     */
    public void broadcastUserList() {
        TextMessage message = ChatMessageFactory.text(Constants.SYSTEM_SENDER, "",
                UserListCodec.encode(onlineUsers()), MessageType.TEXT_GROUP);
        message.setType(MessageType.USER_LIST);
        broadcast(message);
    }

    /**
     * 广播文本消息。
     *
     * @param message 消息
     * @return 发送成功的连接数
     */
    public int broadcastText(Message message) {
        return broadcast(message);
    }

    /**
     * 注册服务器观察者。
     *
     * @param observer 观察者
     */
    public void addObserver(ServerObserver observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    /**
     * 移除服务器观察者。
     *
     * @param observer 观察者
     */
    public void removeObserver(ServerObserver observer) {
        observers.remove(observer);
    }

    /**
     * 通知全部观察者。
     *
     * @param type 事件类型
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
     * 清空在线用户、用户资料与虚拟成员表。
     */
    public void clear() {
        onlineUsers.clear();
        userProfiles.clear();
        virtualUsers.clear();
        LOGGER.info("在线用户表已清空");
    }
}

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

    /**
     * 虚拟成员用户名集合：这些成员只出现在名单（{@link #userProfiles}）里，没有连接。
     *
     * <p>单独记一份而不是每次用 {@link Constants#AI_USERNAME} 现判：虚拟成员是一个
     * 概念而不是某一个具体名字，用集合表达后，将来再挂第二个机器人（如"翻译助手"）
     * 只需调用 {@link #registerVirtualUser}，两个统计口径自动继续成立。</p>
     */
    private final Set<String> virtualUsers = ConcurrentHashMap.newKeySet();

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
        // 真有连接进来时，同名条目不再是"虚拟成员"：不清掉的话 size() 会把这个持有连接的
        // 真人排除在真实在线人数之外，两个口径就都对不上了
        virtualUsers.remove(user.getUsername());
        user.setOnline(true);
        userProfiles.put(user.getUsername(), user);
        notifyObservers(ServerObserver.EventType.USER_ONLINE, user.getDisplayName() + " 已上线");
        LOGGER.info(() -> "用户上线: " + user.getUsername() + "，当前在线 " + onlineUsers.size() + " 人");
        return true;
    }

    /**
     * 注册一名虚拟成员（智能体小助手）。
     *
     * <p><b>为什么单独开一个方法而不是复用 {@link #register}</b>：虚拟成员没有连接，
     * 也就没有 {@link ClientHandler} 可放。硬造一个"什么都不做的 handler"塞进
     * {@link #onlineUsers} 会让 {@link #broadcast} 每次都给一个不存在的对端写一遍字节，
     * 还会让 {@link #isOnline} 对机器人返回 true，从而给机器人开启文件传输、
     * 离线补投等一整条并不成立的路径。这里只写资料表，语义上就是"名单里有这个人，
     * 但没有连接"。</p>
     *
     * <p><b>由此产生的可见取舍</b>：{@link #isOnline} 对虚拟成员返回 false，
     * 因此给机器人发文件会在连接处理器（{@code ClientHandler#isValidFilePeer} 之前）的
     * 虚拟成员分支被拒绝，并给出"小助手只支持文字问答"的中文回执——机器人收不了文件，
     * 这一点必须让使用者看到明确原因，而不是一句协议级的"用户名格式不正确"。</p>
     *
     * <p>另外，本方法写入的成员<b>不计入</b> {@link #size()}（真实在线人数），
     * 但<b>会出现在</b> {@link #onlineUsers()} 的名单里，两个口径的差别见 {@link #size()}。</p>
     *
     * <p>用 {@code put} 而不是 {@code putIfAbsent}：服务器重启或重复调用时应当幂等覆盖，
     * 而不是因为"已经有一份资料"就静默跳过，导致改过的昵称不生效。</p>
     *
     * @param user 虚拟成员的资料（应已脱敏），用户名为空时忽略
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
     * <p>名单口径<b>包含</b>没有连接的虚拟成员（智能体小助手），与 {@link #size()}
     * 只统计真实连接的口径不同，理由见 {@link #size()}：名单回答"能和谁说话"，
     * 人数回答"有几个真实并发"。调用方取总数请用 {@link #size()}，不要用本列表的长度。</p>
     *
     * @return 用户列表，按用户名排序
     */
    public List<User> onlineUsers() {
        List<User> users = new ArrayList<>(userProfiles.values());
        users.sort((a, b) -> a.getUsername().compareTo(b.getUsername()));
        return users;
    }

    /**
     * 用最新的用户资料替换在线缓存中的那一份。
     *
     * <p><b>为什么必须有这个方法</b>：{@link #broadcastUserList()} 编码的是
     * {@link #userProfiles} 这份缓存快照，而不是重新查库。资料变更只写数据库的话，
     * 缓存里留着的仍是登录那一刻的旧值，广播出去的也就还是旧昵称、旧头像——
     * 表现为"界面提示修改成功、重启客户端后也确实变了，但对方看到的一直是老样子"。
     * 昵称修改此前就存在这一问题，头像若沿用同一路径会连自己都看不到变化。</p>
     *
     * <p>为什么只覆盖 {@code online} 这一个运行期字段：用户对象里只有它是"不持久化、
     * 由在线表推导"的状态，其余字段都是数据库的镜像。传入的副本来自
     * {@code copyForTransfer}，其 {@code online} 恒为 false，因此这里必须按在线表重新推导，
     * 否则一次改名就会把该用户在列表里标成离线。</p>
     *
     * <p>用户不在线时直接返回：缓存只维护在线用户，为离线用户塞一份资料会让
     * {@code onlineUsers()} 多出不该出现的人。</p>
     *
     * @param user 变更后的用户资料（应已脱敏）
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
     * 获取"真实在线人数"，即当前持有连接的成员数量。
     *
     * <p><b>与 {@link #onlineUsers()} 的口径差别（刻意不一致）</b>：
     * 本方法<b>排除</b> {@link #registerVirtualUser} 注册的虚拟成员（智能体小助手），
     * 而 {@link #onlineUsers()} 返回的名单<b>包含</b>虚拟成员。两者服务于两件不同的事，
     * 强行统一才会出问题：</p>
     * <ul>
     *   <li><b>人数</b>用于对外通告并发规模与容量：局域网发现应答把它报给尚未连接的客户端，
     *       集成测试也用它在断言"几个人在线上"。虚拟成员没有连接、不占用任何并发资源、
     *       收不到也不会漏掉任何广播，把它算进来会凭空多出一人——服务器一个真实连接都没有时
     *       对外宣称"在线 1 人"正是由此而来。</li>
     *   <li><b>名单</b>用于回答"我可以和谁说话"：客户端好友树与服务器控制台按名单渲染，
     *       小助手确实可以被私聊，必须出现在名单里，否则使用者根本点不到它。</li>
     * </ul>
     *
     * <p>实现上按资料表遍历并排除虚拟成员，而不是用 {@code userProfiles.size() - virtualUsers.size()}：
     * 后者依赖"两个集合始终同步"这一隐式约定，任何一处单独删改都会让结果变成负数或虚高。</p>
     *
     * @return 真实在线成员数量，不含虚拟成员
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
     *
     * <p>虚拟成员集合必须一并清空：{@link #size()} 以"资料表里存在、且不在虚拟成员集合里"
     * 为判定条件，只清资料表会留下一条指向已不存在成员的记录，第二次启动注册新成员时
     * 统计口径就会与名单错位。</p>
     */
    public void clear() {
        onlineUsers.clear();
        userProfiles.clear();
        virtualUsers.clear();
        LOGGER.info("在线用户表已清空");
    }
}

package com.chat.dao;

import com.chat.common.Constants;
import com.chat.common.User;
import com.chat.exception.ChatException;
import com.chat.exception.UserNotFoundException;
import com.chat.util.DateUtil;
import com.chat.util.FileUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 用户数据访问的文件实现。
 *
 * <p>职责：把用户实体持久化到 {@code data/users.txt}，每行一条记录，
 * 字段以制表符分隔，顺序为：用户名、昵称、盐、散列、角色、注册时间、最近登录时间。</p>
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li>内存缓存 {@link ConcurrentHashMap} 作为读缓存，写操作同步刷盘，
 *       既保证重启后数据不丢，又避免每次登录都做一次磁盘扫描。</li>
 *   <li>写操作以“整表重写”方式落盘：用户表体量极小（课程设计级别），
 *       整表重写比维护追加日志简单得多，且天然避免文件损坏。</li>
 *   <li>所有写操作加对象锁，防止多线程并发登录/注册时互相覆盖。</li>
 * </ul>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class UserDaoImpl implements UserDao {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".UserDao");

    /** 数据文件路径 */
    private final String filePath;

    /** 内存缓存：用户名 -> 用户对象 */
    private final Map<String, User> cache = new ConcurrentHashMap<>();

    /** 写锁，保护文件整表重写与缓存更新的原子性 */
    private final Object writeLock = new Object();

    /**
     * 使用默认路径构造 DAO。
     */
    public UserDaoImpl() {
        this(com.chat.common.Config.userFile());
    }

    /**
     * 使用指定文件路径构造 DAO，便于单元测试使用独立临时文件。
     *
     * @param filePath 用户数据文件路径
     */
    public UserDaoImpl(String filePath) {
        this.filePath = filePath;
        reload();
    }

    /**
     * 从磁盘重新加载全部用户到内存缓存。
     *
     * <p>启动时调用；单行解析失败只跳过该行并记录警告，不让一条脏数据导致整个系统无法启动。</p>
     */
    public final void reload() {
        cache.clear();
        File file = new File(filePath);
        if (!file.isFile()) {
            LOGGER.info(() -> "用户数据文件不存在，将在首次写入时创建: " + filePath);
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), Constants.CHARSET)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                // 头部注释与空行不是数据行，静默跳过，否则正常文件也会被误报为损坏
                if (line.trim().isEmpty() || line.startsWith("#")) {
                    continue;
                }
                User user = parseLine(line);
                if (user != null) {
                    cache.put(user.getUsername(), user);
                } else {
                    LOGGER.warning("用户文件第 " + lineNo + " 行格式非法，已跳过");
                }
            }
            LOGGER.info(() -> "用户数据加载完成，共 " + cache.size() + " 条");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "用户数据文件读取失败: " + filePath, e);
        }
    }

    /**
     * 解析一行用户记录。
     *
     * <p>调用方已过滤空行与注释行，此处只负责字段切分与合法性判断。</p>
     *
     * @param line 制表符分隔的文本行
     * @return 用户对象；字段不足或格式非法时返回 null
     */
    private User parseLine(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.split(Constants.FIELD_SEPARATOR, -1);
        if (parts.length < 5) {
            return null;
        }
        User user = new User();
        user.setUsername(parts[0]);
        user.setNickname(parts[1]);
        user.setSalt(parts[2]);
        user.setPasswordHash(parts[3]);
        user.setRole(parts[4]);
        if (parts.length > 5) {
            user.setCreateTime(DateUtil.parse(parts[5]));
        }
        if (parts.length > 6) {
            user.setLastLoginTime(DateUtil.parse(parts[6]));
        }
        return user;
    }

    /**
     * 把用户对象序列化为一行文本。
     *
     * @param user 用户对象
     * @return 制表符分隔的记录行
     */
    private String toLine(User user) {
        return String.join(Constants.FIELD_SEPARATOR,
                nullToEmpty(user.getUsername()),
                nullToEmpty(user.getNickname()),
                nullToEmpty(user.getSalt()),
                nullToEmpty(user.getPasswordHash()),
                nullToEmpty(user.getRole()),
                DateUtil.format(user.getCreateTime()),
                DateUtil.format(user.getLastLoginTime()));
    }

    /**
     * 把可能为 null 的字段转为空字符串，保证字段数量稳定。
     *
     * @param value 原始值
     * @return 非 null 字符串
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value.replace(Constants.FIELD_SEPARATOR, " ");
    }

    /**
     * 把内存缓存整体刷写到磁盘。
     *
     * <p>写入前先确保目录存在；使用临时文件 + 原子替换，避免写到一半崩溃导致用户表损坏。</p>
     *
     * @throws ChatException 写入失败时抛出
     */
    private void flush() throws ChatException {
        try {
            FileUtil.ensureDir(new File(filePath).getParent());
            File target = new File(filePath);
            File temp = new File(filePath + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp.toPath(), Constants.CHARSET,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writer.write("# 用户数据文件：用户名<TAB>昵称<TAB>盐<TAB>散列<TAB>角色<TAB>注册时间<TAB>最近登录");
                writer.newLine();
                List<User> users = new ArrayList<>(cache.values());
                users.sort(Comparator.comparing(User::getUsername));
                for (User user : users) {
                    writer.write(toLine(user));
                    writer.newLine();
                }
            }
            // 原子替换：先写临时文件再移动，保证任何时刻磁盘上都是一份完整数据
            Files.move(temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ChatException("用户数据写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 新增用户。
     *
     * @param entity 用户对象
     * @return 新增成功返回 true；用户名已存在返回 false
     * @throws ChatException 持久化失败时抛出
     */
    @Override
    public boolean save(User entity) throws ChatException {
        if (entity == null || entity.getUsername() == null) {
            throw new ChatException("用户对象或用户名为空，无法保存");
        }
        synchronized (writeLock) {
            if (cache.containsKey(entity.getUsername())) {
                return false;
            }
            cache.put(entity.getUsername(), entity);
            try {
                flush();
                return true;
            } catch (ChatException e) {
                cache.remove(entity.getUsername());
                throw e;
            }
        }
    }

    /**
     * 更新用户。
     *
     * @param entity 用户对象
     * @return 更新成功返回 true；用户不存在返回 false
     * @throws ChatException 持久化失败时抛出
     */
    @Override
    public boolean update(User entity) throws ChatException {
        if (entity == null || entity.getUsername() == null) {
            throw new ChatException("用户对象或用户名为空，无法更新");
        }
        synchronized (writeLock) {
            if (!cache.containsKey(entity.getUsername())) {
                return false;
            }
            User previous = cache.put(entity.getUsername(), entity);
            try {
                flush();
                return true;
            } catch (ChatException e) {
                if (previous != null) {
                    cache.put(previous.getUsername(), previous);
                }
                throw e;
            }
        }
    }

    /**
     * 按用户名删除用户。
     *
     * @param id 用户名
     * @return 删除成功返回 true
     * @throws UserNotFoundException 用户不存在时抛出
     * @throws ChatException         持久化失败时抛出
     */
    @Override
    public boolean deleteById(String id) throws UserNotFoundException, ChatException {
        return deleteByUsername(id);
    }

    /**
     * 按用户名删除用户。
     *
     * @param username 用户名
     * @return 删除成功返回 true
     * @throws UserNotFoundException 用户不存在时抛出
     * @throws ChatException         持久化失败时抛出
     */
    @Override
    public boolean deleteByUsername(String username) throws UserNotFoundException, ChatException {
        synchronized (writeLock) {
            User removed = cache.remove(username);
            if (removed == null) {
                throw new UserNotFoundException("用户不存在: " + username);
            }
            try {
                flush();
                return true;
            } catch (ChatException e) {
                cache.put(removed.getUsername(), removed);
                throw e;
            }
        }
    }

    /**
     * 按用户名查询用户。
     *
     * @param id 用户名
     * @return 用户对象；不存在时返回 null
     */
    @Override
    public User findById(String id) {
        return id == null ? null : cache.get(id);
    }

    /**
     * 按用户名查询用户。
     *
     * @param username 用户名
     * @return 用户对象；不存在时返回 null
     */
    @Override
    public User findByUsername(String username) {
        return findById(username);
    }

    /**
     * 判断用户名是否存在。
     *
     * @param username 用户名
     * @return 存在返回 true
     */
    @Override
    public boolean exists(String username) {
        return username != null && cache.containsKey(username);
    }

    /**
     * 保存或更新用户。
     *
     * @param user 用户对象
     * @return 写入成功返回 true
     * @throws ChatException 持久化失败时抛出
     */
    @Override
    public boolean saveOrUpdate(User user) throws ChatException {
        if (user == null || user.getUsername() == null) {
            throw new ChatException("用户对象或用户名为空，无法保存");
        }
        synchronized (writeLock) {
            boolean isNew = !cache.containsKey(user.getUsername());
            User previous = cache.put(user.getUsername(), user);
            try {
                flush();
                return isNew || previous != null;
            } catch (ChatException e) {
                if (previous != null) {
                    cache.put(previous.getUsername(), previous);
                } else {
                    cache.remove(user.getUsername());
                }
                throw e;
            }
        }
    }

    /**
     * 查询全部用户。
     *
     * @return 用户列表（按用户名排序），永不返回 null
     */
    @Override
    public List<User> findAll() {
        List<User> users = new ArrayList<>(cache.values());
        users.sort(Comparator.comparing(User::getUsername));
        return users;
    }

    /**
     * 统计用户总数。
     *
     * @return 用户数量
     */
    @Override
    public long count() {
        return cache.size();
    }

    /**
     * 获取底层数据文件路径，供测试与排查使用。
     *
     * @return 数据文件路径
     */
    public String getFilePath() {
        return filePath;
    }
}

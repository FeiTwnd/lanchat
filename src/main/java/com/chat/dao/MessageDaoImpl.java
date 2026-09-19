package com.chat.dao;

import com.chat.common.ChatMessageFactory;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;
import com.chat.exception.ChatException;
import com.chat.util.DateUtil;
import com.chat.util.FileUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 聊天消息数据访问的文件实现。
 *
 * <p>职责：把聊天消息按天写入 {@code data/history/yyyy-MM-dd.log}，
 * 每行一条记录，字段为：消息编号、时间、类型、发送者、接收者、内容。</p>
 *
 * <p>为什么按天分文件：单文件无限增长会导致检索变慢、导出困难；
 * 按天切分后，按时间范围查询只需读取命中的几天文件，同时便于人工查看与清理。</p>
 *
 * <p>文件消息的编码：内容字段存放 {@code 文件名|大小|校验和}，
 * 避免为文件消息单独设计一种记录格式，读取时按字段数即可还原类型。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class MessageDaoImpl implements MessageDao {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".MessageDao");

    /** 历史记录目录 */
    private final String historyDir;

    /** 写锁：保证多线程追加消息时不会互相截断 */
    private final Object writeLock = new Object();

    /** 文件消息内容字段的内部连接符 */
    private static final String FILE_FIELD_SEPARATOR = "|";

    /** 历史记录文件扩展名 */
    private static final String LOG_SUFFIX = ".log";

    /**
     * 使用默认目录构造 DAO。
     */
    public MessageDaoImpl() {
        this(com.chat.common.Config.historyDir());
    }

    /**
     * 使用指定目录构造 DAO，便于测试使用临时目录。
     *
     * @param historyDir 历史记录目录
     */
    public MessageDaoImpl(String historyDir) {
        this.historyDir = historyDir;
        ensureDirQuietly();
    }

    /**
     * 静默创建历史目录，失败时仅记录日志（首次写入时还会再尝试一次）。
     */
    private void ensureDirQuietly() {
        try {
            FileUtil.ensureDir(historyDir);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "历史记录目录创建失败: " + historyDir, e);
        }
    }

    /**
     * 获取某一天对应的历史文件。
     *
     * @param dateTime 时间点
     * @return 历史文件对象
     */
    private File fileOf(LocalDateTime dateTime) {
        return new File(historyDir, DateUtil.dayKey(dateTime) + LOG_SUFFIX);
    }

    /**
     * 列出目录下全部历史文件，按文件名（即日期）升序。
     *
     * @return 历史文件数组，目录不存在时返回空数组
     */
    private File[] listLogFiles() {
        File dir = new File(historyDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(LOG_SUFFIX));
        if (files == null) {
            return new File[0];
        }
        java.util.Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    /**
     * 追加一条消息到历史记录。
     *
     * @param message 待保存消息
     * @return 保存成功返回 true
     * @throws ChatException 写入失败时抛出
     */
    @Override
    public boolean append(Message message) throws ChatException {
        if (message == null) {
            throw new ChatException("消息为 null，无法保存历史记录");
        }
        String line = toLine(message);
        File target = fileOf(message.getTimestamp());
        synchronized (writeLock) {
            try {
                FileUtil.ensureDir(historyDir);
                try (BufferedWriter writer = Files.newBufferedWriter(target.toPath(), Constants.CHARSET,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                    writer.write(line);
                    writer.newLine();
                }
                return true;
            } catch (IOException e) {
                throw new ChatException("聊天记录写入失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 把消息编码为一行文本。
     *
     * @param message 消息对象
     * @return 制表符分隔的记录行
     */
    private String toLine(Message message) {
        String content;
        if (message instanceof FileMessage) {
            FileMessage fileMessage = (FileMessage) message;
            content = nullToEmpty(fileMessage.getFileName()) + FILE_FIELD_SEPARATOR
                    + fileMessage.getFileSize() + FILE_FIELD_SEPARATOR
                    + nullToEmpty(fileMessage.getSha256());
        } else if (message instanceof TextMessage) {
            content = ((TextMessage) message).getContent();
        } else if (message instanceof SystemMessage) {
            content = ((SystemMessage) message).getContent();
        } else {
            content = message.getSummary();
        }
        return String.join(Constants.FIELD_SEPARATOR,
                String.valueOf(message.getId()),
                DateUtil.format(message.getTimestamp()),
                message.getType().name(),
                nullToEmpty(message.getSender()),
                nullToEmpty(message.getReceiver()),
                content.replace(Constants.FIELD_SEPARATOR, " ").replace("\n", "\\n"));
    }

    /**
     * 解析一行历史记录。
     *
     * @param line 文本行
     * @return 消息对象；格式非法时返回 null
     */
    private Message parseLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String[] parts = line.split(Constants.FIELD_SEPARATOR, -1);
        if (parts.length < 6) {
            return null;
        }
        MessageType type = MessageType.fromName(parts[2]);
        String sender = parts[3];
        String receiver = parts[4];
        String content = parts[5].replace("\\n", "\n");
        Message message;
        if (type == MessageType.FILE_REQUEST || type == MessageType.FILE_RESULT) {
            FileMessage fileMessage = ChatMessageFactory.file(sender, receiver, type);
            String[] fileParts = content.split("\\" + FILE_FIELD_SEPARATOR, -1);
            fileMessage.setFileName(fileParts.length > 0 ? fileParts[0] : "");
            if (fileParts.length > 1) {
                try {
                    fileMessage.setFileSize(Long.parseLong(fileParts[1]));
                } catch (NumberFormatException e) {
                    fileMessage.setFileSize(0);
                }
            }
            if (fileParts.length > 2) {
                fileMessage.setSha256(fileParts[2]);
            }
            message = fileMessage;
        } else if (type == MessageType.SYSTEM || type == MessageType.ERROR) {
            message = ChatMessageFactory.system(receiver, content);
            message.setType(type);
        } else {
            message = ChatMessageFactory.text(sender, receiver, content, MessageType.TEXT_PRIVATE);
            message.setType(type);
        }
        try {
            message.setId(Long.parseLong(parts[0]));
        } catch (NumberFormatException e) {
            message.setId(0L);
        }
        message.setTimestamp(DateUtil.parse(parts[1]));
        return message;
    }

    /**
     * 把 null 转为空串，保证字段数量稳定。
     *
     * @param value 原始值
     * @return 非 null 字符串
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 读取单个历史文件。
     *
     * @param file 历史文件
     * @return 消息列表，永不返回 null
     */
    private List<Message> readFile(File file) {
        List<Message> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), Constants.CHARSET)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Message message = parseLine(line);
                if (message != null) {
                    messages.add(message);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "历史文件读取失败: " + file.getAbsolutePath(), e);
        }
        return messages;
    }

    /**
     * 按用户与时间范围检索消息。
     *
     * @param username 用户名，null 表示不限制
     * @param from     起始时间，null 表示不限制
     * @param to       结束时间，null 表示不限制
     * @return 时间升序的消息列表
     */
    @Override
    public List<Message> query(String username, LocalDateTime from, LocalDateTime to) {
        List<Message> result = new ArrayList<>();
        for (File file : listLogFiles()) {
            for (Message message : readFile(file)) {
                if (matchUser(message, username) && matchTime(message, from, to)) {
                    result.add(message);
                }
            }
        }
        result.sort(Comparator.comparing(Message::getTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    /**
     * 在全部消息中按关键字检索（忽略大小写）。
     *
     * @param keyword 关键字
     * @return 命中的消息列表
     * @throws ChatException 关键字为空时抛出
     */
    @Override
    public List<Message> search(String keyword) throws ChatException {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new ChatException("检索关键字不能为空");
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        List<Message> result = new ArrayList<>();
        for (File file : listLogFiles()) {
            for (Message message : readFile(file)) {
                String text = (message.getSummary() + " " + message.getSender() + " " + message.getReceiver())
                        .toLowerCase(Locale.ROOT);
                if (text.contains(lower)) {
                    result.add(message);
                }
            }
        }
        result.sort(Comparator.comparing(Message::getTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    /**
     * 判断消息是否与指定用户相关。
     *
     * @param message  消息
     * @param username 用户名，null 表示不限制
     * @return 相关返回 true
     */
    private boolean matchUser(Message message, String username) {
        if (username == null || username.isEmpty()) {
            return true;
        }
        return username.equals(message.getSender()) || username.equals(message.getReceiver())
                || message.isBroadcast();
    }

    /**
     * 判断消息时间是否落在指定区间内。
     *
     * @param message 消息
     * @param from    起始时间，null 表示不限制
     * @param to      结束时间，null 表示不限制
     * @return 在区间内返回 true
     */
    private boolean matchTime(Message message, LocalDateTime from, LocalDateTime to) {
        LocalDateTime time = message.getTimestamp();
        if (time == null) {
            return false;
        }
        if (from != null && time.isBefore(from)) {
            return false;
        }
        return to == null || !time.isAfter(to);
    }

    /**
     * 把消息列表导出为文本文件。
     *
     * @param messages 待导出消息
     * @param target   目标文件
     * @return 实际写入的消息条数
     * @throws IOException   读写失败时抛出
     * @throws ChatException 参数非法时抛出
     */
    @Override
    public int exportTo(List<Message> messages, File target) throws IOException, ChatException {
        if (target == null) {
            throw new ChatException("导出目标文件不能为空");
        }
        if (messages == null) {
            throw new ChatException("导出内容不能为空");
        }
        FileUtil.ensureDir(target.getParent());
        int count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(target.toPath(), Constants.CHARSET,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.write("聊天记录导出文件，生成时间: " + DateUtil.now());
            writer.newLine();
            writer.write("共 " + messages.size() + " 条记录");
            writer.newLine();
            writer.write("========================================");
            writer.newLine();
            for (Message message : messages) {
                writer.write("[" + DateUtil.format(message.getTimestamp()) + "] "
                        + message.getType().getDescription() + " "
                        + message.getSender() + " -> "
                        + (message.isBroadcast() ? Constants.BROADCAST_TAG : message.getReceiver())
                        + " : " + message.getSummary());
                writer.newLine();
                count++;
            }
        }
        return count;
    }

    /**
     * 新增消息（BaseDao 语义：等价于追加）。
     *
     * @param entity 消息对象
     * @return 保存成功返回 true
     * @throws ChatException 写入失败时抛出
     */
    @Override
    public boolean save(Message entity) throws ChatException {
        return append(entity);
    }

    /**
     * 更新消息。
     *
     * <p>历史记录按追加日志语义设计，不支持原地修改，因此统一返回 false，
     * 由调用方改用“追加新记录”的方式表达变更。</p>
     *
     * @param entity 消息对象
     * @return 恒定返回 false
     */
    @Override
    public boolean update(Message entity) {
        LOGGER.fine("历史记录为追加日志，不支持原地更新");
        return false;
    }

    /**
     * 按消息编号删除记录。
     *
     * @param id 消息编号
     * @return 至少删除一条时返回 true
     * @throws ChatException 写入失败时抛出
     */
    @Override
    public boolean deleteById(Long id) throws ChatException {
        if (id == null) {
            return false;
        }
        boolean removed = false;
        synchronized (writeLock) {
            for (File file : listLogFiles()) {
                List<String> kept = new ArrayList<>();
                boolean hit = false;
                try (BufferedReader reader = Files.newBufferedReader(file.toPath(), Constants.CHARSET)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split(Constants.FIELD_SEPARATOR, -1);
                        if (parts.length > 0 && String.valueOf(id).equals(parts[0])) {
                            hit = true;
                            continue;
                        }
                        kept.add(line);
                    }
                } catch (IOException e) {
                    throw new ChatException("历史记录读取失败: " + e.getMessage(), e);
                }
                if (hit) {
                    writeLines(file, kept);
                    removed = true;
                }
            }
        }
        return removed;
    }

    /**
     * 用给定文本行覆盖写回历史文件。
     *
     * @param file  目标文件
     * @param lines 待写入的行
     * @throws ChatException 写入失败时抛出
     */
    private void writeLines(File file, List<String> lines) throws ChatException {
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), Constants.CHARSET,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new ChatException("历史记录重写失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按编号查询消息。
     *
     * @param id 消息编号
     * @return 消息对象；不存在时返回 null
     */
    @Override
    public Message findById(Long id) {
        if (id == null) {
            return null;
        }
        for (File file : listLogFiles()) {
            for (Message message : readFile(file)) {
                if (message.getId() == id) {
                    return message;
                }
            }
        }
        return null;
    }

    /**
     * 查询全部历史消息。
     *
     * @return 时间升序的消息列表
     */
    @Override
    public List<Message> findAll() {
        return query(null, null, null);
    }

    /**
     * 统计历史消息总数。
     *
     * @return 消息条数
     */
    @Override
    public long count() {
        long total = 0;
        for (File file : listLogFiles()) {
            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), Constants.CHARSET)) {
                while (reader.readLine() != null) {
                    total++;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "历史文件统计失败: " + file.getAbsolutePath(), e);
            }
        }
        return total;
    }

    /**
     * 获取历史记录目录，供测试与排查使用。
     *
     * @return 历史目录路径
     */
    public String getHistoryDir() {
        return historyDir;
    }
}

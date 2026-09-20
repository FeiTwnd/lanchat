package com.chat.util;

import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.Message;
import com.chat.common.SystemMessage;
import com.chat.common.TextMessage;
import com.chat.exception.ChatException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * 聊天记录导出文本的渲染与落盘。
 *
 * <p>职责：把消息列表渲染为"一行一条记录"的纯文本，并写成文件。之所以放在公共包，
 * 是因为导出链路横跨两台机器——服务端负责查数据库并渲染，客户端只负责把渲染好的文本
 * 存到自己的磁盘上。格式只在这里实现一份，两端的导出结果必然一致。</p>
 *
 * <p>设计要点：行分隔符固定使用 {@code \n}。导出文本可能经网络从服务端传到客户端，
 * 用平台相关的 {@code lineSeparator} 会让"切分再拼接"多出一层不可见差异，固定字符最省心。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class MessageExporter {

    /** 导出文本的表头分隔线 */
    private static final String SEPARATOR = "========================================";

    /**
     * 工具类禁止实例化。
     */
    private MessageExporter() {
    }

    /**
     * 取一条消息用于展示或导出的单行正文。
     *
     * <p>刻意不用 {@link Message#getSummary()}：文本消息的摘要会截断到 30 个字符，
     * 用它渲染历史记录与导出文件都会丢掉后半段内容。换行会破坏"一行一条记录"的格式，
     * 因此统一压成空格。</p>
     *
     * @param message 消息
     * @return 单行正文，永不为 null
     */
    public static String contentOf(Message message) {
        String text;
        if (message instanceof TextMessage) {
            text = ((TextMessage) message).getContent();
        } else if (message instanceof SystemMessage) {
            text = ((SystemMessage) message).getContent();
        } else if (message instanceof FileMessage) {
            text = fileText((FileMessage) message);
        } else {
            text = message.getSummary();
        }
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * 拼接文件消息的展示文本：文件名与大小之后补上传输结果描述。
     *
     * <p>{@link FileMessage#getSummary()} 只有“文件名 (大小)”，
     * 不带结果的话，历史记录里被拒绝或传输失败的文件会与成功传输的看起来一模一样。</p>
     *
     * @param file 文件消息
     * @return 形如 {@code 报告.pdf (1.20 MB) - 文件传输完成} 的文本
     */
    private static String fileText(FileMessage file) {
        String summary = file.getSummary();
        String detail = file.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            return summary;
        }
        return summary + " - " + detail.trim();
    }

    /**
     * 把消息列表渲染为完整的导出文本。
     *
     * @param messages 待导出消息
     * @return 导出文本，每行以 {@code \n} 结尾
     */
    public static String render(List<Message> messages) {
        return render(messages, null);
    }

    /**
     * 把消息列表渲染为完整的导出文本，并标注数据来源。
     *
     * <p>标注来源是为了自证清白：客户端只负责把服务端回传的文本落盘，
     * 出现"导出内容不对"时，文件里写着是哪台服务器给的数据，一眼就能看出是不是连错了服务器
     * （本项目确实出现过"客户端连着自己的服务端、导出的是本机数据"的排查事故）。</p>
     *
     * @param messages 待导出消息
     * @param source   数据来源描述（形如 {@code 192.168.1.5:9527}），为空时不写该行
     * @return 导出文本，每行以 {@code \n} 结尾
     */
    public static String render(List<Message> messages, String source) {
        StringBuilder builder = new StringBuilder();
        builder.append("聊天记录导出文件，生成时间: ").append(DateUtil.now()).append('\n');
        builder.append("共 ").append(messages.size()).append(" 条记录").append('\n');
        if (source != null && !source.trim().isEmpty()) {
            builder.append("数据来源: 服务器 ").append(source.trim()).append('\n');
        }
        builder.append(SEPARATOR).append('\n');
        for (Message message : messages) {
            builder.append('[').append(DateUtil.format(message.getTimestamp())).append("] ")
                    .append(message.getType().getDescription()).append(' ')
                    .append(message.getSender()).append(" -> ")
                    .append(message.isBroadcast() ? Constants.BROADCAST_TAG : message.getReceiver())
                    .append(" : ").append(contentOf(message)).append('\n');
        }
        return builder.toString();
    }

    /**
     * 把消息列表导出为文本文件。
     *
     * @param messages 待导出消息
     * @param target   目标文件
     * @return 实际写入的消息条数
     * @throws IOException   写入失败时抛出
     * @throws ChatException 参数非法时抛出
     */
    public static int write(List<Message> messages, File target) throws IOException, ChatException {
        if (target == null) {
            throw new ChatException("导出目标文件不能为空");
        }
        if (messages == null) {
            throw new ChatException("导出内容不能为空");
        }
        writeText(render(messages), target);
        return messages.size();
    }

    /**
     * 把已渲染好的导出文本写到目标文件。
     *
     * @param content 导出文本
     * @param target  目标文件
     * @throws IOException 写入失败时抛出
     */
    public static void writeText(String content, File target) throws IOException {
        FileUtil.ensureDir(target.getParent());
        Files.writeString(target.toPath(), content == null ? "" : content, Constants.CHARSET);
    }
}

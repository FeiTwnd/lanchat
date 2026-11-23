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
 * 把聊天记录渲染为纯文本并写入文件。
 */
public final class MessageExporter {
    /** 导出文本中位于表头与记录之间的分隔行。 */
    private static final String SEPARATOR = "========================================";

    private MessageExporter() {
    }

    /**
     * 提取消息的单行正文。
     *
     * @param message 消息对象
     * @return 去掉换行后的正文文本
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

    private static String fileText(FileMessage file) {
        String summary = file.getSummary();
        String detail = file.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            return summary;
        }
        return summary + " - " + detail.trim();
    }

    /**
     * 把消息列表渲染为导出文本。
     *
     * @param messages 消息列表
     * @return 导出文本
     */
    public static String render(List<Message> messages) {
        return render(messages, null);
    }

    /**
     * 把消息列表渲染为带数据来源的导出文本。
     *
     * @param messages 消息列表
     * @param source 数据来源说明，为 null 时不写入
     * @return 导出文本
     */
    public static String render(List<Message> messages, String source) {
        StringBuilder builder = new StringBuilder();
        builder.append("聊天记录导出文件，生成时间: ").append(DateUtil.now()).append('\n');
        builder.append("共 ").append(messages.size()).append(" 条记录").append('\n');
        if (source != null && !source.trim().isEmpty()) {
            builder.append("数据来源: 服务器 ").append(source.trim()).append('\n');
        }
        builder.append(SEPARATOR).append('\n');
        // 逐条输出时间、类型、收发方与正文
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
     * 把消息写入目标文件。
     *
     * @param messages 消息列表
     * @param target 目标文件
     * @return 写入的记录条数
     * @throws IOException 文件写入失败时抛出
     * @throws ChatException 消息列表或目标文件为空时抛出
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
     * 把文本写入文件，父目录不存在时自动创建。
     *
     * @param content 文本内容
     * @param target 目标文件
     * @throws IOException 文件写入失败时抛出
     */
    public static void writeText(String content, File target) throws IOException {
        FileUtil.ensureDir(target.getParent());
        Files.writeString(target.toPath(), content == null ? "" : content, Constants.CHARSET);
    }
}

package com.chat.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 在线用户列表编解码器。
 *
 * <p>职责：把在线用户列表编码为单条消息正文，以及从正文还原用户列表。
 * 编码格式为每行一个用户、字段以竖线分隔：</p>
 *
 * <pre>
 * 用户名|昵称|角色
 * </pre>
 *
 * <p>为什么用纯文本而不是把 {@code List<User>} 塞进消息对象：</p>
 * <ul>
 *   <li>协议只需增加一种消息类型即可承载列表，不必为“带列表的消息”新增类层次；</li>
 *   <li>文本格式便于在日志中直接阅读，出问题时肉眼即可定位；</li>
 *   <li>用户资料变更（例如将来增加头像字段）只需在此处扩展一列，兼容旧客户端（多出的列被忽略）。</li>
 * </ul>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class UserListCodec {

    /** 字段分隔符 */
    private static final char FIELD_SEPARATOR = '|';

    /** 私有构造，禁止实例化工具类 */
    private UserListCodec() {
    }

    /**
     * 编码在线用户列表。
     *
     * @param users 用户集合，可为 null
     * @return 编码后的多行文本；空列表返回空字符串
     */
    public static String encode(List<User> users) {
        if (users == null || users.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(safe(user.getUsername())).append(FIELD_SEPARATOR)
                    .append(safe(user.getNickname())).append(FIELD_SEPARATOR)
                    .append(safe(user.getRole()));
        }
        return builder.toString();
    }

    /**
     * 解码在线用户列表。
     *
     * <p>容错策略：格式非法的行直接跳过而不是抛异常——
     * 用户列表属于展示型数据，宁可少显示一行也不能让客户端解析崩溃。</p>
     *
     * @param text 编码文本，可为 null 或空
     * @return 用户列表，永不返回 null
     */
    public static List<User> decode(String text) {
        List<User> users = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return users;
        }
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\" + FIELD_SEPARATOR, -1);
            if (parts.length < 1 || parts[0].isEmpty()) {
                continue;
            }
            User user = new User(parts[0], parts.length > 1 && !parts[1].isEmpty() ? parts[1] : parts[0]);
            user.setRole(parts.length > 2 && !parts[2].isEmpty() ? parts[2] : Constants.ROLE_USER);
            user.setOnline(true);
            users.add(user);
        }
        return users;
    }

    /**
     * 把字段中的分隔符替换掉，防止昵称里的竖线破坏格式。
     *
     * @param value 原始字段
     * @return 安全字段
     */
    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(FIELD_SEPARATOR, ' ').replace('\n', ' ');
    }
}

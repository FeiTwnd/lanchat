package com.chat.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 在线用户列表文本的编解码工具，每个用户占一行，字段用竖线分隔。
 */
public final class UserListCodec {
    /** 字段分隔符 */
    private static final char FIELD_SEPARATOR = '|';

    private UserListCodec() {
    }

    /**
     * 把用户列表编码为多行文本。
     *
     * @param users 用户列表
     * @return 编码结果，空列表返回空串
     */
    public static String encode(List<User> users) {
        if (users == null || users.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        // 每个用户占一行，行内字段用竖线分隔
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(safe(user.getUsername())).append(FIELD_SEPARATOR)
                    .append(safe(user.getNickname())).append(FIELD_SEPARATOR)
                    .append(safe(user.getRole())).append(FIELD_SEPARATOR)
                    .append(safe(user.getAvatar()));
        }
        return builder.toString();
    }

    /**
     * 解析在线用户列表文本。
     *
     * @param text 用户列表文本
     * @return 解析出的用户列表，空文本返回空列表
     */
    public static List<User> decode(String text) {
        List<User> users = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return users;
        }
        // 逐行解析用户
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

            user.setAvatar(parts.length > 3 ? parts[3] : null);
            user.setOnline(true);
            users.add(user);
        }
        return users;
    }

    /** 把字段中的分隔符与换行替换为空格。 */
    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(FIELD_SEPARATOR, ' ').replace('\n', ' ');
    }
}

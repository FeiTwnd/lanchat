package com.chat.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 编解码工具，提供字符串转义、文本解析与取值方法。
 */
public final class JsonCodec {
    /** 容器允许的最大嵌套层数 */
    private static final int MAX_NESTING_DEPTH = 64;

    private JsonCodec() {
    }

    /**
     * 把字符串转义为 JSON 字符串字面量。
     *
     * @param value 原始字符串
     * @return 转义结果，value 为 null 时返回 null 字面量
     */
    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
            }
        }
        return builder.append('"').toString();
    }

    /**
     * 解析 JSON 文本。
     *
     * @param json JSON 文本
     * @return 解析出的 Java 值
     * @throws IllegalArgumentException 文本格式不合法时
     */
    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON 文本为 null");
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("JSON 文本在第 " + parser.position() + " 个字符之后还有多余内容");
        }
        return value;
    }

    /**
     * 把 JSON 值转换为对象映射。
     *
     * @param value JSON 值
     * @return 对象映射
     * @throws IllegalArgumentException 值不是对象时
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        throw new IllegalArgumentException("JSON 值不是对象: " + describe(value));
    }

    /**
     * 把 JSON 值转换为列表。
     *
     * @param value JSON 值
     * @return 列表
     * @throws IllegalArgumentException 值不是数组时
     */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        throw new IllegalArgumentException("JSON 值不是数组: " + describe(value));
    }

    /**
     * 读取对象中的字符串成员。
     *
     * @param object 对象映射
     * @param key 成员名
     * @return 字符串，成员缺失或不是字符串时返回 null
     */
    public static String asString(Map<String, Object> object, String key) {
        if (object == null || key == null) {
            return null;
        }
        Object value = object.get(key);
        return value instanceof String ? (String) value : null;
    }

    /**
     * 读取对象中的数值成员。
     *
     * @param object 对象映射
     * @param key 成员名
     * @param defaultValue 默认值
     * @return 数值，成员缺失或不是数字时返回默认值
     */
    public static long asLong(Map<String, Object> object, String key, long defaultValue) {
        if (object == null || key == null) {
            return defaultValue;
        }
        Object value = object.get(key);
        return value instanceof Number ? ((Number) value).longValue() : defaultValue;
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /** JSON 文本的递归下降解析器。 */
    private static final class Parser {
        private final String text;

        /** 当前读取位置 */
        private int pos;

        /** 当前容器嵌套层数 */
        private int depth;

        Parser(String text) {
            this.text = text;
        }

        int position() {
            return pos;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw error("内容意外结束");
            }
            char c = text.charAt(pos);
            // 按首字符分派到对应的解析方法
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            expect('{');
            enterContainer();
            try {
                Map<String, Object> result = new LinkedHashMap<>();
                skipWhitespace();
                if (peek() == '}') {
                    pos++;
                    return result;
                }
                // 读取成员直到右花括号
                while (true) {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    expect(':');
                    result.put(key, parseValue());
                    skipWhitespace();
                    char c = next();
                    if (c == '}') {
                        return result;
                    }
                    if (c != ',') {
                        throw error("对象的成员之间应为逗号，实际读到 '" + c + "'");
                    }
                }
            } finally {
                depth--;
            }
        }

        private List<Object> parseArray() {
            expect('[');
            enterContainer();
            try {
                List<Object> result = new ArrayList<>();
                skipWhitespace();
                if (peek() == ']') {
                    pos++;
                    return result;
                }
                // 逐个读取数组元素
                while (true) {
                    result.add(parseValue());
                    skipWhitespace();
                    char c = next();
                    if (c == ']') {
                        return result;
                    }
                    if (c != ',') {
                        throw error("数组的元素之间应为逗号，实际读到 '" + c + "'");
                    }
                }
            } finally {
                depth--;
            }
        }

        private void enterContainer() {
            depth++;
            if (depth > MAX_NESTING_DEPTH) {
                throw error("嵌套层数超过上限 " + MAX_NESTING_DEPTH);
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("字符串没有闭合的双引号");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c != '\\') {
                    // 拒绝未转义的控制字符
                    if (c < 0x20) {
                        pos--;
                        throw error("字符串内出现未转义的控制字符 U+"
                                + String.format("%04X", (int) c));
                    }
                    builder.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("转义符后内容缺失");
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case '"':
                        builder.append('"');
                        break;
                    case '\\':
                        builder.append('\\');
                        break;
                    case '/':
                        builder.append('/');
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    case 'u':
                        builder.append((char) readUnicodeEscape());
                        break;
                    default:
                        throw error("无法识别的转义符: \\" + escaped);
                }
            }
        }

        private int readUnicodeEscape() {
            if (pos + 4 > text.length()) {
                throw error("\\u 转义不足 4 位");
            }
            for (int i = 0; i < 4; i++) {
                if (!isHexDigit(text.charAt(pos + i))) {
                    throw error("\\u 转义必须紧跟 4 位十六进制字符: "
                            + text.substring(pos, Math.min(text.length(), pos + 4)));
                }
            }
            int code = Integer.parseInt(text.substring(pos, pos + 4), 16);
            pos += 4;
            return code;
        }

        private static boolean isHexDigit(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private Double parseNumber() {
            // 按 JSON 语法校验整数、小数与指数部分
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            char first = peek();
            if (first == '0') {
                pos++;
                if (isDigit(peek())) {
                    throw error("JSON 数值不允许多余的前导零");
                }
            } else if (first >= '1' && first <= '9') {
                while (isDigit(peek())) {
                    pos++;
                }
            } else {
                throw error("不是合法的 JSON 数值: 整数部分应以 0 或非零数字开头");
            }
            if (peek() == '.') {
                pos++;
                if (!isDigit(peek())) {
                    throw error("JSON 数值的小数点后缺少数字");
                }
                while (isDigit(peek())) {
                    pos++;
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                if (!isDigit(peek())) {
                    throw error("JSON 数值的指数部分缺少数字");
                }
                while (isDigit(peek())) {
                    pos++;
                }
            }
            String literal = text.substring(start, pos);
            double value = Double.parseDouble(literal);
            if (!Double.isFinite(value)) {
                throw error("JSON 数值超出可表示范围: " + literal);
            }
            return value;
        }

        private char peek() {
            return atEnd() ? '\0' : text.charAt(pos);
        }

        private char next() {
            if (atEnd()) {
                throw error("内容意外结束");
            }
            return text.charAt(pos++);
        }

        private void expect(char expected) {
            char actual = next();
            if (actual != expected) {
                throw error("应为 '" + expected + "'，实际读到 '" + actual + "'");
            }
        }

        private void expectLiteral(String literal) {
            if (pos + literal.length() > text.length()
                    || !text.startsWith(literal, pos)) {
                throw error("应为 " + literal);
            }
            pos += literal.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON 解析失败（第 " + pos + " 个字符）: " + message);
        }
    }
}

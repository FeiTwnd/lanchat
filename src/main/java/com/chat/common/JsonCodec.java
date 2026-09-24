package com.chat.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 编解码器。
 *
 * <p>职责：为"调用外部 HTTP 接口"这一场景提供最小的 JSON 能力——把字符串转义成
 * JSON 字面量（拼请求体），以及把接口返回的 JSON 文本解析成 {@link Map} /
 * {@link List} / {@link String} / {@link Double} / {@link Boolean} / {@code null}
 * 组成的对象树（取回答正文）。</p>
 *
 * <p><b>为什么不引第三方库</b>：README 第 4 行写明"纯标准库实现，不依赖任何第三方框架"，
 * 唯一的外部 jar 是 MySQL 驱动。为了一处 HTTP 调用引入 Gson 或 Jackson 会破坏这条约束，
 * 因此这里手写一个够用的实现。</p>
 *
 * <p><b>与"够用"的边界</b>：本类只实现 JSON 规范本身，不做任何业务解释。
 * 解析器覆盖全部六种值类型、转义序列（含 {@code \\uXXXX}）、嵌套对象与数组、
 * 数值的科学计数法，并对畸形输入给出带位置的错误信息而不是抛
 * {@code NullPointerException} 或静默返回半截数据。</p>
 *
 * <p><b>解析结果的具体类型</b>：对象一律是 {@link LinkedHashMap}（保持键序，便于排查），
 * 数组一律是 {@link ArrayList}，字符串是 {@link String}，布尔是 {@link Boolean}，
 * 数值是 {@link Double}，JSON 的 {@code null} 就是 Java 的 {@code null}。
 * 之所以不做整数与浮点的区分：JSON 本身只有一个 number 类型，
 * 强行区分只会引入"该按哪个精度取"的新问题，调用方需要整数时用
 * {@link #asLong} 显式声明即可。</p>
 *
 * <p>线程安全：本类无状态，静态方法可并发调用。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class JsonCodec {

    /** 私有构造，禁止实例化工具类 */
    private JsonCodec() {
    }

    /**
     * 把字符串转义为 JSON 字符串字面量（含两侧双引号）。
     *
     * <p>转义规则：双引号、反斜杠与 {@code \b \f \n \r \t} 使用 JSON 规定的短转义；
     * 其余 {@code 0x20} 以下的控制字符写成 {@code \\u00XX}；中文等非 ASCII 字符原样保留——
     * 请求体按 UTF-8 编码发送，直接写中文是合法且体积最小的做法，
     * 强行转成 {@code \\uXXXX} 只会把中文提问撑大三倍。</p>
     *
     * @param value 原始字符串，可为 null
     * @return JSON 字符串字面量；入参为 null 时返回 {@code null}（JSON 的 null 字面量）
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
     * @param json JSON 文本，可为 null
     * @return 对象树，取值类型见类注释
     * @throws IllegalArgumentException 文本为 null、结构畸形或尾部存在多余内容时抛出，
     *                                  异常信息包含出错位置，便于定位外部接口返回的异常报文
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
     * 把解析结果按对象使用。
     *
     * @param value 解析出的值
     * @return 对象视图
     * @throws IllegalArgumentException 值不是 JSON 对象时抛出
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        throw new IllegalArgumentException("JSON 值不是对象: " + describe(value));
    }

    /**
     * 把解析结果按数组使用。
     *
     * @param value 解析出的值
     * @return 数组视图
     * @throws IllegalArgumentException 值不是 JSON 数组时抛出
     */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        throw new IllegalArgumentException("JSON 值不是数组: " + describe(value));
    }

    /**
     * 读取对象中的字符串字段。
     *
     * <p>字段不存在、值为 JSON {@code null}、或值不是字符串时统一返回 null，
     * 由调用方决定如何回退：外部接口的返回格式不受本程序控制，
     * 少一个字段不该让整个解析过程失败。</p>
     *
     * @param object 对象，可为 null
     * @param key    字段名
     * @return 字符串值；不可用时返回 null
     */
    public static String asString(Map<String, Object> object, String key) {
        if (object == null || key == null) {
            return null;
        }
        Object value = object.get(key);
        return value instanceof String ? (String) value : null;
    }

    /**
     * 读取对象中的数值字段。
     *
     * @param object       对象，可为 null
     * @param key          字段名
     * @param defaultValue 缺省值
     * @return 数值；字段缺失、为 null 或不是数值类型时返回 defaultValue
     */
    public static long asLong(Map<String, Object> object, String key, long defaultValue) {
        if (object == null || key == null) {
            return defaultValue;
        }
        Object value = object.get(key);
        return value instanceof Number ? ((Number) value).longValue() : defaultValue;
    }

    /**
     * 描述一个解析结果的类型，仅用于错误信息。
     *
     * @param value 解析出的值
     * @return 类型描述；null 返回 {@code "null"}
     */
    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /**
     * JSON 递归下降解析器。
     *
     * <p>一次解析创建一个实例，持有"读到哪儿了"这一个状态；不对外暴露，
     * 因此无需考虑重入与线程安全。</p>
     */
    private static final class Parser {

        /** 待解析文本 */
        private final String text;

        /** 当前读取位置 */
        private int pos;

        /**
         * 构造解析器。
         *
         * @param text 待解析文本
         */
        Parser(String text) {
            this.text = text;
        }

        /**
         * 当前读取位置。
         *
         * @return 位置下标
         */
        int position() {
            return pos;
        }

        /**
         * 是否已读完。
         *
         * @return 读完返回 true
         */
        boolean atEnd() {
            return pos >= text.length();
        }

        /**
         * 跳过空白字符。
         *
         * <p>JSON 规范允许的空白只有空格、制表符、换行与回车四种，
         * 这里按规范处理而不是用 {@code Character.isWhitespace}：
         * 后者会连带放过中文全角空格之类不该出现在 JSON 结构里的字符。</p>
         */
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

        /**
         * 解析一个值。
         *
         * @return 解析结果
         */
        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw error("内容意外结束");
            }
            char c = text.charAt(pos);
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

        /**
         * 解析一个对象。
         *
         * @return 键值映射
         */
        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
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
        }

        /**
         * 解析一个数组。
         *
         * @return 元素列表
         */
        private List<Object> parseArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return result;
            }
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
        }

        /**
         * 解析一个字符串。
         *
         * <p>{@code \\uXXXX} 转义按 UTF-16 码元追加：成对出现的两个转义
         * （如 emoji 的 {@code \\uD83D\\uDE00}）会自动拼成正确的代理对，
         * 无需额外处理。</p>
         *
         * @return 字符串内容
         */
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
                        if (pos + 4 > text.length()) {
                            throw error("\\u 转义不足 4 位");
                        }
                        String hex = text.substring(pos, pos + 4);
                        try {
                            builder.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("\\u 转义不是合法的十六进制: " + hex);
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("无法识别的转义符: \\" + escaped);
                }
            }
        }

        /**
         * 解析一个数值。
         *
         * <p>先按 JSON 数值的文法扫描出一段子串，再交给
         * {@link Double#parseDouble} 转换：直接依赖后者会把 {@code NaN}、
         * {@code Infinity}、十六进制等 JSON 不允许的形式也放进来。</p>
         *
         * @return 数值
         */
        private Double parseNumber() {
            int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            while (!atEnd()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String literal = text.substring(start, pos);
            try {
                return Double.valueOf(literal);
            } catch (NumberFormatException e) {
                throw error("不是合法的 JSON 数值: " + literal);
            }
        }

        /**
         * 读取下一个字符但不移动位置。
         *
         * @return 当前字符；已读完时返回 {@code '\0'}
         */
        private char peek() {
            return atEnd() ? '\0' : text.charAt(pos);
        }

        /**
         * 读取下一个字符并移动位置。
         *
         * @return 读到的字符
         */
        private char next() {
            if (atEnd()) {
                throw error("内容意外结束");
            }
            return text.charAt(pos++);
        }

        /**
         * 断言下一个字符是指定字符。
         *
         * @param expected 期望字符
         */
        private void expect(char expected) {
            char actual = next();
            if (actual != expected) {
                throw error("应为 '" + expected + "'，实际读到 '" + actual + "'");
            }
        }

        /**
         * 断言接下来的内容是某个字面量（{@code true} / {@code false} / {@code null}）。
         *
         * @param literal 期望字面量
         */
        private void expectLiteral(String literal) {
            if (pos + literal.length() > text.length()
                    || !text.startsWith(literal, pos)) {
                throw error("应为 " + literal);
            }
            pos += literal.length();
        }

        /**
         * 构造带位置的解析错误。
         *
         * @param message 错误说明
         * @return 异常对象，由调用方抛出
         */
        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON 解析失败（第 " + pos + " 个字符）: " + message);
        }
    }
}

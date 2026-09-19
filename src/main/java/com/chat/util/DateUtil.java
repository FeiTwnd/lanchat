package com.chat.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 日期时间工具类。
 *
 * <p>职责：统一系统的日期格式化与解析规则，避免各处自行拼装格式串导致历史记录无法回读。</p>
 *
 * <p>线程安全：{@link DateTimeFormatter} 本身不可变且线程安全，
 * 因此这里使用静态常量而非旧的 {@code SimpleDateFormat}（后者非线程安全，是经典陷阱）。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class DateUtil {

    /** 日期时间格式器：yyyy-MM-dd HH:mm:ss */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 仅时间格式器：HH:mm:ss，用于界面展示 */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 仅日期格式器：yyyy-MM-dd，用于按日期检索与展示 */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 私有构造，禁止实例化工具类 */
    private DateUtil() {
    }

    /**
     * 格式化日期时间为标准字符串。
     *
     * @param dateTime 日期时间，可为 null
     * @return 形如 {@code 2025-01-01 10:20:30} 的字符串；入参为 null 时返回空字符串
     */
    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? "" : DATE_TIME_FORMATTER.format(dateTime);
    }

    /**
     * 仅格式化时间部分，用于聊天窗口展示。
     *
     * @param dateTime 日期时间，可为 null
     * @return 形如 {@code 10:20:30} 的字符串；入参为 null 时返回空字符串
     */
    public static String formatTime(LocalDateTime dateTime) {
        return dateTime == null ? "" : TIME_FORMATTER.format(dateTime);
    }

    /**
     * 格式化当前时间。
     *
     * @return 当前时间的标准格式字符串
     */
    public static String now() {
        return DATE_TIME_FORMATTER.format(LocalDateTime.now());
    }

    /**
     * 解析标准格式的日期时间字符串。
     *
     * @param text 形如 {@code 2025-01-01 10:20:30} 的文本
     * @return 解析结果；文本为空或格式非法时返回 null
     */
    public static LocalDateTime parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), DATE_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 获取指定日期的“日键”，用于历史记录文件命名。
     *
     * @param dateTime 日期时间
     * @return 形如 {@code 2025-01-01} 的字符串
     */
    public static String dayKey(LocalDateTime dateTime) {
        LocalDateTime target = dateTime == null ? LocalDateTime.now() : dateTime;
        return DATE_FORMATTER.format(target);
    }

    /**
     * 获取今天的日键。
     *
     * @return 形如 {@code 2025-01-01} 的字符串
     */
    public static String todayKey() {
        return DATE_FORMATTER.format(LocalDate.now());
    }

    /**
     * 把毫秒数转换为可读的时长文本，用于日志与传输耗时展示。
     *
     * @param millis 毫秒数
     * @return 形如 {@code 1.5 秒} 或 {@code 320 毫秒} 的字符串
     */
    public static String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + " 毫秒";
        }
        Duration duration = Duration.ofMillis(millis);
        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return String.format("%.1f 秒", millis / 1000.0);
        }
        return String.format("%d 分 %d 秒", seconds / 60, seconds % 60);
    }
}

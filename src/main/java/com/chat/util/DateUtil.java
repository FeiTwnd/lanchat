package com.chat.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 日期时间格式化与解析工具。
 */
public final class DateUtil {
    /** 完整日期时间格式，精确到秒。 */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 时分秒格式。 */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 日期格式。 */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DateUtil() {
    }

    /**
     * 格式化为 yyyy-MM-dd HH:mm:ss。
     *
     * @param dateTime 待格式化的时间
     * @return 格式化结果，入参为 null 时返回空串
     */
    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? "" : DATE_TIME_FORMATTER.format(dateTime);
    }

    /**
     * 格式化为 HH:mm:ss。
     *
     * @param dateTime 待格式化的时间
     * @return 格式化结果，入参为 null 时返回空串
     */
    public static String formatTime(LocalDateTime dateTime) {
        return dateTime == null ? "" : TIME_FORMATTER.format(dateTime);
    }

    /**
     * 返回当前时间的完整格式化文本。
     *
     * @return 当前时间，格式为 yyyy-MM-dd HH:mm:ss
     */
    public static String now() {
        return DATE_TIME_FORMATTER.format(LocalDateTime.now());
    }

    /**
     * 解析 yyyy-MM-dd HH:mm:ss 格式的文本。
     *
     * @param text 待解析文本
     * @return 解析结果，空文本或格式不符时返回 null
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
     * 返回指定时间所属日期的文本。
     *
     * @param dateTime 时间，为 null 时取当前时间
     * @return 日期文本，格式为 yyyy-MM-dd
     */
    public static String dayKey(LocalDateTime dateTime) {
        LocalDateTime target = dateTime == null ? LocalDateTime.now() : dateTime;
        return DATE_FORMATTER.format(target);
    }

    /**
     * 返回今天的日期文本。
     *
     * @return 日期文本，格式为 yyyy-MM-dd
     */
    public static String todayKey() {
        return DATE_FORMATTER.format(LocalDate.now());
    }

    /**
     * 把毫秒数转换为中文可读时长。
     *
     * @param millis 毫秒数
     * @return 形如 500 毫秒、1.5 秒或 2 分 3 秒的文本
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

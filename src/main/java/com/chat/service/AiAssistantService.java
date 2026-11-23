package com.chat.service;

import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.common.JsonCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 智能体小助手服务，负责把提问提交到外部工作流接口并异步取回回答。
 */
public class AiAssistantService {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".AiAssistantService");

    private static final int MAX_CONCURRENT_ASKS = 2;

    private static final int ASK_QUEUE_CAPACITY = 32;

    /** 回执与日志中异常原因的最大字符数。 */
    private static final int MAX_REASON_LENGTH = 200;

    /** 响应体最大字节数（1 MiB）。 */
    private static final int MAX_RESPONSE_BODY_BYTES = 1024 * 1024;

    /** 同一提问者的最小提问间隔（毫秒）。 */
    private static final long MIN_ASK_INTERVAL_MS = 3_000L;

    private static final int RATE_LIMIT_CLEANUP_THRESHOLD = 256;

    private static final String RATE_LIMITED_REPLY = "提问太快了，请稍等几秒再问。";

    private final String apiUrl;

    private final String workflowId;

    private final String token;

    private final boolean apiUrlUsable;

    /** 每个提问者的最近提问时刻（毫秒时间戳）。 */
    private final Map<String, Long> lastAskAt = new ConcurrentHashMap<>();

    private final AtomicLong lastRateLimitCleanup = new AtomicLong();

    private final HttpClient httpClient;

    private final ExecutorService workers;

    /**
     * 读取配置并创建 HTTP 客户端与问答线程池。
     */
    public AiAssistantService() {
        this.apiUrl = Config.aiApiUrl();
        this.workflowId = Config.aiWorkflowId();
        this.token = Config.aiApiToken();
        // 校验令牌能否放入 HTTP 请求头
        if (!token.isEmpty() && !isHeaderValueSafe(token)) {
            LOGGER.warning("ai.api.token 含有 HTTP 头不允许的字符（换行、空格或非 ASCII 字符），"
                    + "小助手不会启用；请在配置文件中重新填写该令牌");
        }
        // 校验接口地址
        this.apiUrlUsable = isUsableEndpoint(apiUrl);
        if (!apiUrlUsable) {
            // 地址不可用时记录协议名
            LOGGER.warning("ai.api.url 不是可用的 http/https 地址（scheme=" + describeScheme(apiUrl)
                    + "），小助手不会启用；请检查配置文件中的该地址");
        }
        // 固定使用 HTTP/1.1 并设置连接超时
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Constants.AI_CONNECT_TIMEOUT_MS))
                .build();
        this.workers = new ThreadPoolExecutor(MAX_CONCURRENT_ASKS, MAX_CONCURRENT_ASKS,
                Constants.WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(ASK_QUEUE_CAPACITY),
                daemonFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 判断小助手是否已配置完成并可用。
     *
     * @return 可用返回 true
     */
    public boolean isEnabled() {
        return apiUrlUsable && !workflowId.isEmpty() && !token.isEmpty() && isHeaderValueSafe(token);
    }

    /**
     * 判断字符串是否为可用的 http 或 https 地址。
     *
     * @param url 接口地址
     * @return 可用返回 true
     */
    private static boolean isUsableEndpoint(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (IllegalArgumentException e) {
            // 地址无法解析
            return false;
        }
    }

    /**
     * 取出接口地址的协议名，用于日志。
     *
     * @param url 接口地址
     * @return 协议名；缺失时返回“缺失”，无法解析时返回“无法解析”
     */
    private static String describeScheme(String url) {
        if (url == null) {
            return "缺失";
        }
        try {
            String scheme = URI.create(url).getScheme();
            return scheme == null ? "缺失" : scheme.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "无法解析";
        }
    }

    /**
     * 提交一次提问，回答就绪后通过回调返回。
     *
     * @param asker 提问者用户名
     * @param question 提问正文
     * @param sink 回答回调，参数依次为提问者与回答正文
     */
    public void ask(String asker, String question, BiConsumer<String, String> sink) {
        if (sink == null) {
            return;
        }
        if (!isEnabled()) {
            // 未启用时直接回调提示
            sink.accept(asker, "小助手尚未配置好，请联系管理员。");
            return;
        }
        // 按提问者限频
        if (!allowAsk(asker)) {
            // 被限频时直接回调提示
            sink.accept(asker, RATE_LIMITED_REPLY);
            return;
        }
        try {
            // 提交到问答线程池
            workers.execute(() -> {
                try {
                    sink.accept(asker, sanitize(requestAnswer(asker, question)));
                } catch (InterruptedException e) {
                    // 恢复中断标记
                    Thread.currentThread().interrupt();
                } catch (RuntimeException | IOException e) {
                    // 记录失败原因并回调提示
                    LOGGER.log(Level.WARNING, "智能体问答失败: " + asker + " - " + redact(e.getMessage()));
                    sink.accept(asker, sanitize("抱歉，这次没能取到回答：" + friendlyReason(e)));
                } catch (Throwable t) {
                    // 处理 Error 级别的异常
                    LOGGER.log(Level.SEVERE, "智能体问答遇到严重错误: " + asker + " - "
                            + redact(t.getClass().getName() + ": " + t.getMessage()));
                    sink.accept(asker, "抱歉，小助手内部出错，这次没能取到回答，请稍后再问一次。");
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列已满时拒绝
            LOGGER.info(() -> "智能体问答队列已满，已拒绝: " + asker);
            sink.accept(asker, "现在提问的人有点多，请稍后再试。");
        }
    }

    /**
     * 判断本次提问是否通过按人限频。
     *
     * @param asker 提问者用户名
     * @return 允许提问返回 true
     */
    private boolean allowAsk(String asker) {
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean(true);
        // 空用户名取空串作为计数键
        String key = asker == null ? "" : asker;
        lastAskAt.compute(key, (name, previous) -> {
            if (previous != null && now - previous < MIN_ASK_INTERVAL_MS) {
                allowed.set(false);
                return previous;
            }
            return now;
        });
        // 通过限频检查后清理过期记录
        if (allowed.get()) {
            evictStaleAskers(now);
        }
        return allowed.get();
    }

    /**
     * 清理超过限频间隔的提问记录。
     *
     * @param now 当前毫秒时间戳
     */
    private void evictStaleAskers(long now) {
        if (lastAskAt.size() <= RATE_LIMIT_CLEANUP_THRESHOLD) {
            return;
        }
        long lastCleanup = lastRateLimitCleanup.get();
        if (now - lastCleanup < MIN_ASK_INTERVAL_MS || !lastRateLimitCleanup.compareAndSet(lastCleanup, now)) {
            return;
        }
        // 移除超过限频间隔的记录
        lastAskAt.entrySet().removeIf(entry -> now - entry.getValue() >= MIN_ASK_INTERVAL_MS);
    }

    /**
     * 停止问答线程池。
     */
    public void shutdown() {
        workers.shutdownNow();
    }

    private String requestAnswer(String asker, String question) throws IOException, InterruptedException {
        // 构造带令牌的 POST 请求
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofMillis(Constants.AI_REQUEST_TIMEOUT_MS))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(asker, question),
                        StandardCharsets.UTF_8))
                .build();

        // 发送请求并读取状态码
        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status != 200) {
            // 非 200 状态先关闭响应体
            closeQuietly(response.body());
            throw new IOException("接口返回 HTTP " + status + "（" + describeStatus(status) + "）");
        }
        try {
            return extractAnswer(readBody(response.body()));
        } finally {
            closeQuietly(response.body());
        }
    }

    /**
     * 读取响应体，超过大小上限时中止。
     */
    private String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IOException("平台没有返回内容。");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            if (buffer.size() + read > MAX_RESPONSE_BODY_BYTES) {
                throw new IOException("智能体平台返回的内容过大（超过 "
                        + (MAX_RESPONSE_BODY_BYTES / 1024) + " KiB），已中止读取。");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关闭异常忽略
        }
    }

    private String buildRequestBody(String asker, String question) {
        return '{'
                + "\"workflow_id\":" + JsonCodec.quote(workflowId) + ','
                + "\"parameters\":{\"input\":" + JsonCodec.quote(question) + '}'
                + '}';
    }

    private String extractAnswer(String body) throws IOException {
        Map<String, Object> root;
        try {
            root = JsonCodec.asObject(JsonCodec.parse(body));
        } catch (IllegalArgumentException e) {
            throw new IOException("返回内容不是合法 JSON：" + e.getMessage());
        }
        // 校验业务状态码
        long code = JsonCodec.asLong(root, "code", -1L);
        if (code != 0L) {
            throw new IOException(describeApiError(code, JsonCodec.asString(root, "msg")));
        }
        // data 可能是字符串或对象
        Object data = root.get("data");
        if (data instanceof String) {
            String payload = (String) data;
            String output = extractWorkflowOutput(payload);
            if (output != null && !output.trim().isEmpty()) {
                return output;
            }
        } else if (data instanceof Map) {
            String output = JsonCodec.asString(JsonCodec.asObject(data), "output");
            if (output != null && !output.trim().isEmpty()) {
                return output;
            }
        }
        throw new IOException("接口没有返回工作流输出。");
    }

    /**
     * 从 data 字符串中解析 output，解析失败时按原文返回。
     */
    private static String extractWorkflowOutput(String payload) {
        try {
            Map<String, Object> data = JsonCodec.asObject(JsonCodec.parse(payload));
            String output = JsonCodec.asString(data, "output");
            return output == null ? payload : output;
        } catch (IllegalArgumentException e) {
            // 解析失败时按原文返回
            return payload;
        }
    }

    private static String describeApiError(long code, String msg) {
        switch ((int) code) {
            case 4000:
                return "请求参数不合法，请检查工作流输入参数";
            case 4101:
                return "访问令牌无效或已过期，请重新生成";
            case 4200:
                return "工作流不存在或尚未发布，请核对配置与发布状态";
            default:
                return "接口返回错误（code=" + code
                        + (msg == null || msg.isEmpty() ? "" : ", msg=" + msg) + "）";
        }
    }

    private String friendlyReason(Exception e) {
        if (e instanceof HttpTimeoutException) {
            return "等待回答超时，请稍后再问一次。";
        }
        if (e instanceof java.net.ConnectException) {
            return "服务器连不上智能体平台，请检查网络。";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "无法解析智能体平台域名，请检查网络。";
        }
        if (e instanceof IOException) {
            // 其他 IO 异常返回脱敏后的原因
            return redact(e.getMessage() == null ? "未知原因。" : e.getMessage());
        }
        return "智能体服务暂时不可用，请稍后再试。";
    }

    /**
     * 掩去令牌并截断异常文本。
     *
     * @param text 原始文本
     * @return 处理后的文本
     */
    private String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = token == null || token.isEmpty() ? text : text.replace(token, "***");
        StringBuilder builder = new StringBuilder(masked.length());
        for (int i = 0; i < masked.length() && builder.length() <= MAX_REASON_LENGTH; i++) {
            char c = masked.charAt(i);
            if (!Character.isISOControl(c)) {
                builder.append(c);
            }
        }
        return builder.length() > MAX_REASON_LENGTH
                ? builder.substring(0, MAX_REASON_LENGTH) + "..."
                : builder.toString();
    }

    /**
     * 判断字符串是否只含可放入 HTTP 头的可见 ASCII 字符。
     *
     * @param value 待判断的字符串
     * @return 全部字符合法返回 true
     */
    private static boolean isHeaderValueSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private String describeStatus(int statusCode) {
        switch (statusCode) {
            case 401:
                return "访问令牌无效或已过期，请重新生成";
            case 403:
                return "令牌无权访问该工作流，或工作流尚未发布";
            case 404:
                return "工作流编号不存在，请核对配置";
            case 429:
                return "调用过于频繁，已被平台限流";
            default:
                return "平台返回了错误";
        }
    }

    /**
     * 规范化换行、去掉控制字符并限制正文长度。
     *
     * @param raw 原始回答
     * @return 处理后的回答
     */
    private String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        // 统一换行符
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        // 去掉除换行与制表符外的控制字符
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t') {
                continue;
            }
            builder.append(c);
        }
        String text = builder.toString().trim();
        if (text.length() > Constants.MESSAGE_MAX_LENGTH) {
            int keep = Constants.MESSAGE_MAX_LENGTH - 3;
            if (keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1))) {
                // 截断位置落在代理对中间时前移一位
                keep--;
            }
            text = text.substring(0, keep) + "...";
        }
        return text;
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "chat-ai-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getWorkflowId() {
        return workflowId;
    }
}

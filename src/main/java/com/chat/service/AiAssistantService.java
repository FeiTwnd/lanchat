package com.chat.service;

import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.common.JsonCodec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 智能体小助手服务。
 *
 * <p>职责：把使用者提给"智能体小助手"的问题转成一次外部 HTTP 调用，取回回答正文，
 * 再交回调用方投递给提问者。外部平台选用扣子（Coze），接口为
 * {@code POST https://api.coze.cn/open_api/v2/chat}。</p>
 *
 * <p><b>为什么是服务端的一个虚拟成员而不是客户端直连</b>：见
 * {@link com.chat.server.UserManager#registerVirtualUser}。要点是所有客户端零改动，
 * 小助手在好友树里就是一名普通成员。</p>
 *
 * <p><b>无会话上下文</b>：请求里不带 {@code conversation_id}，每次提问都是独立的一问一答。
 * 这既符合"回答本程序怎么用"这一用途（问题都是自包含的），也省掉了
 * "哪个用户当时处在哪段会话里"这片需要过期与并发保护的状态。</p>
 *
 * <p><b>为什么必须异步</b>：一次问答的耗时由外部平台决定（本类按
 * {@link Constants#AI_REQUEST_TIMEOUT_MS} 设上限）。若在调用方线程上同步等待，
 * 被阻塞的会是 {@link com.chat.server.ClientHandler} 的读取循环——那个线程同时负责
 * 应答心跳，一旦被卡满 {@link Constants#HEARTBEAT_TIMEOUT_MS}，提问者本人会被服务器
 * 判定为掉线并强制断开。因此 {@link #ask} 立即返回，真正的等待发生在本类的线程池里。</p>
 *
 * <p><b>凭据处理</b>：令牌只出现在请求头里，不写日志、不进异常信息。启动日志只报
 * "是否已配置"，不报任何片段。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class AiAssistantService {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".AiAssistantService");

    /** 扣子返回的聊天消息中，代表"助手正式回答"的角色与类型 */
    private static final String ROLE_ASSISTANT = "assistant";

    /** 只会取 {@code type=answer} 的消息：同一份返回里还有 {@code verbose} 等中间态内容 */
    private static final String TYPE_ANSWER = "answer";

    /**
     * 同时进行的问答数上限。
     *
     * <p>取 2 是因为用途是"问怎么用"，不是高并发客服；限制并发既能防止
     * 提问潮把服务器的出网连接数拉满，也让外部平台的限流更不容易被触发。</p>
     */
    private static final int MAX_CONCURRENT_ASKS = 2;

    /**
     * 等待队列长度。
     *
     * <p>队列满时明确拒绝并回一句"稍后再试"，而不是无限排队：
     * 排队意味着提问者盯着一个可能几十秒后才出现的回答，
     * 明确拒绝反而让他知道该重来一次。</p>
     */
    private static final int ASK_QUEUE_CAPACITY = 32;

    /** 实际使用的接口地址；默认取内置常量，可由配置覆盖 */
    private final String apiUrl;

    /** 外部平台的智能体编号 */
    private final String botId;

    /** 访问令牌，只用于请求头 */
    private final String token;

    /**
     * JDK 自带的 HTTP 客户端，无需任何第三方依赖。
     *
     * <p><b>为什么显式锁定 HTTP/1.1</b>：JDK 的 HttpClient 在 HTTPS 之外的地址上默认会先
     * 发一次 {@code Upgrade: h2c} 试探 HTTP/2 明文升级，等对端回 {@code 101} 才继续。
     * 智能体平台提供的是 HTTP/1.1 接口，这次试探纯属多一个来回；更要紧的是，
     * 任何不理解该扩展的中转（企业代理、抓包工具、调试桩）都会让请求停在这里不动，
     * 表现成"提问发出去之后一直没有回音"。锁定版本号可以整段避开这个不确定性。</p>
     */
    private final HttpClient httpClient;

    /** 问答工作线程池，只在真正有提问时才创建线程 */
    private final ExecutorService workers;

    /**
     * 构造服务并读取配置。
     *
     * <p>构造阶段不做任何网络动作：配置缺失时本对象仍然存在，只是
     * {@link #isEnabled()} 为 false，服务器照常启动，小助手不出现在成员列表里。</p>
     */
    public AiAssistantService() {
        this.apiUrl = Config.aiApiUrl();
        this.botId = Config.aiBotId();
        this.token = Config.aiApiToken();
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
     * 判断小助手是否可用。
     *
     * <p>两个必填项都配齐才算启用——把"填了令牌"本身当作开关，
     * 不再单设一个 {@code ai.enabled}：两个开关表达同一件事时，
     * 总会出现"开关开着但令牌没填"这种只能靠日志解释的状态。</p>
     *
     * @return 已配置且可用返回 true
     */
    public boolean isEnabled() {
        return !botId.isEmpty() && !token.isEmpty();
    }

    /**
     * 提交一次提问，回答就绪后回调。
     *
     * <p>本方法立即返回，不阻塞调用线程。回调在问答线程池的线程上执行，
     * 实现方若要操作界面需自行切到事件分发线程。</p>
     *
     * @param asker    提问者用户名，用于回投与时序排查
     * @param question 提问正文
     * @param sink     答复回调：参数依次为提问者用户名、机器人要回复的正文
     *                 （失败时同样走这里，正文是一句道歉加中文原因，绝不静默丢弃）
     */
    public void ask(String asker, String question, BiConsumer<String, String> sink) {
        if (sink == null) {
            return;
        }
        if (!isEnabled()) {
            sink.accept(asker, "小助手尚未配置好，请联系管理员。");
            return;
        }
        try {
            workers.execute(() -> {
                try {
                    sink.accept(asker, sanitize(requestAnswer(asker, question)));
                } catch (InterruptedException e) {
                    // 服务器停止时线程池被中断，属于正常收尾，不当作故障上报
                    Thread.currentThread().interrupt();
                } catch (RuntimeException | IOException e) {
                    LOGGER.log(Level.WARNING, "智能体问答失败: " + asker + " - " + e.getMessage());
                    sink.accept(asker, sanitize("抱歉，这次没能取到回答：" + friendlyReason(e)));
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.info(() -> "智能体问答队列已满，已拒绝: " + asker);
            sink.accept(asker, "现在提问的人有点多，请稍后再试。");
        }
    }

    /**
     * 关闭线程池，用于服务器停止时善后。
     *
     * <p>不等待正在进行的问答完成：那些回答的投递依赖在线表，
     * 而在线表在停止流程里已经被清空，等下去也没有意义。</p>
     */
    public void shutdown() {
        workers.shutdownNow();
    }

    /**
     * 真正发起一次问答调用。
     *
     * @param asker    提问者用户名，作为外部平台的终端用户标识
     * @param question 提问正文
     * @return 已清洗、可直接投递的回答正文
     * @throws IOException          网络失败、超时或接口返回非成功状态时抛出
     * @throws InterruptedException 等待响应期间线程被中断时抛出
     */
    private String requestAnswer(String asker, String question) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofMillis(Constants.AI_REQUEST_TIMEOUT_MS))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(asker, question),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("接口返回 HTTP " + response.statusCode()
                    + "（" + describeStatus(response.statusCode()) + "）");
        }
        return extractAnswer(response.body());
    }

    /**
     * 拼装请求体。
     *
     * <p>{@code stream} 固定为 false：只用一问一答的完整结果，不处理流式分片。
     * {@code user} 用提问者的用户名而不是一个固定的占位串，是为了让外部平台按人计量与限流——
     * 用同一个值会让所有人的调用合成一条额度。</p>
     *
     * @param asker    提问者用户名
     * @param question 提问正文
     * @return JSON 请求体
     */
    private String buildRequestBody(String asker, String question) {
        return '{'
                + "\"bot_id\":" + JsonCodec.quote(botId) + ','
                + "\"user\":" + JsonCodec.quote(asker) + ','
                + "\"query\":" + JsonCodec.quote(question) + ','
                + "\"stream\":false"
                + '}';
    }

    /**
     * 从返回报文中取出助手回答。
     *
     * <p>返回体中 {@code messages} 是一个数组，既有助手的正式回答
     * （{@code role=assistant}、{@code type=answer}），也有若干中间态内容
     * 与用户自己的提问。这里只认第一段正式回答，取不到时用返回体里的
     * {@code code} 与 {@code msg} 说明原因——那两个字段是平台报错时唯一的有效信息。</p>
     *
     * @param body 返回报文
     * @return 回答正文
     * @throws IOException 报文无法解析或其中没有可用回答时抛出
     */
    private String extractAnswer(String body) throws IOException {
        Map<String, Object> root;
        try {
            root = JsonCodec.asObject(JsonCodec.parse(body));
        } catch (IllegalArgumentException e) {
            throw new IOException("返回内容不是合法 JSON：" + e.getMessage());
        }
        Object messages = root.get("messages");
        if (messages instanceof List) {
            for (Object item : JsonCodec.asArray(messages)) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> message = JsonCodec.asObject(item);
                if (!ROLE_ASSISTANT.equals(JsonCodec.asString(message, "role"))
                        || !TYPE_ANSWER.equals(JsonCodec.asString(message, "type"))) {
                    continue;
                }
                String content = JsonCodec.asString(message, "content");
                if (content != null && !content.trim().isEmpty()) {
                    return content;
                }
            }
        }
        long code = JsonCodec.asLong(root, "code", -1L);
        String reason = JsonCodec.asString(root, "msg");
        throw new IOException("接口没有返回回答（code=" + code
                + (reason == null || reason.isEmpty() ? "" : ", msg=" + reason) + "）");
    }

    /**
     * 把异常翻译成给使用者看的中文原因。
     *
     * <p>刻意不把原始异常信息整段抛给使用者：那些信息里混着类名、主机名与英文堆栈措辞，
     * 对"程序怎么用"的使用者没有帮助。按类型归成几句可行动的提示，
     * 完整信息留在服务器日志里给管理员。</p>
     *
     * @param e 问答过程中抛出的异常
     * @return 一句中文原因
     */
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
        return e.getMessage() == null ? "未知原因。" : e.getMessage();
    }

    /**
     * 把 HTTP 状态码翻译成可行动的排查提示。
     *
     * <p>这几种状态在接入调试阶段最常撞上，且都不是"重试就能好"的类型，
     * 直接把病因写在回答里，比让使用者对着一个数字查文档有用。</p>
     *
     * @param statusCode HTTP 状态码
     * @return 中文说明
     */
    private String describeStatus(int statusCode) {
        switch (statusCode) {
            case 401:
                return "访问令牌无效或已过期，请重新生成";
            case 403:
                return "智能体未发布，或发布时没勾选 API 渠道";
            case 404:
                return "bot_id 不存在，请核对配置";
            case 429:
                return "调用过于频繁，已被平台限流";
            default:
                return "平台返回了错误";
        }
    }

    /**
     * 清洗回答正文，使其满足本项目的消息约束。
     *
     * <p>三件事：把各种换行统一成 {@code \n}（Windows 风格的 {@code \r\n} 属于
     * 非法控制字符，界面与协议都只认 {@code \n}）；剔除其余控制字符
     * （与 {@link com.chat.common.ChatMessageFactory#hasIllegalControlChar} 同口径）；
     * 按 {@link Constants#MESSAGE_MAX_LENGTH} 截断——平台返回的长文可能远超本项目的正文上限，
     * 不截断会把一条超出协议约束的消息塞进聊天窗口。</p>
     *
     * @param raw 平台返回的原始正文
     * @return 可直接投递的正文
     */
    private String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
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
            text = text.substring(0, Constants.MESSAGE_MAX_LENGTH - 3) + "...";
        }
        return text;
    }

    /**
     * 创建守护线程工厂。
     *
     * <p>线程名带固定前缀便于 jstack 定位；设为守护线程是为了不让一个还在等待外部响应的
     * 问答线程拖住 JVM 退出。</p>
     *
     * @return 线程工厂
     */
    private static ThreadFactory daemonFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "chat-ai-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 读取接口地址，供日志展示。
     *
     * @return 当前生效的接口地址
     */
    public String getApiUrl() {
        return apiUrl;
    }

    /**
     * 读取配置好的智能体编号。
     *
     * @return 智能体编号
     */
    public String getBotId() {
        return botId;
    }
}

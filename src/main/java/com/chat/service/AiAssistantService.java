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
import java.util.List;
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

    /**
     * 回执与日志里异常原因的最大长度。
     *
     * <p>异常文本是给排查用的线索，不是正文；不设上限时一段 JDK 的长报文
     * 会把聊天窗口刷满，也会把日志冲散。</p>
     */
    private static final int MAX_REASON_LENGTH = 200;

    /**
     * 单次响应体的最大字节数（1 MiB）。
     *
     * <p>{@code BodyHandlers.ofString} 是不设防地把整个响应读进堆：对端只要持续吐数据，
     * 堆就会被撑爆，而 OOM 发生在 HttpClient 自己的线程上——提问者拿不到任何回执，
     * 只看到小助手永久性地"正在思考"。真实回答是一段短文，1 MiB 已远超正常所需，
     * 足以容纳任何合理回复；超限即中止并回报一句中文原因。
     * 这个上限只服务于本类的读取逻辑，因此留在本类里而不是公共常量表。</p>
     */
    private static final int MAX_RESPONSE_BODY_BYTES = 1024 * 1024;

    /**
     * 同一提问者的最小提问间隔（3 秒）。
     *
     * <p>每次提问都会产生一次外部平台的<b>计费</b>调用。并发与队列只约束"同时在飞"的数量，
     * 拦不住同一个人反复追问——那只受限于他的键盘速度。给每人一个最小间隔，
     * 既保住正常的一问一答手感，又让刷接口变得没有收益。</p>
     */
    private static final long MIN_ASK_INTERVAL_MS = 3_000L;

    /**
     * 限频记录表的条目清理阈值。
     *
     * <p>记录表按用户名累积，用完不清理就是一个只增不减的容器。虽然条目数在理论上
     * 受账号总数约束，但没有理由让一份"只用来判断 3 秒"的状态长期驻留，超过阈值即清理过期项。</p>
     */
    private static final int RATE_LIMIT_CLEANUP_THRESHOLD = 256;

    /** 被限频时回给提问者的固定提示 */
    private static final String RATE_LIMITED_REPLY = "提问太快了，请稍等几秒再问。";

    /** 实际使用的接口地址；默认取内置常量，可由配置覆盖 */
    private final String apiUrl;

    /** 外部平台的智能体编号 */
    private final String botId;

    /** 访问令牌，只用于请求头 */
    private final String token;

    /**
     * 接口地址是否可用（可解析且 scheme 为 http/https）。
     *
     * <p>构造阶段就判定并固化，而不是每次提问再解析：配置写错属于启动期问题，
     * 让它表现为"小助手没有出现"，而不是"每次提问都换回一句英文异常"。
     * 与令牌非法同样的失败方式，管理员看启动日志即可定位。</p>
     */
    private final boolean apiUrlUsable;

    /**
     * 每个提问者的最近一次提问时刻（毫秒时间戳），用于限频。
     *
     * <p>用 {@link ConcurrentHashMap} 而不是普通表：提问来自各自连接的读取线程，
     * 天然并发；计数表又只做"读改写一个时间戳"这一件事，用锁或更大结构都不划算。</p>
     */
    private final Map<String, Long> lastAskAt = new ConcurrentHashMap<>();

    /** 上次清理限频记录的时刻，用于把清理动作限制在低频（见 {@link #evictStaleAskers(long)}） */
    private final AtomicLong lastRateLimitCleanup = new AtomicLong();

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
        // 令牌是手工拷进配置文件的，很容易在末尾多带一个换行或混入空格。
        // 这种令牌进不了 HTTP 头，而 JDK 抛出的异常文本会把整个头值（含令牌）原样带出来，
        // 一旦被日志或回执带出去就是凭据泄露；因此这里就地判定为"未配置"，
        // 让服务器照常启动、小助手不注册，只留一条不含令牌的配置提示。
        if (!token.isEmpty() && !isHeaderValueSafe(token)) {
            LOGGER.warning("ai.api.token 含有 HTTP 头不允许的字符（换行、空格或非 ASCII 字符），"
                    + "小助手不会启用；请在配置文件中重新填写该令牌");
        }
        this.apiUrlUsable = isUsableEndpoint(apiUrl);
        if (!apiUrlUsable) {
            // 只报协议，不回显原始地址：地址上可能挂着查询串形式的凭据，而协议名不可能含令牌
            LOGGER.warning("ai.api.url 不是可用的 http/https 地址（scheme=" + describeScheme(apiUrl)
                    + "），小助手不会启用；请检查配置文件中的该地址");
        }
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
     * <p>令牌还必须能放进 HTTP 头：含换行、空格或非 ASCII 字符的令牌只会让每次提问
     * 都失败在请求构造阶段，不如当作未配置——那样使用者看到的是"没有小助手"，
     * 而不是一个每次提问都报错的成员。</p>
     *
     * <p>接口地址同理：{@code file:///etc/passwd} 这类地址在构造时就已判为不可用
     * （见 {@link #isUsableEndpoint(String)}），配置错了就不启用，
     * 而不是等到每次提问才把一句英文异常回给使用者。</p>
     *
     * @return 已配置且可用返回 true
     */
    public boolean isEnabled() {
        return apiUrlUsable && !botId.isEmpty() && !token.isEmpty() && isHeaderValueSafe(token);
    }

    /**
     * 判断接口地址是否可用。
     *
     * <p>只接受 http 与 https：其余协议（{@code file}、{@code ftp}、{@code jar} 等）
     * 要么根本发不出请求，要么会去读本机资源，都不是"调用外部智能体平台"该做的事。
     * 主机名也要求非空——{@code http:///path} 能通过 URI 解析，但不可能连得通。</p>
     *
     * @param url 配置中的接口地址
     * @return 可作为 HTTP 接口地址返回 true
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
            // URI.create 对非法格式抛的就是它：当作"不可用"，交由启动日志说明
            return false;
        }
    }

    /**
     * 取出接口地址的协议名，仅供日志使用。
     *
     * <p>刻意只暴露协议：地址本身可能带查询串形式的凭据，而协议名不可能含令牌。</p>
     *
     * @param url 配置中的接口地址
     * @return 小写协议名；缺失时返回"缺失"，无法解析时返回"无法解析"
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
     * 提交一次提问，回答就绪后回调。
     *
     * <p>本方法立即返回，不阻塞调用线程。回调在问答线程池的线程上执行，
     * 实现方若要操作界面需自行切到事件分发线程。</p>
     *
     * <p>同一提问者的两次提问至少间隔 {@link #MIN_ASK_INTERVAL_MS}：间隔之内直接回调一句中文提示，
     * 既不占用工作线程，也不会产生对外计费调用。未启用、被限频、队列已满这三种"没提交成功"
     * 的情况同样走回调，调用方看到的永远是一条消息而不是沉默。</p>
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
        if (!allowAsk(asker)) {
            // 限频判定放在提交任务之前：被挡下的提问不该占用工作线程与队列名额
            sink.accept(asker, RATE_LIMITED_REPLY);
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
                    LOGGER.log(Level.WARNING, "智能体问答失败: " + asker + " - " + redact(e.getMessage()));
                    sink.accept(asker, sanitize("抱歉，这次没能取到回答：" + friendlyReason(e)));
                } catch (Throwable t) {
                    // Error（例如栈溢出、内存耗尽）不在上面两个分支的覆盖范围内。
                    // 少了这一层，工作线程会直接死掉且不产生任何回执，使用者面对的是一片沉默，
                    // 与"绝不静默丢弃"相矛盾；兜底回一句固定致歉，保证任何情况下都有回应。
                    // 只记录类型与经脱敏处理的消息：直接把 Throwable 交给日志会原样打印它的
                    // 报错文本，而那段文本有可能夹带访问令牌。
                    LOGGER.log(Level.SEVERE, "智能体问答遇到严重错误: " + asker + " - "
                            + redact(t.getClass().getName() + ": " + t.getMessage()));
                    sink.accept(asker, "抱歉，小助手内部出错，这次没能取到回答，请稍后再问一次。");
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.info(() -> "智能体问答队列已满，已拒绝: " + asker);
            sink.accept(asker, "现在提问的人有点多，请稍后再试。");
        }
    }

    /**
     * 判断本次提问是否通过按人限频。
     *
     * <p>用 {@link ConcurrentHashMap#compute} 完成"读上一次时刻、比较、写入新时刻"这一整段：
     * 拆成先 {@code get} 再 {@code put} 的话，同一个人快速双击发两条消息时，
     * 两条都会读到同一个旧时刻并双双通过——限频在最容易触发的场景下失效。</p>
     *
     * @param asker 提问者用户名
     * @return 本次允许提问返回 true
     */
    private boolean allowAsk(String asker) {
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean(true);
        // 用户名由调用方保证非空，这里的兜底只是不让 ConcurrentHashMap 因 null 键抛异常
        String key = asker == null ? "" : asker;
        lastAskAt.compute(key, (name, previous) -> {
            if (previous != null && now - previous < MIN_ASK_INTERVAL_MS) {
                allowed.set(false);
                return previous;
            }
            return now;
        });
        if (allowed.get()) {
            evictStaleAskers(now);
        }
        return allowed.get();
    }

    /**
     * 清理限频记录表中的过期条目，避免这张表只增不减。
     *
     * <p>只在条目数超过阈值时动手，且两次清理至少间隔一个限频周期：
     * 清理本身要遍历整张表，若每次提问都做，反而把限频的开销放大成每人一次全表扫描。</p>
     *
     * @param now 当前时刻（毫秒）
     */
    private void evictStaleAskers(long now) {
        if (lastAskAt.size() <= RATE_LIMIT_CLEANUP_THRESHOLD) {
            return;
        }
        long lastCleanup = lastRateLimitCleanup.get();
        if (now - lastCleanup < MIN_ASK_INTERVAL_MS || !lastRateLimitCleanup.compareAndSet(lastCleanup, now)) {
            return;
        }
        // 只删已经过了限频周期的记录：删早了等于放行一次超频提问
        lastAskAt.entrySet().removeIf(entry -> now - entry.getValue() >= MIN_ASK_INTERVAL_MS);
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
        // 用 ofInputStream 而不是 ofString：后者会把响应体无上限地堆进堆内存，
        // 对端持续吐数据就能把本进程撑爆（OOM 发生在 HttpClient 自己的线程上，
        // 提问者连一句回执都收不到）。这里拿到流，由 readBody 按上限读。
        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status != 200) {
            // 非成功状态不看正文，但必须把流关掉，否则连接会一直挂到超时
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
     * 按上限读取响应体并解码为 UTF-8 文本。
     *
     * <p>逐块读取并在超限时立刻停止：先读完整段再判断长度等于没有上限。
     * 超限抛出的是本类自己构造的中文 {@link IOException}，会经
     * {@link #friendlyReason(Exception)} 原样回给使用者，说明"内容过大"而不是一个堆栈。</p>
     *
     * @param stream 响应体输入流
     * @return 解码后的文本
     * @throws IOException 读取失败或内容超过 {@link #MAX_RESPONSE_BODY_BYTES} 时抛出
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

    /**
     * 静默关闭一个输入流。
     *
     * <p>关闭响应体流会同时释放底层连接；关闭本身失败不影响本次问答的结论，
     * 因此只吞掉异常，不覆盖真正的失败原因。</p>
     *
     * @param stream 待关闭的流，可为 null
     */
    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // 释放连接失败无法补救，也不该改写调用方正在处理的错误
        }
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
     * <p><b>未知异常一律不回显原始文本</b>：这类异常多来自 JDK 的参数校验，
     * 而它的报错会把整个请求头值抄进来——请求头里就有访问令牌（见 {@link #redact(String)}）。
     * 对一个"任何用户都能提一次问"的入口来说，这等于把管理员凭据发给使用者。</p>
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
        if (e instanceof IOException) {
            // 走到这里的 IOException 都是本类自己抛的中文原因，可以原样回显
            return redact(e.getMessage() == null ? "未知原因。" : e.getMessage());
        }
        return "智能体服务暂时不可用，请稍后再试。";
    }

    /**
     * 脱敏一段将要写进日志或回执的文本。
     *
     * <p>做三件事：把令牌值替换成 {@code ***}、剔除控制字符、截断到可读长度。
     * 它是"异常文本可能夹带凭据"这条链路的最后一道闸门，凡是把异常信息往外送的地方都要过一遍。</p>
     *
     * @param text 原始文本，可为 null
     * @return 可安全外送的文本；入参为 null 时返回 null
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
     * 判断令牌能否安全地放进 HTTP 头。
     *
     * <p>只接受可打印 ASCII（不含空格）：平台的令牌形如 {@code pat_xxx}，
     * 出现其它字符一定是复制时混入了换行、空格或全角字符。</p>
     *
     * @param value 待检查的取值
     * @return 可以放进请求头返回 true
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
     * <p>截断时若切点正好落在代理对中间，会留下一个孤立的高代理项——那不是一个合法字符，
     * 客户端渲染、日志与数据库编码都会因此出问题。因此切点前一位是高代理项时多退一个码元，
     * 让被截掉的是整个字符而不是它的一半；退让后总长只会比上限更短。</p>
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
            int keep = Constants.MESSAGE_MAX_LENGTH - 3;
            if (keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1))) {
                // 切点前一位是高代理项，说明它和下一个码元本是一个字符，整体丢弃
                keep--;
            }
            text = text.substring(0, keep) + "...";
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

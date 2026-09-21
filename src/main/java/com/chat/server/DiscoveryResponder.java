package com.chat.server;

import com.chat.common.Config;
import com.chat.common.Constants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UDP 局域网发现应答器。
 *
 * <p>职责：监听广播端口，收到客户端发来的 {@code DISCOVER} 报文后，
 * 单播回复 {@code ANNOUNCE|服务器IP|TCP端口|在线人数}，
 * 使客户端无需手工输入 IP 即可在局域网内自动找到聊天服务器。</p>
 *
 * <p>为什么用 UDP 而不是让客户端扫描网段：</p>
 * <ul>
 *   <li>UDP 广播一次即可触达全部主机，成本恒定；网段扫描需要遍历 254 个地址，慢且容易被防火墙拦截；</li>
 *   <li>发现失败时客户端仍可手动输入 IP，因此 UDP 只是“加速器”而非唯一路径，天然容错。</li>
 * </ul>
 *
 * <p>安全考量：发现端口对局域网完全开放，任何主机都能发包，因此应答器只做“最小暴露”处理——
 * 只认严格符合固定前缀与地址格式的报文，并按来源 IP 限流。报文里不含服务器内部状态，
 * 也不执行任何耗时的解析动作；被丢弃的恶意包不会影响正常客户端的发现。</p>
 *
 * <p>线程模型：单个守护线程循环接收，收到请求立即回复，不做任何耗时处理。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class DiscoveryResponder {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".DiscoveryResponder");

    /** 客户端发现请求的固定内容 */
    public static final String DISCOVER_REQUEST = "LANCHAT_DISCOVER";

    /** 服务器应答前缀 */
    public static final String ANNOUNCE_PREFIX = "LANCHAT_ANNOUNCE";

    /** 应答报文分隔符 */
    public static final String ANNOUNCE_SEPARATOR = "|";

    /** 请求报文长度上限（字节）：合法请求只有固定前缀的长度，超出即视为畸形包 */
    private static final int MAX_REQUEST_BYTES = 64;

    /** 应答报文长度上限（字符）：四个字段均为短文本，超过该值说明对端实现异常或恶意构造 */
    private static final int MAX_ANNOUNCE_LENGTH = 256;

    /**
     * 单个来源 IP 的令牌补充速率：5 次/秒。
     *
     * <p>取值依据：正常客户端一次发现只发 1 到 2 个包（每个广播地址一个，外加一次回环单播），
     * 秒级 5 次对演示、多网卡机器与连续点击“刷新”都绰绰有余；而放大攻击需要成千上万的请求，
     * 该速率足以把单源放大倍数压到可忽略的量级。</p>
     */
    private static final double RATE_LIMIT_PER_SECOND = 5.0;

    /**
     * 单源突发容量：10 个请求。
     *
     * <p>取速率的两倍，允许客户端在极短时间内（例如多个网卡同时广播）突发若干包而不被误伤。</p>
     */
    private static final int RATE_LIMIT_BURST = 10;

    /**
     * 限流表条目数上限：触顶后先回收空闲条目，必要时淘汰最久未用条目，
     * 保证内存占用有界且后来的正常客户端不会被永久拒绝。
     *
     * <p>为什么这些阈值写成类内常量而不是配置项：它们是防御性取值而非业务参数，
     * 做成配置项既增加配置文件复杂度，又可能被误改成失效值（例如把速率配成 0 会导致
     * 自动发现整体不可用，且现象是“功能静默失效”，很难排查）。因此本轮不新增配置项。</p>
     */
    private static final int BUCKET_LIMIT = 256;

    /** IPv4 点分十进制格式校验，避免为畸形“IP”字符串创建限流条目 */
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})$");

    /** 服务器引用，用于读取在线人数与端口 */
    private final ChatServer server;

    /** 监听端口 */
    private final int port;

    /** 按来源 IP 的令牌桶，用于限制发现请求频率 */
    private final Map<String, SourceBucket> rateBuckets = new ConcurrentHashMap<>();

    /** 因超限被丢弃的请求计数，用于按次数输出告警，避免刷屏 */
    private long droppedRequests;

    /** 因限流表触顶而淘汰最久未用来源的累计次数，用于低频告警 */
    private long evictedBuckets;

    /** UDP 套接字 */
    private DatagramSocket socket;

    /** 监听线程 */
    private Thread worker;

    /** 运行标志 */
    private volatile boolean running;

    /**
     * 构造应答器。
     *
     * @param server 服务器实例
     * @param port   监听端口
     */
    public DiscoveryResponder(ChatServer server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * 启动应答线程。
     *
     * <p>套接字创建失败时只记录日志并放弃启动：自动发现属于辅助功能，
     * 其失败不应阻止 TCP 主服务运行。</p>
     */
    public void start() {
        try {
            socket = new DatagramSocket(port);
            socket.setBroadcast(true);
            socket.setSoTimeout(1000);
            running = true;
            worker = new Thread(this::loop, "chat-discovery-responder");
            worker.setDaemon(true);
            worker.start();
            LOGGER.info(() -> "UDP 发现应答器已启动，端口 " + port);
        } catch (SocketException e) {
            LOGGER.log(Level.WARNING, "UDP 发现应答器启动失败（端口可能被占用）: " + e.getMessage(), e);
        }
    }

    /**
     * 接收循环。
     *
     * <p>使用带超时的 {@code receive}，使线程在 {@link #shutdown()} 后最迟 1 秒内自然退出，
     * 而不需要依赖 {@code interrupt} 去打断阻塞的 IO 调用。</p>
     *
     * <p>循环内部对单个报文“只忽略不抛出”：畸形包、限流丢弃、回复失败都不会终止循环，
     * 否则攻击者只要构造一个能触发异常的包即可让自动发现整体失效。</p>
     */
    private void loop() {
        byte[] buffer = new byte[256];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                handle(packet);
            } catch (java.net.SocketTimeoutException e) {
                // 超时属于正常轮询，继续下一轮以检查 running 标志
                continue;
            } catch (IOException e) {
                if (running) {
                    // 单个包的回复失败（例如对端已关闭端口触发 ICMP 不可达）不应终止监听线程
                    LOGGER.log(Level.FINE, "UDP 应答异常（忽略并继续监听）: " + e.getMessage(), e);
                }
            }
        }
        LOGGER.info("UDP 发现应答器已退出");
    }

    /**
     * 处理单个已接收报文：校验格式、按来源限流，通过后才回复应答。
     *
     * @param packet 已接收的数据包
     * @throws IOException 回复失败时抛出，由接收循环忽略
     */
    private void handle(DatagramPacket packet) throws IOException {
        if (!isDiscoverRequest(packet)) {
            // 格式非法一律静默丢弃：回复会暴露服务存在，也会被用作反射放大
            return;
        }
        String sourceIp = packet.getAddress() == null ? null : packet.getAddress().getHostAddress();
        if (!allowRequest(sourceIp)) {
            return;
        }
        byte[] response = buildAnnouncement().getBytes(StandardCharsets.UTF_8);
        DatagramPacket reply = new DatagramPacket(response, response.length,
                packet.getAddress(), packet.getPort());
        socket.send(reply);
        LOGGER.fine(() -> "已响应发现请求: " + sourceIp);
    }

    /**
     * 判断报文是否为合法的发现请求。
     *
     * <p>三条硬性约束：长度不超过 {@link #MAX_REQUEST_BYTES}、按 UTF-8 解码后（允许尾部空白）
     * 与 {@link #DISCOVER_REQUEST} 完全相等、来源地址是点分十进制 IPv4。
     * 最后一条是为了防止伪造的“IP 字符串”在限流表中留下无意义条目。</p>
     *
     * @param packet 数据包
     * @return 合法返回 true
     */
    private boolean isDiscoverRequest(DatagramPacket packet) {
        int length = packet.getLength();
        if (length <= 0 || length > MAX_REQUEST_BYTES) {
            return false;
        }
        String request = new String(packet.getData(), packet.getOffset(), length, StandardCharsets.UTF_8);
        if (!DISCOVER_REQUEST.equals(request.trim())) {
            return false;
        }
        InetAddress address = packet.getAddress();
        return address != null && isIpv4(address.getHostAddress());
    }

    /**
     * 按来源 IP 做令牌桶限流。
     *
     * <p>桶按来源懒创建，仅在请求通过令牌判断后才保留；超限请求直接返回 false，
     * 由调用方静默丢弃。超限次数累积到一定量才输出一条 warning，
     * 避免被高频攻击刷出海量日志（日志洪水本身也是一种拒绝服务）。</p>
     *
     * <p>条目管理：表大小以 {@link #BUCKET_LIMIT} 为硬上限，触顶时先回收空闲条目，
     * 必要时淘汰最久未用的条目，因此后来的正常客户端不会被永久拒绝。
     * 这样即使攻击者伪造海量源 IP，内存占用也有界。</p>
     *
     * @param sourceIp 来源 IP
     * @return 允许处理返回 true
     */
    private boolean allowRequest(String sourceIp) {
        if (sourceIp == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        SourceBucket bucket = rateBuckets.get(sourceIp);
        if (bucket == null) {
            if (rateBuckets.size() >= BUCKET_LIMIT) {
                cleanupBuckets();
            }
            // 兜底防御：清理策略保证会腾出位置，此处仅在极端异常下避免越过上限继续扩张
            if (rateBuckets.size() >= BUCKET_LIMIT) {
                return false;
            }
            bucket = rateBuckets.computeIfAbsent(sourceIp, key -> new SourceBucket(now));
        }
        if (bucket.tryAcquire(now, RATE_LIMIT_PER_SECOND, RATE_LIMIT_BURST)) {
            return true;
        }
        logDropped();
        return false;
    }

    /**
     * 输出被限流丢弃的告警，每 100 次一条，防止日志洪水。
     *
     * <p>计数只在接收线程中访问，因此不需要同步。</p>
     */
    private void logDropped() {
        droppedRequests++;
        if (droppedRequests % 100 == 0) {
            LOGGER.warning(() -> "发现请求被限流丢弃，累计 " + droppedRequests + " 次（单来源上限 "
                    + (int) RATE_LIMIT_PER_SECOND + " 次/秒）");
        }
    }

    /**
     * 限流表的显式清理与回收策略。
     *
     * <p>两级处理，第一级总是优先：</p>
     * <ol>
     *   <li>回收“令牌已回满”的来源条目。回满的桶重建后与旧桶完全等价，回收不削弱限流，
     *       也不会让攻击者通过制造请求提前回收自己的条目；</li>
     *   <li>若没有任何回满的桶（说明表里的来源都还很活跃），则淘汰“最久未被使用”的条目。
     *       为什么必须做这一级：持续高频的来源其令牌补充时刻总落在同一个计时器刻度内，
     *       短时间内可能永远不满足“回满”条件，只靠第一级会让表被少数来源长期占满，
     *       进而把后来的正常客户端永久挡在门外；而淘汰最久未用条目后，被淘汰的来源
     *       下次请求会重新获得一个满桶，行为等价于未被限流。</li>
     * </ol>
     *
     * <p>代价说明：清理只在表触顶时触发，256 个条目的遍历对单个 UDP 接收线程可以忽略，
     * 因此不再额外引入时间节流，策略保持确定性（不依赖计时器精度）。</p>
     */
    private void cleanupBuckets() {
        Iterator<Map.Entry<String, SourceBucket>> iterator = rateBuckets.entrySet().iterator();
        int removed = 0;
        while (iterator.hasNext()) {
            SourceBucket bucket = iterator.next().getValue();
            if (bucket.isIdle(RATE_LIMIT_BURST)) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            int recalled = removed;
            LOGGER.fine(() -> "限流表已回收 " + recalled + " 个空闲来源条目");
            return;
        }
        if (evictLeastRecent()) {
            evictedBuckets++;
            if (evictedBuckets % 100 == 0) {
                LOGGER.warning(() -> "限流表触顶且无空闲条目，累计淘汰最久未用来源 " + evictedBuckets + " 次");
            }
        }
    }

    /**
     * 淘汰最久未被使用的一个来源条目。
     *
     * @return 确实淘汰了条目返回 true
     */
    private boolean evictLeastRecent() {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, SourceBucket> entry : rateBuckets.entrySet()) {
            long last = entry.getValue().lastUsed();
            if (last < oldest) {
                oldest = last;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey == null) {
            return false;
        }
        rateBuckets.remove(oldestKey);
        return true;
    }

    /**
     * 校验字符串是否为点分十进制 IPv4 地址。
     *
     * @param ip 待校验字符串
     * @return 合法返回 true
     */
    private static boolean isIpv4(String ip) {
        if (ip == null) {
            return false;
        }
        Matcher matcher = IPV4_PATTERN.matcher(ip);
        if (!matcher.matches()) {
            return false;
        }
        for (int group = 1; group <= 4; group++) {
            int value;
            try {
                value = Integer.parseInt(matcher.group(group));
            } catch (NumberFormatException e) {
                // 正则已限定为 1 到 3 位数字，这里只兜底防御，理论上不会触发
                return false;
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构造应答报文。
     *
     * @return 形如 {@code LANCHAT_ANNOUNCE|192.168.1.5|9527|3} 的文本
     */
    private String buildAnnouncement() {
        return ANNOUNCE_PREFIX + ANNOUNCE_SEPARATOR
                + server.localAddress() + ANNOUNCE_SEPARATOR
                + server.getPort() + ANNOUNCE_SEPARATOR
                + server.getUserManager().size();
    }

    /**
     * 关闭应答器。
     */
    public void shutdown() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        this.socket = null;
    }

    /**
     * 是否运行中。
     *
     * @return 运行中返回 true
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取监听端口。
     *
     * @return UDP 端口
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取本机广播地址列表的描述，用于界面提示。
     *
     * @return 形如 {@code 自动发现在端口 30000} 的提示文本
     */
    public static String describe() {
        return "自动发现端口 " + Config.discoveryPort();
    }

    /**
     * 解析客户端发现结果。
     *
     * <p>除固定前缀与字段数外，还校验各字段的取值范围：地址必须是可解析的 IP/主机名，
     * 端口落在 1 - 65535，在线人数非负。这样客户端拿到的一定是可直接用于连接的条目，
     * 不必在界面层重复防御。</p>
     *
     * @param announcement 应答报文
     * @return 长度为 3 的数组 {@code [ip, port, onlineCount]}；格式非法时返回 null
     */
    public static String[] parseAnnouncement(String announcement) {
        if (announcement == null || announcement.length() > MAX_ANNOUNCE_LENGTH) {
            return null;
        }
        if (!announcement.startsWith(ANNOUNCE_PREFIX)) {
            return null;
        }
        String[] parts = announcement.split("\\" + ANNOUNCE_SEPARATOR, -1);
        if (parts.length != 4) {
            return null;
        }
        String host = parts[1].trim();
        if (!isResolvableHost(host)) {
            return null;
        }
        int port = parseStrictInt(parts[2]);
        if (port < 1 || port > 65535) {
            return null;
        }
        int onlineCount = parseStrictInt(parts[3]);
        if (onlineCount < 0) {
            return null;
        }
        return new String[]{host, String.valueOf(port), String.valueOf(onlineCount)};
    }

    /**
     * 校验主机字段是否为可解析的地址。
     *
     * <p>使用 {@link InetAddress#getByName(String)} 而不是自行拼正则：它同时支持 IPv4 与 IPv6，
     * 且对明显非法的字符串（含空格、超长、非法字符）会直接抛异常。</p>
     *
     * @param host 主机字段
     * @return 可解析返回 true
     */
    private static boolean isResolvableHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 64) {
            return false;
        }
        try {
            return InetAddress.getByName(host) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 严格解析非负十进制整数。
     *
     * <p>不使用 {@link Integer#parseInt} 的宽松语义（接受 {@code +5} 与前导空白），
     * 以保证“同一应答只有一种合法写法”。</p>
     *
     * @param text 待解析文本
     * @return 解析结果；非法返回 -1
     */
    private static int parseStrictInt(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            // 纯数字仍解析失败只能是超出 int 范围，按非法值处理
            return -1;
        }
    }

    /**
     * 判断给定地址是否为回环地址，界面上据此提示“本机服务器”。
     *
     * @param host 主机地址
     * @return 回环地址返回 true
     */
    public static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 单个来源 IP 的令牌桶。
     *
     * <p>只记录“令牌数与上次补充时间”两个字段，按需计算补充量，
     * 因此不需要定时任务去刷新每个桶——空闲来源不会占用任何 CPU，
     * 也不会因为线程调度而影响发现响应速度。</p>
     *
     * <p>线程安全：仅被接收线程访问，但仍使用 {@code synchronized} 保护，
     * 以防将来出现多个接收线程时出现计数竞争。</p>
     */
    private static final class SourceBucket {

        /** 当前可用令牌数 */
        private double tokens;

        /** 上次补充令牌的时间戳（毫秒） */
        private long lastRefill;

        /**
         * 创建令牌数已满的桶。
         *
         * @param now 当前时间戳（毫秒）
         */
        private SourceBucket(long now) {
            this.tokens = RATE_LIMIT_BURST;
            this.lastRefill = now;
        }

        /**
         * 尝试取走一个令牌。
         *
         * @param now       当前时间戳（毫秒）
         * @param perSecond 每秒补充的令牌数
         * @param burst     桶容量
         * @return 取到令牌返回 true；令牌不足（应丢弃请求）返回 false
         */
        private synchronized boolean tryAcquire(long now, double perSecond, int burst) {
            refill(now, perSecond, burst);
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }

        /**
         * 判断桶是否已回满令牌、可以回收。
         *
         * <p>判定条件只有“令牌已回满”这一条，不附加时间条件：回满的桶重建后与旧桶完全等价，
         * 回收不会削弱限流，攻击者也无法通过伪造请求让条目被提前回收而绕过限流。
         * 若表触顶时没有任何回满的桶，则由 {@link #evictLeastRecent()} 兜底。</p>
         *
         * @param burst 桶容量
         * @return 可回收返回 true
         */
        private synchronized boolean isIdle(int burst) {
            return tokens >= burst;
        }

        /**
         * 获取最近一次被使用（或被补充令牌）的时间戳。
         *
         * <p>用于清理时挑选“最久未被使用”的条目，因此不需要加锁读取：
         * 该值只用于排序，读到相邻的旧值不影响淘汰结果的正确性。</p>
         *
         * @return 时间戳（毫秒）
         */
        private long lastUsed() {
            return lastRefill;
        }

        /**
         * 按流逝时间补充令牌，并按桶容量封顶。
         *
         * @param now       当前时间戳（毫秒）
         * @param perSecond 每秒补充的令牌数
         * @param burst     桶容量
         */
        private void refill(long now, double perSecond, int burst) {
            long elapsed = now - lastRefill;
            if (elapsed <= 0) {
                return;
            }
            tokens = Math.min(burst, tokens + elapsed * perSecond / 1000.0);
            lastRefill = now;
        }
    }
}

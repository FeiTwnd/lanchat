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
 * UDP 自动发现应答器，响应局域网内的发现请求并返回服务器地址、端口与在线人数。
 */
public class DiscoveryResponder {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".DiscoveryResponder");

    /** 发现请求的固定报文内容。 */
    public static final String DISCOVER_REQUEST = "LANCHAT_DISCOVER";

    /** 应答报文前缀。 */
    public static final String ANNOUNCE_PREFIX = "LANCHAT_ANNOUNCE";

    /** 应答报文字段分隔符。 */
    public static final String ANNOUNCE_SEPARATOR = "|";

    /** 发现请求允许的最大字节数。 */
    private static final int MAX_REQUEST_BYTES = 64;

    /** 应答报文允许的最大长度。 */
    private static final int MAX_ANNOUNCE_LENGTH = 256;

    /** 单个来源每秒允许的请求数。 */
    private static final double RATE_LIMIT_PER_SECOND = 5.0;

    /** 单个来源允许的突发请求数。 */
    private static final int RATE_LIMIT_BURST = 10;

    /** 限流表最多保留的来源数。 */
    private static final int BUCKET_LIMIT = 256;

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})$");

    private final ChatServer server;

    private final int port;

    private final Map<String, SourceBucket> rateBuckets = new ConcurrentHashMap<>();

    private long droppedRequests;

    private long evictedBuckets;

    private DatagramSocket socket;

    private Thread worker;

    private volatile boolean running;

    /**
     * 创建发现应答器。
     *
     * @param server 服务器实例
     * @param port UDP 监听端口
     */
    public DiscoveryResponder(ChatServer server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * 启动 UDP 监听线程。
     */
    public void start() {
        try {
            // 绑定 UDP 端口并允许广播
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

    /** 循环接收并处理发现请求。 */
    private void loop() {
        byte[] buffer = new byte[256];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                handle(packet);
            } catch (java.net.SocketTimeoutException e) {
                // 接收超时后继续监听
                continue;
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.FINE, "UDP 应答异常（忽略并继续监听）: " + e.getMessage(), e);
                }
            }
        }
        LOGGER.info("UDP 发现应答器已退出");
    }

    /** 校验请求并回送服务器应答。 */
    private void handle(DatagramPacket packet) throws IOException {
        if (!isDiscoverRequest(packet)) {
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

    /** 判断报文是否为合法的发现请求。 */
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

    /** 按来源地址做令牌桶限流。 */
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

    /** 累计丢弃次数并定期输出告警。 */
    private void logDropped() {
        droppedRequests++;
        if (droppedRequests % 100 == 0) {
            LOGGER.warning(() -> "发现请求被限流丢弃，累计 " + droppedRequests + " 次（单来源上限 "
                    + (int) RATE_LIMIT_PER_SECOND + " 次/秒）");
        }
    }

    /** 回收空闲的限流条目，必要时淘汰最久未用的来源。 */
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

    /** 淘汰最久未使用的限流条目。 */
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
                return false;
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    private String buildAnnouncement() {
        return ANNOUNCE_PREFIX + ANNOUNCE_SEPARATOR
                + server.localAddress() + ANNOUNCE_SEPARATOR
                + server.getPort() + ANNOUNCE_SEPARATOR
                + server.getUserManager().size();
    }

    /**
     * 停止应答器并关闭套接字。
     */
    public void shutdown() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        this.socket = null;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    /**
     * 返回发现端口的说明文字。
     *
     * @return 说明文字
     */
    public static String describe() {
        return "自动发现端口 " + Config.discoveryPort();
    }

    /**
     * 解析应答报文。
     *
     * @param announcement 报文内容
     * @return 包含主机、端口与在线人数的数组，格式非法时返回 null
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

    /** 解析非负整数，失败返回 -1。 */
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
            return -1;
        }
    }

    /**
     * 判断主机是否为回环地址。
     *
     * @param host 主机名或地址
     * @return 是回环地址返回 true，解析失败返回 false
     */
    public static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 记录单个来源的令牌桶状态。
     */
    private static final class SourceBucket {
        /** 当前可用令牌数。 */
        private double tokens;

        /** 上次补充令牌的时间戳，单位毫秒。 */
        private long lastRefill;

        private SourceBucket(long now) {
            this.tokens = RATE_LIMIT_BURST;
            this.lastRefill = now;
        }

        private synchronized boolean tryAcquire(long now, double perSecond, int burst) {
            refill(now, perSecond, burst);
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }

        private synchronized boolean isIdle(int burst) {
            return tokens >= burst;
        }

        private long lastUsed() {
            return lastRefill;
        }

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

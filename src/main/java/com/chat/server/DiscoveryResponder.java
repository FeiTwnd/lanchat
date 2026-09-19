package com.chat.server;

import com.chat.common.Config;
import com.chat.common.Constants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    /** 服务器引用，用于读取在线人数与端口 */
    private final ChatServer server;

    /** 监听端口 */
    private final int port;

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
     */
    private void loop() {
        byte[] buffer = new byte[256];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                String request = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8);
                if (!DISCOVER_REQUEST.equals(request.trim())) {
                    continue;
                }
                byte[] response = buildAnnouncement().getBytes(StandardCharsets.UTF_8);
                DatagramPacket reply = new DatagramPacket(response, response.length,
                        packet.getAddress(), packet.getPort());
                socket.send(reply);
                LOGGER.fine(() -> "已响应发现请求: " + packet.getAddress().getHostAddress());
            } catch (java.net.SocketTimeoutException e) {
                // 超时属于正常轮询，继续下一轮以检查 running 标志
                continue;
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.WARNING, "UDP 应答异常: " + e.getMessage(), e);
                }
                break;
            }
        }
        LOGGER.info("UDP 发现应答器已退出");
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
     * @param announcement 应答报文
     * @return 长度为 3 的数组 {@code [ip, port, onlineCount]}；格式非法时返回 null
     */
    public static String[] parseAnnouncement(String announcement) {
        if (announcement == null || !announcement.startsWith(ANNOUNCE_PREFIX)) {
            return null;
        }
        String[] parts = announcement.split("\\" + ANNOUNCE_SEPARATOR, -1);
        if (parts.length < 4) {
            return null;
        }
        return new String[]{parts[1], parts[2], parts[3]};
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
}

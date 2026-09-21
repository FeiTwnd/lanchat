package com.chat.client;

import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.server.DiscoveryResponder;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 局域网服务器自动发现客户端。
 *
 * <p>职责：向本网段广播 {@code LANCHAT_DISCOVER} 报文，收集 {@link DiscoveryResponder}
 * 返回的应答，解析出可用的服务器地址列表，供登录界面一键填充。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>向每个网卡的广播地址各发一次，避免多网卡机器只走默认网卡导致发现失败；</li>
 *   <li>同时向 255.255.255.255 发送一次，兼容部分未正确枚举广播地址的环境；</li>
 *   <li>在规定超时窗口内持续接收，用去重集合避免同一次发现出现重复条目；</li>
 *   <li>发现失败返回空列表而非抛异常——自动发现只是便捷入口，用户仍可手动输入 IP。</li>
 * </ul>
 *
 * <p>健壮性：接收窗口内收到的任何报文都可能是伪造/畸形的，因此解析失败一律忽略，
 * 既不中断接收循环也不放入结果集；结果规模另设上限，防止同一网段内的异常主机
 * 用海量伪造应答把内存和界面撑爆。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class DiscoveryClient {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".DiscoveryClient");

    /** 接收缓冲区大小（字节），与应答长度上限保持同一量级 */
    private static final int RECEIVE_BUFFER_SIZE = 256;

    /** 单次发现接受的最大服务器条目数，超出后不再收录新条目 */
    private static final int MAX_SERVER_ENTRIES = 64;

    /** 发现结果条目：服务器地址与在线人数 */
    public static final class ServerInfo {

        /** 服务器地址 */
        private final String host;

        /** 服务器 TCP 端口 */
        private final int port;

        /** 应答时的在线人数 */
        private final int onlineCount;

        /**
         * 构造发现结果。
         *
         * @param host        服务器地址
         * @param port        TCP 端口
         * @param onlineCount 在线人数
         */
        public ServerInfo(String host, int port, int onlineCount) {
            this.host = host;
            this.port = port;
            this.onlineCount = onlineCount;
        }

        /**
         * 获取服务器地址。
         *
         * @return 主机地址
         */
        public String getHost() {
            return host;
        }

        /**
         * 获取端口。
         *
         * @return TCP 端口
         */
        public int getPort() {
            return port;
        }

        /**
         * 获取在线人数。
         *
         * @return 在线人数
         */
        public int getOnlineCount() {
            return onlineCount;
        }

        /**
         * 生成界面展示文本。
         *
         * @return 形如 {@code 192.168.1.5:9527（在线 3 人，本机）} 的文本
         */
        public String describe() {
            String suffix = DiscoveryResponder.isLoopback(host) ? "，本机服务器" : "";
            return host + ":" + port + "（在线 " + onlineCount + " 人" + suffix + "）";
        }

        /**
         * 以地址与端口判定唯一性。
         *
         * @param obj 比较对象
         * @return 相同返回 true
         */
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ServerInfo)) {
                return false;
            }
            ServerInfo other = (ServerInfo) obj;
            return port == other.port && host.equals(other.host);
        }

        /**
         * 计算散列码。
         *
         * @return 散列码
         */
        @Override
        public int hashCode() {
            return host.hashCode() * 31 + port;
        }

        /**
         * 输出条目信息。
         *
         * @return 展示文本
         */
        @Override
        public String toString() {
            return describe();
        }
    }

    /**
     * 执行一次自动发现。
     *
     * @param timeoutMillis 等待应答的总时长（毫秒）
     * @return 服务器列表，按地址排序；未发现任何服务器时返回空列表
     */
    public List<ServerInfo> discover(int timeoutMillis) {
        int port = Config.discoveryPort();
        Set<ServerInfo> servers = new LinkedHashSet<>();
        byte[] payload = DiscoveryResponder.DISCOVER_REQUEST.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(Math.min(500, timeoutMillis));
            for (InetAddress address : broadcastAddresses()) {
                try {
                    socket.send(new DatagramPacket(payload, payload.length, address, port));
                } catch (IOException e) {
                    LOGGER.fine(() -> "向 " + address + " 广播失败: " + e.getMessage());
                }
            }
            sendLoopbackProbe(socket, payload, port, servers);
            collectResponses(socket, servers, timeoutMillis);
        } catch (SocketException e) {
            LOGGER.log(Level.WARNING, "自动发现失败（无法创建 UDP 套接字）: " + e.getMessage(), e);
        }
        List<ServerInfo> result = new ArrayList<>(servers);
        result.sort((a, b) -> a.getHost().compareTo(b.getHost()));
        LOGGER.info(() -> "自动发现完成，共找到 " + result.size() + " 台服务器");
        return result;
    }

    /**
     * 额外向回环地址发一次单播探测。
     *
     * <p>UDP 广播通常不会回到本机，而教室演示、开发自测与集成测试都常在同一台机器上
     * 同时运行服务器与客户端。补一次回环单播，可让"本机服务器"立即可被发现，
     * 无需依赖操作系统的广播回环行为。</p>
     *
     * @param socket  套接字
     * @param payload 请求报文
     * @param port    发现端口
     * @param servers 结果集合
     */
    private void sendLoopbackProbe(DatagramSocket socket, byte[] payload, int port, Set<ServerInfo> servers) {
        try {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            socket.send(new DatagramPacket(payload, payload.length, loopback, port));
            // 本机应答几乎瞬时到达，单独收一次并设置短超时，避免拖慢整体发现过程
            socket.setSoTimeout(300);
            byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            String text = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                    StandardCharsets.UTF_8);
            ServerInfo info = parse(text);
            if (info != null) {
                // 应答中的地址可能是 0.0.0.0 或非回环地址，本机场景统一归一化为回环地址更可靠
                servers.add(new ServerInfo(loopback.getHostAddress(), info.getPort(), info.getOnlineCount()));
            }
        } catch (SocketTimeoutException e) {
            LOGGER.fine("回环探测未获应答，继续等待广播应答");
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "回环探测失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在超时窗口内持续收集应答。
     *
     * <p>使用“剩余时间”递减的方式收敛等待，避免最后一个超时窗口无谓等待。
     * 循环内对每个报文单独判错：解析失败只是忽略该包，接收循环继续等待下一个应答，
     * 否则一台异常主机就能让本次发现提前结束。</p>
     *
     * @param socket        套接字
     * @param servers       结果集合
     * @param timeoutMillis 总等待时间
     */
    private void collectResponses(DatagramSocket socket, Set<ServerInfo> servers, int timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
                socket.receive(packet);
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8);
                ServerInfo info = parse(text);
                if (info == null) {
                    continue;
                }
                if (servers.size() >= MAX_SERVER_ENTRIES) {
                    LOGGER.warning("发现结果已达上限 " + MAX_SERVER_ENTRIES + " 条，忽略后续应答");
                    return;
                }
                servers.add(info);
            } catch (SocketTimeoutException e) {
                return;
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "接收发现应答异常: " + e.getMessage(), e);
                return;
            }
        }
    }

    /**
     * 解析应答报文。
     *
     * <p>长度与各字段范围由 {@link DiscoveryResponder#parseAnnouncement(String)} 统一校验，
     * 这里只负责把结果包装为条目；任何解析异常都不得向上传播，否则会打断接收线程的收集循环。</p>
     *
     * @param text 报文文本
     * @return 服务器信息；格式非法时返回 null
     */
    private ServerInfo parse(String text) {
        String[] parts;
        try {
            parts = DiscoveryResponder.parseAnnouncement(text);
        } catch (RuntimeException e) {
            // 防御性兜底：解析路径已做格式校验，此处仅保证异常不会终止发现流程
            LOGGER.log(Level.FINE, "解析发现应答失败: " + e.getMessage(), e);
            return null;
        }
        if (parts == null) {
            return null;
        }
        return new ServerInfo(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /**
     * 枚举本机所有广播地址。
     *
     * @return 广播地址列表；枚举失败时回退为有限广播地址 255.255.255.255
     */
    private List<InetAddress> broadcastAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (broadcast != null) {
                        addresses.add(broadcast);
                    }
                }
            }
        } catch (SocketException e) {
            LOGGER.log(Level.FINE, "网卡枚举失败，回退有限广播地址", e);
        }
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "有限广播地址解析失败", e);
        }
        return addresses.isEmpty() ? Collections.emptyList() : addresses;
    }

    /**
     * 阻塞式自动发现，供非界面场景（命令行、测试）使用。
     *
     * <p>界面调用方应把本方法放入后台线程或 {@code SwingWorker}，
     * 否则会阻塞事件分发线程导致界面假死。</p>
     *
     * @param timeoutMillis 超时时间
     * @return 服务器列表
     */
    public static List<ServerInfo> discoverOnce(int timeoutMillis) {
        return new DiscoveryClient().discover(timeoutMillis);
    }

    /**
     * 构造回环地址的服务器条目，供“本机启动服务器”场景直接使用。
     *
     * @param port TCP 端口
     * @return 服务器条目
     */
    public static ServerInfo loopback(int port) {
        return new ServerInfo("127.0.0.1", port, 0);
    }

    /**
     * 判断给定地址是否可用于连接测试。
     *
     * @param host 主机地址
     * @param port 端口
     * @return 在超时时间内可建立连接返回 true
     */
    public static boolean probe(String host, int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress(host, port), Constants.CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

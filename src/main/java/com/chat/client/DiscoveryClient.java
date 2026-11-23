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
 * 通过 UDP 广播发现局域网内正在运行的聊天服务器。
 */
public class DiscoveryClient {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".DiscoveryClient");

    /** 单个应答报文的接收缓冲区大小，单位字节。 */
    private static final int RECEIVE_BUFFER_SIZE = 256;

    /** 单次发现最多保留的服务器数量。 */
    private static final int MAX_SERVER_ENTRIES = 64;

    /**
     * 一条服务器发现结果，包含主机地址、端口和在线人数。
     */
    public static final class ServerInfo {
        private final String host;

        private final int port;

        private final int onlineCount;

        /**
         * 构造一条服务器信息。
         *
         * @param host 服务器主机地址
         * @param port 服务器端口
         * @param onlineCount 当前在线人数
         */
        public ServerInfo(String host, int port, int onlineCount) {
            this.host = host;
            this.port = port;
            this.onlineCount = onlineCount;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public int getOnlineCount() {
            return onlineCount;
        }

        /**
         * 返回用于界面展示的服务器描述文字。
         *
         * @return 主机、端口与在线人数的组合文字
         */
        public String describe() {
            String suffix = DiscoveryResponder.isLoopback(host) ? "，本机服务器" : "";
            return host + ":" + port + "（在线 " + onlineCount + " 人" + suffix + "）";
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ServerInfo)) {
                return false;
            }
            ServerInfo other = (ServerInfo) obj;
            return port == other.port && host.equals(other.host);
        }

        @Override
        public int hashCode() {
            return host.hashCode() * 31 + port;
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /**
     * 广播发现请求并收集应答。
     *
     * @param timeoutMillis 等待应答的总时长，单位毫秒
     * @return 按主机地址排序的服务器列表，未发现时为空列表
     */
    public List<ServerInfo> discover(int timeoutMillis) {
        int port = Config.discoveryPort();
        Set<ServerInfo> servers = new LinkedHashSet<>();
        byte[] payload = DiscoveryResponder.DISCOVER_REQUEST.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(Math.min(500, timeoutMillis));
            // 向每个广播地址发送一份发现请求
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
        // 按主机地址排序，保证界面中的列表顺序稳定
        result.sort((a, b) -> a.getHost().compareTo(b.getHost()));
        LOGGER.info(() -> "自动发现完成，共找到 " + result.size() + " 台服务器");
        return result;
    }

    /** 向回环地址发送一次探测请求。 */
    private void sendLoopbackProbe(DatagramSocket socket, byte[] payload, int port, Set<ServerInfo> servers) {
        try {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            socket.send(new DatagramPacket(payload, payload.length, loopback, port));

            // 回环探测只等 300 毫秒
            socket.setSoTimeout(300);
            byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            ServerInfo info = parse(packet);
            if (info != null) {
                servers.add(info);
            }
        } catch (SocketTimeoutException e) {
            LOGGER.fine("回环探测未获应答，继续等待广播应答");
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "回环探测失败: " + e.getMessage(), e);
        }
    }

    /** 在截止时间前接收应答，直到超时。 */
    private void collectResponses(DatagramSocket socket, Set<ServerInfo> servers, int timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
                socket.receive(packet);
                ServerInfo info = parse(packet);
                if (info == null) {
                    continue;
                }
                if (servers.size() >= MAX_SERVER_ENTRIES) {
                    LOGGER.warning("发现结果已达上限 " + MAX_SERVER_ENTRIES + " 条，忽略其余应答");
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

    /** 解析应答报文，并用报文的实际来源地址作为主机地址。 */
    private ServerInfo parse(DatagramPacket packet) {
        String text = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                StandardCharsets.UTF_8);
        ServerInfo announced = parse(text);
        if (announced == null) {
            return null;
        }
        InetAddress source = packet.getAddress();
        if (source == null) {
            return announced;
        }
        return new ServerInfo(source.getHostAddress(), announced.getPort(), announced.getOnlineCount());
    }

    /** 把应答文本解析为服务器信息，格式不合法时返回 null。 */
    private ServerInfo parse(String text) {
        String[] parts;
        try {
            parts = DiscoveryResponder.parseAnnouncement(text);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "解析发现应答失败: " + e.getMessage(), e);
            return null;
        }
        if (parts == null) {
            return null;
        }
        return new ServerInfo(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /** 枚举所有已启用网卡的广播地址，并补上有限广播地址。 */
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
     * 创建临时客户端并执行一次发现。
     *
     * @param timeoutMillis 等待应答的总时长，单位毫秒
     * @return 发现的服务器列表
     */
    public static List<ServerInfo> discoverOnce(int timeoutMillis) {
        return new DiscoveryClient().discover(timeoutMillis);
    }

    /**
     * 构造本机服务器信息。
     *
     * @param port 服务器端口
     * @return 主机固定为 127.0.0.1 的服务器信息
     */
    public static ServerInfo loopback(int port) {
        return new ServerInfo("127.0.0.1", port, 0);
    }

    /**
     * 尝试建立 TCP 连接，判断目标地址是否可连接。
     *
     * @param host 目标主机
     * @param port 目标端口
     * @return 连接成功返回 true
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

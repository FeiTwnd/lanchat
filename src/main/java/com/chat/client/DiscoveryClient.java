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
 * @author Java 课程设计
 * @version 1.0
 */
public class DiscoveryClient {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".DiscoveryClient");

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
     * 在超时窗口内持续收集应答。
     *
     * <p>使用“剩余时间”递减的方式收敛等待，避免最后一个超时窗口无谓等待。</p>
     *
     * @param socket        套接字
     * @param servers       结果集合
     * @param timeoutMillis 总等待时间
     */
    private void collectResponses(DatagramSocket socket, Set<ServerInfo> servers, int timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        byte[] buffer = new byte[256];
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
                socket.receive(packet);
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8);
                ServerInfo info = parse(text);
                if (info != null) {
                    servers.add(info);
                }
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
     * @param text 报文文本
     * @return 服务器信息；格式非法时返回 null
     */
    private ServerInfo parse(String text) {
        String[] parts = DiscoveryResponder.parseAnnouncement(text);
        if (parts == null) {
            return null;
        }
        try {
            return new ServerInfo(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            LOGGER.fine(() -> "发现应答数值非法: " + text);
            return null;
        }
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

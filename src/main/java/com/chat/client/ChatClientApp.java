package com.chat.client;

import com.chat.client.ui.LoginUI;
import com.chat.common.Constants;
import com.chat.server.ChatServer;

import javax.swing.SwingUtilities;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 客户端启动入口。
 *
 * <p>职责：解析命令行参数、初始化日志、以两种模式启动客户端：</p>
 * <ul>
 *   <li>{@code java com.chat.client.ChatClientApp} —— 图形界面模式（默认）；</li>
 *   <li>{@code java com.chat.client.ChatClientApp --local-server} —— 先在本机启动一个服务器，
 *       再打开登录界面，方便单机演示与调试验证；</li>
 *   <li>{@code java com.chat.client.ChatClientApp --help} —— 输出使用说明。</li>
 * </ul>
 *
 * <p>之所以提供 {@code --local-server}：课程演示常在同一台机器上同时展示服务器与多个客户端，
 * 手动先开服务器再开客户端容易遗漏，该参数可一键完成。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class ChatClientApp {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".ChatClientApp");

    /** 私有构造，禁止实例化入口类 */
    private ChatClientApp() {
    }

    /**
     * 程序入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        configureLogging();
        LOGGER.info(() -> Constants.APP_NAME + " " + Constants.APP_VERSION + " 客户端启动，Java "
                + System.getProperty("java.version"));
        for (String arg : args) {
            if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                printUsage();
                return;
            }
        }
        boolean localServer = contains(args, "--local-server");
        if (localServer) {
            boolean started = ChatServer.getInstance().start();
            if (started) {
                LOGGER.info("已在本机启动内置服务器，端口 " + ChatServer.getInstance().getPort());
                Runtime.getRuntime().addShutdownHook(new Thread(ChatServer.getInstance()::stop, "chat-stop"));
            } else {
                LOGGER.warning("内置服务器启动失败（端口可能被占用），将继续以普通客户端模式运行");
            }
        }
        SwingUtilities.invokeLater(() -> {
            LoginUI login = new LoginUI();
            login.setVisible(true);
        });
    }

    /**
     * 判断参数列表中是否包含指定参数。
     *
     * @param args 参数数组
     * @param flag 目标参数
     * @return 包含返回 true
     */
    private static boolean contains(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 配置日志级别与格式，保证关键操作在控制台可见。
     */
    private static void configureLogging() {
        try {
            System.setProperty("java.util.logging.SimpleFormatter.format",
                    "%1$tF %1$tT [%4$s] %3$s - %5$s%6$s%n");
            Logger root = Logger.getLogger("");
            root.setLevel(Level.INFO);
        } catch (SecurityException e) {
            // 受限环境下无法修改日志配置，属于可忽略情况
            System.err.println("日志配置失败: " + e.getMessage());
        }
    }

    /**
     * 输出使用说明。
     */
    private static void printUsage() {
        System.out.println(Constants.APP_NAME + " " + Constants.APP_VERSION);
        System.out.println("用法:");
        System.out.println("  java com.chat.client.ChatClientApp                 启动图形客户端");
        System.out.println("  java com.chat.client.ChatClientApp --local-server  先启动本机服务器再打开客户端");
        System.out.println("  java com.chat.client.ChatClientApp --help          显示本帮助");
        System.out.println("服务器启动方式:");
        System.out.println("  java com.chat.server.ChatServer                    启动带图形控制台的服务器");
    }
}

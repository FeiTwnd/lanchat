package com.chat.client;

import com.chat.client.ui.LoginUI;
import com.chat.common.Constants;
import com.chat.server.ChatServer;

import javax.swing.SwingUtilities;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 客户端启动入口，负责解析命令行参数并以图形界面方式启动客户端。
 */
public final class ChatClientApp {
    private static final Logger LOGGER = Logger.getLogger(Constants.LOGGER_NAME + ".ChatClientApp");

    private ChatClientApp() {
    }

    /**
     * 程序入口，处理命令行参数并打开登录窗口。
     *
     * @param args 命令行参数，支持 --local-server 与 --help
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
            // 先启动本机内置服务器，进程退出时随关闭钩子停止
            boolean started = ChatServer.getInstance().start();
            if (started) {
                LOGGER.info("已在本机启动内置服务器，端口 " + ChatServer.getInstance().getPort());
                Runtime.getRuntime().addShutdownHook(new Thread(ChatServer.getInstance()::stop, "chat-stop"));
            } else {
                LOGGER.warning("内置服务器启动失败（端口可能被占用），将继续以普通客户端模式运行");
            }
        }
        SwingUtilities.invokeLater(() -> {
            // 在事件分发线程中创建登录窗口
            LoginUI login = new LoginUI();
            login.setVisible(true);
        });
    }

    private static boolean contains(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    /** 设置日志输出格式并把根日志级别调为 INFO。 */
    private static void configureLogging() {
        try {
            System.setProperty("java.util.logging.SimpleFormatter.format",
                    "%1$tF %1$tT [%4$s] %3$s - %5$s%6$s%n");
            Logger root = Logger.getLogger("");
            root.setLevel(Level.INFO);
        } catch (SecurityException e) {
            System.err.println("日志配置失败: " + e.getMessage());
        }
    }

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

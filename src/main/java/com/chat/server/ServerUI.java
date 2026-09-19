package com.chat.server;

import com.chat.client.ui.BaseUI;
import com.chat.client.ui.theme.SkinButton;
import com.chat.client.ui.theme.Theme;
import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.common.User;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 服务器图形界面。
 *
 * <p>职责：为管理员提供服务器可视化控制台——查看运行状态、启停服务、
 * 观察在线用户、浏览实时日志。界面本身不包含任何业务逻辑，
 * 所有事件通过 {@link ServerObserver} 从服务器核心推送而来。</p>
 *
 * <p>线程安全：观察者回调发生在网络线程，界面更新统一通过
 * {@link BaseUI#onEdt(Runnable)} 切换到事件分发线程。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class ServerUI extends BaseUI implements ServerObserver {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250111L;

    /** 服务器实例 */
    private final transient ChatServer server;

    /** 在线用户表模型 */
    private final DefaultTableModel userTableModel = new DefaultTableModel(
            new String[]{"用户名", "昵称", "角色", "远端地址"}, 0) {
        /** 序列化版本号 */
        private static final long serialVersionUID = 20250112L;

        /**
         * 禁止直接编辑表格单元格。
         *
         * @param row    行号
         * @param column 列号
         * @return 恒定返回 false
         */
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    /** 日志文本区 */
    private final JTextArea logArea = new JTextArea();

    /** 状态标签 */
    private final JLabel statusLabel = new JLabel("服务器未启动");

    /** 启动按钮 */
    private final SkinButton startButton = new SkinButton("启动服务器", SkinButton.Kind.PRIMARY);

    /** 停止按钮 */
    private final SkinButton stopButton = new SkinButton("停止服务器", SkinButton.Kind.NORMAL);

    /** 在线人数标签 */
    private final JLabel onlineLabel = new JLabel("在线人数: 0");

    /** 定时刷新器，周期性同步在线人数等运行指标 */
    private final transient ScheduledExecutorService refresher =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "server-ui-refresh");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 构造服务器界面。
     *
     * @param server 服务器实例
     */
    public ServerUI(ChatServer server) {
        super(Constants.APP_NAME + " - 服务器控制台 " + Constants.APP_VERSION);
        this.server = server;
        initComponents();
        server.addObserver(this);
        setSize(860, 600);
        centerOnScreen();
        startRefresher();
    }

    /**
     * 组装界面组件。
     */
    private void initComponents() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("运行日志", new JScrollPane(logArea));
        tabs.addTab("在线用户", new JScrollPane(new JTable(userTableModel)));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildInfoPanel(), tabs);
        splitPane.setResizeWeight(0.25);
        splitPane.setDividerLocation(150);

        body().setLayout(new BorderLayout());
        body().add(splitPane, BorderLayout.CENTER);
        body().add(buildControlPanel(), BorderLayout.SOUTH);

        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        stopButton.setEnabled(false);
        appendLog("界面已就绪，点击“启动服务器”开始提供服务");
        appendLog("配置信息: TCP 端口 " + Config.serverPort() + "，"
                + DiscoveryResponder.describe() + "，配置加载 "
                + (Config.isLoaded() ? "成功" : "失败（使用默认值）"));
    }

    /**
     * 构建信息面板。
     *
     * @return 面板对象
     */
    private JPanel buildInfoPanel() {
        JPanel panel = new JPanel(new GridLayout(3, 1, 4, 4));
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createTitledBorder("服务器状态"));
        panel.add(statusLabel);
        panel.add(onlineLabel);
        JLabel hint = new JLabel("提示: 客户端启动后会自动发现本服务器，也可手动输入本机 IP");
        hint.setForeground(Theme.TEXT_WEAK);
        panel.add(hint);
        return panel;
    }

    /**
     * 构建控制按钮面板。
     *
     * @return 面板对象
     */
    private JPanel buildControlPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        panel.setBackground(Theme.BG);
        panel.add(startButton);
        panel.add(stopButton);
        SkinButton clearButton = new SkinButton("清空日志", SkinButton.Kind.NORMAL);
        clearButton.addActionListener(e -> logArea.setText(""));
        panel.add(clearButton);
        panel.setPreferredSize(new Dimension(100, 48));
        return panel;
    }

    /**
     * 启动服务器。
     */
    private void startServer() {
        boolean started = server.start();
        if (started) {
            statusLabel.setText("服务器运行中，监听端口 " + server.getPort());
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            appendLog("服务器启动成功，本机地址 " + server.localAddress());
        } else {
            statusLabel.setText("服务器启动失败");
            showError("服务器启动失败，请检查端口 " + server.getPort() + " 是否被占用");
            appendLog("服务器启动失败");
        }
    }

    /**
     * 停止服务器。
     */
    private void stopServer() {
        if (!confirm("确定要停止服务器吗？所有在线客户端将断开连接。")) {
            return;
        }
        server.stop();
        statusLabel.setText("服务器已停止");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        userTableModel.setRowCount(0);
        onlineLabel.setText("在线人数: 0");
    }

    /**
     * 启动定时刷新任务，周期性同步在线人数与用户列表。
     */
    private void startRefresher() {
        refresher.scheduleWithFixedDelay(() -> {
            if (server.isRunning()) {
                refreshUserTable();
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    /**
     * 刷新在线用户表格。
     */
    private void refreshUserTable() {
        List<User> users = server.getUserManager().onlineUsers();
        onEdt(() -> {
            userTableModel.setRowCount(0);
            for (User user : users) {
                ClientHandler handler = server.getUserManager().get(user.getUsername());
                userTableModel.addRow(new Object[]{
                        user.getUsername(),
                        user.getNickname(),
                        user.isAdmin() ? "管理员" : "普通用户",
                        handler == null ? "-" : handler.getRemoteAddress()});
            }
            onlineLabel.setText("在线人数: " + users.size());
        });
    }

    /**
     * 追加一行日志。
     *
     * @param message 日志内容
     */
    private void appendLog(String message) {
        onEdt(() -> {
            logArea.append(logLine(message) + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * 接收服务器事件并写入日志。
     *
     * @param type    事件类型
     * @param content 事件描述
     */
    @Override
    public void onEvent(EventType type, String content) {
        appendLog("[" + type + "] " + content);
        if (type == EventType.USER_ONLINE || type == EventType.USER_OFFLINE) {
            refreshUserTable();
        }
    }

    /**
     * 窗口关闭时释放资源并停止服务器。
     */
    @Override
    public void dispose() {
        refresher.shutdownNow();
        server.removeObserver(this);
        if (server.isRunning() && confirm("关闭控制台将同时停止服务器，是否继续？")) {
            server.stop();
        }
        super.dispose();
    }

    /**
     * 独立启动服务器界面的入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ServerUI(ChatServer.getInstance()).setVisible(true));
    }
}

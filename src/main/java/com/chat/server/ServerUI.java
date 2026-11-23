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
 * 服务器控制台界面，提供启动与停止控制、运行日志和在线用户列表。
 */
public class ServerUI extends BaseUI implements ServerObserver {
    private static final long serialVersionUID = 20250111L;

    private final transient ChatServer server;

    private final DefaultTableModel userTableModel = new DefaultTableModel(
            new String[]{"用户名", "昵称", "角色", "远端地址"}, 0) {
        private static final long serialVersionUID = 20250112L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    private final JTextArea logArea = new JTextArea();

    private final JLabel statusLabel = new JLabel("服务器未启动");

    private final SkinButton startButton = new SkinButton("启动服务器", SkinButton.Kind.PRIMARY);

    private final SkinButton stopButton = new SkinButton("停止服务器", SkinButton.Kind.NORMAL);

    private final JLabel onlineLabel = new JLabel("在线人数: 0");

    private final transient ScheduledExecutorService refresher =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "server-ui-refresh");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 创建服务器控制台。
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

    /** 构建界面组件并写入初始日志。 */
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

    /** 启动定时刷新任务。 */
    private void startRefresher() {
        refresher.scheduleWithFixedDelay(() -> {
            if (server.isRunning()) {
                refreshUserTable();
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    /** 刷新在线用户表与在线人数。 */
    private void refreshUserTable() {
        // 先取用户快照，再回到界面线程更新表格
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

            onlineLabel.setText("在线人数: " + server.getUserManager().size());
        });
    }

    private void appendLog(String message) {
        onEdt(() -> {
            logArea.append(logLine(message) + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * 把服务器事件写入日志，用户上下线时刷新用户表。
     *
     * @param type 事件类型
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
     * 关闭界面，必要时停止服务器。
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
     * 打开服务器控制台。
     *
     * @param args 命令行参数，未使用
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ServerUI(ChatServer.getInstance()).setVisible(true));
    }
}

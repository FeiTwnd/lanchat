package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.client.DiscoveryClient;
import com.chat.client.ui.theme.Glyphs;
import com.chat.client.ui.theme.SkinButton;
import com.chat.client.ui.theme.Theme;
import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.TextMessage;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 登录窗口，负责服务器地址与账号输入、连接登录、注册以及自动发现局域网服务器。
 */
public class LoginUI extends BaseUI implements ChatListener {
    private static final long serialVersionUID = 20250121L;

    private final JTextField hostField = new JTextField("127.0.0.1", 14);

    private final JTextField portField = new JTextField(String.valueOf(Config.serverPort()), 6);

    private final JTextField usernameField = new JTextField(14);

    private final JPasswordField passwordField = new JPasswordField(14);

    private final JTextField nicknameField = new JTextField(14);

    private final JLabel statusLabel = new JLabel("请输入账号信息或点击“自动发现服务器”");

    private final SkinButton loginButton = new SkinButton("登录", SkinButton.Kind.PRIMARY);

    private final SkinButton registerButton = new SkinButton("注册新账号", SkinButton.Kind.NORMAL);

    private final SkinButton discoverButton =
            new SkinButton("自动发现服务器", SkinButton.Kind.NORMAL);

    /** 登录请求等待服务端回执的同步锁。 */
    private transient volatile CountDownLatch loginLatch;

    private transient volatile boolean loginSucceeded;

    private transient volatile String loginFailure = "";

    /** 注册请求等待服务端回执的同步锁。 */
    private transient volatile CountDownLatch registerLatch;

    private transient volatile boolean registerSucceeded;

    private transient volatile String registerMessage = "";

    private transient ChatClient client;

    /** 客户端已交给主窗口，关闭本窗口时不再断开连接。 */
    private transient boolean sessionHandedOver;

    /**
     * 创建使用默认服务器地址与端口的登录窗口。
     */
    public LoginUI() {
        this(null, 0);
    }

    /**
     * 创建登录窗口并预填服务器信息。
     *
     * @param host 预填的服务器地址，为空时保持默认值
     * @param port 预填的端口，小于等于 0 时使用配置默认值
     */
    public LoginUI(String host, int port) {
        super(Constants.APP_NAME + " " + Constants.APP_VERSION + " - 登录");
        if (host != null && !host.trim().isEmpty()) {
            hostField.setText(host.trim());
        }
        if (port > 0) {
            portField.setText(String.valueOf(port));
        }
        initComponents();
        setSize(580, 470);
        centerOnScreen();
        getRootPane().setDefaultButton(loginButton);
    }

    private void initComponents() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Theme.CARD);
        form.setBorder(BorderFactory.createEmptyBorder(14, 18, 8, 18));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 6, 4, 6);
        constraints.anchor = GridBagConstraints.WEST;

        addRow(form, constraints, 0, "服务器地址:", hostField);
        addRow(form, constraints, 1, "服务器端口:", portField);
        addRow(form, constraints, 2, "用户名:", usernameField);
        addRow(form, constraints, 3, "密码:", passwordField);
        addRow(form, constraints, 4, "昵称（注册用）:", nicknameField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 10));
        buttons.setBackground(Theme.CARD);
        buttons.add(loginButton);
        buttons.add(registerButton);
        buttons.add(discoverButton);

        loginButton.addActionListener(e -> doLogin());
        registerButton.addActionListener(e -> doRegister());
        discoverButton.addActionListener(e -> doDiscover());

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(Theme.CARD);
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusLabel.setForeground(Theme.TEXT_WEAK);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 18, 8, 0));
        statusLabel.setPreferredSize(new Dimension(520, 24));

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(Theme.CARD);
        south.add(buttons, BorderLayout.CENTER);
        south.add(statusPanel, BorderLayout.SOUTH);

        body().setLayout(new BorderLayout());
        body().add(buildBrand(), BorderLayout.NORTH);
        body().add(form, BorderLayout.CENTER);
        body().add(south, BorderLayout.SOUTH);
    }

    private JPanel buildBrand() {
        JPanel brand = new JPanel(new BorderLayout(14, 0));
        brand.setBackground(Theme.PRIMARY);
        brand.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
        text.setOpaque(false);
        JLabel title = new JLabel(Constants.APP_NAME);
        title.setFont(Theme.font(18, Font.BOLD));
        title.setForeground(Color.WHITE);
        JLabel slogan = new JLabel("局域网即时通讯 · 私聊 / 群聊 / 文件传输");
        slogan.setFont(Theme.fontSmall());
        slogan.setForeground(new Color(0xE6F6FE));
        text.add(title);
        text.add(slogan);

        brand.add(new JLabel(Glyphs.logo(40)), BorderLayout.WEST);
        brand.add(text, BorderLayout.CENTER);
        return brand;
    }

    private void addRow(JPanel panel, GridBagConstraints constraints, int row, String label,
                        java.awt.Component field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.fill = GridBagConstraints.NONE;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);

        constraints.gridx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        panel.add(field, constraints);
    }

    /**
     * 校验输入并在后台线程完成连接与登录。
     */
    private void doLogin() {
        String host = hostField.getText().trim();
        String name = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            showError("端口必须是数字");
            return;
        }
        // 校验必填项
        if (host.isEmpty() || name.isEmpty()) {
            showError("服务器地址与用户名不能为空");
            return;
        }
        if (password.isEmpty()) {
            showError("密码不能为空");
            return;
        }
        setBusy(true, "正在连接服务器……");
        resetLoginState();
        createClient();

        // 后台连接服务器并登录
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return client.openConnection(host, port) && client.login(name, password);
            }

            @Override
            protected void done() {
                boolean requested = false;
                try {
                    requested = get();
                } catch (Exception e) {
                    loginFailure = "连接异常: " + e.getMessage();
                }
                if (!requested) {
                    // 连接失败时恢复按钮并关闭客户端
                    String reason = loginFailure.isEmpty() ? "无法连接服务器" : loginFailure;
                    setBusy(false, reason);
                    showError(loginFailure.isEmpty() ? "无法连接服务器，请检查地址与端口" : loginFailure);
                    cleanupClient();
                    return;
                }
                awaitLoginResult();
            }
        }.execute();
    }

    /**
     * 等待登录回执并处理成功或失败的结果。
     */
    private void awaitLoginResult() {
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    // 最多等待 6 秒登录回执
                    CountDownLatch latch = loginLatch;
                    return latch != null && latch.await(6, TimeUnit.SECONDS) && loginSucceeded;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            @Override
            protected void done() {
                boolean success = false;
                try {
                    success = get();
                } catch (Exception e) {
                    loginFailure = "登录响应异常";
                }
                if (success) {
                    openMainWindow();
                    return;
                }
                String reason = loginFailure.isEmpty() ? "登录超时或失败" : loginFailure;
                setBusy(false, reason);
                showError(reason);
                cleanupClient();
            }
        }.execute();
    }

    private void openMainWindow() {
        setBusy(false, "登录成功");
        ClientUI main = new ClientUI(client);
        main.setVisible(true);

        sessionHandedOver = true;
        dispose();
    }

    /**
     * 校验注册信息并在后台提交注册，成功后自动登录。
     */
    private void doRegister() {
        String host = hostField.getText().trim();
        String name = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());
        String nickname = nicknameField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            showError("端口必须是数字");
            return;
        }
        // 校验必填项
        if (host.isEmpty() || name.isEmpty() || password.isEmpty()) {
            showError("服务器地址、用户名与密码不能为空");
            return;
        }
        setBusy(true, "正在提交注册信息……");
        registerSucceeded = false;
        registerMessage = "";
        registerLatch = new CountDownLatch(1);
        createClient();

        // 后台连接服务器并发送注册请求
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                if (!client.openConnection(host, port)) {
                    return false;
                }
                client.sendRegister(name, password, nickname);
                try {
                    CountDownLatch latch = registerLatch;
                    if (latch != null) {
                        latch.await(6, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }

            @Override
            protected void done() {
                boolean connected = false;
                try {
                    connected = get();
                } catch (Exception e) {
                    registerMessage = "注册异常: " + e.getMessage();
                }
                if (!connected) {
                    setBusy(false, "无法连接服务器");
                    showError("无法连接服务器，请检查地址与端口");
                    cleanupClient();
                    return;
                }
                if (!registerSucceeded) {
                    String reason = registerMessage.isEmpty() ? "注册失败" : registerMessage;
                    setBusy(false, reason);
                    showError(reason);
                    cleanupClient();
                    return;
                }

                setBusy(true, "注册成功，正在自动登录……");
                showInfo("注册成功，正在自动登录……");
                // 注册成功后用同一账号登录
                resetLoginState();
                client.login(name, password);
                awaitLoginResult();
            }
        }.execute();
    }

    /**
     * 在后台广播搜索局域网服务器并让用户选择。
     */
    private void doDiscover() {
        setBusy(true, "正在广播搜索局域网服务器……");
        new SwingWorker<List<DiscoveryClient.ServerInfo>, Void>() {
            @Override
            protected List<DiscoveryClient.ServerInfo> doInBackground() {
                // 等待 2 秒收集服务器应答
                return DiscoveryClient.discoverOnce(2000);
            }

            @Override
            protected void done() {
                List<DiscoveryClient.ServerInfo> servers;
                try {
                    servers = get();
                } catch (Exception e) {
                    setBusy(false, "自动发现失败");
                    showError("自动发现失败: " + e.getMessage());
                    return;
                }
                if (servers.isEmpty()) {
                    setBusy(false, "未发现服务器，请手动填写地址");
                    showInfo("未发现在线服务器。\n\n请确认服务器已启动，或手动填写服务器 IP 地址。");
                    return;
                }
                if (servers.size() == 1) {
                    applyServer(servers.get(0));
                    setBusy(false, "已发现服务器: " + servers.get(0).describe());
                    return;
                }
                JList<DiscoveryClient.ServerInfo> list = new JList<>(servers.toArray(new DiscoveryClient.ServerInfo[0]));
                list.setSelectedIndex(0);
                list.setFont(FONT_NORMAL);
                int choice = JOptionPane.showConfirmDialog(LoginUI.this, new JScrollPane(list),
                        "发现 " + servers.size() + " 台服务器，请选择", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE);
                DiscoveryClient.ServerInfo selected = list.getSelectedValue();
                if (choice == JOptionPane.OK_OPTION && selected != null) {
                    applyServer(selected);
                    setBusy(false, "已选择服务器: " + selected.describe());
                } else {
                    setBusy(false, "已取消选择");
                }
            }
        }.execute();
    }

    private void applyServer(DiscoveryClient.ServerInfo info) {
        hostField.setText(info.getHost());
        portField.setText(String.valueOf(info.getPort()));
    }

    private void closePreviousClient() {
        if (client != null) {
            client.removeListener(this);
            client.close();
            client = null;
        }
    }

    private void resetLoginState() {
        loginSucceeded = false;
        loginFailure = "";
        loginLatch = new CountDownLatch(1);
    }

    private void createClient() {
        closePreviousClient();
        client = new ChatClient();
        client.addListener(this);
    }

    private void setBusy(boolean busy, String status) {
        onEdt(() -> {
            loginButton.setEnabled(!busy);
            registerButton.setEnabled(!busy);
            discoverButton.setEnabled(!busy);
            statusLabel.setText(status);
        });
    }

    private void cleanupClient() {
        if (client != null) {
            client.removeListener(this);
            client.close();
            client = null;
        }
        setBusy(false, statusLabel.getText());
    }

    /**
     * 处理登录结果、注册结果以及系统与错误消息。
     *
     * @param message 收到的消息
     */
    @Override
    public void onMessage(Message message) {
        if (message.getType() == MessageType.LOGIN_RESULT && message instanceof TextMessage) {
            // 登录结果写入等待状态并唤醒登录线程
            String[] result = ChatClient.parseLoginResult(((TextMessage) message).getContent());
            loginSucceeded = "1".equals(result[0]);
            loginFailure = loginSucceeded ? "" : result[1];
            if (loginLatch != null) {
                loginLatch.countDown();
            }
            return;
        }
        if (message.getType() == MessageType.REGISTER_RESULT && message instanceof TextMessage) {
            // 注册结果写入等待状态并唤醒注册线程
            String[] result = ChatClient.parseRegisterResult(((TextMessage) message).getContent());
            registerSucceeded = "1".equals(result[0]);
            registerMessage = result[1];
            if (registerLatch != null) {
                registerLatch.countDown();
            }
            return;
        }
        if (message.getType() == MessageType.SYSTEM || message.getType() == MessageType.ERROR) {
            // 系统消息与错误显示在状态栏
            onEdt(() -> {
                statusLabel.setText(message.getSummary());
                if (message.getType() == MessageType.ERROR) {
                    showError(message.getSummary());
                }
            });
        }
    }

    /**
     * 在状态栏显示连接状态变化。
     *
     * @param connected 当前是否已连接
     * @param reason 状态说明
     */
    @Override
    public void onConnectionChanged(boolean connected, String reason) {
        onEdt(() -> statusLabel.setText(reason));
    }

    /**
     * 关闭窗口，未交接给主窗口时断开客户端连接。
     */
    @Override
    public void dispose() {
        if (client != null) {
            client.removeListener(this);
            if (!sessionHandedOver) {
                client.close();
            }
        }
        super.dispose();
    }
}

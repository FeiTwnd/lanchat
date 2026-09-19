package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.client.DiscoveryClient;
import com.chat.common.Config;
import com.chat.common.Constants;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.TextMessage;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 登录窗口。
 *
 * <p>职责：程序入口界面。提供服务器地址填写（或自动发现）、登录、注册、
 * 修改密码等能力，登录成功后打开 {@link ClientUI} 主窗口。</p>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li>所有网络操作都在 {@link SwingWorker} 中执行，避免阻塞事件分发线程导致界面假死；</li>
 *   <li>登录结果通过 {@link ChatListener} 异步回调，用 {@link CountDownLatch} 与
 *       等待线程同步，兼顾“不卡界面”与“结果可判定”；</li>
 *   <li>自动发现使用 UDP 广播，失败时静默降级为手动填写。</li>
 * </ul>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class LoginUI extends BaseUI implements ChatListener {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250121L;

    /** 服务器地址输入框 */
    private final JTextField hostField = new JTextField("127.0.0.1", 14);

    /** 服务器端口输入框 */
    private final JTextField portField = new JTextField(String.valueOf(Config.serverPort()), 6);

    /** 用户名输入框 */
    private final JTextField usernameField = new JTextField(14);

    /** 密码输入框 */
    private final JPasswordField passwordField = new JPasswordField(14);

    /** 昵称输入框（注册时可填） */
    private final JTextField nicknameField = new JTextField(14);

    /** 状态标签 */
    private final JLabel statusLabel = new JLabel("请输入账号信息或点击“自动发现服务器”");

    /** 登录按钮 */
    private final JButton loginButton = new JButton("登录");

    /** 注册按钮 */
    private final JButton registerButton = new JButton("注册新账号");

    /** 发现按钮 */
    private final JButton discoverButton = new JButton("自动发现服务器");

    /** 登录结果等待锁 */
    private transient CountDownLatch loginLatch;

    /** 登录是否成功 */
    private transient volatile boolean loginSucceeded;

    /** 登录失败原因 */
    private transient volatile String loginFailure = "";

    /** 注册结果等待锁 */
    private transient CountDownLatch registerLatch;

    /** 注册是否成功 */
    private transient volatile boolean registerSucceeded;

    /** 注册结果说明 */
    private transient volatile String registerMessage = "";

    /** 当前客户端实例 */
    private transient ChatClient client;

    /**
     * 会话所有权是否已交接给主窗口。
     *
     * <p>登录成功后连接由 {@link ClientUI} 接管，此时登录窗口的关闭动作只应释放窗口自身，
     * 若仍然关闭连接会把刚建立的会话一并断开，导致主窗口一出现就提示连接已断开。</p>
     */
    private transient boolean sessionHandedOver;

    /**
     * 构造登录窗口。
     */
    public LoginUI() {
        super(Constants.APP_NAME + " " + Constants.APP_VERSION + " - 登录");
        initComponents();
        setSize(560, 380);
        centerOnScreen();
        getRootPane().setDefaultButton(loginButton);
    }

    /**
     * 组装界面组件。
     */
    private void initComponents() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 6, 4, 6);
        constraints.anchor = GridBagConstraints.WEST;

        addRow(form, constraints, 0, "服务器地址:", hostField);
        addRow(form, constraints, 1, "服务器端口:", portField);
        addRow(form, constraints, 2, "用户名:", usernameField);
        addRow(form, constraints, 3, "密码:", passwordField);
        addRow(form, constraints, 4, "昵称（注册用）:", nicknameField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 6));
        buttons.add(loginButton);
        buttons.add(registerButton);
        buttons.add(discoverButton);
        JButton passwordButton = new JButton("修改密码");
        passwordButton.addActionListener(e -> changePassword());
        buttons.add(passwordButton);

        loginButton.addActionListener(e -> doLogin());
        registerButton.addActionListener(e -> doRegister());
        discoverButton.addActionListener(e -> doDiscover());

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.WEST);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        add(statusPanel, BorderLayout.NORTH);
        statusLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 0, 0));
        statusLabel.setPreferredSize(new Dimension(520, 28));
    }

    /**
     * 向表单追加一行“标签 + 输入框”。
     *
     * @param panel       表单面板
     * @param constraints 布局约束
     * @param row         行号
     * @param label       标签文本
     * @param field       输入组件
     */
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
     * 执行登录：后台建立连接并等待登录结果。
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
        if (host.isEmpty() || name.isEmpty()) {
            showError("服务器地址与用户名不能为空");
            return;
        }
        if (password.isEmpty()) {
            showError("密码不能为空");
            return;
        }
        setBusy(true, "正在连接服务器……");
        loginSucceeded = false;
        loginFailure = "";
        loginLatch = new CountDownLatch(1);
        client = new ChatClient();
        client.addListener(this);

        new SwingWorker<Boolean, Void>() {
            /**
             * 后台线程执行连接。
             *
             * @return 是否连接成功
             */
            @Override
            protected Boolean doInBackground() {
                return client.connect(host, port, name, password);
            }

            /**
             * 连接动作完成后等待服务器返回登录结果。
             *
             * @param connected 连接是否成功
             */
            @Override
            protected void done() {
                boolean connected = false;
                try {
                    connected = get();
                } catch (Exception e) {
                    loginFailure = "连接异常: " + e.getMessage();
                }
                if (!connected) {
                    setBusy(false, loginFailure.isEmpty() ? "无法连接服务器" : loginFailure);
                    showError(loginFailure.isEmpty() ? "无法连接服务器，请检查地址与端口" : loginFailure);
                    cleanupClient();
                    return;
                }
                new SwingWorker<Boolean, Void>() {
                    /**
                     * 后台等待登录结果。
                     *
                     * @return 是否登录成功
                     */
                    @Override
                    protected Boolean doInBackground() {
                        try {
                            return loginLatch.await(6, TimeUnit.SECONDS) && loginSucceeded;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }

                    /**
                     * 登录成功则打开主窗口。
                     *
                     * @param success 是否成功
                     */
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
                        } else {
                            String reason = loginFailure.isEmpty() ? "登录超时或失败" : loginFailure;
                            setBusy(false, reason);
                            showError(reason);
                            cleanupClient();
                        }
                    }
                }.execute();
            }
        }.execute();
    }

    /**
     * 打开主窗口并关闭登录窗口。
     */
    private void openMainWindow() {
        setBusy(false, "登录成功");
        ClientUI main = new ClientUI(client);
        main.setVisible(true);
        // 先标记所有权已交接，再关闭登录窗口，避免 dispose() 把正在使用的连接关掉
        sessionHandedOver = true;
        dispose();
    }

    /**
     * 执行注册：需要先建立连接，注册成功后自动登录。
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
        if (host.isEmpty() || name.isEmpty() || password.isEmpty()) {
            showError("服务器地址、用户名与密码不能为空");
            return;
        }
        setBusy(true, "正在提交注册信息……");
        registerSucceeded = false;
        registerMessage = "";
        registerLatch = new CountDownLatch(1);
        client = new ChatClient();
        client.addListener(this);

        new SwingWorker<Boolean, Void>() {
            /**
             * 后台建立连接并发送注册请求。
             *
             * @return 是否连接成功
             */
            @Override
            protected Boolean doInBackground() {
                if (!client.connect(host, port, name, password)) {
                    return false;
                }
                client.sendRegister(name, password, nickname);
                try {
                    registerLatch.await(6, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }

            /**
             * 根据注册结果显示提示。
             */
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
                if (registerSucceeded) {
                    setBusy(false, "注册成功，正在登录……");
                    // 注册成功后直接用同一连接登录，减少用户一次输入
                    client.send(loginMessageOf(usernameField.getText().trim(),
                            new String(passwordField.getPassword())));
                    loginSucceeded = false;
                    loginFailure = "";
                    loginLatch = new CountDownLatch(1);
                    showInfo("注册成功，正在自动登录……");
                } else {
                    String reason = registerMessage.isEmpty() ? "注册失败" : registerMessage;
                    setBusy(false, reason);
                    showError(reason);
                    cleanupClient();
                }
            }
        }.execute();
    }

    /**
     * 构造登录请求消息（供注册成功后自动登录复用）。
     *
     * @param name     用户名
     * @param password 密码
     * @return 登录消息
     */
    private TextMessage loginMessageOf(String name, String password) {
        TextMessage message = com.chat.common.ChatMessageFactory.text(name, Constants.SYSTEM_SENDER,
                name + "|" + password, MessageType.TEXT_PRIVATE);
        message.setType(MessageType.LOGIN);
        return message;
    }

    /**
     * 自动发现局域网服务器。
     */
    private void doDiscover() {
        setBusy(true, "正在广播搜索局域网服务器……");
        new SwingWorker<List<DiscoveryClient.ServerInfo>, Void>() {
            /**
             * 后台执行 UDP 发现。
             *
             * @return 服务器列表
             */
            @Override
            protected List<DiscoveryClient.ServerInfo> doInBackground() {
                return DiscoveryClient.discoverOnce(2000);
            }

            /**
             * 展示发现结果供用户选择。
             */
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

    /**
     * 把发现的服务器信息填入表单。
     *
     * @param info 服务器信息
     */
    private void applyServer(DiscoveryClient.ServerInfo info) {
        hostField.setText(info.getHost());
        portField.setText(String.valueOf(info.getPort()));
    }

    /**
     * 修改密码：需先建立连接，通过 PASSWORD_CHANGE 消息由服务器校验。
     */
    private void changePassword() {
        String host = hostField.getText().trim();
        String name = usernameField.getText().trim();
        String oldPassword = new String(passwordField.getPassword());
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            showError("端口必须是数字");
            return;
        }
        if (host.isEmpty() || name.isEmpty() || oldPassword.isEmpty()) {
            showError("请先填写服务器地址、用户名与当前密码");
            return;
        }
        String newPassword = JOptionPane.showInputDialog(this, "请输入新密码（"
                + Constants.PASSWORD_MIN_LENGTH + "-" + Constants.PASSWORD_MAX_LENGTH + " 位）:",
                "修改密码", JOptionPane.QUESTION_MESSAGE);
        if (newPassword == null || newPassword.isEmpty()) {
            return;
        }
        setBusy(true, "正在提交密码修改请求……");
        new SwingWorker<Boolean, Void>() {
            /**
             * 后台连接并发送修改密码请求。
             *
             * @return 是否连接成功
             */
            @Override
            protected Boolean doInBackground() {
                if (!client.connect(host, port, name, oldPassword)) {
                    return false;
                }
                TextMessage message = com.chat.common.ChatMessageFactory.text(name,
                        Constants.SYSTEM_SENDER, oldPassword + "|" + newPassword, MessageType.TEXT_PRIVATE);
                message.setType(MessageType.PASSWORD_CHANGE);
                client.send(message);
                return true;
            }

            /**
             * 提示用户查看服务器回执。
             */
            @Override
            protected void done() {
                boolean connected = false;
                try {
                    connected = get();
                } catch (Exception e) {
                    // 连接失败原因会在监听器中提示，这里只需复位界面状态
                    connected = false;
                }
                setBusy(false, connected ? "密码修改请求已发送，请查看提示" : "无法连接服务器");
                if (connected) {
                    showInfo("密码修改请求已发送。\n\n若原密码正确，服务器会返回“密码修改成功”，"
                            + "随后请使用新密码登录。");
                } else {
                    showError("无法连接服务器，请检查地址与端口");
                }
            }
        }.execute();
    }

    /**
     * 设置界面忙碌状态。
     *
     * @param busy   是否忙碌
     * @param status 状态文本
     */
    private void setBusy(boolean busy, String status) {
        onEdt(() -> {
            loginButton.setEnabled(!busy);
            registerButton.setEnabled(!busy);
            discoverButton.setEnabled(!busy);
            statusLabel.setText(status);
        });
    }

    /**
     * 释放客户端资源。
     */
    private void cleanupClient() {
        if (client != null) {
            client.removeListener(this);
            client.close();
            client = null;
        }
        setBusy(false, statusLabel.getText());
    }

    /**
     * 处理服务器消息：分别唤醒登录或注册等待线程。
     *
     * @param message 消息
     */
    @Override
    public void onMessage(Message message) {
        if (message.getType() == MessageType.LOGIN_RESULT && message instanceof TextMessage) {
            String[] result = ChatClient.parseLoginResult(((TextMessage) message).getContent());
            loginSucceeded = "1".equals(result[0]);
            loginFailure = loginSucceeded ? "" : result[1];
            if (loginLatch != null) {
                loginLatch.countDown();
            }
            return;
        }
        if (message.getType() == MessageType.REGISTER_RESULT && message instanceof TextMessage) {
            String[] result = ChatClient.parseRegisterResult(((TextMessage) message).getContent());
            registerSucceeded = "1".equals(result[0]);
            registerMessage = result[1];
            if (registerLatch != null) {
                registerLatch.countDown();
            }
            return;
        }
        if (message.getType() == MessageType.SYSTEM || message.getType() == MessageType.ERROR) {
            onEdt(() -> {
                statusLabel.setText(message.getSummary());
                if (message.getType() == MessageType.ERROR) {
                    showError(message.getSummary());
                }
            });
        }
    }

    /**
     * 连接状态变化：登录前断开时给出明确提示。
     *
     * @param connected 是否连接
     * @param reason    原因
     */
    @Override
    public void onConnectionChanged(boolean connected, String reason) {
        onEdt(() -> statusLabel.setText(reason));
    }

    /**
     * 窗口关闭时释放资源。
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

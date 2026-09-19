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
    private final SkinButton loginButton = new SkinButton("登录", SkinButton.Kind.PRIMARY);

    /** 注册按钮 */
    private final SkinButton registerButton = new SkinButton("注册新账号", SkinButton.Kind.NORMAL);

    /** 发现按钮 */
    private final SkinButton discoverButton =
            new SkinButton("自动发现服务器", SkinButton.Kind.NORMAL);

    /** 登录结果等待锁 */
    private transient volatile CountDownLatch loginLatch;

    /** 登录是否成功 */
    private transient volatile boolean loginSucceeded;

    /** 登录失败原因 */
    private transient volatile String loginFailure = "";

    /** 注册结果等待锁 */
    private transient volatile CountDownLatch registerLatch;

    /** 注册是否成功 */
    private transient volatile boolean registerSucceeded;

    /** 注册结果说明 */
    private transient volatile String registerMessage = "";

    /** 改密回执等待锁 */
    private transient volatile CountDownLatch noticeLatch;

    /** 是否正在等待改密回执（用于把下一条系统通知当作回执处理） */
    private transient volatile boolean awaitingNotice;

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
        this(null, 0);
    }

    /**
     * 构造登录窗口并预填服务器地址。
     *
     * <p>退出登录后重新回到本窗口时会复用上次连接的服务器地址：在多机使用场景下，
     * 服务器 IP 不固定为 127.0.0.1，每次都让使用者重新输入既繁琐又容易输错。</p>
     *
     * @param host 服务器地址，为 null 或空时使用默认值 127.0.0.1
     * @param port 服务器端口，小于等于 0 时使用配置中的端口
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

    /**
     * 组装界面组件。
     */
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
        SkinButton passwordButton = new SkinButton("修改密码", SkinButton.Kind.GHOST);
        passwordButton.addActionListener(e -> changePassword());
        buttons.add(passwordButton);

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

    /**
     * 构建品牌头部：徽标、程序名与副标题。
     *
     * @return 面板
     */
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
        resetLoginState();
        createClient();

        new SwingWorker<Boolean, Void>() {
            /**
             * 后台线程执行连接并发送登录请求。
             *
             * @return 请求是否已提交
             */
            @Override
            protected Boolean doInBackground() {
                return client.openConnection(host, port) && client.login(name, password);
            }

            /**
             * 连接失败直接提示；提交成功则进入统一的登录结果等待流程。
             *
             * @param requested 请求是否已提交
             */
            @Override
            protected void done() {
                boolean requested = false;
                try {
                    requested = get();
                } catch (Exception e) {
                    loginFailure = "连接异常: " + e.getMessage();
                }
                if (!requested) {
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
     * 等待服务器返回登录结果。
     *
     * <p>本方法是打开主窗口的唯一入口：登录与"注册后自动登录"都必须经过它。
     * 早期版本的注册分支只发出了登录请求却没有等待结果，于是界面永远停在登录页，
     * 而服务器端账号已经在线，再次点击登录就会被拒绝为"已在其它位置登录"。</p>
     */
    private void awaitLoginResult() {
        new SwingWorker<Boolean, Void>() {
            /**
             * 后台等待登录结果。
             *
             * @return 是否登录成功
             */
            @Override
            protected Boolean doInBackground() {
                try {
                    CountDownLatch latch = loginLatch;
                    return latch != null && latch.await(6, TimeUnit.SECONDS) && loginSucceeded;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            /**
             * 登录成功则打开主窗口，否则复位界面并释放连接。
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
                    return;
                }
                String reason = loginFailure.isEmpty() ? "登录超时或失败" : loginFailure;
                setBusy(false, reason);
                showError(reason);
                cleanupClient();
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
     * 执行注册：连接服务器提交注册，成功后在同一连接上自动登录。
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
        createClient();

        new SwingWorker<Boolean, Void>() {
            /**
             * 后台建立连接并发送注册请求。
             *
             * @return 是否连接成功
             */
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

            /**
             * 根据注册结果决定是否继续自动登录。
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
                if (!registerSucceeded) {
                    String reason = registerMessage.isEmpty() ? "注册失败" : registerMessage;
                    setBusy(false, reason);
                    showError(reason);
                    cleanupClient();
                    return;
                }
                // 注册成功：复用同一条连接登录，并交给统一的等待流程打开主窗口
                // 此处保持忙碌状态，避免使用者在自动登录期间重复点击而新建连接
                setBusy(true, "注册成功，正在自动登录……");
                showInfo("注册成功，正在自动登录……");
                resetLoginState();
                client.login(name, password);
                awaitLoginResult();
            }
        }.execute();
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
     * 修改密码：先登录校验身份，再通过 PASSWORD_CHANGE 消息请求服务器改密。
     *
     * <p>服务器要求登录态才能改密，因此本流程会短暂登录：改密回执到达后立刻断开连接，
     * 否则使用者的账号会以"只改了个密码"的方式挂在在线列表里，本人却看不到任何界面。</p>
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
        awaitingNotice = false;
        resetLoginState();
        createClient();

        new SwingWorker<Boolean, Void>() {
            /**
             * 后台连接、登录并等待登录结果。
             *
             * @return 是否已登录成功
             */
            @Override
            protected Boolean doInBackground() {
                if (!client.openConnection(host, port)) {
                    return false;
                }
                client.login(name, oldPassword);
                try {
                    CountDownLatch latch = loginLatch;
                    return latch != null && latch.await(6, TimeUnit.SECONDS) && loginSucceeded;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            /**
             * 登录成功则发送改密请求并等待回执。
             *
             * @param loggedIn 是否已登录
             */
            @Override
            protected void done() {
                boolean loggedIn = false;
                try {
                    loggedIn = get();
                } catch (Exception e) {
                    loginFailure = "连接异常: " + e.getMessage();
                }
                if (!loggedIn) {
                    String reason = loginFailure.isEmpty() ? "登录失败，无法修改密码" : loginFailure;
                    setBusy(false, reason);
                    showError(reason);
                    cleanupClient();
                    return;
                }
                awaitingNotice = true;
                noticeLatch = new CountDownLatch(1);
                TextMessage message = com.chat.common.ChatMessageFactory.text(name,
                        Constants.SYSTEM_SENDER, oldPassword + "|" + newPassword, MessageType.TEXT_PRIVATE);
                message.setType(MessageType.PASSWORD_CHANGE);
                client.send(message);
                ackPasswordChange();
            }
        }.execute();
    }

    /**
     * 等待改密回执（服务器的系统通知）并断开临时连接。
     */
    private void ackPasswordChange() {
        new SwingWorker<Boolean, Void>() {
            /**
             * 后台等待回执。
             *
             * @return 是否收到回执
             */
            @Override
            protected Boolean doInBackground() {
                try {
                    CountDownLatch latch = noticeLatch;
                    return latch != null && latch.await(6, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            /**
             * 无论是否收到回执都要断开连接，避免账号被临时登录态挂住。
             *
             * @param received 是否收到回执
             */
            @Override
            protected void done() {
                boolean received = false;
                try {
                    received = get();
                } catch (Exception e) {
                    received = false;
                }
                awaitingNotice = false;
                cleanupClient();
                setBusy(false, received ? "密码修改已处理，请查看提示" : "未收到服务器回执，请稍后用新密码尝试登录");
            }
        }.execute();
    }

    /**
     * 关闭上一个客户端连接（若有）。
     *
     * <p>登录、注册、改密都会新建连接：若不先清理旧连接，旧连接会一直保持登录态，
     * 新连接再登录同一账号时会被服务器判定为"已在其它位置登录"。</p>
     */
    private void closePreviousClient() {
        if (client != null) {
            client.removeListener(this);
            client.close();
            client = null;
        }
    }

    /**
     * 重置登录等待状态，必须在发送登录请求之前调用。
     */
    private void resetLoginState() {
        loginSucceeded = false;
        loginFailure = "";
        loginLatch = new CountDownLatch(1);
    }

    /**
     * 创建客户端并注册本窗口为监听器。
     */
    private void createClient() {
        closePreviousClient();
        client = new ChatClient();
        client.addListener(this);
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
     * 处理服务器消息：分别唤醒登录、注册或改密等待线程。
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
            // 改密请求的回执是一条系统通知：此时唤醒等待线程，由它负责断开临时登录态
            if (awaitingNotice && noticeLatch != null) {
                noticeLatch.countDown();
            }
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

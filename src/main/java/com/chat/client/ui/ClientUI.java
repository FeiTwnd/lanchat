package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.Result;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.common.UserListCodec;
import com.chat.service.MessageService;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端主窗口。
 *
 * <p>职责：登录成功后的操作中枢——展示在线用户列表，提供打开私聊、进入群聊、
 * 发送文件、查询历史记录、修改昵称、删除用户（管理员）等入口，
 * 并把服务器推送的消息路由到对应窗口。</p>
 *
 * <p>消息路由规则：</p>
 * <ul>
 *   <li>{@code USER_LIST} -> 刷新在线用户表格；</li>
 *   <li>{@code TEXT_GROUP} / {@code SYSTEM} / {@code ERROR} -> 投递群聊窗口；</li>
 *   <li>{@code TEXT_PRIVATE} -> 按对方用户名投递私聊窗口（窗口不存在则自动创建）；</li>
 *   <li>文件类消息 -> 投递文件传输窗口；</li>
 *   <li>{@code HISTORY_RESULT} -> 弹出历史记录对话框。</li>
 * </ul>
 *
 * <p>线程安全：所有网络回调都可能发生在非界面线程，因此本类所有界面更新
 * 统一通过 {@link BaseUI#onEdt(Runnable)} 调度。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class ClientUI extends BaseUI implements ChatListener {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250119L;

    /** 客户端实例 */
    private final transient ChatClient client;

    /** 私聊窗口表：对方用户名 -> 窗口 */
    private final transient Map<String, PrivateChatUI> privateWindows = new ConcurrentHashMap<>();

    /** 群聊窗口 */
    private transient GroupChatUI groupWindow;

    /** 在线用户表格模型 */
    private final DefaultTableModel userTableModel = new DefaultTableModel(
            new String[]{"用户名", "昵称", "角色"}, 0) {
        /** 序列化版本号 */
        private static final long serialVersionUID = 20250120L;

        /**
         * 禁止编辑单元格。
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

    /** 在线用户表格 */
    private final JTable userTable = new JTable(userTableModel);

    /** 状态栏 */
    private final JLabel statusLabel = new JLabel("已连接");

    /** 当前在线用户缓存，供私聊与删除操作使用 */
    private final transient Map<String, User> onlineUsers = new ConcurrentHashMap<>();

    /** 消息业务服务，仅用于本地导出聊天记录 */
    private final transient MessageService messageService = new MessageService();

    /**
     * 构造客户端主窗口。
     *
     * @param client 已完成登录的客户端
     */
    public ClientUI(ChatClient client) {
        super(Constants.APP_NAME + " - " + client.getNickname() + "(" + client.getUsername() + ")");
        this.client = client;
        initComponents();
        client.addListener(this);
        client.requestUserList();
        setSize(880, 600);
        centerOnScreen();
        openGroupWindow(false);
    }

    /**
     * 组装界面组件。
     */
    private void initComponents() {
        userTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userTable.setRowHeight(24);
        // 双击用户行直接打开私聊窗口，符合即时通讯软件的通用交互习惯
        userTable.addMouseListener(new MouseAdapter() {
            /**
             * 双击打开私聊窗口。
             *
             * @param e 鼠标事件
             */
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openPrivateChatWithSelected();
                }
            }
        });

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildUserPanel(), buildFunctionPanel());
        splitPane.setDividerLocation(420);
        splitPane.setResizeWeight(0.55);

        setLayout(new BorderLayout());
        add(splitPane, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
    }

    /**
     * 构建在线用户面板。
     *
     * @return 面板
     */
    private JPanel buildUserPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(javax.swing.BorderFactory.createTitledBorder("在线用户（双击开始私聊）"));
        panel.add(new JScrollPane(userTable), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton privateButton = new JButton("发起私聊");
        privateButton.addActionListener(e -> openPrivateChatWithSelected());
        JButton fileButton = new JButton("发送文件");
        fileButton.addActionListener(e -> openFileTransferWithSelected());
        JButton refreshButton = new JButton("刷新列表");
        refreshButton.addActionListener(e -> client.requestUserList());
        buttons.add(privateButton);
        buttons.add(fileButton);
        buttons.add(refreshButton);
        panel.add(buttons, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(430, 480));
        return panel;
    }

    /**
     * 构建功能面板。
     *
     * @return 面板
     */
    private JPanel buildFunctionPanel() {
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 6, 6));
        panel.setBorder(javax.swing.BorderFactory.createTitledBorder("功能"));

        panel.add(button("进入群聊大厅", e -> openGroupWindow(true)));
        panel.add(button("发送文件…", e -> chooseAndSendFile()));
        panel.add(button("查询聊天记录…", e -> showHistoryDialog()));
        panel.add(button("导出我的聊天记录", e -> exportHistory()));
        panel.add(button("修改昵称…", e -> changeNickname()));
        panel.add(button("修改密码…", e -> showPasswordHint()));
        panel.add(button("删除用户（管理员）…", e -> deleteUser()));

        JTextArea tips = new JTextArea();
        tips.setEditable(false);
        tips.setLineWrap(true);
        tips.setWrapStyleWord(true);
        tips.setFont(FONT_NORMAL);
        tips.setText("使用提示：\n"
                + "1. 在线用户列表由服务器实时推送，用户上线/下线会自动刷新。\n"
                + "2. 双击某个用户即可开始私聊。\n"
                + "3. 文件传输支持进度显示，接收到的文件保存在 data/received 目录。\n"
                + "4. 聊天记录按天保存在 data/history 目录，可按时间范围查询与导出。");
        panel.add(new JScrollPane(tips));
        return panel;
    }

    /**
     * 创建按钮并绑定事件。
     *
     * @param text   按钮文本
     * @param action 事件处理器
     * @return 按钮对象
     */
    private JButton button(String text, java.awt.event.ActionListener action) {
        JButton button = new JButton(text);
        button.addActionListener(action);
        return button;
    }

    /**
     * 构建底部状态栏。
     *
     * @return 面板
     */
    private JPanel buildStatusBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(statusLabel, BorderLayout.WEST);
        JLabel hint = new JLabel("服务器: 已连接    用户: " + client.getUsername() + "    ");
        panel.add(hint, BorderLayout.EAST);
        return panel;
    }

    /**
     * 打开当前选中用户的私聊窗口。
     */
    private void openPrivateChatWithSelected() {
        String username = selectedUsername();
        if (username == null) {
            showInfo("请先在左侧列表中选择一个用户");
            return;
        }
        if (username.equals(client.getUsername())) {
            showInfo("不能与自己私聊，请使用群聊大厅");
            return;
        }
        openPrivateWindow(username);
    }

    /**
     * 打开当前选中用户的文件传输窗口。
     */
    private void openFileTransferWithSelected() {
        String username = selectedUsername();
        if (username == null) {
            showInfo("请先在左侧列表中选择一个用户");
            return;
        }
        if (username.equals(client.getUsername())) {
            showInfo("不能给自己发送文件");
            return;
        }
        FileTransferUI window = FileTransferUI.windowFor(client, username);
        window.setVisible(true);
    }

    /**
     * 获取当前选中行的用户名。
     *
     * @return 用户名；未选中时返回 null
     */
    private String selectedUsername() {
        int row = userTable.getSelectedRow();
        if (row < 0) {
            return null;
        }
        return String.valueOf(userTableModel.getValueAt(row, 0));
    }

    /**
     * 打开（或复用）指定用户的私聊窗口。
     *
     * @param username 对方用户名
     * @return 私聊窗口
     */
    public PrivateChatUI openPrivateWindow(String username) {
        PrivateChatUI window = privateWindows.computeIfAbsent(username, key -> {
            PrivateChatUI created = new PrivateChatUI(client, key);
            client.addListener(created.getPanel());
            return created;
        });
        if (!window.isVisible()) {
            window.setVisible(true);
        }
        window.toFront();
        return window;
    }

    /**
     * 打开群聊窗口。
     *
     * @param toFront 是否置顶显示
     * @return 群聊窗口
     */
    public GroupChatUI openGroupWindow(boolean toFront) {
        if (groupWindow == null) {
            groupWindow = new GroupChatUI(client, client.getNickname());
            client.addListener(groupWindow.getPanel());
        }
        if (!groupWindow.isVisible()) {
            groupWindow.setVisible(true);
        }
        if (toFront) {
            groupWindow.toFront();
        }
        return groupWindow;
    }

    /**
     * 弹出文件选择框并向选中用户发送文件。
     */
    private void chooseAndSendFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要发送的文件");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        String receiver = selectedUsername();
        if (receiver == null || receiver.equals(client.getUsername())) {
            String input = JOptionPane.showInputDialog(this, "请输入接收方用户名:", "选择接收方",
                    JOptionPane.QUESTION_MESSAGE);
            if (input == null || input.trim().isEmpty()) {
                return;
            }
            receiver = input.trim();
        }
        FileTransferUI window = FileTransferUI.windowFor(client, receiver);
        window.setVisible(true);
        window.presetFile(file);
        window.appendLog("已选择文件 " + file.getAbsolutePath() + "，点击“发送”开始传输");
    }

    /**
     * 弹出历史记录查询对话框。
     */
    private void showHistoryDialog() {
        JPanel panel = new JPanel(new java.awt.GridLayout(3, 2, 6, 6));
        javax.swing.JTextField userField = new javax.swing.JTextField(client.getUsername());
        javax.swing.JTextField fromField = new javax.swing.JTextField(LocalDate.now().minusDays(7).toString());
        javax.swing.JTextField toField = new javax.swing.JTextField(LocalDate.now().toString());
        panel.add(new JLabel("用户名（留空查全部）:"));
        panel.add(userField);
        panel.add(new JLabel("起始日期 (yyyy-MM-dd):"));
        panel.add(fromField);
        panel.add(new JLabel("结束日期 (yyyy-MM-dd):"));
        panel.add(toField);
        int choice = JOptionPane.showConfirmDialog(this, panel, "查询聊天记录",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        client.requestHistory(userField.getText().trim(), fromField.getText().trim(), toField.getText().trim());
        statusLabel.setText("正在查询聊天记录……");
    }

    /**
     * 导出当前用户的聊天记录。
     */
    private void exportHistory() {
        Result<File> result = messageService.exportHistory(client.getUsername());
        if (result.isSuccess()) {
            showInfo("导出成功: " + result.getData().getAbsolutePath());
        } else {
            showError("导出失败: " + result.getMessage());
        }
    }

    /**
     * 修改昵称。
     */
    private void changeNickname() {
        String input = JOptionPane.showInputDialog(this, "请输入新的昵称（最长 "
                + Constants.NICKNAME_MAX_LENGTH + " 个字符）:", client.getNickname());
        if (input == null || input.trim().isEmpty()) {
            return;
        }
        // 昵称修改通过私聊通道发送给服务器：正文格式 CHANGE_NICK|新昵称
        TextMessage message = com.chat.common.ChatMessageFactory.text(client.getUsername(),
                Constants.SYSTEM_SENDER, "CHANGE_NICK|" + input.trim(), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.USER_UPDATE);
        client.send(message);
        client.setNickname(input.trim());
        showInfo("昵称修改请求已发送");
    }

    /**
     * 提示修改密码需在登录界面完成。
     */
    private void showPasswordHint() {
        showInfo("为保障账号安全，修改密码请在登录界面使用“修改密码”功能，"
                + "需要提供原密码进行校验。");
    }

    /**
     * 删除用户（仅管理员可用）。
     */
    private void deleteUser() {
        String target = selectedUsername();
        if (target == null) {
            target = JOptionPane.showInputDialog(this, "请输入要删除的用户名:", "删除用户",
                    JOptionPane.QUESTION_MESSAGE);
        }
        if (target == null || target.trim().isEmpty()) {
            return;
        }
        if (!confirm("确定要删除用户 " + target + " 吗？该操作不可恢复（仅管理员可执行）。")) {
            return;
        }
        TextMessage message = com.chat.common.ChatMessageFactory.text(client.getUsername(),
                Constants.SYSTEM_SENDER, "DELETE_USER|" + target.trim(), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.USER_UPDATE);
        client.send(message);
        showInfo("删除请求已发送：" + target);
    }

    /**
     * 依据在线用户列表刷新表格。
     *
     * @param text 用户列表编码文本
     */
    private void refreshUserTable(String text) {
        List<User> users = UserListCodec.decode(text);
        onEdt(() -> {
            userTableModel.setRowCount(0);
            onlineUsers.clear();
            for (User user : users) {
                onlineUsers.put(user.getUsername(), user);
                userTableModel.addRow(new Object[]{user.getUsername(), user.getNickname(),
                        user.isAdmin() ? "管理员" : "普通用户"});
            }
            statusLabel.setText("在线用户 " + users.size() + " 人（含自己）");
        });
    }

    /**
     * 处理服务器推送的消息。
     *
     * @param message 消息
     */
    @Override
    public void onMessage(Message message) {
        switch (message.getType()) {
            case USER_LIST:
                refreshUserTable(message instanceof TextMessage
                        ? ((TextMessage) message).getContent() : "");
                break;
            case LOGIN_RESULT:
                // 登录结果已在登录界面处理，主窗口阶段可安全忽略
                break;
            case TEXT_PRIVATE:
                handlePrivateMessage((TextMessage) message);
                break;
            case TEXT_GROUP:
                openGroupWindow(false).getPanel().onMessage(message);
                break;
            case SYSTEM:
            case ERROR:
                openGroupWindow(false).getPanel().onMessage(message);
                statusLabel.setText(message.getSummary());
                break;
            case FILE_REQUEST:
            case FILE_ACCEPT:
            case FILE_REJECT:
            case FILE_RESULT:
                routeFileMessage(message);
                break;
            case FILE_CHUNK:
                routeProgress((FileMessage) message);
                break;
            case HISTORY_RESULT:
                showHistoryResult((TextMessage) message);
                break;
            default:
                statusLabel.setText("收到消息: " + message.getType().getDescription());
                break;
        }
    }

    /**
     * 处理私聊消息：打开对应窗口并投递。
     *
     * @param message 私聊文本消息
     */
    private void handlePrivateMessage(TextMessage message) {
        String peer = client.getUsername().equals(message.getSender())
                ? message.getReceiver() : message.getSender();
        if (peer == null || peer.isEmpty()) {
            return;
        }
        PrivateChatUI window = openPrivateWindow(peer);
        onEdt(() -> window.getPanel().onMessage(message));
    }

    /**
     * 路由文件类消息到对应窗口。
     *
     * @param message 文件消息
     */
    private void routeFileMessage(Message raw) {
        // FILE_REQUEST 可能以文本消息承载元信息，统一在这里还原成文件消息
        FileMessage message = raw instanceof FileMessage ? (FileMessage) raw
                : ChatClient.decodeRequest((TextMessage) raw);
        if (message == null) {
            statusLabel.setText("收到无法解析的文件消息");
            return;
        }
        boolean incoming = message.getType() == MessageType.FILE_REQUEST;
        String peer = incoming ? message.getSender() : counterpartOf(message);
        FileTransferUI window = FileTransferUI.ownerOf(message.getTransferId());
        if (window == null) {
            window = FileTransferUI.windowFor(client, peer == null ? "未知用户" : peer);
        }
        window.setVisible(true);
        window.handleFileMessage(message);
    }

    /**
     * 路由文件进度消息。
     *
     * @param message 进度消息
     */
    private void routeProgress(FileMessage message) {
        FileTransferUI window = FileTransferUI.ownerOf(message.getTransferId());
        if (window != null) {
            window.handleFileMessage(message);
            return;
        }
        // 接收场景下进度窗口由请求消息提前注册；若未注册则创建与发送者同名的窗口
        FileTransferUI created = FileTransferUI.windowFor(client, message.getSender());
        created.setVisible(true);
        created.registerTransfer(message.getTransferId());
        created.handleFileMessage(message);
    }

    /**
     * 推断文件消息的对端用户名。
     *
     * @param message 文件消息
     * @return 对端用户名
     */
    private String counterpartOf(FileMessage message) {
        return client.getUsername().equals(message.getSender())
                ? message.getReceiver() : message.getSender();
    }

    /**
     * 展示历史记录查询结果。
     *
     * @param message 历史记录结果消息
     */
    private void showHistoryResult(TextMessage message) {
        String[] lines = message.getContent().split("\n", -1);
        String[] head = lines.length > 0 ? lines[0].split("\\|", -1) : new String[]{"0", "无数据"};
        if (head.length < 2 || !"1".equals(head[0])) {
            onEdt(() -> {
                statusLabel.setText("查询失败");
                showError("聊天记录查询失败: " + (head.length > 1 ? head[1] : "未知原因"));
            });
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                builder.append(lines[i].replace('|', ' ')).append(System.lineSeparator());
            }
        }
        onEdt(() -> {
            statusLabel.setText("聊天记录查询完成，共 " + head[1] + " 条");
            JTextArea area = new JTextArea(builder.toString(), 20, 60);
            area.setEditable(false);
            area.setFont(FONT_NORMAL);
            JOptionPane.showMessageDialog(this, new JScrollPane(area),
                    "聊天记录（共 " + head[1] + " 条）", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    /**
     * 处理连接状态变化。
     *
     * @param connected 是否连接
     * @param reason    原因
     */
    @Override
    public void onConnectionChanged(boolean connected, String reason) {
        onEdt(() -> {
            statusLabel.setText(reason);
            if (!connected) {
                showError(reason + "\n程序将退出，请重新登录。");
                client.close();
                dispose();
                System.exit(0);
            }
        });
    }

    /**
     * 窗口关闭时断开连接并退出程序。
     */
    @Override
    public void dispose() {
        client.removeListener(this);
        for (PrivateChatUI window : privateWindows.values()) {
            window.dispose();
        }
        privateWindows.clear();
        if (groupWindow != null) {
            groupWindow.dispose();
            groupWindow = null;
        }
        client.close();
        super.dispose();
    }

    /**
     * 获取客户端实例，供外部（如登录界面）使用。
     *
     * @return 客户端实例
     */
    public ChatClient getClient() {
        return client;
    }

    /**
     * 获取当前在线用户缓存。
     *
     * @return 用户名到用户对象的映射
     */
    public Map<String, User> getOnlineUsers() {
        return onlineUsers;
    }
}

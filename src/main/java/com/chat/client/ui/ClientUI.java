package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.client.ui.theme.AvatarFactory;
import com.chat.client.ui.theme.Glyphs;
import com.chat.client.ui.theme.SkinButton;
import com.chat.client.ui.theme.Theme;
import com.chat.common.Constants;
import com.chat.common.FileMessage;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.Result;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.common.UserListCodec;
import com.chat.service.MessageService;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
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
 * 查询历史记录、修改昵称、删除用户（管理员）等入口，并把服务器推送的消息路由到对应窗口。
 * 发送文件的入口在各聊天窗口内（见 {@link BaseChatPanel}），本窗口不再提供"先选人再发文件"的路径。</p>
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

    /** 群聊入口按钮，用于展示未读条数 */
    private transient SkinButton groupButton;

    /** 群聊未读条数：群聊窗口未打开时累计，打开后清零 */
    private transient int unreadGroupCount;

    /**
     * 是否正在退出登录。
     *
     * <p>退出登录会主动断开连接，而"意外断开"的处理分支会直接结束进程；
     * 用本标志把主动退出与意外断开区分开，否则点击退出登录会变成关闭整个程序。</p>
     */
    private transient volatile boolean loggingOut;

    /** 在线用户树（按角色分组、头像区分在线离线、支持关键字过滤） */
    private final OnlineUserTree userTree = new OnlineUserTree();

    /** 状态栏 */
    private final JLabel statusLabel = new JLabel("已连接");

    /** 头部信息行：角色与在线人数 */
    private final JLabel headerMeta = new JLabel();

    /** 头部头像：用户列表到达后按真实角色重绘（管理员带橙色描边） */
    private final JLabel headerAvatar = new JLabel();

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
        // 刻意不在此处打开群聊大厅：登录后应先看到在线用户列表，
        // 群聊由使用者点击"进入群聊大厅"按需打开；窗口未打开期间的群聊消息会累计未读条数
    }

    /**
     * 组装界面组件。
     */
    private void initComponents() {
        // 双击用户节点直接打开私聊窗口，符合即时通讯软件的通用交互习惯
        userTree.addMouseListener(new MouseAdapter() {

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
        splitPane.setDividerLocation(440);
        splitPane.setResizeWeight(0.55);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setOpaque(false);

        body().setLayout(new BorderLayout());
        body().add(buildHeader(), BorderLayout.NORTH);
        body().add(splitPane, BorderLayout.CENTER);
        body().add(buildStatusBar(), BorderLayout.SOUTH);
    }

    /**
     * 构建头部卡片：头像、昵称、角色与在线人数、刷新入口。
     *
     * @return 面板
     */
    private JPanel buildHeader() {
        JPanel card = card();
        card.setLayout(new BorderLayout(12, 0));

        JPanel identity = new JPanel(new GridLayout(2, 1, 0, 2));
        identity.setOpaque(false);
        JLabel name = new JLabel(displayName() + "（" + client.getUsername() + "）");
        name.setFont(Theme.font(16, Font.BOLD));
        name.setForeground(Theme.TEXT);
        headerMeta.setFont(Theme.fontSmall());
        headerMeta.setForeground(Theme.TEXT_WEAK);
        headerMeta.setText("正在获取在线用户……");
        identity.add(name);
        identity.add(headerMeta);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        actions.setOpaque(false);
        actions.add(SkinButton.normal("刷新列表", e -> client.requestUserList())
                .withIcon(Glyphs.refresh(16, Theme.PRIMARY)));

        headerAvatar.setIcon(AvatarFactory.avatar(true, false, 48));
        card.add(headerAvatar, BorderLayout.WEST);
        card.add(identity, BorderLayout.CENTER);
        card.add(actions, BorderLayout.EAST);
        return card;
    }

    /**
     * 构建在线用户面板：搜索框 + 用户树 + 操作按钮。
     *
     * @return 面板
     */
    private JPanel buildUserPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(Theme.CARD);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JTextField searchField = new JTextField();
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        searchField.setToolTipText("按昵称或账号过滤用户");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {

            /**
             * 输入内容时刷新过滤。
             *
             * @param e 文档事件
             */
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                userTree.setFilter(searchField.getText());
            }

            /**
             * 删除内容时刷新过滤。
             *
             * @param e 文档事件
             */
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                userTree.setFilter(searchField.getText());
            }

            /**
             * 属性变化时刷新过滤。
             *
             * @param e 文档事件
             */
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                userTree.setFilter(searchField.getText());
            }
        });
        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.setOpaque(false);
        searchRow.add(new JLabel(Glyphs.search(16, Theme.TEXT_WEAK)), BorderLayout.WEST);
        searchRow.add(searchField, BorderLayout.CENTER);
        panel.add(searchRow, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(userTree);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.CARD);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.setOpaque(false);
        buttons.add(SkinButton.primary("发起私聊", e -> openPrivateChatWithSelected()));
        buttons.add(SkinButton.normal("刷新列表", e -> client.requestUserList()));
        panel.add(buttons, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(450, 480));
        return panel;
    }

    /**
     * 构建功能面板。
     *
     * @return 面板
     */
    private JPanel buildFunctionPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 8));

        groupButton = SkinButton.menu("进入群聊大厅", Glyphs.group(18, Theme.PRIMARY),
                e -> openGroupWindow(true));
        panel.add(groupButton);
        panel.add(SkinButton.menu("查询聊天记录…", Glyphs.history(18, Theme.PRIMARY),
                e -> showHistoryDialog()));
        panel.add(SkinButton.menu("导出我的聊天记录", Glyphs.export(18, Theme.PRIMARY),
                e -> exportHistory()));
        panel.add(SkinButton.menu("修改昵称…", Glyphs.user(18, Theme.PRIMARY),
                e -> changeNickname()));
        panel.add(SkinButton.menu("修改密码…", Glyphs.lock(18, Theme.PRIMARY),
                e -> showPasswordHint()));
        panel.add(SkinButton.menu("退出登录", Glyphs.logout(18, Theme.TEXT_WEAK),
                e -> logout()));
        panel.add(SkinButton.menu("删除用户（管理员）…", Glyphs.trash(18, Theme.DANGER),
                SkinButton.Kind.DANGER, e -> deleteUser()));

        JTextArea tips = new JTextArea();
        tips.setEditable(false);
        tips.setLineWrap(true);
        tips.setWrapStyleWord(true);
        tips.setFont(Theme.fontSmall());
        tips.setForeground(Theme.TEXT_WEAK);
        tips.setBackground(Theme.BG);
        tips.setText("使用提示：\n"
                + "1. 在线用户列表由服务器实时推送，用户上线/下线会自动刷新。\n"
                + "2. 双击某个用户即可开始私聊；离线的用户会被置灰并归入“离线”分组。\n"
                + "3. 发送文件在私聊窗口或群聊窗口内点击“发送文件”：私聊发给对方，群聊发给全部在线用户。\n"
                + "4. 接收到的文件保存在 data/received 目录；聊天记录保存在服务器数据库中，可随时查询或导出。\n"
                + "5. 聊天记录可按时间范围查询，也可以一键导出为文本文件。\n"
                + "6. 点击“退出登录”会注销当前账号并返回登录界面，可换账号继续使用。");
        panel.add(new JScrollPane(tips));
        return panel;
    }

    /**
     * 构建底部状态栏。
     *
     * @return 面板
     */
    private JPanel buildStatusBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 10));
        statusLabel.setForeground(Theme.TEXT);
        panel.add(statusLabel, BorderLayout.WEST);
        JLabel hint = new JLabel("服务器: 已连接    用户: " + client.getUsername() + "    ");
        hint.setForeground(Theme.TEXT_WEAK);
        panel.add(hint, BorderLayout.EAST);
        return panel;
    }

    /**
     * 获取当前用户的显示名。
     *
     * @return 昵称；昵称为空时返回用户名
     */
    private String displayName() {
        String nickname = client.getNickname();
        return nickname == null || nickname.trim().isEmpty() ? client.getUsername() : nickname;
    }

    /**
     * 判断当前登录用户是否为管理员。
     *
     * <p>角色信息来自服务器推送的用户列表；列表尚未到达时按普通用户处理。</p>
     *
     * @return 管理员返回 true
     */
    private boolean isAdmin() {
        User self = userTree.onlineUsers().get(client.getUsername());
        return self != null && self.isAdmin();
    }

    /**
     * 打开当前选中用户的私聊窗口。
     *
     * <p>离线的用户同样允许打开窗口：服务器会以错误消息回执说明对方不在线，
     * 这比在界面层静默拦下更容易让使用者理解发生了什么。</p>
     */
    private void openPrivateChatWithSelected() {
        User target = userTree.selectedUser();
        if (target == null) {
            showInfo("请先在左侧列表中选择一个用户");
            return;
        }
        if (target.getUsername().equals(client.getUsername())) {
            showInfo("不能与自己私聊，请使用群聊大厅");
            return;
        }
        if (!userTree.selectedIsOnline()) {
            statusLabel.setText("提示：" + target.getUsername() + " 已离线，消息可能无法送达");
        }
        openPrivateWindow(target.getUsername());
    }

    /**
     * 获取当前选中的用户名。
     *
     * @return 用户名；未选中用户节点时返回 null
     */
    private String selectedUsername() {
        User user = userTree.selectedUser();
        return user == null ? null : user.getUsername();
    }

    /**
     * 打开（或复用）指定用户的私聊窗口。
     *
     * @param username 对方用户名
     * @return 私聊窗口
     */
    public PrivateChatUI openPrivateWindow(String username) {
        // 面板不再单独注册为客户端监听器：消息统一由本窗口分发，
        // 否则同一条消息会被“面板自监听”与“本窗口转发”各渲染一次
        PrivateChatUI window = privateWindows.get(username);
        if (window == null) {
            window = new PrivateChatUI(client, username);
            privateWindows.put(username, window);
            // 新建窗口时补拉与该用户的最近对话：面板里的内容只存在于内存，
            // 关闭窗口或重启客户端后若不补拉，使用者会以为对方的消息丢了
            client.requestHistory(client.getUsername(), "", "", username);
        }
        if (!window.isVisible()) {
            window.setVisible(true);
        }
        window.toFront();
        return window;
    }

    /**
     * 打开群聊窗口。
     *
     * @param show 是否显示窗口：false 只保证窗口对象存在（用于缓存消息），true 才真正显示并置顶
     * @return 群聊窗口
     */
    public GroupChatUI openGroupWindow(boolean show) {
        if (groupWindow == null) {
            // 群发文件需要知道"此刻有哪些人在线"，面板只接收一份只读快照，不反向依赖本窗口
            groupWindow = new GroupChatUI(client, client.getNickname(),
                    () -> new java.util.ArrayList<>(userTree.onlineUsers().keySet()));
        }
        if (show) {
            unreadGroupCount = 0;
            updateGroupButtonText();
            if (!groupWindow.isVisible()) {
                groupWindow.setVisible(true);
            }
            groupWindow.toFront();
        }
        return groupWindow;
    }

    /**
     * 获取群聊面板，窗口未打开时只把消息缓存进面板并累计未读条数。
     *
     * <p>本方法必须在事件分发线程调用（调用点均已通过 {@link BaseUI#onEdt(Runnable)} 调度）。</p>
     *
     * @return 群聊面板
     */
    private GroupChatPanel groupPanel() {
        openGroupWindow(false);
        if (!groupWindow.isVisible()) {
            unreadGroupCount++;
            updateGroupButtonText();
        }
        return groupWindow.getPanel();
    }

    /**
     * 刷新群聊入口按钮的未读提示。
     */
    private void updateGroupButtonText() {
        if (groupButton != null) {
            groupButton.setText(unreadGroupCount == 0
                    ? "进入群聊大厅" : "进入群聊大厅（" + unreadGroupCount + " 条未读）");
        }
    }

    /**
     * 退出登录：注销服务器会话、关闭全部子窗口并返回登录界面。
     *
     * <p>显式发送 LOGOUT 而不是直接关闭 socket：服务器收到 LOGOUT 会立即注销在线状态并
     * 广播最新列表，其它客户端不用等读操作失败才发现本用户已下线。</p>
     */
    private void logout() {
        if (!confirm("确定要退出当前账号 " + client.getUsername() + " 吗？")) {
            return;
        }
        loggingOut = true;
        final String host = client.getServerHost();
        final int port = client.getServerPort();
        // 发送要在后台线程完成：网络写入不应该阻塞事件分发线程
        new javax.swing.SwingWorker<Boolean, Void>() {

            /**
             * 后台发送退出登录请求。
             *
             * @return 是否发送成功
             */
            @Override
            protected Boolean doInBackground() {
                return client.sendLogout();
            }

            /**
             * 释放当前窗口并回到登录界面，同时预填原来的服务器地址。
             *
             * @param sent 是否发送成功
             */
            @Override
            protected void done() {
                dispose();
                LoginUI login = new LoginUI(host, port);
                login.setVisible(true);
            }
        }.execute();
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
     * 依据在线用户列表刷新用户树与头部信息。
     *
     * @param text 用户列表编码文本
     */
    private void refreshUserList(String text) {
        List<User> users = UserListCodec.decode(text);
        onEdt(() -> {
            int count = userTree.update(users);
            boolean admin = isAdmin();
            headerAvatar.setIcon(AvatarFactory.avatar(true, admin, 48));
            headerMeta.setText((admin ? "管理员" : "普通用户")
                    + " · 在线用户 " + count + " 人"
                    + (userTree.offlineCount() > 0 ? "，离线 " + userTree.offlineCount() + " 人" : "")
                    + " · 双击用户开始私聊");
            statusLabel.setText("在线用户 " + count + " 人（含自己）");
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
                refreshUserList(message instanceof TextMessage
                        ? ((TextMessage) message).getContent() : "");
                break;
            case LOGIN_RESULT:
                // 登录结果已在登录界面处理，主窗口阶段可安全忽略
                break;
            case TEXT_PRIVATE:
                handlePrivateMessage((TextMessage) message);
                break;
            case TEXT_GROUP:
                // 群聊窗口未打开时消息先入面板并计未读，不主动弹出窗口打扰使用者
                onEdt(() -> groupPanel().onMessage(message));
                break;
            case SYSTEM:
                onEdt(() -> {
                    groupPanel().onMessage(message);
                    statusLabel.setText(message.getSummary());
                });
                break;
            case ERROR:
                onEdt(() -> {
                    groupPanel().onMessage(message);
                    statusLabel.setText(message.getSummary());
                    // 发送方界面上消息已经回显，若把"未送达"之类的错误只写进状态栏，
                    // 使用者会误以为对方收到了，因此这里必须弹窗明确提示
                    showError(message.getSummary());
                });
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
                onEdt(() -> showHistoryResult((TextMessage) message));
                break;
            default:
                onEdt(() -> statusLabel.setText("收到消息: " + message.getType().getDescription()));
                break;
        }
    }

    /**
     * 处理私聊消息：打开对应窗口并投递。
     *
     * <p>本方法由网络接收线程回调，窗口的创建与内容追加必须整体切到事件分发线程。
     * 若在网络线程创建窗口，会与该线程中正在排版的 {@code JTextPane} 形成
     * “事件线程持有文档写锁等待 AWT 树锁、网络线程持有 AWT 树锁等待文档读锁”的
     * 死锁，界面会彻底卡死。</p>
     *
     * @param message 私聊文本消息
     */
    private void handlePrivateMessage(TextMessage message) {
        String self = client.getUsername();
        // 服务器在私聊投递成功后会给发送方回一条“送达回执”，其发送者与接收者都是发送者本人。
        // 本地发送时已经回显消息，若把回执也走一遍分发，发送方会弹出一个“与自己私聊”的窗口，
        // 因此这里直接忽略；回执的价值在于协议层确认送达（集成用例 IT-03 依赖它）。
        if (self != null && self.equals(message.getSender()) && self.equals(message.getReceiver())) {
            return;
        }
        String peer = self != null && self.equals(message.getSender())
                ? message.getReceiver() : message.getSender();
        if (peer == null || peer.isEmpty()) {
            return;
        }
        onEdt(() -> openPrivateWindow(peer).getPanel().onMessage(message));
    }

    /**
     * 路由文件类消息到对应窗口。
     *
     * <p>同 {@link #handlePrivateMessage(TextMessage)}：窗口的创建、显示与日志追加
     * 一律在事件分发线程执行，网络线程只负责解码协议内容。</p>
     *
     * @param raw 文件消息（可能由文本消息承载元信息）
     */
    private void routeFileMessage(Message raw) {
        // FILE_REQUEST 可能以文本消息承载元信息，统一在这里还原成文件消息
        FileMessage message = raw instanceof FileMessage ? (FileMessage) raw
                : ChatClient.decodeRequest((TextMessage) raw);
        if (message == null) {
            onEdt(() -> statusLabel.setText("收到无法解析的文件消息"));
            return;
        }
        onEdt(() -> {
            boolean incoming = message.getType() == MessageType.FILE_REQUEST;
            String peer = incoming ? message.getSender() : counterpartOf(message);
            FileTransferUI window = FileTransferUI.ownerOf(message.getTransferId());
            if (window == null) {
                window = FileTransferUI.windowFor(client, peer == null ? "未知用户" : peer);
            }
            window.setVisible(true);
            window.handleFileMessage(message);
        });
    }

    /**
     * 路由文件进度消息。
     *
     * @param message 进度消息
     */
    private void routeProgress(FileMessage message) {
        onEdt(() -> {
            FileTransferUI window = FileTransferUI.ownerOf(message.getTransferId());
            if (window == null) {
                // 接收场景下进度窗口由请求消息提前注册；若未注册则创建与发送者同名的窗口
                window = FileTransferUI.windowFor(client, message.getSender());
                window.setVisible(true);
                window.registerTransfer(message.getTransferId());
            }
            window.handleFileMessage(message);
        });
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
     * <p>两种用途共用一条响应：正文头部第三个字段是"会话对象"，
     * 非空表示这是私聊窗口补拉的历史（渲染进窗口），为空表示使用者主动查询（弹对话框）。</p>
     *
     * @param message 历史记录结果消息
     */
    private void showHistoryResult(TextMessage message) {
        String[] lines = message.getContent().split("\n", -1);
        String[] head = lines.length > 0 ? lines[0].split("\\|", -1) : new String[]{"0", "无数据"};
        String peer = head.length > 2 ? head[2] : "";
        if (head.length < 2 || !"1".equals(head[0])) {
            onEdt(() -> {
                statusLabel.setText("查询失败");
                showError("聊天记录查询失败: " + (head.length > 1 ? head[1] : "未知原因"));
            });
            return;
        }
        if (!peer.isEmpty()) {
            onEdt(() -> renderConversationHistory(peer, lines));
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
     * 把补拉到的会话历史渲染进对应的私聊窗口。
     *
     * @param peer  会话对象用户名
     * @param lines 响应正文按行切分后的数组，首行为头部
     */
    private void renderConversationHistory(String peer, String[] lines) {
        PrivateChatUI window = privateWindows.get(peer);
        if (window == null) {
            // 窗口已被关闭（例如使用者在响应到达前又关掉了），无需再渲染
            return;
        }
        List<String[]> records = new java.util.ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            // 正文里可能含竖线，限制切分次数可保证它完整落在最后一个字段
            String[] fields = lines[i].split("\\|", 4);
            if (fields.length < 4) {
                continue;
            }
            records.add(new String[]{fields[0], fields[1], fields[3]});
        }
        window.getPanel().fillHistory(records);
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
            // 聊天面板不再单独监听客户端，连接状态由本窗口统一转发，
            // 保证子窗口上的“连接已断开”提示与本窗口一致
            for (PrivateChatUI window : privateWindows.values()) {
                window.getPanel().onConnectionChanged(connected, reason);
            }
            if (groupWindow != null) {
                groupWindow.getPanel().onConnectionChanged(connected, reason);
            }
            if (!connected && !loggingOut) {
                // 断开是终态：先注销监听再关闭连接，否则 close() 会再次回调本方法，
                // 用“已断开与服务器的连接”覆盖真实原因并弹出第二个对话框
                client.removeListener(this);
                client.close();
                showError(reason + "\n程序将退出，请重新登录。");
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
        // 文件传输窗口由静态注册表管理，不在上面两个集合里，需要单独关闭
        FileTransferUI.disposeAll();
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
        return userTree.onlineUsers();
    }
}

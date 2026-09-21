package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.client.ui.theme.AvatarFactory;
import com.chat.client.ui.theme.Glyphs;
import com.chat.client.ui.theme.SkinButton;
import com.chat.client.ui.theme.Theme;
import com.chat.common.Constants;
import com.chat.common.Config;
import com.chat.common.FileMessage;
import com.chat.common.Message;
import com.chat.common.MessageType;
import com.chat.common.TextMessage;
import com.chat.common.User;
import com.chat.common.UserListCodec;
import com.chat.util.MessageExporter;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
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
 *   <li>{@code OFFLINE_MESSAGE} -> 同样投递私聊窗口，并标注为离线补投，外观与普通私聊一致；</li>
 *   <li>文件类消息 -> 投递文件传输窗口；</li>
 *   <li>{@code HISTORY_RESULT} -> 弹出历史记录对话框；</li>
 *   <li>{@code EXPORT_RESULT} -> 把服务端渲染好的导出文本写入本机文件。</li>
 * </ul>
 *
 * <p>可靠性相关展示：</p>
 * <ul>
 *   <li>底部状态栏展示连接状态（连接中/已连接/重连中第 n 次/已恢复）与消息发送状态；</li>
 *   <li>断线后进入自动重连，界面只提示一次断线，重连进度在状态栏持续更新；</li>
 *   <li>"对方离线""发送失败"这类需要用户关注的状态会同时写一行到对应聊天窗口。</li>
 * </ul>
 *
 * <p>线程安全：所有网络回调都可能发生在非界面线程，因此本类所有界面更新
 * 统一通过 {@link BaseUI#onEdt(Runnable)} 调度。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class ClientUI extends BaseUI implements ChatListener, ChatClient.MessageStateListener {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20250119L;

    /** 日志记录器 */
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(Constants.LOGGER_NAME + ".ClientUI");

    /** 等待服务端返回导出内容的超时时间（毫秒） */
    private static final int EXPORT_TIMEOUT_MS = 10000;

    /** 客户端实例 */
    private final transient ChatClient client;

    /** 导出等待计时器：收到导出结果或超时后停止，避免重复提示 */
    private transient Timer exportTimer;

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

    /**
     * 各聊天面板是否已经收到过本次断线的提示。
     *
     * <p>重连会按退避序列反复上报"正在重连（第 n 次）"，若每次都转发给聊天面板，
     * 断线时间稍长就会把聊天记录刷满状态行；本标志保证一次断线只在面板里留下一行提示。</p>
     */
    private transient boolean panelsNotifiedDisconnect;

    /** 撤回时限（毫秒）：与服务端一致，只有本人 2 分钟内发出的消息可撤回 */
    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;

    /**
     * 气泡索引的保留时长（毫秒）。
     *
     * <p>取得比撤回时限更宽松，是为了容忍客户端与服务端的时钟偏差：
     * 客户端认为"刚到 2 分钟"而服务端认为"还能撤回"时，索引若已被清理，
     * 界面就会失去撤回入口，用户只能看到服务端的成功回执却找不到被改的气泡。</p>
     */
    private static final long BUBBLE_KEEP_MS = 10 * 60 * 1000L;

    /** 历史分页请求的每页条数，与设计文档一致 */
    private static final String HISTORY_PAGE_SIZE = "50";

    /** 历史分页游标行的前缀，该行由服务端附在正文中，仅用于翻页、不渲染 */
    private static final String HISTORY_CURSOR_PREFIX = "#beforeId=";

    /** 气泡索引：messageId -> 气泡信息，用于撤回时定位并替换聊天气泡 */
    private final transient Map<String, BubbleRef> bubbleIndex = new ConcurrentHashMap<>();

    /** 历史游标：对端用户名 -> 上一页最小主键，用于"加载更早的消息" */
    private final transient Map<String, String> historyCursors = new ConcurrentHashMap<>();

    /** 已发起"加载更早的消息"的对端集合，用于区分首次加载与向前翻页 */
    private final transient java.util.Set<String> earlierRequests =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 在线用户树（按角色分组、头像区分在线离线、支持关键字过滤） */
    private final OnlineUserTree userTree = new OnlineUserTree();

    /** 状态栏 */
    private final JLabel statusLabel = new JLabel("已连接");

    /** 状态栏右侧的服务器与用户信息，连接状态变化时同步刷新 */
    private final JLabel serverHint = new JLabel();

    /** 头部信息行：角色与在线人数 */
    private final JLabel headerMeta = new JLabel();

    /** 头部头像：用户列表到达后按真实角色重绘（管理员带橙色描边） */
    private final JLabel headerAvatar = new JLabel();

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
        client.addMessageStateListener(this);
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
        panel.add(SkinButton.menu("搜索聊天记录…", Glyphs.search(18, Theme.PRIMARY),
                e -> searchHistory()));
        panel.add(SkinButton.menu("撤回消息…", Glyphs.trash(18, Theme.DANGER),
                e -> recallMessage()));
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
        serverHint.setForeground(Theme.TEXT_WEAK);
        serverHint.setText(serverHintText(true));
        panel.add(serverHint, BorderLayout.EAST);
        return panel;
    }

    /**
     * 生成状态栏右侧的服务器与用户信息文本。
     *
     * @param connected 当前是否已连接
     * @return 提示文本
     */
    private String serverHintText(boolean connected) {
        return "服务器: " + (connected ? "已连接" : "未连接")
                + "    用户: " + client.getUsername() + "    ";
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
            // 顶部翻页入口由主窗口装配：聊天面板保持"只负责渲染"的单一职责，
            // 翻页所需的游标由主窗口在与服务端交互时掌握
            window.getPanel().add(buildEarlierPanel(username), BorderLayout.NORTH);
            // 新建窗口时补拉与该用户的最近一页对话：面板里的内容只存在于内存，
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
     *
     * <p>导出完全走服务端：客户端只发一个请求，由服务端查库渲染后回传，本机负责落盘
     * （见 {@link #saveExportResult(TextMessage)}）。客户端没有任何数据库代码，
     * 因此换一台机器登录同样能导出自己的记录。</p>
     */
    private void exportHistory() {
        if (!client.requestExport()) {
            showError("导出请求发送失败，请检查与服务端的连接");
            return;
        }
        statusLabel.setText("正在导出聊天记录……");
        // 旧版服务端不认识导出请求，会当作未处理的控制消息直接丢掉；
        // 有超时提示才能让"点了没反应"变成一句能看懂的原因
        if (exportTimer != null) {
            exportTimer.stop();
        }
        exportTimer = new Timer(EXPORT_TIMEOUT_MS, event -> {
            exportTimer.stop();
            statusLabel.setText("导出超时");
            showError("导出超时：服务端在 " + (EXPORT_TIMEOUT_MS / 1000)
                    + " 秒内没有响应。请确认服务端已重启到最新版本（旧版服务端不支持导出请求）。");
        });
        exportTimer.setRepeats(false);
        exportTimer.start();
    }

    /**
     * 把服务端回传的导出内容写入本机文件。
     *
     * <p>文件固定落在本机的 {@code data/export} 目录：导出是给使用者留存副本用的，
     * 必须写在发起导出的那台机器上，而不是服务器上。</p>
     *
     * @param message 导出结果消息
     */
    private void saveExportResult(TextMessage message) {
        if (exportTimer != null) {
            exportTimer.stop();
        }
        String[] lines = message.getContent().split("\n", -1);
        String[] head = lines.length > 0 ? lines[0].split("\\|", -1) : new String[]{"0", "无数据"};
        if (head.length < 2 || !"1".equals(head[0])) {
            statusLabel.setText("导出失败");
            showError("导出失败: " + (head.length > 1 ? head[1] : "未知原因"));
            return;
        }
        // 首行是"1|条数"，其后是服务端渲染好的正文；用换行拼回去即可保持原样的行结构
        String text = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length));
        File target = new File(Config.exportDir(), "chat-" + client.getUsername() + "-"
                + System.currentTimeMillis() + ".txt");
        try {
            MessageExporter.writeText(text, target);
            statusLabel.setText("聊天记录已导出，共 " + head[1] + " 条");
            showInfo("导出成功: " + target.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "导出文件写入失败", e);
            statusLabel.setText("导出失败");
            showError("导出失败: " + e.getMessage());
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
            case OFFLINE_MESSAGE:
                handleOfflineMessage((TextMessage) message);
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
            case SEARCH_RESULT:
                onEdt(() -> showSearchResult((TextMessage) message));
                break;
            case MSG_RECALL:
                // 撤回请求可能由本端发起（OK/FAIL），也可能是对端撤回后的通知（PEER）
                onEdt(() -> handleRecallNotice((TextMessage) message));
                break;
            case EXPORT_RESULT:
                onEdt(() -> saveExportResult((TextMessage) message));
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
        String sender = message.getSender();
        String receiver = message.getReceiver();
        // 服务器在私聊投递成功后会给发送方回一条“送达回执”，其发送者与接收者都是发送者本人。
        // 本地发送时已经回显消息，若把回执也走一遍分发，发送方会弹出一个“与自己私聊”的窗口，
        // 因此这里直接忽略；回执的价值在于协议层确认送达（集成用例 IT-03 依赖它）。
        if (self != null && self.equals(sender) && self.equals(receiver)) {
            return;
        }
        if (self != null && self.equals(sender)) {
            // 发送者字段是自己、接收者却是别人：服务器只会把消息转发给接收方，正常不会出现这种情况。
            // 一旦出现说明本端与服务器对"我是谁"的认知不一致，继续分发会凭空造出一个以自己命名的窗口，
            // 因此宁可丢弃并留日志，也不要污染界面。
            LOGGER.warning(() -> "忽略身份异常的私聊消息: self=" + self + ", sender=" + sender
                    + ", receiver=" + receiver);
            return;
        }
        if (sender == null || sender.isEmpty()) {
            return;
        }
        // 走到这里发送者必然是对端，会话键直接取它即可
        String content = message.getContent() == null ? "" : message.getContent();
        onEdt(() -> {
            PrivateChatPanel panel = openPrivateWindow(sender).getPanel();
            panel.onMessage(message);
            // 登记气泡：对端撤回时需要按消息标识找到这一行并替换为撤回提示
            rememberBubble(message.getMessageId(), sender, content, false);
        });
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
        String cursor = null;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            if (lines[i].startsWith(HISTORY_CURSOR_PREFIX)) {
                // 游标行只用于翻页，绝不渲染进聊天记录
                cursor = lines[i].substring(HISTORY_CURSOR_PREFIX.length()).trim();
                continue;
            }
            // 正文里可能含竖线，限制切分次数可保证它完整落在最后一个字段
            String[] fields = lines[i].split("\\|", 4);
            if (fields.length < 4) {
                continue;
            }
            records.add(new String[]{fields[0], fields[1], fields[3]});
        }
        if (cursor != null) {
            historyCursors.put(peer, cursor);
        }
        PrivateChatPanel panel = window.getPanel();
        boolean paged = earlierRequests.remove(peer);
        JScrollPane scroll = scrollPaneOf(panel);
        int oldValue = scroll == null ? 0 : scroll.getVerticalScrollBar().getValue();
        int oldMax = scroll == null ? 0 : scroll.getVerticalScrollBar().getMaximum();
        panel.fillHistory(records);
        if (!paged || scroll == null) {
            return;
        }
        // 向前翻页后把视口同步下移"新增内容的像素高度"，使用者正在看的那一行就不会跳动
        final JScrollPane target = scroll;
        final int shift = oldValue + (target.getVerticalScrollBar().getMaximum() - oldMax);
        // 必须等布局完成，否则新插入的行还没被计入滚动范围，补偿量会偏小
        javax.swing.SwingUtilities.invokeLater(() -> target.getVerticalScrollBar().setValue(shift));
    }

    /**
     * 在容器中查找聊天记录滚动面板。
     *
     * @param container 容器（聊天面板）
     * @return 滚动面板；未找到返回 null
     */
    private JScrollPane scrollPaneOf(java.awt.Container container) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof JScrollPane) {
                return (JScrollPane) component;
            }
        }
        return null;
    }

    /**
     * 构建聊天窗口顶部的"加载更早的消息"入口。
     *
     * <p>刻意放在聊天区上方而不是底部：更早的记录会插入到聊天区顶部，
     * 入口与其作用位置在同一侧，使用者不必先把滚动条拖到顶部再找按钮。</p>
     *
     * @param peer 对端用户名
     * @return 面板
     */
    private JPanel buildEarlierPanel(String peer) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        panel.setOpaque(false);
        panel.add(SkinButton.normal("加载更早的消息", e -> loadEarlierMessages(peer)));
        return panel;
    }

    /**
     * 按当前游标向前加载一页历史记录。
     *
     * @param peer 对端用户名
     */
    private void loadEarlierMessages(String peer) {
        String cursor = historyCursors.get(peer);
        if (cursor == null || cursor.isEmpty()) {
            // 没有游标说明服务端已经表示"没有更早的记录"，或首次加载还没回来
            statusLabel.setText("没有更早的消息了");
            return;
        }
        earlierRequests.add(peer);
        if (!client.requestHistory(client.getUsername(), "", "", peer, cursor, HISTORY_PAGE_SIZE)) {
            earlierRequests.remove(peer);
            showError("加载更早的消息失败，请检查与服务端的连接");
            return;
        }
        statusLabel.setText("正在加载更早的消息……");
    }

    /**
     * 弹出关键字输入框并提交搜索请求。
     */
    private void searchHistory() {
        String keyword = JOptionPane.showInputDialog(this, "请输入要搜索的关键字:", "");
        if (keyword == null || keyword.trim().isEmpty()) {
            return;
        }
        if (!client.requestSearch(keyword.trim())) {
            showError("搜索请求发送失败，请检查与服务端的连接");
            return;
        }
        statusLabel.setText("正在搜索聊天记录……");
    }

    /**
     * 展示搜索结果。
     *
     * <p>正文格式：成功为 {@code 1|条数} 加若干行 {@code 时间|发送者|接收者|正文}，
     * 失败为 {@code 0|中文原因}。</p>
     *
     * @param message 搜索结果消息
     */
    private void showSearchResult(TextMessage message) {
        String content = message.getContent() == null ? "" : message.getContent();
        String[] lines = content.split("\n", -1);
        String[] head = lines.length > 0 ? lines[0].split("\\|", -1) : new String[]{"0", "无数据"};
        if (head.length < 2 || !"1".equals(head[0])) {
            statusLabel.setText("搜索失败");
            showError("搜索失败: " + (head.length > 1 ? head[1] : "未知原因"));
            return;
        }
        List<String[]> hits = new java.util.ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            // 正文可能含竖线，限制切分次数可保证它完整落在最后一个字段
            String[] fields = lines[i].split("\\|", 4);
            if (fields.length < 4) {
                continue;
            }
            hits.add(fields);
        }
        statusLabel.setText("搜索完成，共 " + head[1] + " 条命中");
        if (hits.isEmpty()) {
            showInfo("没有找到匹配的聊天记录");
            return;
        }
        showSearchDialog(head[1], hits);
    }

    /**
     * 用非模态对话框展示搜索结果表格。
     *
     * <p>刻意不用模态对话框：双击结果行要打开对应会话窗口，
     * 模态对话框会把新窗口压在下面，使用者看不到"跳转"的结果。</p>
     *
     * @param count 命中条数（服务端给出，原样展示）
     * @param hits  命中记录，每项为 {@code {时间, 发送者, 接收者, 正文}}
     */
    private void showSearchDialog(String count, List<String[]> hits) {
        Object[][] rows = new Object[hits.size()][4];
        for (int i = 0; i < hits.size(); i++) {
            String[] fields = hits.get(i);
            rows[i] = new Object[]{fields[0], fields[1], fields[2], fields[3]};
        }
        DefaultTableModel model = new DefaultTableModel(rows,
                new Object[]{"时间", "发送者", "接收者", "内容"}) {

            /** 序列化版本号 */
            private static final long serialVersionUID = 20260921L;

            /**
             * 禁止直接编辑单元格。
             *
             * @param row    行号
             * @param column 列号
             * @return 恒为 false
             */
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setFont(FONT_NORMAL);
        table.setRowHeight(24);
        table.addMouseListener(new MouseAdapter() {

            /**
             * 双击结果行跳转到对应会话。
             *
             * @param e 鼠标事件
             */
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0) {
                    return;
                }
                // 表格支持排序，必须把视图行号换算成模型行号再取值
                int modelRow = table.convertRowIndexToModel(row);
                jumpToSearchHit(String.valueOf(model.getValueAt(modelRow, 1)),
                        String.valueOf(model.getValueAt(modelRow, 2)));
            }
        });

        JLabel tip = new JLabel("共 " + count + " 条命中，双击某行可跳转到对应会话");
        tip.setFont(Theme.fontSmall());
        tip.setForeground(Theme.TEXT_WEAK);
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBackground(Theme.CARD);
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        content.add(tip, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);

        JDialog dialog = new JDialog(this, "搜索结果", false);
        dialog.setLayout(new BorderLayout());
        dialog.add(content, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        actions.setBackground(Theme.CARD);
        actions.add(SkinButton.normal("关闭", event -> dialog.dispose()));
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.setSize(780, 460);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * 从搜索命中记录跳转到对应会话。
     *
     * @param sender   记录中的发送者
     * @param receiver 记录中的接收者
     */
    private void jumpToSearchHit(String sender, String receiver) {
        String self = client.getUsername();
        String peer;
        if (self.equals(sender)) {
            peer = receiver;
        } else if (self.equals(receiver)) {
            peer = sender;
        } else {
            showInfo("这条记录不属于当前账号的私聊会话，无法跳转");
            return;
        }
        if (peer == null || peer.isEmpty() || Constants.BROADCAST_TAG.equals(peer) || self.equals(peer)) {
            // 群聊记录没有单一会话对象，打开私聊窗口没有意义
            showInfo("该消息为群聊消息，无法定位到私聊窗口，请在群聊大厅查看");
            return;
        }
        openPrivateWindow(peer);
        statusLabel.setText("已打开与 " + peer + " 的会话");
    }

    /**
     * 处理撤回相关通知。
     *
     * <p>三种正文：{@code OK|messageId} 表示本人撤回成功，
     * {@code FAIL|messageId|中文原因} 表示服务端拒绝，
     * {@code PEER|messageId|发送者} 表示对端撤回了某条消息。</p>
     *
     * @param message 撤回通知
     */
    private void handleRecallNotice(TextMessage message) {
        String content = message.getContent() == null ? "" : message.getContent();
        String[] fields = content.split("\\|", 3);
        if (fields.length < 2 || fields[1].trim().isEmpty()) {
            LOGGER.warning("撤回通知格式非法，已忽略");
            return;
        }
        String state = fields[0].trim();
        String messageId = fields[1].trim();
        BubbleRef known = bubbleIndex.get(messageId);
        if ("OK".equals(state)) {
            if (!replaceBubble(messageId, "你撤回了一条消息")) {
                appendRecallLine(known == null ? null : known.peer, "你撤回了一条消息");
            }
            statusLabel.setText("消息已撤回");
        } else if ("FAIL".equals(state)) {
            String reason = fields.length > 2 && !fields[2].trim().isEmpty()
                    ? fields[2].trim() : "未知原因";
            statusLabel.setText("撤回失败");
            showError("撤回失败：" + reason);
        } else if ("PEER".equals(state)) {
            String sender = fields.length > 2 && !fields[2].trim().isEmpty()
                    ? fields[2].trim() : (known == null ? "对方" : known.peer);
            if (!replaceBubble(messageId, "对方撤回了一条消息")) {
                appendRecallLine(sender, "对方撤回了一条消息");
            }
            statusLabel.setText(sender + " 撤回了一条消息");
        } else {
            LOGGER.warning(() -> "未知的撤回通知状态: " + state);
        }
    }

    /**
     * 把对应气泡替换为撤回提示。
     *
     * <p>气泡由 {@link MessageBubble} 绘制，其正文是一个只读的 {@code JTextArea}。
     * 这里只通过组件树与公开方法定位并改写它，不访问聊天面板的私有字段：
     * 面板怎么组织气泡属于它的实现细节，主窗口依赖"气泡正文可被替换"这一最小约定。
     * 定位失败时返回 false，由调用方降级为追加一行系统提示，保证撤回结果一定可见。</p>
     *
     * @param messageId 稳定消息标识
     * @param newText   替换后的文本
     * @return 完成替换返回 true；找不到气泡返回 false
     */
    private boolean replaceBubble(String messageId, String newText) {
        BubbleRef ref = bubbleIndex.get(messageId);
        if (ref == null) {
            return false;
        }
        PrivateChatUI window = privateWindows.get(ref.peer);
        if (window == null) {
            return false;
        }
        MessageBubble bubble = findBubble(window.getPanel(), ref.content, ref.mine);
        if (bubble == null) {
            return false;
        }
        JTextArea body = findContentArea(bubble);
        if (body == null) {
            return false;
        }
        body.setText(newText);
        body.setForeground(BaseChatPanel.COLOR_SYSTEM);
        bubble.setStatusText("已撤回");
        bubble.revalidate();
        bubble.repaint();
        bubbleIndex.remove(messageId);
        return true;
    }

    /**
     * 在组件树中查找指定正文的气泡。
     *
     * @param container 容器
     * @param content   消息正文
     * @param mine      是否为本人发送的气泡
     * @return 匹配的气泡；未找到返回 null
     */
    private MessageBubble findBubble(java.awt.Container container, String content, boolean mine) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof MessageBubble) {
                MessageBubble bubble = (MessageBubble) component;
                boolean self = bubble.getKind() == MessageBubble.Kind.SELF;
                if (self == mine && content.equals(bubble.getContentText())) {
                    return bubble;
                }
            }
            if (component instanceof java.awt.Container) {
                MessageBubble found = findBubble((java.awt.Container) component, content, mine);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 在气泡内部查找承载正文的文本区。
     *
     * @param container 气泡组件
     * @return 正文文本区；未找到返回 null
     */
    private JTextArea findContentArea(java.awt.Container container) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof JTextArea) {
                return (JTextArea) component;
            }
            if (component instanceof java.awt.Container) {
                JTextArea found = findContentArea((java.awt.Container) component);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 在对应会话窗口写入一行撤回提示。
     *
     * <p>这是气泡替换失败时的降级路径：即使界面结构变化导致找不到目标气泡，
     * 使用者也一定能看到"已撤回"的结果，而不是点了撤回却毫无反馈。</p>
     *
     * @param peer 对端用户名，可为 null
     * @param text 提示文本
     */
    private void appendRecallLine(String peer, String text) {
        if (peer == null) {
            return;
        }
        PrivateChatUI window = privateWindows.get(peer);
        if (window != null) {
            window.getPanel().appendLine("[系统] " + text, BaseChatPanel.COLOR_SYSTEM);
        }
    }

    /**
     * 弹出可撤回消息列表并提交撤回请求。
     *
     * <p>撤回入口放在主窗口菜单而不是聊天气泡右键：气泡菜单由聊天面板实现，
     * 而"哪条消息是本机刚发出、是否还在 2 分钟内"这类信息只有掌握发送状态的
     * 主窗口知道，放在这里可以避免两处各记一份而互相矛盾。</p>
     */
    private void recallMessage() {
        long now = System.currentTimeMillis();
        List<Map.Entry<String, BubbleRef>> candidates = new java.util.ArrayList<>();
        for (Map.Entry<String, BubbleRef> entry : bubbleIndex.entrySet()) {
            BubbleRef ref = entry.getValue();
            if (ref.mine && now - ref.createdAt <= RECALL_WINDOW_MS) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            showInfo("最近 2 分钟内没有可撤回的消息");
            return;
        }
        candidates.sort((left, right) ->
                Long.compare(right.getValue().createdAt, left.getValue().createdAt));
        String[] options = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            BubbleRef ref = candidates.get(i).getValue();
            options[i] = "发给 " + ref.peer + "：" + ref.content;
        }
        Object choice = JOptionPane.showInputDialog(this, "选择要撤回的消息（仅限 2 分钟内发送）:",
                "撤回消息", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == null) {
            return;
        }
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(choice)) {
                if (!client.requestRecall(candidates.get(i).getKey())) {
                    showError("撤回请求发送失败，请检查与服务端的连接");
                } else {
                    statusLabel.setText("撤回请求已发送……");
                }
                return;
            }
        }
    }

    /**
     * 登记一个气泡，供撤回时定位。
     *
     * @param messageId 稳定消息标识
     * @param peer      对端用户名
     * @param content   消息正文
     * @param mine      是否本人发送
     */
    private void rememberBubble(String messageId, String peer, String content, boolean mine) {
        if (messageId == null || messageId.isEmpty() || peer == null || content == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long deadline = now - BUBBLE_KEEP_MS;
        // 索引只在"可能被撤回"的时间窗内有用，过期即清理，避免长会话下无限增长
        bubbleIndex.entrySet().removeIf(entry -> entry.getValue().createdAt < deadline);
        bubbleIndex.put(messageId, new BubbleRef(peer, content, mine, now));
    }

    /**
     * 处理服务端补投的离线消息。
     *
     * <p>离线消息与普通私聊消息的唯一区别是来源：它是接收方离线期间积压、上线后补发的。
     * 之所以不直接复用 {@link #handlePrivateMessage(TextMessage)}：那条路径会按普通消息渲染，
     * 使用者无法分辨"刚刚发来的"和"离线期间积压的"；这里在发送者后面加一个离线标记，
     * 渲染颜色与普通私聊保持一致。网络层已负责回确认与去重，本方法只做展示。</p>
     *
     * @param message 离线消息
     */
    private void handleOfflineMessage(TextMessage message) {
        String sender = message.getSender();
        if (sender == null || sender.isEmpty()) {
            return;
        }
        String content = message.getContent() == null ? "" : message.getContent();
        onEdt(() -> {
            PrivateChatPanel panel = openPrivateWindow(sender).getPanel();
            panel.appendMessage(sender + "（离线消息）", content, BaseChatPanel.COLOR_OTHER);
            // 离线消息同样登记气泡：对方撤回时也要能把这一行替换掉
            rememberBubble(message.getMessageId(), sender, content, false);
        });
    }

    /**
     * 处理连接状态变化。
     *
     * <p>重连标记必须在网络线程上同步读取：本方法回调后要切到事件分发线程执行，
     * 而重连可能在这段间隙内已经结束，届时再读 {@code isReconnecting()} 会得到 false，
     * 界面就会把"刚刚重连成功"误判成"彻底断开"而退出程序。</p>
     *
     * @param connected 是否连接
     * @param reason    原因
     */
    @Override
    public void onConnectionChanged(boolean connected, String reason) {
        boolean reconnecting = client.isReconnecting();
        onEdt(() -> {
            statusLabel.setText(reason);
            serverHint.setText(serverHintText(connected));
            if (connected) {
                panelsNotifiedDisconnect = false;
            }
            // 断线期间只在首次通知时向聊天面板写入一行，避免每次重连尝试都刷屏
            if (connected || !panelsNotifiedDisconnect || !reconnecting) {
                for (PrivateChatUI window : privateWindows.values()) {
                    window.getPanel().onConnectionChanged(connected, reason);
                }
                if (groupWindow != null) {
                    groupWindow.getPanel().onConnectionChanged(connected, reason);
                }
                if (!connected) {
                    panelsNotifiedDisconnect = true;
                }
            }
            if (!connected && !loggingOut && !reconnecting) {
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
     * 处理消息发送状态变化。
     *
     * <p>状态一律写入底部状态栏；"对方离线""发送失败"这类需要使用者关注的状态，
     * 额外在对应私聊窗口留下一条系统提示行。发送中与已送达不写行，
     * 否则每发一条消息都会多出一行状态，聊天记录会被状态信息挤满。</p>
     *
     * @param messageId 稳定消息标识
     * @param state     新状态
     * @param detail    补充说明
     */
    @Override
    public void onMessageState(String messageId, ChatClient.SendState state, String detail) {
        // 与连接状态同理：对端用户名要在网络线程上取，切到事件分发线程后记录可能已被清理
        String peer = client.getMessageReceiver(messageId);
        String text = sendStateText(state, detail);
        if (state == ChatClient.SendState.SENDING) {
            // 本地回显由聊天面板负责渲染，这里只登记索引：撤回时界面需要凭消息标识找到那一行
            String content = client.getMessageContent(messageId);
            if (content != null) {
                rememberBubble(messageId, peer, content, true);
            }
        }
        onEdt(() -> {
            statusLabel.setText(text);
            boolean noteworthy = state == ChatClient.SendState.OFFLINE
                    || state == ChatClient.SendState.FAILED;
            if (!noteworthy || peer == null) {
                return;
            }
            PrivateChatUI window = privateWindows.get(peer);
            if (window != null) {
                window.getPanel().appendLine("[系统] " + text, BaseChatPanel.COLOR_SYSTEM);
            }
        });
    }

    /**
     * 把发送状态转换为界面提示文本。
     *
     * @param state  发送状态
     * @param detail 服务端给出的补充说明，可为空
     * @return 中文提示文本
     */
    private String sendStateText(ChatClient.SendState state, String detail) {
        String extra = detail == null ? "" : detail.trim();
        switch (state) {
            case SENDING:
                return extra.isEmpty() ? "消息发送中……" : "消息发送中……（" + extra + "）";
            case DELIVERED:
                return "消息已送达";
            case OFFLINE:
                return extra.isEmpty() ? "对方离线，消息将在其上线后送达" : "对方离线：" + extra;
            case FAILED:
                return extra.isEmpty() ? "消息发送失败" : "消息发送失败：" + extra;
            default:
                return state.getDescription();
        }
    }

    /**
     * 窗口关闭时断开连接并退出程序。
     */
    @Override
    public void dispose() {
        client.removeListener(this);
        client.removeMessageStateListener(this);
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
        bubbleIndex.clear();
        historyCursors.clear();
        earlierRequests.clear();
        super.dispose();
    }

    /**
     * 聊天气泡的索引信息。
     *
     * <p>撤回要在界面上精确定位"哪一行是目标消息"，而聊天记录控件只保存纯文本，
     * 本身不带消息标识；索引把标识与"哪段正文属于哪个会话"对应起来，
     * 用正文反向定位行比记录行号更稳——补拉历史或插入行都会让行号失效。</p>
     */
    private static final class BubbleRef {

        /** 气泡所属会话的对端用户名 */
        private final String peer;

        /** 消息正文（用于在聊天记录中定位该行） */
        private final String content;

        /** 是否为本人发出的消息 */
        private final boolean mine;

        /** 记录时刻（毫秒），用于判断是否还在撤回时限内 */
        private final long createdAt;

        /**
         * 构造气泡索引项。
         *
         * @param peer      对端用户名
         * @param content   消息正文
         * @param mine      是否本人发送
         * @param createdAt 记录时刻
         */
        private BubbleRef(String peer, String content, boolean mine, long createdAt) {
            this.peer = peer;
            this.content = content;
            this.mine = mine;
            this.createdAt = createdAt;
        }
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

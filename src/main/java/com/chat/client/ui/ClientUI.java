package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ChatListener;
import com.chat.client.ui.theme.AvatarFactory;
import com.chat.client.ui.theme.Glyphs;
import com.chat.client.ui.theme.PngIcons;
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
import com.chat.util.FileUtil;
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
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端主窗口：登录成功后展示在线用户列表和功能入口，
 * 负责创建并管理私聊、群聊、设置等窗口。
 * 服务端推送的消息在这里按类型分发到对应窗口，
 * 未读计数、历史分页与导出也由本类维护。
 */
public class ClientUI extends BaseUI implements ChatListener, ChatClient.MessageStateListener {
    private static final long serialVersionUID = 20250119L;

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(Constants.LOGGER_NAME + ".ClientUI");

    /** 请求服务端响应的超时时间（毫秒），导出与刷新列表共用 */
    private static final int EXPORT_TIMEOUT_MS = 10000;

    private static final int TILE_ICON_SIDE = 40;

    private final transient ChatClient client;

    private transient Timer exportTimer;

    private transient String exportLabel;

    /** 本次导出的文件标识，取对端用户名或 group */
    private transient String exportTag;

    private transient boolean manualRefreshPending;

    private transient Timer refreshTimer;

    private final transient Map<String, PrivateChatUI> privateWindows = new ConcurrentHashMap<>();

    private transient GroupChatUI groupWindow;

    private transient SettingsUI settingsWindow;

    private transient SkinButton groupButton;

    private transient int unreadGroupCount;

    private final transient Map<String, Integer> unreadPrivate = new java.util.LinkedHashMap<>();

    private final transient DisconnectBanner banner = new DisconnectBanner();

    private transient boolean bannerWasReconnecting;

    private transient Timer bannerTimer;

    /** 取不到平台双击间隔时使用的兜底值（毫秒） */
    private static final int PROFILE_CLICK_DELAY_FALLBACK_MS = 300;

    private transient Timer profileClickTimer;

    private transient volatile boolean loggingOut;

    private transient boolean panelsNotifiedDisconnect;

    /** 撤回时限（毫秒） */
    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;

    /** 气泡索引的保留时长（毫秒） */
    private static final long BUBBLE_KEEP_MS = 10 * 60 * 1000L;

    /** 历史分页每页条数 */
    private static final String HISTORY_PAGE_SIZE = "50";

    private static final java.time.format.DateTimeFormatter TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 服务端历史与搜索结果的行格式版本标记 */
    private static final String ROW_VERSION = "v2";

    /** 分页游标行的前缀，该行只用于翻页、不渲染 */
    private static final String HISTORY_CURSOR_PREFIX = "#beforeId=";

    /** 消息标识到气泡位置的索引，撤回时用来定位气泡 */
    private final transient Map<String, BubbleRef> bubbleIndex = new ConcurrentHashMap<>();

    /** 对端用户名到上一页游标的映射 */
    private final transient Map<String, String> historyCursors = new ConcurrentHashMap<>();

    private transient JDialog queryDialog;

    private transient DefaultTableModel queryModel;

    /** 查询结果的原始行，与结果表行一一对应 */
    private final transient List<String[]> queryRecords = new java.util.ArrayList<>();

    /** 已发起向前翻页请求的会话集合 */
    private final transient java.util.Set<String> earlierRequests =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final OnlineUserTree userTree = new OnlineUserTree();

    private final JLabel statusLabel = new JLabel("已连接");

    private final JLabel serverHint = new JLabel();

    private final JLabel headerMeta = new JLabel();

    private final JLabel headerAvatar = new JLabel();

    private final JLabel headerName = new JLabel();

    /**
     * 创建客户端主窗口。
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
    }

    private void initComponents() {
        // 单击查看资料卡，双击打开私聊窗口
        userTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    cancelProfileCard();
                    openPrivateChatWithSelected();
                } else if (e.getClickCount() == 1) {
                    scheduleProfileCard(e);
                }
            }
        });

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildUserPanel(), buildFunctionPanel());
        splitPane.setDividerLocation(440);
        splitPane.setResizeWeight(0.55);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setOpaque(false);

        // 头部卡片与断线横幅都放在窗口顶部
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(buildHeader(), BorderLayout.NORTH);
        north.add(banner, BorderLayout.SOUTH);

        body().setLayout(new BorderLayout());
        body().add(north, BorderLayout.NORTH);
        body().add(splitPane, BorderLayout.CENTER);
        body().add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private void scheduleProfileCard(MouseEvent event) {
        cancelProfileCard();
        // 等过双击间隔仍无第二次点击再弹卡片
        profileClickTimer = new Timer(doubleClickInterval(),
                mouseEvent -> showProfileCardAt(event));
        profileClickTimer.setRepeats(false);
        profileClickTimer.start();
    }

    private void cancelProfileCard() {
        if (profileClickTimer != null) {
            profileClickTimer.stop();
            profileClickTimer = null;
        }
    }

    private static int doubleClickInterval() {
        Object interval = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
        return interval instanceof Integer ? (Integer) interval : PROFILE_CLICK_DELAY_FALLBACK_MS;
    }

    private void showProfileCardAt(MouseEvent event) {
        TreePath path = userTree.getPathForLocation(event.getX(), event.getY());
        if (path == null) {
            return;
        }
        User user = userTree.selectedUserOn(path);
        if (user != null) {
            UserProfileCard.show(this, user, userTree.isOnline(user.getUsername()),
                    () -> openPrivateWindow(user.getUsername()));
        }
    }

    private JPanel buildHeader() {
        JPanel card = card();
        card.setLayout(new BorderLayout(12, 0));

        JPanel identity = new JPanel(new GridLayout(2, 1, 0, 2));
        identity.setOpaque(false);
        headerName.setText(displayName() + "（" + client.getUsername() + "）");
        headerName.setFont(Theme.font(16, Font.BOLD));
        headerName.setForeground(Theme.TEXT);
        headerMeta.setFont(Theme.fontSmall());
        headerMeta.setForeground(Theme.TEXT_WEAK);
        headerMeta.setText("正在获取在线用户……");
        identity.add(headerName);
        identity.add(headerMeta);

        headerAvatar.setIcon(AvatarFactory.avatar(client.getUsername(), true, false, 48));
        card.add(headerAvatar, BorderLayout.WEST);
        card.add(identity, BorderLayout.CENTER);
        return card;
    }

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
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                userTree.setFilter(searchField.getText());
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                userTree.setFilter(searchField.getText());
            }

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

        buttons.add(SkinButton.normal("刷新列表", e -> refreshUserListManually()));
        panel.add(buttons, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(450, 480));
        return panel;
    }

    private JPanel buildFunctionPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 8));

        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8));
        grid.setOpaque(false);
        groupButton = functionTile("群聊大厅", "群聊大厅", Glyphs.group(40, Theme.PRIMARY),
                e -> openGroupWindow(true));
        grid.add(groupButton);

        grid.add(functionTile("搜索", "搜索", Glyphs.search(40, Theme.PRIMARY),
                e -> showQueryDialog()));
        grid.add(functionTile("设置", "设置", Glyphs.settings(40, Theme.PRIMARY),
                e -> openSettings()));
        grid.add(functionTile("退出登录", "退出", Glyphs.logout(40, Theme.TEXT_WEAK),
                e -> logout()));
        panel.add(grid, BorderLayout.CENTER);

        JTextArea tips = new JTextArea();
        tips.setEditable(false);
        tips.setLineWrap(true);
        tips.setWrapStyleWord(true);
        tips.setFont(Theme.fontSmall());
        tips.setForeground(Theme.TEXT_WEAK);
        tips.setBackground(Theme.BG);
        tips.setText("使用提示：\n"
                + "1. 在线用户列表由服务器实时推送，用户上线/下线会自动刷新；也可点“刷新列表”主动拉取。\n"
                + "2. 双击某个用户即可开始私聊；离线的用户会被置灰并归入“离线”分组。\n"
                + "3. 双击自己可以打开“与我”的会话，用来记事、发文件给自己。\n"
                + "4. 发送文件在私聊窗口或群聊窗口内点击“发送文件”：私聊发给对方，群聊发给全部在线用户。\n"
                + "5. 接收到的文件保存在 data/received 目录；聊天记录保存在服务器数据库中，可随时查询或导出。\n"
                + "6. 聊天记录可按日期范围或按关键字查找，双击结果行可跳转到对应会话。");
        panel.add(new JScrollPane(tips), BorderLayout.SOUTH);
        return panel;
    }

    private SkinButton functionTile(String text, String iconName, javax.swing.Icon fallback,
                                    java.awt.event.ActionListener action) {
        SkinButton button = new SkinButton(text, SkinButton.Kind.NORMAL);
        button.setIcon(PngIcons.load(iconName, TILE_ICON_SIDE, fallback));
        button.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        button.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        button.setIconTextGap(6);
        button.setFont(Theme.font(14, Font.PLAIN));
        button.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 6));
        button.addActionListener(action);
        return button;
    }

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

    private String serverHintText(boolean connected) {
        return "服务器: " + (connected ? "已连接" : "未连接")
                + "    用户: " + client.getUsername() + "    ";
    }

    private String displayName() {
        String nickname = client.getNickname();
        return nickname == null || nickname.trim().isEmpty() ? client.getUsername() : nickname;
    }

    private boolean isAdmin() {
        User self = userTree.onlineUsers().get(client.getUsername());
        return self != null && self.isAdmin();
    }

    private void openPrivateChatWithSelected() {
        User target = userTree.selectedUser();
        if (target == null) {
            showInfo("请先在左侧列表中选择一个用户");
            return;
        }
        if (!userTree.selectedIsOnline()) {
            statusLabel.setText("提示：" + target.getUsername() + " 已离线，消息可能无法送达");
        }
        openPrivateWindow(target.getUsername());
    }

    /**
     * 打开与指定用户的私聊窗口，已打开时置顶。
     *
     * @param username 对方用户名
     * @return 私聊窗口
     */
    public PrivateChatUI openPrivateWindow(String username) {
        return privateWindowFor(username, true);
    }

    /** 取出或创建与指定用户的私聊窗口，窗口未显示时累计未读。 */
    private PrivateChatUI privateWindowFor(String username, boolean show) {
        PrivateChatUI window = privateWindows.get(username);
        if (window == null) {
            window = new PrivateChatUI(client, username);
            privateWindows.put(username, window);

            // 装配头像与撤回处理
            preparePanel(window.getPanel(), username, false);

            window.getPanel().add(buildConversationTools(username, false), BorderLayout.NORTH);

            window.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowGainedFocus(java.awt.event.WindowEvent event) {
                    clearPrivateUnread(username);
                }
            });

            // 首次打开时补拉与对方的聊天记录
            client.requestHistory(client.getUsername(), "", "", username);
        }
        if (show) {
            clearPrivateUnread(username);
            if (!window.isVisible()) {
                window.setVisible(true);
            }
            int state = window.getExtendedState();
            if ((state & java.awt.Frame.ICONIFIED) != 0) {
                window.setExtendedState(state & ~java.awt.Frame.ICONIFIED);
            }
            if (!window.isFocused()) {
                window.toFront();
            }
        } else if (!window.isShowing()
                || (window.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) {
            // 窗口未显示时只累计未读，不主动弹出
            addPrivateUnread(username);
        }

        window.getPanel().setSendTargetAvailable(userTree.isOnline(username)
                || client.getUsername().equals(username));
        return window;
    }

    private void preparePanel(BaseChatPanel panel, String peer, boolean group) {
        panel.setAvatarProvider(this::avatarFor);
        panel.setRecallHandler(new BaseChatPanel.RecallHandler() {
            @Override
            public boolean canRecall(String messageId) {
                return canRecallNow(messageId);
            }

            @Override
            public void recall(String messageId) {
                submitRecall(messageId);
            }

            @Override
            public void remember(String messageId, String content, boolean mine,
                                 java.time.LocalDateTime time) {
                rememberBubbleAt(messageId, peer, group, content, mine, time);
            }
        });
    }

    private javax.swing.Icon avatarFor(String username) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        User user = userTree.findUser(username);
        return AvatarFactory.avatar(username, user == null || userTree.isOnline(username),
                user != null && user.isAdmin(), MessageBubble.AVATAR_SIZE);
    }

    private void addPrivateUnread(String username) {
        unreadPrivate.merge(username, 1, Integer::sum);
        refreshUnreadBadges();
    }

    private void clearPrivateUnread(String username) {
        if (unreadPrivate.remove(username) != null) {
            refreshUnreadBadges();
        }
    }

    /** 把私聊与群聊的未读数汇总到用户树和标题栏角标。 */
    private void refreshUnreadBadges() {
        userTree.setUnread(unreadPrivate);
        int total = unreadGroupCount;
        for (int count : unreadPrivate.values()) {
            total += count;
        }
        if (titleBar() != null) {
            titleBar().setBadgeCount(total);
        }
    }

    /**
     * 打开群聊大厅，首次调用时创建窗口。
     *
     * @param show 是否显示窗口并置顶
     * @return 群聊窗口
     */
    public GroupChatUI openGroupWindow(boolean show) {
        if (groupWindow == null) {
            groupWindow = new GroupChatUI(client, client.getNickname(),
                    () -> new java.util.ArrayList<>(userTree.onlineUsers().keySet()));

            preparePanel(groupWindow.getPanel(), Constants.BROADCAST_TAG, true);

            groupWindow.getPanel().add(buildConversationTools(null, true), BorderLayout.NORTH);

            groupWindow.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowGainedFocus(java.awt.event.WindowEvent event) {
                    unreadGroupCount = 0;
                    updateGroupButtonText();
                }
            });
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

    /** 取群聊面板，窗口未显示时累计未读。 */
    private GroupChatPanel groupPanel() {
        GroupChatPanel panel = groupPanelNoUnread();

        if (!groupWindow.isShowing()
                || (groupWindow.getExtendedState() & java.awt.Frame.ICONIFIED) != 0) {
            unreadGroupCount++;
            updateGroupButtonText();
        }
        return panel;
    }

    /** 取群聊面板，不改变未读计数。 */
    private GroupChatPanel groupPanelNoUnread() {
        openGroupWindow(false);
        return groupWindow.getPanel();
    }

    private void updateGroupButtonText() {
        if (groupButton != null) {
            groupButton.setText(unreadGroupCount == 0
                    ? "群聊大厅" : "群聊大厅（" + unreadGroupCount + " 条未读）");
        }

        refreshUnreadBadges();
    }

    private void logout() {
        if (!confirm("确定要退出当前账号 " + client.getUsername() + " 吗？")) {
            return;
        }
        loggingOut = true;
        final String host = client.getServerHost();
        final int port = client.getServerPort();

        // 在后台发送退出请求，结束后回到登录窗口
        new javax.swing.SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return client.sendLogout();
            }

            @Override
            protected void done() {
                dispose();
                LoginUI login = new LoginUI(host, port);
                login.setVisible(true);
            }
        }.execute();
    }

    private void showQueryDialog() {
        if (queryDialog != null && queryDialog.isDisplayable()) {
            queryDialog.toFront();
            return;
        }
        JTextField userField = new JTextField(client.getUsername());
        JTextField fromField = new JTextField(LocalDate.now().minusDays(7).toString());
        JTextField toField = new JTextField(LocalDate.now().toString());
        JTextField keywordField = new JTextField();

        JPanel byDate = new JPanel(new GridLayout(3, 2, 6, 6));
        byDate.setBackground(Theme.CARD);
        byDate.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        byDate.add(new JLabel("用户名（留空查全部）:"));
        byDate.add(userField);
        byDate.add(new JLabel("起始日期 (yyyy-MM-dd):"));
        byDate.add(fromField);
        byDate.add(new JLabel("结束日期 (yyyy-MM-dd):"));
        byDate.add(toField);

        JPanel byKeyword = new JPanel(new BorderLayout(6, 6));
        byKeyword.setBackground(Theme.CARD);
        byKeyword.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel keywordRow = new JPanel(new BorderLayout(6, 0));
        keywordRow.setOpaque(false);
        keywordRow.add(new JLabel("关键字:"), BorderLayout.WEST);
        keywordRow.add(keywordField, BorderLayout.CENTER);
        JLabel keywordHint = new JLabel("由服务端解密后匹配，可搜到历史会话中的任意片段");
        keywordHint.setFont(Theme.fontSmall());
        keywordHint.setForeground(Theme.TEXT_WEAK);
        byKeyword.add(keywordRow, BorderLayout.NORTH);
        byKeyword.add(keywordHint, BorderLayout.CENTER);

        javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
        tabs.addTab("按日期", byDate);
        tabs.addTab("按内容", byKeyword);

        queryModel = new DefaultTableModel(new Object[0][],
                new Object[]{"时间", "类型", "会话对象", "内容"}) {
            private static final long serialVersionUID = 20260922L;

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(queryModel);
        table.setAutoCreateRowSorter(true);
        table.setFont(FONT_NORMAL);
        table.setRowHeight(24);
        // 双击结果行跳到对应会话
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0) {
                    return;
                }

                String[] record = recordAt(table.convertRowIndexToModel(row));
                if (record != null) {
                    jumpToRecord(record);
                }
            }
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        actions.setBackground(Theme.CARD);
        // 按当前页签执行日期查询或内容搜索
        actions.add(SkinButton.primary("查询", event -> {
            if (tabs.getSelectedIndex() == 0) {
                runDateQuery(userField.getText().trim(), fromField.getText().trim(),
                        toField.getText().trim());
            } else {
                runKeywordQuery(keywordField.getText().trim());
            }
        }));
        actions.add(SkinButton.normal("关闭", event -> queryDialog.dispose()));

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBackground(Theme.CARD);
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(tabs, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);

        JDialog dialog = new JDialog(this, "查询/搜索聊天记录", false);
        queryDialog = dialog;
        dialog.setLayout(new BorderLayout());
        dialog.add(content, BorderLayout.CENTER);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.setSize(820, 520);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void runDateQuery(String username, String from, String to) {
        if (!client.requestHistory(username, from, to)) {
            showError("查询请求发送失败，请检查与服务端的连接");
            return;
        }
        awaitQueryResult("正在查询聊天记录……");
    }

    private void runKeywordQuery(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            showInfo("请先输入要搜索的关键字");
            return;
        }
        if (!client.requestSearch(keyword)) {
            showError("搜索请求发送失败，请检查与服务端的连接");
            return;
        }
        awaitQueryResult("正在搜索聊天记录……");
    }

    private void awaitQueryResult(String pending) {
        queryRecords.clear();
        if (queryModel != null) {
            queryModel.setRowCount(0);
        }
        statusLabel.setText(pending);
    }

    private void fillResultTable(List<String[]> records, String summary) {
        queryRecords.clear();
        queryRecords.addAll(records);
        if (queryModel != null) {
            queryModel.setRowCount(0);
            for (String[] record : records) {
                queryModel.addRow(displayRowOf(record));
            }
        }
        statusLabel.setText(summary);
        if (records.isEmpty()) {
            showInfo("没有找到符合条件的聊天记录");
        }
    }

    private Object[] displayRowOf(String[] record) {
        String receiver = record[2] == null ? "" : record[2];
        boolean group = receiver.isEmpty() || Constants.BROADCAST_TAG.equals(receiver);
        if (group) {
            return new Object[]{record[0], "群聊", senderOf(record) + " → 全体", record[4]};
        }
        return new Object[]{record[0], "私聊", peerOf(record), record[4]};
    }

    private String peerOf(String[] record) {
        String self = client.getUsername();
        if (self.equals(record[1])) {
            return record[2];
        }
        if (self.equals(record[2])) {
            return record[1];
        }
        return "—";
    }

    private String senderOf(String[] record) {
        return record[1] == null ? "" : record[1];
    }

    private String[] recordAt(int modelRow) {
        if (modelRow < 0 || modelRow >= queryRecords.size()) {
            return null;
        }
        return queryRecords.get(modelRow);
    }

    /** 请求服务端导出当前会话记录，并启动等待超时计时。 */
    private void exportConversation(String peer, boolean group) {
        String scope = group ? Constants.BROADCAST_SCOPE
                : Constants.EXPORT_SCOPE_PREFIX + (peer == null ? "" : peer);
        if (!client.requestExport(scope)) {
            showError("导出请求发送失败，请检查与服务端的连接");
            return;
        }

        exportLabel = group ? "群聊大厅" : ("与 " + peer + " 的私聊");
        exportTag = group ? "group" : (peer == null ? "peer" : peer);
        statusLabel.setText("正在导出" + exportLabel + "……");

        if (exportTimer != null) {
            exportTimer.stop();
        }
        // 超时仍未收到结果时给出提示
        exportTimer = new Timer(EXPORT_TIMEOUT_MS, event -> {
            exportTimer.stop();
            statusLabel.setText("导出超时");
            showError("导出超时：服务端在 " + (EXPORT_TIMEOUT_MS / 1000)
                    + " 秒内没有响应。请确认服务端已启动并支持导出请求。");
        });
        exportTimer.setRepeats(false);
        exportTimer.start();
    }

    private JPanel buildConversationTools(String peer, boolean group) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        panel.setOpaque(false);
        if (!group) {
            panel.add(SkinButton.normal("加载更早的消息", e -> loadEarlierMessages(peer)));
        }
        panel.add(SkinButton.normal("导出本会话记录", e -> exportConversation(peer, group)));
        return panel;
    }

    /** 解析导出结果首行，选择保存位置后把记录写成文本文件。 */
    private void saveExportResult(TextMessage message) {
        if (exportTimer != null) {
            exportTimer.stop();
        }
        // 首行为 成功标记|结果 的头部行，失败时第二列是原因
        String[] lines = message.getContent().split("\n", -1);
        String[] head = lines.length > 0 ? lines[0].split("\\|", -1) : new String[]{"0", "无数据"};
        if (head.length < 2 || !"1".equals(head[0])) {
            statusLabel.setText("导出失败");
            showError("导出失败: " + (head.length > 1 ? head[1] : "未知原因"));
            return;
        }

        String text = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length));
        String label = exportLabel == null ? "聊天记录" : exportLabel;
        File target = chooseExportTarget(label);
        if (target == null) {
            statusLabel.setText("已取消导出");
            return;
        }
        try {
            MessageExporter.writeText(text, target);
            statusLabel.setText(label + "已导出，共 " + head[1] + " 条");
            showInfo(label + " 导出成功，共 " + head[1] + " 条\n\n" + target.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "导出文件写入失败", e);
            statusLabel.setText("导出失败");
            showError("导出失败: " + e.getMessage());
        }
    }

    /** 选择导出文件的保存位置，目录不可用时改用用户主目录。 */
    private File chooseExportTarget(String label) {
        String suggested = "chat-" + client.getUsername() + "-"
                + (exportTag == null ? "" : exportTag + "-")
                + System.currentTimeMillis() + ".txt";
        File dir = new File(Config.exportDir());
        try {
            FileUtil.ensureDir(dir.getPath());
        } catch (IOException e) {
            LOGGER.log(java.util.logging.Level.FINE, "创建导出目录失败: " + dir, e);
            // 导出目录不可用时改存用户主目录
            dir = new File(System.getProperty("user.home", "."));
        }
        File target = NativeFileDialog.save(null, "保存" + label + "到", dir, suggested);
        if (target == null) {
            return null;
        }
        if (target.exists() && !confirm("该文件已存在，是否覆盖？\n\n" + target.getAbsolutePath())) {
            return null;
        }
        return target;
    }

    private void openSettings() {
        if (settingsWindow != null && settingsWindow.isDisplayable()) {
            settingsWindow.toFront();
            return;
        }

        settingsWindow = new SettingsUI(client, this::onProfileChanged, isAdmin(),
                isAdmin() ? this::requestDeleteUser : null);
        settingsWindow.setVisible(true);
    }

    private void onProfileChanged(String nickname, String avatar) {
        onEdt(() -> {
            if (nickname != null) {
                client.setNickname(nickname);
            }
            if (avatar != null) {
                AvatarFactory.putAvatar(client.getUsername(), avatar.isEmpty() ? null : avatar);
            }
            refreshSelfHeader();
            repaintChatWindows();
        });
    }

    private void refreshSelfHeader() {
        String display = displayName();
        headerName.setText(display + "（" + client.getUsername() + "）");
        setTitle(Constants.APP_NAME + " - " + display + "(" + client.getUsername() + ")");
        User self = userTree.findUser(client.getUsername());
        headerAvatar.setIcon(AvatarFactory.avatar(client.getUsername(),
                self == null || userTree.isOnline(client.getUsername()),
                self != null && self.isAdmin(), 48));
    }

    private void repaintChatWindows() {
        for (PrivateChatUI window : privateWindows.values()) {
            window.getPanel().repaint();
        }
        if (groupWindow != null) {
            groupWindow.getPanel().repaint();
        }
    }

    private void requestDeleteUser(String target) {
        if (target == null || target.trim().isEmpty()) {
            return;
        }
        // 以系统发送者身份发出删除请求
        TextMessage message = com.chat.common.ChatMessageFactory.text(client.getUsername(),
                Constants.SYSTEM_SENDER, "DELETE_USER|" + target.trim(), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.USER_UPDATE);
        client.send(message);
        statusLabel.setText("删除请求已发送：" + target);
    }

    private void refreshUserListManually() {
        if (!client.requestUserList()) {
            showError("刷新请求发送失败，请检查与服务端的连接");
            return;
        }
        manualRefreshPending = true;
        statusLabel.setText("正在刷新在线用户……");
        if (refreshTimer != null) {
            refreshTimer.stop();
        }

        // 超时未收到用户列表时结束等待
        refreshTimer = new Timer(EXPORT_TIMEOUT_MS, event -> {
            refreshTimer.stop();
            manualRefreshPending = false;
            statusLabel.setText("刷新超时");
            showError("刷新超时：服务端在 " + (EXPORT_TIMEOUT_MS / 1000)
                    + " 秒内没有响应，请确认服务端是否正常运行。");
        });
        refreshTimer.setRepeats(false);
        refreshTimer.start();
    }

    /** 用服务端下发的用户列表刷新用户树、头部信息和状态栏。 */
    private void refreshUserList(String text) {
        List<User> users = UserListCodec.decode(text);
        onEdt(() -> {
            // 同步头像缓存
            for (User user : users) {
                AvatarFactory.putAvatar(user.getUsername(), user.getAvatar());
            }
            int count = userTree.update(users);

            // 清掉已不在列表中的用户的未读
            if (unreadPrivate.keySet().removeIf(username -> userTree.findUser(username) == null)) {
                refreshUnreadBadges();
            }
            boolean admin = isAdmin();
            refreshSelfHeader();
            headerMeta.setText((admin ? "管理员" : "普通用户")
                    + " · 在线用户 " + count + " 人"
                    + (userTree.offlineCount() > 0 ? "，离线 " + userTree.offlineCount() + " 人" : "")
                    + " · 双击用户开始私聊");

            statusLabel.setText("刷新成功：在线用户 " + count + " 人（含自己）· "
                    + java.time.LocalTime.now().format(TIME_FORMAT));

            applySendTargets();

            repaintChatWindows();
            if (manualRefreshPending) {
                manualRefreshPending = false;
                if (refreshTimer != null) {
                    refreshTimer.stop();
                }
                showInfo("已刷新在线用户\n\n在线：" + count + " 人（含自己）"
                        + (userTree.offlineCount() > 0
                        ? "\n离线：" + userTree.offlineCount() + " 人" : "")
                        + "\n\n刷新时间："
                        + java.time.LocalTime.now().format(TIME_FORMAT));
            }
        });
    }

    private void applySendTargets() {
        for (Map.Entry<String, PrivateChatUI> entry : privateWindows.entrySet()) {
            String peer = entry.getKey();
            entry.getValue().getPanel().setSendTargetAvailable(userTree.isOnline(peer)
                    || peer.equals(client.getUsername()));
        }
    }

    /**
     * 按消息类型把服务端推送分发到对应窗口或结果表。
     *
     * @param message 服务端推送的消息
     */
    @Override
    public void onMessage(Message message) {
        // 按消息类型分发
        switch (message.getType()) {
            case USER_LIST:
                refreshUserList(message instanceof TextMessage
                        ? ((TextMessage) message).getContent() : "");
                break;
            case LOGIN_RESULT:

                break;
            case TEXT_PRIVATE:
                handlePrivateMessage((TextMessage) message);
                break;
            case OFFLINE_MESSAGE:
                handleOfflineMessage((TextMessage) message);
                break;
            // 群聊消息交给群聊面板，窗口未显示时累计未读
            case TEXT_GROUP:

                onEdt(() -> groupPanel().onMessage(message));
                break;
            case SYSTEM:
                onEdt(() -> {
                    groupPanelNoUnread().onMessage(message);
                    statusLabel.setText(message.getSummary());

                    String summary = message.getSummary();
                    if (settingsWindow != null && summary != null && summary.contains("删除")) {
                        settingsWindow.onDeleteResult(summary);
                    }
                });
                break;
            case ERROR:
                onEdt(() -> {
                    groupPanelNoUnread().onMessage(message);
                    statusLabel.setText(message.getSummary());

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

                onEdt(() -> handleRecallNotice((TextMessage) message));
                break;
            case PROFILE_RESULT:

                onEdt(() -> {
                    String content = message instanceof TextMessage
                            ? ((TextMessage) message).getContent() : "";
                    if (settingsWindow != null) {
                        settingsWindow.onProfileResult(content);
                    }
                    statusLabel.setText(profileResultText(content));
                });
                break;
            case EXPORT_RESULT:
                onEdt(() -> saveExportResult((TextMessage) message));
                break;
            default:
                onEdt(() -> statusLabel.setText("收到消息: " + message.getType().getDescription()));
                break;
        }
    }

    private String profileResultText(String content) {
        String[] fields = content == null ? new String[0] : content.split("\\|", 3);
        if (fields.length < 3) {
            return content == null || content.isEmpty() ? "资料变更请求已处理" : content;
        }
        String section = SettingsUI.sectionName(fields[1].trim());
        String reason = fields[2].trim();
        if ("1".equals(fields[0].trim())) {
            return section + "修改成功";
        }
        return section + "修改失败：" + (reason.isEmpty() ? "未知原因" : reason);
    }

    private void handlePrivateMessage(TextMessage message) {
        String self = client.getUsername();
        String sender = message.getSender();
        String receiver = message.getReceiver();

        // 收发双方都是自己时忽略这条回显消息
        if (self != null && self.equals(sender) && self.equals(receiver)) {
            return;
        }
        if (self != null && self.equals(sender)) {
            // 发送者是自己但接收者不是自己，按异常消息丢弃
            LOGGER.warning(() -> "忽略身份异常的私聊消息: self=" + self + ", sender=" + sender
                    + ", receiver=" + receiver);
            return;
        }
        if (sender == null || sender.isEmpty()) {
            return;
        }

        // 小助手的答复直接带到窗口前
        onEdt(() -> {
            PrivateChatUI window = Constants.AI_USERNAME.equals(sender)
                    ? openPrivateWindow(sender)
                    : privateWindowFor(sender, false);

            window.getPanel().onMessage(message);
        });
    }

    private void routeFileMessage(Message raw) {
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

    private void routeProgress(FileMessage message) {
        onEdt(() -> {
            FileTransferUI window = FileTransferUI.ownerOf(message.getTransferId());
            if (window == null) {
                window = FileTransferUI.windowFor(client, message.getSender());
                window.setVisible(true);
                window.registerTransfer(message.getTransferId());
            }
            window.handleFileMessage(message);
        });
    }

    private String counterpartOf(FileMessage message) {
        return client.getUsername().equals(message.getSender())
                ? message.getReceiver() : message.getSender();
    }

    /** 解析历史查询结果，按是否带会话对象分送私聊窗口或结果表。 */
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

        boolean v2 = head.length >= 4 && ROW_VERSION.equals(head[3]);
        if (!peer.isEmpty()) {
            onEdt(() -> renderConversationHistory(peer, lines, v2));
            return;
        }
        List<String[]> records = parseRows(lines, v2, null);
        onEdt(() -> {
            ensureQueryDialog();
            fillResultTable(records, "聊天记录查询完成，共 " + head[1] + " 条");
        });
    }

    private void ensureQueryDialog() {
        if (queryDialog == null || !queryDialog.isDisplayable()) {
            showQueryDialog();
        }
    }

    /** 把私聊历史填入窗口，向前翻页时补偿滚动位置。 */
    private void renderConversationHistory(String peer, String[] lines, boolean v2) {
        PrivateChatUI window = privateWindows.get(peer);
        if (window == null) {
            return;
        }
        List<String[]> records = parseRows(lines, v2, peer);
        PrivateChatPanel panel = window.getPanel();
        boolean paged = earlierRequests.remove(peer);
        JScrollPane scroll = scrollPaneOf(panel);
        // 记录插入前的滚动位置，用于翻页后补偿
        int oldValue = scroll == null ? 0 : scroll.getVerticalScrollBar().getValue();
        int oldMax = scroll == null ? 0 : scroll.getVerticalScrollBar().getMaximum();
        panel.fillHistory(records);
        if (!paged || scroll == null) {
            return;
        }

        final JScrollPane target = scroll;
        final int shift = oldValue + (target.getVerticalScrollBar().getMaximum() - oldMax);

        javax.swing.SwingUtilities.invokeLater(() -> target.getVerticalScrollBar().setValue(shift));
    }

    /** 解析结果正文中的各行，识别游标行并区分两种行格式。 */
    private List<String[]> parseRows(String[] lines, boolean v2, String peer) {
        List<String[]> records = new java.util.ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            if (lines[i].startsWith(HISTORY_CURSOR_PREFIX)) {
                if (peer != null) {
                    historyCursors.put(peer,
                            lines[i].substring(HISTORY_CURSOR_PREFIX.length()).trim());
                }
                continue;
            }

            String[] fields = lines[i].split("\\|", v2 ? 5 : 4);
            if (fields.length < (v2 ? 5 : 4)) {
                continue;
            }
            records.add(v2 ? fields
                    : new String[]{fields[0], fields[1], fields[2], "", fields[3]});
        }
        return records;
    }

    private JScrollPane scrollPaneOf(java.awt.Container container) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof JScrollPane) {
                return (JScrollPane) component;
            }
        }
        return null;
    }

    private void loadEarlierMessages(String peer) {
        String cursor = historyCursors.get(peer);
        if (cursor == null || cursor.isEmpty()) {
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

    private void showSearchResult(TextMessage message) {
        String content = message.getContent() == null ? "" : message.getContent();
        String[] lines = content.split("\n", -1);
        String[] head = lines.length > 0 ? lines[0].split("\\|", -1) : new String[]{"0", "无数据"};
        if (head.length < 2 || !"1".equals(head[0])) {
            statusLabel.setText("搜索失败");
            showError("搜索失败: " + (head.length > 1 ? head[1] : "未知原因"));
            return;
        }
        boolean v2 = head.length >= 3 && ROW_VERSION.equals(head[2]);
        List<String[]> hits = parseRows(lines, v2, null);
        onEdt(() -> {
            ensureQueryDialog();
            fillResultTable(hits, "搜索完成，共 " + head[1] + " 条命中");
        });
    }

    private void jumpToRecord(String[] record) {
        if (record == null || record.length < 5) {
            return;
        }
        String self = client.getUsername();
        String sender = senderOf(record);
        String receiver = record[2] == null ? "" : record[2];
        String messageId = record[3] == null ? "" : record[3];
        boolean group = receiver.isEmpty() || Constants.BROADCAST_TAG.equals(receiver);
        if (group) {
            jumpToGroupRecord(sender, messageId, record[4]);
            return;
        }
        if (!self.equals(sender) && !self.equals(receiver)) {
            showInfo("这条记录属于其他用户之间的会话，当前账号无法跳转");
            return;
        }
        String peer = self.equals(sender) ? receiver : sender;
        if (peer == null || peer.isEmpty()) {
            showInfo("这条记录没有可跳转的会话对象");
            return;
        }
        openPrivateWindow(peer);
        PrivateChatPanel panel = privateWindows.get(peer).getPanel();
        if (!messageId.isEmpty() && panel.locateMessage(messageId)) {
            statusLabel.setText("已定位到与 " + peer + " 的这条消息");
            return;
        }
        statusLabel.setText("已打开与 " + peer + " 的会话");
        showInfo("已打开与 " + peer + " 的会话，但这条消息不在窗口当前的记录里。\n"
                + "可以点窗口顶部的“加载更早的消息”继续向前查找。");
    }

    private void jumpToGroupRecord(String sender, String messageId, String content) {
        if (messageId.isEmpty()) {
            openGroupWindow(true);
            showInfo("这条群聊记录来自升级前写库的老数据，没有消息标识，无法精确定位。\n"
                    + "已为你打开群聊大厅，可用该时间点前后对照查找。");
            return;
        }
        GroupChatPanel panel = openGroupWindow(true).getPanel();
        if (panel.locateMessage(messageId)) {
            statusLabel.setText("已定位到群聊里的这条消息");
            return;
        }
        panel.appendLine("[系统] 以下为搜索结果定位到的历史消息", BaseChatPanel.COLOR_SYSTEM);
        panel.appendMessage(sender, content, BaseChatPanel.COLOR_OTHER, messageId, null, sender);

        javax.swing.SwingUtilities.invokeLater(() -> panel.locateMessage(messageId));
        statusLabel.setText("已在群聊大厅补出这条历史消息");
    }

    /** 处理撤回回执与对端撤回通知，替换对应气泡或补一行提示。 */
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
                appendRecallLine(known == null ? null : known.peer,
                        known != null && known.group, "你撤回了一条消息");
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
                boolean group = known != null && known.group;
                appendRecallLine(group ? Constants.BROADCAST_TAG : sender, group,
                        "对方撤回了一条消息");
            }
            statusLabel.setText(sender + " 撤回了一条消息");
        } else {
            LOGGER.warning(() -> "未知的撤回通知状态: " + state);
        }
    }

    private boolean replaceBubble(String messageId, String newText) {
        BubbleRef ref = messageId == null ? null : bubbleIndex.remove(messageId);
        if (ref == null) {
            return false;
        }
        BaseChatPanel panel = panelFor(ref.peer, ref.group);
        return panel != null && panel.replaceMessage(messageId, newText);
    }

    private BaseChatPanel panelFor(String peer, boolean group) {
        if (group) {
            return groupWindow == null ? null : groupWindow.getPanel();
        }
        PrivateChatUI window = peer == null ? null : privateWindows.get(peer);
        return window == null ? null : window.getPanel();
    }

    private void appendRecallLine(String peer, boolean group, String text) {
        BaseChatPanel panel = panelFor(peer, group);
        if (panel != null) {
            panel.appendRecallNotice(text);
        }
    }

    private boolean canRecallNow(String messageId) {
        BubbleRef ref = messageId == null ? null : bubbleIndex.get(messageId);
        if (ref == null || !ref.mine) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - ref.createdAt;

        return elapsed >= 0 && elapsed <= RECALL_WINDOW_MS;
    }

    private void submitRecall(String messageId) {
        if (!client.requestRecall(messageId)) {
            showError("撤回请求发送失败，请检查与服务端的连接");
        } else {
            statusLabel.setText("撤回请求已发送……");
        }
    }

    /** 登记消息气泡的位置信息，并清理超出保留时长的过期索引。 */
    private void rememberBubbleAt(String messageId, String peer, boolean group, String content,
                                  boolean mine, java.time.LocalDateTime time) {
        if (messageId == null || messageId.isEmpty() || peer == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long createdAt = time == null ? now
                : time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (createdAt > now) {
            createdAt = now;
        }
        long deadline = now - BUBBLE_KEEP_MS;

        bubbleIndex.entrySet().removeIf(entry -> entry.getValue().createdAt < deadline);
        bubbleIndex.put(messageId,
                new BubbleRef(peer, group, content == null ? "" : content, mine, createdAt));
    }

    private void handleOfflineMessage(TextMessage message) {
        String sender = message.getSender();
        if (sender == null || sender.isEmpty()) {
            return;
        }
        String content = message.getContent() == null ? "" : message.getContent();
        // 离线补投的消息在窗口中单独标注
        onEdt(() -> {
            PrivateChatPanel panel = privateWindowFor(sender, false).getPanel();

            panel.appendMessage(sender + "（离线消息）", content, BaseChatPanel.COLOR_OTHER,
                    message.getMessageId(), null, sender);
        });
    }

    /**
     * 处理连接状态变化，更新状态栏与断线横幅，并把状态转发给各聊天面板。
     *
     * @param connected 当前是否已连接
     * @param reason 状态说明
     */
    @Override
    public void onConnectionChanged(boolean connected, String reason) {
        boolean reconnecting = client.isReconnecting();
        onEdt(() -> {
            statusLabel.setText(reason);
            serverHint.setText(serverHintText(connected));
            updateBanner(connected, reconnecting, reason);
            // 连接恢复后重置面板提示标记
            if (connected) {
                panelsNotifiedDisconnect = false;
            }

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
            // 非主动退出且不再重连时结束程序
            if (!connected && !loggingOut && !reconnecting) {
                client.removeListener(this);
                client.close();
                showError(reason + "\n程序将退出，请重新登录。");
                dispose();
                System.exit(0);
            }
        });
    }

    private void updateBanner(boolean connected, boolean reconnecting, String reason) {
        if (connected) {
            // 重连恢复后短暂提示再自动收起
            if (bannerWasReconnecting) {
                banner.showRestored("已重新连接，会话已恢复");
                startBannerTimer();
            }
            bannerWasReconnecting = false;
            return;
        }
        if (reconnecting) {
            stopBannerTimer();

            banner.showReconnecting(reason);
            bannerWasReconnecting = true;
            return;
        }

        stopBannerTimer();
        bannerWasReconnecting = false;
        banner.dismiss();
    }

    private void startBannerTimer() {
        stopBannerTimer();
        bannerTimer = new Timer(3000, event -> banner.dismiss());
        bannerTimer.setRepeats(false);
        bannerTimer.start();
    }

    private void stopBannerTimer() {
        if (bannerTimer != null) {
            bannerTimer.stop();
            bannerTimer = null;
        }
    }

    /**
     * 处理单条消息的发送状态，更新状态栏，必要时把提示写进私聊窗口。
     *
     * @param messageId 消息标识
     * @param state 发送状态
     * @param detail 附加说明
     */
    @Override
    public void onMessageState(String messageId, ChatClient.SendState state, String detail) {
        String peer = client.getMessageReceiver(messageId);

        boolean group = peer == null || peer.isEmpty();
        String text = sendStateText(state, detail);
        // 发送中的消息先登记进气泡索引
        if (state == ChatClient.SendState.SENDING) {
            String content = client.getMessageContent(messageId);
            if (content != null) {
                rememberBubbleAt(messageId, group ? Constants.BROADCAST_TAG : peer, group,
                        content, true, null);
            }
        }
        onEdt(() -> {
            statusLabel.setText(text);
            // 只有离线与失败状态写进聊天窗口
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
     * 关闭主窗口及所有子窗口，并释放客户端资源。
     */
    @Override
    public void dispose() {
        client.removeListener(this);
        client.removeMessageStateListener(this);
        // 先关闭所有私聊窗口
        for (PrivateChatUI window : privateWindows.values()) {
            window.dispose();
        }
        privateWindows.clear();
        if (groupWindow != null) {
            groupWindow.dispose();
            groupWindow = null;
        }

        if (settingsWindow != null) {
            settingsWindow.dispose();
            settingsWindow = null;
        }
        if (queryDialog != null) {
            queryDialog.dispose();
            queryDialog = null;
            queryModel = null;
        }

        FileTransferUI.disposeAll();
        client.close();
        bubbleIndex.clear();
        historyCursors.clear();
        earlierRequests.clear();
        queryRecords.clear();
        super.dispose();
    }

    /**
     * 气泡的定位信息：所属会话、是否群聊、内容、是否本人发送以及发送时间。
     */
    private static final class BubbleRef {
        private final String peer;

        private final boolean group;

        private final String content;

        private final boolean mine;

        private final long createdAt;

        private BubbleRef(String peer, boolean group, String content, boolean mine, long createdAt) {
            this.peer = peer;
            this.group = group;
            this.content = content;
            this.mine = mine;
            this.createdAt = createdAt;
        }
    }

    public ChatClient getClient() {
        return client;
    }

    public Map<String, User> getOnlineUsers() {
        return userTree.onlineUsers();
    }
}

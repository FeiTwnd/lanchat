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
import javax.swing.JFileChooser;
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
 * 客户端主窗口。
 *
 * <p>职责：登录成功后的操作中枢——展示在线用户列表，右栏提供群聊大厅、搜索、设置、
 * 退出登录四个入口，并把服务器推送的消息路由到对应窗口。
 * 发送文件与导出记录的入口都在各聊天窗口内（见 {@link BaseChatPanel}），
 * 本窗口不再提供"先选人再发文件"或"导出我的全部记录"的路径。</p>
 *
 * <p>消息路由规则：</p>
 * <ul>
 *   <li>{@code USER_LIST} -> 刷新在线用户表格；</li>
 *   <li>{@code TEXT_GROUP} / {@code SYSTEM} / {@code ERROR} -> 投递群聊窗口；</li>
 *   <li>{@code TEXT_PRIVATE} -> 按对方用户名投递私聊窗口（窗口不存在则自动创建）；</li>
 *   <li>{@code OFFLINE_MESSAGE} -> 同样投递私聊窗口，并标注为离线补投，外观与普通私聊一致；</li>
 *   <li>文件类消息 -> 投递文件传输窗口；</li>
 *   <li>{@code HISTORY_RESULT} -> 私聊窗口会话补拉/向前翻页渲染到窗口，主动查询则填入结果表；</li>
 *   <li>{@code SEARCH_RESULT} -> 填入与主动查询同一张结果表，双击命中行可跳转到对应会话；</li>
 *   <li>{@code MSG_RECALL} -> 本人撤回回执或对端撤回通知，替换对应气泡为撤回提示；</li>
 *   <li>{@code EXPORT_RESULT} -> 把服务端渲染好的导出文本写入本机文件。</li>
 * </ul>
 *
 * <p>可靠性相关展示：</p>
 * <ul>
 *   <li>底部状态栏展示连接状态（连接中/已连接/重连中第 n 次/已恢复）与消息发送状态；</li>
 *   <li>断线后进入自动重连，顶部横幅常驻提示重连进度，恢复后短暂提示一次"会话已恢复"；</li>
 *   <li>"对方离线""发送失败"这类需要用户关注的状态会同时写一行到对应聊天窗口。</li>
 * </ul>
 *
 * <p>未读提示：</p>
 * <ul>
 *   <li>私聊与群聊都遵循"窗口没打开就只累计未读、不主动弹窗"，否则未读永远来不及累计；</li>
 *   <li>私聊未读画在好友树对应用户行的右端，群聊未读写在群聊入口按钮上；</li>
 *   <li>两者的合计画在标题栏角标上，打开对应窗口即清零。</li>
 * </ul>
 *
 * <p>数据规模相关入口：</p>
 * <ul>
 *   <li>历史记录按"上一页最小主键"分页拉取，私聊窗口顶部提供"加载更早的消息"，
 *       向前插入后补偿滚动位置，使用者正在看的那一行不会跳动；</li>
 *   <li>"按内容"与"按日期"两种查法共用同一张结果表：前者由服务端解密后匹配
 *       （正文以密文入库，只有服务端能解密），后者走历史查询通道；</li>
 *   <li>撤回的入口是本人消息气泡的右键菜单，精确指向"这一条"：可撤回与否由
 *       {@link #canRecallNow(String)} 判定，服务端回执后按消息标识把那条气泡换成
 *       一行居中的撤回提示（见 {@link BaseChatPanel#replaceMessage}）。</li>
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

    /** 右栏"田"字格入口的图标边长（像素） */
    private static final int TILE_ICON_SIDE = 40;

    /** 客户端实例 */
    private final transient ChatClient client;

    /** 导出等待计时器：收到导出结果或超时后停止，避免重复提示 */
    private transient Timer exportTimer;

    /** 本次导出的人类可读名称，如"与 alice 的私聊"，用于提示与状态栏 */
    private transient String exportLabel;

    /** 本次导出的文件名标识，取对端用户名或 group，用于区分同名导出文件 */
    private transient String exportTag;

    /**
     * 使用者是否刚点过"刷新列表"。
     *
     * <p>用户列表还有一条推送通道（有人上线/下线时服务器主动下发），那条路径上的刷新
     * 不能弹窗——否则聊天中途别人一上线就弹一个对话框出来。只有本字段为真时才弹，
     * 由 {@link #refreshUserListManually()} 置位、收到回执后复位。</p>
     */
    private transient boolean manualRefreshPending;

    /** 主动刷新的等待计时器，防止服务端不回执时界面停在"正在刷新" */
    private transient Timer refreshTimer;

    /** 私聊窗口表：对方用户名 -> 窗口 */
    private final transient Map<String, PrivateChatUI> privateWindows = new ConcurrentHashMap<>();

    /** 群聊窗口 */
    private transient GroupChatUI groupWindow;

    /**
     * 设置窗口，同一时刻只保留一个。
     *
     * <p>设置是"改完就走"的辅助窗口，连开多个既不必要，也会让"改完头像到底哪一份是准的"
     * 变得含糊（各窗口里的旧值不会互相刷新）。已存在时只置顶复用。</p>
     */
    private transient SettingsUI settingsWindow;

    /** 群聊入口按钮，用于展示未读条数 */
    private transient SkinButton groupButton;

    /** 群聊未读条数：群聊窗口未打开时累计，打开后清零 */
    private transient int unreadGroupCount;

    /**
     * 私聊未读条数：对端用户名 -> 未读条数。
     *
     * <p>只在事件分发线程内读写（计数发生在消息分发、清零发生在窗口显示，两者都已在 EDT 上），
     * 因此用普通 {@code LinkedHashMap} 而不必上并发集合。键存在的意义是"这个会话有未读"，
     * 计数归零时直接移除，不给界面留一个计数为 0 的空条目。</p>
     */
    private final transient Map<String, Integer> unreadPrivate = new java.util.LinkedHashMap<>();

    /** 断线横幅：重连中常驻、恢复后短暂提示，默认隐藏 */
    private final transient DisconnectBanner banner = new DisconnectBanner();

    /** 上一次连接状态回调是否处于"正在重连"，用于区分"重连成功"与"登录首次连接成功" */
    private transient boolean bannerWasReconnecting;

    /** 恢复横幅的自动隐藏计时器 */
    private transient Timer bannerTimer;

    /** 单击用户行打开资料卡的确认延时兜底值（毫秒）：读不到平台双击间隔时使用 */
    private static final int PROFILE_CLICK_DELAY_FALLBACK_MS = 300;

    /** 资料卡计时器：等待期内若来了第二次点击（即双击）就取消，不弹多余的卡片 */
    private transient Timer profileClickTimer;

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

    /** 状态栏时间戳格式：精确到秒即可，再细会占掉状态栏本就紧张的空间 */
    private static final java.time.format.DateTimeFormatter TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 服务端历史/搜索记录的行格式版本标记。
     *
     * <p>新版行在"时间|发送者|接收者"之后多一段消息标识（正文恒居末列），
     * 头部行也带上本标记；不带的即升级前的旧格式。两种都要能解析，
     * 因为服务端与客户端是分别升级的。</p>
     */
    private static final String ROW_VERSION = "v2";

    /** 历史分页游标行的前缀，该行由服务端附在正文中，仅用于翻页、不渲染 */
    private static final String HISTORY_CURSOR_PREFIX = "#beforeId=";

    /** 气泡索引：messageId -> 气泡信息，用于撤回时定位并替换聊天气泡 */
    private final transient Map<String, BubbleRef> bubbleIndex = new ConcurrentHashMap<>();

    /** 历史游标：对端用户名 -> 上一页最小主键，用于"加载更早的消息" */
    private final transient Map<String, String> historyCursors = new ConcurrentHashMap<>();

    /** 查询/搜索对话框，未打开时为 null */
    private transient JDialog queryDialog;

    /** 查询/搜索对话框里的结果表模型 */
    private transient DefaultTableModel queryModel;

    /**
     * 结果表的原始记录，与表行一一对应。
     *
     * <p>存原始行而不是"表格显示的那四列"，是因为双击跳转需要消息标识与收发双方，
     * 这三样都不在表格里显示；另存一份也让"表格按时间排序"不会打乱跳转的对应关系。</p>
     */
    private final transient List<String[]> queryRecords = new java.util.ArrayList<>();

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

    /** 头部的昵称与用户名：改名后需要就地重写 */
    private final JLabel headerName = new JLabel();

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
        // 单击用户行查看资料卡、双击直接打开私聊窗口，符合即时通讯软件的通用交互习惯
        userTree.addMouseListener(new MouseAdapter() {

            /**
             * 单击查看资料卡，双击打开私聊窗口。
             *
             * @param e 鼠标事件
             */
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    // 双击的第一下已经排了一个待弹的资料卡，这里必须先撤掉，
                    // 否则"双击打开私聊"会顺带弹出一张停在屏幕上的资料卡
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

        // 断线横幅与头部卡片共用 NORTH：横幅必须紧贴窗口顶部且不能顶掉头部卡片，
        // 因此 NORTH 内再套一层，横幅放在头部卡片下方
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(buildHeader(), BorderLayout.NORTH);
        north.add(banner, BorderLayout.SOUTH);

        body().setLayout(new BorderLayout());
        body().add(north, BorderLayout.NORTH);
        body().add(splitPane, BorderLayout.CENTER);
        body().add(buildStatusBar(), BorderLayout.SOUTH);
    }

    /**
     * 延时确认一次"单击用户行"：过了平台的双击间隔仍没有第二次点击，才弹出资料卡。
     *
     * <p>Swing 对一次双击会先投递 {@code clickCount == 1} 的事件、再投递 {@code == 2} 的事件，
     * 在第一次点击上立即弹卡的话，使用者每次双击打开私聊都会顺带弹出一张资料卡。</p>
     *
     * <p>间隔取平台的 {@code awt.multiClickInterval}，与系统设置里"双击速度"保持一致：
     * 写死一个偏小的值会让手速慢的使用者依然看到多余的卡片。</p>
     *
     * @param event 单击事件
     */
    private void scheduleProfileCard(MouseEvent event) {
        cancelProfileCard();
        profileClickTimer = new Timer(doubleClickInterval(),
                mouseEvent -> showProfileCardAt(event));
        profileClickTimer.setRepeats(false);
        profileClickTimer.start();
    }

    /**
     * 撤销尚未到期的资料卡计时器。
     */
    private void cancelProfileCard() {
        if (profileClickTimer != null) {
            profileClickTimer.stop();
            profileClickTimer = null;
        }
    }

    /**
     * 读取当前平台的双击判定间隔。
     *
     * @return 毫秒数；取不到时返回 {@link #PROFILE_CLICK_DELAY_FALLBACK_MS}
     */
    private static int doubleClickInterval() {
        Object interval = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
        return interval instanceof Integer ? (Integer) interval : PROFILE_CLICK_DELAY_FALLBACK_MS;
    }

    /**
     * 按点击位置弹出用户资料卡。
     *
     * <p>只有命中用户叶子节点才弹：分组标题行与根节点没有对应的用户，
     * 点在它们上面弹出一张空卡片比不弹更糟。</p>
     *
     * @param event 鼠标事件
     */
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

    /**
     * 构建头部卡片：头像、昵称、角色与在线人数。
     *
     * <p>头部不再放"刷新列表"：刷新刷的是左侧的用户树，入口与作用对象同区更符合直觉，
     * 两处入口并存时使用者还要先判断"这两个按钮有没有区别"。</p>
     *
     * @return 面板
     */
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

        // 头部头像在用户列表到达前只能按"普通用户 + 离线"画：此刻还不知道自己的角色，
        // 也不知道自己有没有自定义头像；列表到达后 refreshUserList 会按用户名重画
        headerAvatar.setIcon(AvatarFactory.avatar(client.getUsername(), true, false, 48));
        card.add(headerAvatar, BorderLayout.WEST);
        card.add(identity, BorderLayout.CENTER);
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
        // "发起私聊"已删除：它与"双击用户行"是同一件事，多一个按钮只会让人先怀疑
        // "双击和这个按钮有没有区别"。刷新保留，但结果改为弹窗反馈——
        // 状态栏那行字在用户列表本身没变化时几乎看不出刷新到底有没有生效
        buttons.add(SkinButton.normal("刷新列表", e -> refreshUserListManually()));
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
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 8));

        // 右栏只留四个入口，排成 2×2 的"田"字：原先把七个功能竖着摞成一列，
        // 每一项都只有一行字，右栏显得又长又空，而实际常用的就是这四个
        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 8));
        grid.setOpaque(false);
        groupButton = functionTile("群聊大厅", "群聊大厅", Glyphs.group(40, Theme.PRIMARY),
                e -> openGroupWindow(true));
        grid.add(groupButton);
        // 查询与搜索合并为一个入口：两者的差别只是"按日期"还是"按内容"，
        // 做成两个按钮时使用者必须先猜自己手里的线索属于哪一个，猜错就得关掉重开
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

    /**
     * 构建一枚"田"字格里的功能入口：图标在上、文字在下，整格可点。
     *
     * <p>图标优先取 {@code image/} 目录下的同名 PNG，取不到时回落到代码绘制的矢量图标：
     * 使用者换一台机器、或者只拷了源码没拷图片素材时，四个入口仍然是四个能认出来的图标，
     * 而不是四个空白方块。</p>
     *
     * @param text     按钮文本
     * @param iconName 图标文件名（不含扩展名）
     * @param fallback 兜底矢量图标
     * @param action   点击事件
     * @return 按钮
     */
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
     *
     * <p>选中自己同样允许打开——这是"与我"的会话（备忘、发文件给自己），
     * 服务器侧已放开自我私聊的收发与历史查询。</p>
     */
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
     * 打开（或复用）指定用户的私聊窗口。
     *
     * @param username 对方用户名
     * @return 私聊窗口
     */
    public PrivateChatUI openPrivateWindow(String username) {
        return privateWindowFor(username, true);
    }

    /**
     * 取出（必要时创建）私聊窗口。
     *
     * <p>{@code show=false} 只保证窗口与面板存在，用于把收到的消息缓存进面板并累计未读，
     * 与 {@link #openGroupWindow(boolean)} 的语义完全一致：不主动弹出窗口打扰使用者，
     * 否则私聊未读永远来不及累计就会被"消息一到就置顶窗口"抹平。</p>
     *
     * @param username 对方用户名
     * @param show     是否显示并置顶窗口
     * @return 私聊窗口
     */
    private PrivateChatUI privateWindowFor(String username, boolean show) {
        // 面板不再单独注册为客户端监听器：消息统一由本窗口分发，
        // 否则同一条消息会被“面板自监听”与“本窗口转发”各渲染一次
        PrivateChatUI window = privateWindows.get(username);
        if (window == null) {
            window = new PrivateChatUI(client, username);
            privateWindows.put(username, window);
            // 头像与撤回策略都由主窗口注入：面板拿不到在线名单，也不该重复记一份时限判断
            preparePanel(window.getPanel(), username, false);
            // 顶部工具条（翻页 + 导出）由主窗口装配：聊天面板保持"只负责渲染"的单一职责，
            // 翻页所需的游标与导出所需的落盘路径都由主窗口掌握
            window.getPanel().add(buildConversationTools(username, false), BorderLayout.NORTH);
            // 使用者直接用鼠标点回窗口（不经过本方法）时也要清未读，因此额外挂一次焦点监听
            window.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowGainedFocus(java.awt.event.WindowEvent event) {
                    clearPrivateUnread(username);
                }
            });
            // 新建窗口时补拉与该用户的最近一页对话：面板里的内容只存在于内存，
            // 关闭窗口或重启客户端后若不补拉，使用者会以为对方的消息丢了。
            // 与自己聊天时收件人填自己，服务端按同一张表按"收件人是自己"取出全部记录
            client.requestHistory(client.getUsername(), "", "", username);
        }
        if (show) {
            clearPrivateUnread(username);
            if (!window.isVisible()) {
                window.setVisible(true);
            }
            window.toFront();
        } else if (!window.isVisible()) {
            addPrivateUnread(username);
        }
        // 会话窗口可能在两次用户列表刷新之间被创建，新建时必须按当前名单补一次可发送状态，
        // 否则它的"发送文件"按钮会一直停在默认的可用态，对端离线时点下去仍是先弹文件选择器
        window.getPanel().setSendTargetAvailable(userTree.isOnline(username)
                || client.getUsername().equals(username));
        return window;
    }

    /**
     * 给聊天面板装上主窗口才能提供的能力：头像解析与撤回策略。
     *
     * <p>两样东西面板都拿不到——在线名单与角色在用户树里，发送时刻与撤回时限在主窗口心里——
     * 因此统一在面板创建时注入一次，面板只负责"问一句、照着画"。</p>
     *
     * @param panel 聊天面板
     * @param peer  会话对象用户名；群聊传广播标记
     * @param group 是否群聊面板
     */
    private void preparePanel(BaseChatPanel panel, String peer, boolean group) {
        panel.setAvatarProvider(this::avatarFor);
        panel.setRecallHandler(new BaseChatPanel.RecallHandler() {

            /**
             * 判断这条消息此刻是否可撤回。
             *
             * @param messageId 稳定消息标识
             * @return 可撤回返回 true
             */
            @Override
            public boolean canRecall(String messageId) {
                return canRecallNow(messageId);
            }

            /**
             * 提交撤回请求。
             *
             * @param messageId 稳定消息标识
             */
            @Override
            public void recall(String messageId) {
                submitRecall(messageId);
            }

            /**
             * 登记一个已渲染的气泡。
             *
             * <p>这是气泡索引的第二条来源，也是更可靠的一条：消息一旦画到界面上就必然
             * 经过这里，不再依赖"发送状态回调到达时发送记录还没被清理"这种时机。
             * 补拉出来的历史消息同样会经过 {@code addRow}，因此历史气泡也获得了撤回入口。</p>
             *
             * @param messageId 稳定消息标识
             * @param content   消息正文
             * @param mine      是否本人发送
             * @param time      该消息在界面上显示的时刻
             */
            @Override
            public void remember(String messageId, String content, boolean mine,
                                 java.time.LocalDateTime time) {
                rememberBubbleAt(messageId, peer, group, content, mine, time);
            }
        });
    }

    /**
     * 解析用户名对应的头像。
     *
     * <p>角色与在线态都从用户树里查：不在树上的用户名（例如群聊里刚离线的发送者、
     * 或用户列表尚未到达时的自己）按在线处理——消息既然已经送到，对方此刻多半是在线的，
     * 画成灰头像反而是在传递一个错误的信息。</p>
     *
     * @param username 用户名
     * @return 头像图标；用户名为空时返回 null（由气泡回落到默认头像）
     */
    private javax.swing.Icon avatarFor(String username) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        User user = userTree.findUser(username);
        return AvatarFactory.avatar(username, user == null || userTree.isOnline(username),
                user != null && user.isAdmin(), MessageBubble.AVATAR_SIZE);
    }

    /**
     * 累计一个会话的私聊未读并刷新角标。
     *
     * @param username 对方用户名
     */
    private void addPrivateUnread(String username) {
        unreadPrivate.merge(username, 1, Integer::sum);
        refreshUnreadBadges();
    }

    /**
     * 清除一个会话的私聊未读并刷新角标。
     *
     * @param username 对方用户名
     */
    private void clearPrivateUnread(String username) {
        if (unreadPrivate.remove(username) != null) {
            refreshUnreadBadges();
        }
    }

    /**
     * 刷新全部未读角标：好友树上每个会话的红点，以及标题栏上的合计角标。
     *
     * <p>合计值在这里现算而不是另记一个计数器：另记一份就必须在每次增减时同步维护，
     * 漏掉任何一处都会让角标与实际未读长期不符。</p>
     */
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
            // 群聊同样要能撤回与看头像：先前面板拿不到在线名单，群聊的右键菜单里
            // 只有"引用"没有"撤回"，同一个功能在两个窗口表现不一致
            preparePanel(groupWindow.getPanel(), Constants.BROADCAST_TAG, true);
            // 群聊同样有"导出这一段对话"的需求，但群聊没有历史补拉通道（服务端不提供
            // 群聊分页），因此这条工具条上只有导出
            groupWindow.getPanel().add(buildConversationTools(null, true), BorderLayout.NORTH);
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
            // 文案与右栏其余三个入口保持一致（都是"去哪儿"，不带动词），
            // 未读条数只在真的有未读时缀在后面，避免平时把按钮文字撑长
            groupButton.setText(unreadGroupCount == 0
                    ? "群聊大厅" : "群聊大厅（" + unreadGroupCount + " 条未读）");
        }
        // 群聊未读同时计入标题栏的合计角标，两者必须一起刷新
        refreshUnreadBadges();
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
     * 打开"查询/搜索聊天记录"对话框。
     *
     * <p>同一个窗口里用页签切换"按日期"与"按内容"两种查法：两者的差别只是
     * 手里拿的是时间范围还是关键字，结果表的列、双击跳转的行为完全一样。
     * 做成两个入口时使用者必须先猜自己的线索属于哪一种，猜错就得关掉重来。</p>
     *
     * <p>对话框非模态且同一时刻只保留一个：双击结果行要能打开会话窗口，
     * 模态对话框会把新窗口压在下面；两张结果表并存则会让"结果到底填到哪一张"变得含糊。</p>
     */
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

            /** 序列化版本号 */
            private static final long serialVersionUID = 20260922L;

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
        JTable table = new JTable(queryModel);
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
                String[] record = recordAt(table.convertRowIndexToModel(row));
                if (record != null) {
                    jumpToRecord(record);
                }
            }
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        actions.setBackground(Theme.CARD);
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

    /**
     * 按日期范围查询，结果填入结果表。
     *
     * @param username 用户名，留空表示查全部
     * @param from     起始日期
     * @param to       结束日期
     */
    private void runDateQuery(String username, String from, String to) {
        if (!client.requestHistory(username, from, to)) {
            showError("查询请求发送失败，请检查与服务端的连接");
            return;
        }
        awaitQueryResult("正在查询聊天记录……");
    }

    /**
     * 按关键字查询，结果填入同一张结果表。
     *
     * @param keyword 关键字
     */
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

    /**
     * 提交查询后清空上一次的结果并给出等待提示。
     *
     * <p>必须先清空：不清的话使用者会盯着上一轮的旧结果等新结果，
     * 两边内容相近时甚至以为"查询没生效"。</p>
     *
     * @param pending 等待中的状态文案
     */
    private void awaitQueryResult(String pending) {
        queryRecords.clear();
        if (queryModel != null) {
            queryModel.setRowCount(0);
        }
        statusLabel.setText(pending);
    }

    /**
     * 把一批聊天记录填进结果表。
     *
     * <p>服务端给的行是 {@code 时间|发送者|接收者|消息标识|正文}，而表格要显示的是
     * "这条消息发生在哪个会话、是群聊还是私聊"：两者不能一一对应，因此原始行原样留在
     * {@link #queryRecords} 里供双击跳转使用，表格只显示由它派生的四列。
     * 表格支持排序，若不这样留一份，排序后行号与原始记录就会错位。</p>
     *
     * @param records 记录行
     * @param summary 状态栏文案
     */
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

    /**
     * 把一条原始记录转换成结果表的四列。
     *
     * @param record 原始记录 {@code {时间, 发送者, 接收者, 消息标识, 正文}}
     * @return 表格行 {@code {时间, 类型, 会话对象, 内容}}
     */
    private Object[] displayRowOf(String[] record) {
        String receiver = record[2] == null ? "" : record[2];
        boolean group = receiver.isEmpty() || Constants.BROADCAST_TAG.equals(receiver);
        if (group) {
            // 群聊没有单一会话对象，把发送者写进"会话对象"列：只说"群聊"的话，
            // 一屏同类记录看上去完全一样，反而认不出哪条对哪条
            return new Object[]{record[0], "群聊", senderOf(record) + " → 全体", record[4]};
        }
        return new Object[]{record[0], "私聊", peerOf(record), record[4]};
    }

    /**
     * 取记录中"另一个参与者"的用户名。
     *
     * <p>与自己聊天的记录收发双方都是本人，此时返回本人——这恰好也是对端的正确取值。</p>
     *
     * @param record 原始记录
     * @return 对端用户名；记录与本账号无关时返回 {@code —}
     */
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

    /**
     * 取记录中的发送者用户名。
     *
     * @param record 原始记录
     * @return 发送者用户名
     */
    private String senderOf(String[] record) {
        return record[1] == null ? "" : record[1];
    }

    /**
     * 按表格行号取出对应的原始记录。
     *
     * @param modelRow 模型行号
     * @return 原始记录；行号越界时返回 null
     */
    private String[] recordAt(int modelRow) {
        if (modelRow < 0 || modelRow >= queryRecords.size()) {
            return null;
        }
        return queryRecords.get(modelRow);
    }

    /**
     * 导出某一个会话的聊天记录。
     *
     * <p>入口在私聊/群聊窗口自己的顶部工具条上：导出的是"这个窗口里看到的这段对话"，
     * 入口与作用对象同处一窗，不必先回主窗口再猜"导出的是哪一段"。
     * 主窗口右栏原先那个"导出我的聊天记录"入口随之移除。</p>
     *
     * <p>导出完全走服务端：客户端只发一个请求，由服务端查库、按范围筛选并渲染后回传，
     * 本机负责落盘（见 {@link #saveExportResult(TextMessage)}）。客户端没有任何数据库代码，
     * 因此换一台机器登录同样能导出自己的记录。</p>
     *
     * @param peer  对端用户名；群聊传 null
     * @param group 是否群聊大厅
     */
    private void exportConversation(String peer, boolean group) {
        String scope = group ? Constants.BROADCAST_SCOPE
                : Constants.EXPORT_SCOPE_PREFIX + (peer == null ? "" : peer);
        if (!client.requestExport(scope)) {
            showError("导出请求发送失败，请检查与服务端的连接");
            return;
        }
        // 结果回来时才知道该写进哪个文件名、该说成"哪个会话"，先把这两样记下来
        exportLabel = group ? "群聊大厅" : ("与 " + peer + " 的私聊");
        exportTag = group ? "group" : (peer == null ? "peer" : peer);
        statusLabel.setText("正在导出" + exportLabel + "……");
        // 旧版服务端不认识导出范围，会把范围连同请求一起当作未处理的控制消息丢掉；
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
     * 构建会话窗口顶部的工具条。
     *
     * <p>放"加载更早的消息"与"导出本会话记录"两枚按钮：两者都是"对这一段对话整体做点什么"，
     * 且都放在聊天区上方——入口与作用位置在同一侧，使用者不必先翻到顶部或底部再找按钮。</p>
     *
     * @param peer  对端用户名；群聊传 null
     * @param group 是否群聊大厅
     * @return 面板
     */
    private JPanel buildConversationTools(String peer, boolean group) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        panel.setOpaque(false);
        if (!group) {
            panel.add(SkinButton.normal("加载更早的消息", e -> loadEarlierMessages(peer)));
        }
        panel.add(SkinButton.normal("导出本会话记录", e -> exportConversation(peer, group)));
        return panel;
    }

    /**
     * 把服务端回传的导出内容写入本机文件。
     *
     * <p>落盘位置由使用者当场决定（见 {@link #chooseExportTarget(String)}）：
     * 导出是"把这段对话存起来"的动作，存成什么名字、存到哪个盘，
     * 属于使用者自己的整理习惯——固定写进 {@code data/export} 之后，
     * 想归到"课程设计/验收材料"这种目录里还得再手动搬一次。
     * 只把默认目录设成 {@code data/export}：它仍然是那个"最可能就是这里"的位置。</p>
     *
     * <p>无论存到哪里，都写在本机而不是服务器上：导出结果由服务端查库渲染、回传，
     * 文件则必须落在发起导出的这台机器上。</p>
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

    /**
     * 询问导出文件保存到哪个目录、叫什么名字。
     *
     * <p>JFileChooser 自带的保存对话框既能改文件名也能换目录，正是"存到哪、叫什么都由我决定"
     * 需要的两件事，不必自己再拼一个输入框。选中已存在的文件时会先问一句是否覆盖：
     * 覆盖是不可逆的，而文件选择器本身不会替我们拦这一下。</p>
     *
     * <p>对话框的宿主传 null 而不是主窗口：发起导出的是聊天窗口，
     * 主窗口可能被它挡在后面，把对话框挂在主窗口上会连对话框一起藏到后面去，
     * 使用者看到的就是"点了导出没反应"。无宿主的对话框由系统居中显示，不会藏。</p>
     *
     * @param label 会话名称，用于标题与默认文件名
     * @return 目标文件；使用者取消或选择覆盖时返回 null
     */
    private File chooseExportTarget(String label) {
        // 默认文件名带上会话标识与时间戳：导出一段对话可能连做几次，
        // 只按会话命名会互相覆盖，事后分不清哪份是哪次导的
        String suggested = "chat-" + client.getUsername() + "-"
                + (exportTag == null ? "" : exportTag + "-")
                + System.currentTimeMillis() + ".txt";
        File dir = new File(Config.exportDir());
        try {
            // 目录要先建出来，否则文件选择器打开时会停在一个不存在的路径上
            FileUtil.ensureDir(dir.getPath());
        } catch (IOException e) {
            // 建不出来就退回用户主目录，总比弹一个空路径的选择器好
            LOGGER.log(java.util.logging.Level.FINE, "创建导出目录失败: " + dir, e);
            dir = new File(System.getProperty("user.home", "."));
        }
        JFileChooser chooser = new JFileChooser(dir);
        chooser.setDialogTitle("保存" + label + "到");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setSelectedFile(new File(dir, suggested));
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File target = chooser.getSelectedFile();
        if (target == null) {
            return null;
        }
        if (target.exists() && !confirm("该文件已存在，是否覆盖？\n\n" + target.getAbsolutePath())) {
            return null;
        }
        return target;
    }

    /**
     * 打开设置窗口：昵称、头像与密码都在这里改。
     *
     * <p>三件事原本散在三处——昵称在主窗口右栏、密码在登录界面、头像根本没有入口，
     * 而它们都属于"改我自己的资料"，合并之后使用者不必先猜"这件事该去哪个窗口做"。
     * 同一时刻只保留一个设置窗口，已打开时只置顶复用。</p>
     */
    private void openSettings() {
        if (settingsWindow != null && settingsWindow.isDisplayable()) {
            settingsWindow.toFront();
            return;
        }
        // 只有管理员才拿到删除用户的入口：分区在窗口内按这个标志决定要不要建，
        // 而不是建好之后置灰——置灰的按钮会让人反复琢磨"为什么我不能点"
        settingsWindow = new SettingsUI(client, this::onProfileChanged, isAdmin(),
                isAdmin() ? this::requestDeleteUser : null);
        settingsWindow.setVisible(true);
    }

    /**
     * 资料（昵称或头像）变更成功后的界面同步。
     *
     * <p>昵称与头像各有一份缓存副本：昵称在 {@code ChatClient} 里（头部标题、窗口标题都读它），
     * 头像在 {@link AvatarFactory} 的静态注册表里（所有画头像的地方都读它）。
     * 服务器随后推送的新用户列表会把两者都纠正一遍，但那是异步的——
     * 这里先就地改掉，使用者点完"保存"立刻就能看到结果。</p>
     *
     * @param nickname 新昵称；为 null 表示昵称未变
     * @param avatar   新头像的 Base64 文本；为 null 表示头像未变；空串表示恢复默认头像
     */
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

    /**
     * 按最新的昵称、角色与头像重画头部卡片。
     *
     * <p>标题栏也一并改：使用者换了昵称却看到窗口标题还是旧的，会以为修改没生效。</p>
     */
    private void refreshSelfHeader() {
        String display = displayName();
        headerName.setText(display + "（" + client.getUsername() + "）");
        setTitle(Constants.APP_NAME + " - " + display + "(" + client.getUsername() + ")");
        User self = userTree.findUser(client.getUsername());
        headerAvatar.setIcon(AvatarFactory.avatar(client.getUsername(),
                self == null || userTree.isOnline(client.getUsername()),
                self != null && self.isAdmin(), 48));
    }

    /**
     * 重画所有已打开的聊天窗口。
     *
     * <p>头像与昵称是"从公共注册表里现查"的，不重新绘制的话，已经画在屏幕上的气泡
     * 还会停在旧头像上；已打开的窗口不重画，使用者就只能靠关掉再打开来刷新。</p>
     */
    private void repaintChatWindows() {
        for (PrivateChatUI window : privateWindows.values()) {
            window.getPanel().repaint();
        }
        if (groupWindow != null) {
            groupWindow.getPanel().repaint();
        }
    }

    /**
     * 提交一个删除用户请求。
     *
     * <p>入口在设置窗口（见 {@link #openSettings()}），这里只负责把它送出去：
     * 界面上"从哪儿点的"由设置窗口决定，协议怎么拼由本方法统一负责，
     * 免得下次改线格式时又要满界面找调用点。</p>
     *
     * @param target 待删除的用户名
     */
    private void requestDeleteUser(String target) {
        if (target == null || target.trim().isEmpty()) {
            return;
        }
        TextMessage message = com.chat.common.ChatMessageFactory.text(client.getUsername(),
                Constants.SYSTEM_SENDER, "DELETE_USER|" + target.trim(), MessageType.TEXT_PRIVATE);
        message.setType(MessageType.USER_UPDATE);
        client.send(message);
        statusLabel.setText("删除请求已发送：" + target);
    }

    /**
     * 使用者点击"刷新列表"后的主动刷新。
     *
     * <p>与服务器推送式刷新的区别只在反馈方式：这一条要弹窗，因为它是使用者的主动动作，
     * 点了没反应比刷新出旧数据更让人困惑；推送式刷新只更新状态栏，避免聊天中途被打断。</p>
     */
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
        // 与导出同一套兜底：服务端不回执时，界面不能一直停在"正在刷新"上
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

    /**
     * 依据在线用户列表刷新用户树与头部信息。
     *
     * @param text 用户列表编码文本
     */
    private void refreshUserList(String text) {
        List<User> users = UserListCodec.decode(text);
        onEdt(() -> {
            // 头像先于用户树登记：渲染器在 update() 里就会取头像，
            // 晚一步的话这一轮画的仍是旧图，要等下一次刷新才纠正过来
            for (User user : users) {
                AvatarFactory.putAvatar(user.getUsername(), user.getAvatar());
            }
            int count = userTree.update(users);
            boolean admin = isAdmin();
            refreshSelfHeader();
            headerMeta.setText((admin ? "管理员" : "普通用户")
                    + " · 在线用户 " + count + " 人"
                    + (userTree.offlineCount() > 0 ? "，离线 " + userTree.offlineCount() + " 人" : "")
                    + " · 双击用户开始私聊");
            // 刷新是使用者主动发起或服务器推送的动作，状态栏必须给出"什么时候刷到了什么"，
            // 只写"在线用户 N 人"的话，人数没变时看起来和刷新前毫无差别
            statusLabel.setText("刷新成功：在线用户 " + count + " 人（含自己）· "
                    + java.time.LocalTime.now().format(TIME_FORMAT));
            // 名单变了，各会话"能不能发文件"的判断随之变化：对端刚上线要放开、刚下线要置灰
            applySendTargets();
            // 自己或别人的头像可能刚变过，已打开的气泡不重画就会停在旧头像上
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

    /**
     * 依据最新的在线名单刷新各聊天面板的"可发送"状态。
     *
     * <p>在线判断只认用户树：它是服务器推送的实时名单，面板自己没有这份数据。
     * 群聊面板实时读名单（见 {@code GroupChatPanel.hasSendTarget()}），
     * 只需要刷新私聊窗口。</p>
     */
    private void applySendTargets() {
        for (Map.Entry<String, PrivateChatUI> entry : privateWindows.entrySet()) {
            String peer = entry.getKey();
            entry.getValue().getPanel().setSendTargetAvailable(userTree.isOnline(peer)
                    || peer.equals(client.getUsername()));
        }
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
                    // 删除用户是唯一由设置窗口发起、结果走系统消息回来的操作，
                    // 结果也贴回发起它的那个窗口，否则使用者点完删除只能盯着主窗口状态栏
                    String summary = message.getSummary();
                    if (settingsWindow != null && summary != null && summary.contains("删除")) {
                        settingsWindow.onDeleteResult(summary);
                    }
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
            case PROFILE_RESULT:
                // 资料变更的回执只发给发起请求的那一端，由设置窗口负责展示；
                // 状态栏同步写一句，设置窗口恰好被关掉时结果也不会石沉大海
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

    /**
     * 把资料变更回执改写为一句状态栏文案。
     *
     * <p>正文格式 {@code 成败|分区|中文原因}。解析失败时原样返回，
     * 宁可显示一段看不懂的协议文本，也不要让使用者面对一个空白的"没反应"。</p>
     *
     * @param content 回执正文
     * @return 状态栏文案
     */
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
        onEdt(() -> {
            // 不主动弹出窗口：与群聊一致，窗口没打开时只把消息缓存进面板并累计未读，
            // 否则"消息一到就置顶私聊窗口"会让私聊未读永远来不及累计。
            // 气泡索引不必在这里另记一份：面板渲染完这一行后会回调 RecallHandler#remember，
            // 那条路径同时覆盖了实时消息与补拉的历史消息，两处都记反而会各写一份时刻
            privateWindowFor(sender, false).getPanel().onMessage(message);
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
     * 非空表示这是私聊窗口补拉的历史（渲染进窗口），为空表示使用者主动查询（填入结果表）。</p>
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
        // 头部第 4 段是格式版本标记，客户端据此兼容未升级的服务端
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

    /**
     * 确保查询结果窗口存在，供"结果比窗口先到"的场合使用。
     *
     * <p>正常顺序是先开窗口再发请求，但使用者完全可能在等待期间把窗口关掉；
     * 此时结果若无处可填，使用者看到的就是"查了但什么都没发生"。</p>
     */
    private void ensureQueryDialog() {
        if (queryDialog == null || !queryDialog.isDisplayable()) {
            showQueryDialog();
        }
    }

    /**
     * 把补拉到的会话历史渲染进对应的私聊窗口。
     *
     * @param peer  会话对象用户名
     * @param lines 响应正文按行切分后的数组，首行为头部
     * @param v2    行格式是否为新版（含消息标识）
     */
    private void renderConversationHistory(String peer, String[] lines, boolean v2) {
        PrivateChatUI window = privateWindows.get(peer);
        if (window == null) {
            // 窗口已被关闭（例如使用者在响应到达前又关掉了），无需再渲染
            return;
        }
        List<String[]> records = parseRows(lines, v2, peer);
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
     * 把响应正文里的记录行解析成统一的五段记录。
     *
     * <p>统一为 {@code {时间, 发送者, 接收者, 消息标识, 正文}} 之后，补拉历史（渲染进窗口）
     * 与主动查询（填入结果表）两条路径就能共用同一份解析：它们的差别只在结果去哪，
     * 不在数据长什么样。</p>
     *
     * <p>新版行是 {@code 时间|发送者|接收者|消息标识|正文}，旧版行没有消息标识那一段。
     * 两种都必须能解析：服务端与客户端是分别部署的，使用者不会总是同时升级两端。</p>
     *
     * @param lines   响应正文按行切分后的数组，首行为头部
     * @param v2      行格式是否为新版
     * @param peer    会话对象用户名；非空时把响应里的分页游标记到该会话名下，为空则不记
     * @return 记录行
     */
    private List<String[]> parseRows(String[] lines, boolean v2, String peer) {
        List<String[]> records = new java.util.ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            if (lines[i].startsWith(HISTORY_CURSOR_PREFIX)) {
                // 游标行只用于翻页，绝不渲染进聊天记录
                if (peer != null) {
                    historyCursors.put(peer,
                            lines[i].substring(HISTORY_CURSOR_PREFIX.length()).trim());
                }
                continue;
            }
            // 正文里可能含竖线，限制切分次数可保证它完整落在最后一个字段
            String[] fields = lines[i].split("\\|", v2 ? 5 : 4);
            if (fields.length < (v2 ? 5 : 4)) {
                continue;
            }
            records.add(v2 ? fields
                    : new String[]{fields[0], fields[1], fields[2], "", fields[3]});
        }
        return records;
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
     * 展示搜索结果。
     *
     * <p>正文格式：成功为 {@code 1|条数|v2} 加若干行 {@code 时间|发送者|接收者|消息标识|正文}，
     * 失败为 {@code 0|中文原因}。结果与"按日期查询"共用同一张结果表——
     * 两条通道给的数据本来就同构，分成两张表只会让使用者面对两个几乎一样的窗口。</p>
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
        boolean v2 = head.length >= 3 && ROW_VERSION.equals(head[2]);
        List<String[]> hits = parseRows(lines, v2, null);
        onEdt(() -> {
            ensureQueryDialog();
            fillResultTable(hits, "搜索完成，共 " + head[1] + " 条命中");
        });
    }

    /**
     * 从结果表的一行跳转到对应会话并定位到那一条消息。
     *
     * <p>群聊与私聊都要能跳：只说"请在群聊大厅查看"等于让使用者自己再去翻一遍，
     * 与"双击跳转"这个功能的初衷相反。群聊没有历史补拉通道，
     * 因此目标不在面板内存里时按记录里的真实发送者与正文补一个气泡再定位，
     * 并先插一行说明——不说明的话，使用者会以为这是刚刚收到的新消息。</p>
     *
     * @param record 原始记录 {@code {时间, 发送者, 接收者, 消息标识, 正文}}
     */
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

    /**
     * 跳转到群聊里的某条记录。
     *
     * @param sender    记录中的发送者
     * @param messageId 消息标识，可能为空（升级前写入的老记录）
     * @param content   消息正文
     */
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
        // 定位放在下一次事件循环：气泡刚插进去还没参与布局，此刻滚动量算出来是错的
        javax.swing.SwingUtilities.invokeLater(() -> panel.locateMessage(messageId));
        statusLabel.setText("已在群聊大厅补出这条历史消息");
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
                // 降级路径：气泡不在（会话窗口已关、或索引已过期）时，
                // 至少把结果写进对应的会话窗口，不让"撤回"变成一个静默的按钮
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
                // 群聊的 PEER 通知里第三段是撤回者的用户名，没有"哪个会话"的信息；
                // 索引在时按索引走，索引不在时退回到"写到那个人的私聊窗口"
                boolean group = known != null && known.group;
                appendRecallLine(group ? Constants.BROADCAST_TAG : sender, group,
                        "对方撤回了一条消息");
            }
            statusLabel.setText(sender + " 撤回了一条消息");
        } else {
            LOGGER.warning(() -> "未知的撤回通知状态: " + state);
        }
    }

    /**
     * 把对应气泡替换为撤回提示。
     *
     * <p>定位完全交给聊天面板：面板手里有"消息标识 -> 气泡"的索引，
     * 能一步找到目标行。主窗口本身不遍历组件树，也不按正文内容做匹配——
     * 同一会话里两条相同正文的消息（"好的""收到"这类极常见）会让按内容匹配改错行，
     * 那是此前"撤回不稳定"的另一半原因。</p>
     *
     * <p>索引项在替换时一并移除：撤回是不可逆的终态，留着它只会让"撤回消息…"的候选列表
     * 反复列出同一行。</p>
     *
     * @param messageId 稳定消息标识
     * @param newText   替换后的文本
     * @return 完成替换返回 true；找不到气泡或面板返回 false
     */
    private boolean replaceBubble(String messageId, String newText) {
        BubbleRef ref = messageId == null ? null : bubbleIndex.remove(messageId);
        if (ref == null) {
            return false;
        }
        BaseChatPanel panel = panelFor(ref.peer, ref.group);
        return panel != null && panel.replaceMessage(messageId, newText);
    }

    /**
     * 取出承载某会话的聊天面板。
     *
     * <p>只取已存在的窗口，不顺手创建：撤回通知可能来自一个使用者早已关掉的会话，
     * 为了一条"某人撤回了某条消息"就凭空弹出一个窗口，比什么都不做更打扰人。</p>
     *
     * @param peer  会话对象用户名，群聊传广播标记
     * @param group 是否群聊
     * @return 聊天面板；窗口不在时返回 null
     */
    private BaseChatPanel panelFor(String peer, boolean group) {
        if (group) {
            return groupWindow == null ? null : groupWindow.getPanel();
        }
        PrivateChatUI window = peer == null ? null : privateWindows.get(peer);
        return window == null ? null : window.getPanel();
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
    private void appendRecallLine(String peer, boolean group, String text) {
        BaseChatPanel panel = panelFor(peer, group);
        if (panel != null) {
            // 走撤回提示而不是普通系统行：两者在界面上长得一样，但前者会被面板记进
            // "已撤回"集合，窗口补拉历史重建列表时不会把撤回过的那条又画回成气泡
            panel.appendRecallNotice(text);
        }
    }

    /**
     * 判断某条消息此刻是否可撤回。
     *
     * <p>撤回策略的唯一落点，同时服务于气泡右键菜单与主窗口的功能面板入口，
     * 两处共用同一份判断，不各自记一份时限。真正的权威仍是服务端：
     * 这里只是让界面不要提供一个按下去必然失败的入口。</p>
     *
     * @param messageId 稳定消息标识
     * @return 可撤回返回 true
     */
    private boolean canRecallNow(String messageId) {
        BubbleRef ref = messageId == null ? null : bubbleIndex.get(messageId);
        if (ref == null || !ref.mine) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - ref.createdAt;
        // 下界与上界都要判：只有上界时，一条来自"比本机快几分钟"的服务端的历史消息
        // 会算出负的已过时长，几小时前的老消息反而变成"可以撤回"
        return elapsed >= 0 && elapsed <= RECALL_WINDOW_MS;
    }

    /**
     * 提交一条撤回请求。
     *
     * @param messageId 稳定消息标识
     */
    private void submitRecall(String messageId) {
        if (!client.requestRecall(messageId)) {
            showError("撤回请求发送失败，请检查与服务端的连接");
        } else {
            statusLabel.setText("撤回请求已发送……");
        }
    }

    /**
     * 登记一个刚发生的气泡，供撤回时定位。
     *
     * @param messageId 稳定消息标识
     * @param peer      对端用户名
     * @param content   消息正文
     * @param mine      是否本人发送
     */
    private void rememberBubble(String messageId, String peer, String content, boolean mine) {
        rememberBubbleAt(messageId, peer, false, content, mine, null);
    }

    /**
     * 登记一个气泡，供撤回时定位。
     *
     * <p>时刻来源有两处，都必须按"这在客户端看来是多久以前"归一：实时消息取本机当前时刻，
     * 补拉的历史消息取服务端记录的发送时间。归一之后还要把未来时刻压回当前时刻——
     * 客户端与服务端的时钟不可能完全一致，若不压，一个"比本机快 3 分钟"的服务端
     * 会让刚发出的消息算出负的已过时长，撤回时限判断随之失真。</p>
     *
     * @param messageId 稳定消息标识
     * @param peer      对端用户名；群聊传广播标记
     * @param group     是否群聊消息
     * @param content   消息正文，可为 null
     * @param mine      是否本人发送
     * @param time      消息时间；为 null 表示"就是现在"
     */
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
        // 索引只在"可能被撤回"的时间窗内有用，过期即清理，避免长会话下无限增长
        bubbleIndex.entrySet().removeIf(entry -> entry.getValue().createdAt < deadline);
        bubbleIndex.put(messageId,
                new BubbleRef(peer, group, content == null ? "" : content, mine, createdAt));
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
            // 离线补投同样是使用者没看过的真实消息，与普通私聊一样缓存并计入未读，不弹窗
            PrivateChatPanel panel = privateWindowFor(sender, false).getPanel();
            // 头像归属必须显式给出发送者：显示名是"某某（离线消息）"，拿它去查头像
            // 既查不到角色也查不到在线态，只会画出一个与本人无关的随机剪影。
            // 气泡索引由面板渲染后回调登记（见 RecallHandler#remember），这里不必再记一次
            panel.appendMessage(sender + "（离线消息）", content, BaseChatPanel.COLOR_OTHER,
                    message.getMessageId(), null, sender);
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
            updateBanner(connected, reconnecting, reason);
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
     * 依据连接状态更新顶部断线横幅。
     *
     * <p>三种走向：正在重连时常驻警示横幅；恢复连接后短暂提示一次"会话已恢复"，
     * 让使用者明确知道刚才那段时间的消息没有丢；其余情况隐藏横幅。</p>
     *
     * <p>"已恢复"只在上一状态确实是重连中才提示：登录成功同样会回调 {@code connected=true}，
     * 不加这个判断的话每次登录都会闪出一条"已重新连接"。</p>
     *
     * @param connected    是否已连接
     * @param reconnecting 是否处于重连流程中
     * @param reason       服务端给出或本地推断的原因
     */
    private void updateBanner(boolean connected, boolean reconnecting, String reason) {
        if (connected) {
            if (bannerWasReconnecting) {
                banner.showRestored("已重新连接，会话已恢复");
                startBannerTimer();
            }
            bannerWasReconnecting = false;
            return;
        }
        if (reconnecting) {
            // 文案直接用回调带来的原因：服务端/网络层已经把它写成"…，正在重连（第 n 次）"，
            // 界面再拼一次次数只会两处口径
            banner.showReconnecting(reason);
            bannerWasReconnecting = true;
            return;
        }
        // 走到这里说明是终态断开，紧接着就会退出程序，横幅只是兜底不留残影
        bannerWasReconnecting = false;
        banner.dismiss();
    }

    /**
     * 启动恢复横幅的自动隐藏计时器。
     *
     * <p>每次重新计时前先停掉上一个，避免连续两次重连恢复时第一个计时器提前把第二个横幅收掉。</p>
     */
    private void startBannerTimer() {
        if (bannerTimer != null) {
            bannerTimer.stop();
        }
        bannerTimer = new Timer(3000, event -> banner.dismiss());
        bannerTimer.setRepeats(false);
        bannerTimer.start();
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
        // 群聊没有单一接收方，发送记录里的接收者是空串：这里归一成广播标记，
        // 撤回时才分得清"该找群聊窗口"还是"该找某个私聊窗口"
        boolean group = peer == null || peer.isEmpty();
        String text = sendStateText(state, detail);
        if (state == ChatClient.SendState.SENDING) {
            // 本地回显由聊天面板负责渲染，这里只登记索引：撤回时界面需要凭消息标识找到那一行
            String content = client.getMessageContent(messageId);
            if (content != null) {
                rememberBubbleAt(messageId, group ? Constants.BROADCAST_TAG : peer, group,
                        content, true, null);
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
        // 设置窗口与查询窗口是主窗口的从属窗口：主窗口一关，它们没有继续存在的理由
        if (settingsWindow != null) {
            settingsWindow.dispose();
            settingsWindow = null;
        }
        if (queryDialog != null) {
            queryDialog.dispose();
            queryDialog = null;
            queryModel = null;
        }
        // 文件传输窗口由静态注册表管理，不在上面两个集合里，需要单独关闭
        FileTransferUI.disposeAll();
        client.close();
        bubbleIndex.clear();
        historyCursors.clear();
        earlierRequests.clear();
        queryRecords.clear();
        super.dispose();
    }

    /**
     * 聊天气泡的索引信息。
     *
     * <p>撤回要在界面上精确定位"哪一行是目标消息"，而聊天记录控件只保存纯文本，
     * 本身不带消息标识；索引把标识与"这条消息属于哪个会话"对应起来，
     * 供撤回回执到达时把请求路由到正确的面板。</p>
     *
     * <p>"是否群聊"单列一个布尔分量而不靠"对端用户名等于 {@code ALL}"来判断：
     * {@code ALL} 只是约定俗成的广播标记，{@code USERNAME_PATTERN} 并不禁止真的有人
     * 注册一个叫 ALL 的账号，一旦撞名就再也分不清这条消息该撤回在哪个窗口。</p>
     */
    private static final class BubbleRef {

        /** 气泡所属会话的对端用户名；群聊消息固定为广播标记 */
        private final String peer;

        /** 是否属于群聊会话 */
        private final boolean group;

        /** 消息正文（用于在撤回候选列表里指认"是哪一条"） */
        private final String content;

        /** 是否为本人发出的消息 */
        private final boolean mine;

        /** 记录时刻（毫秒），用于判断是否还在撤回时限内 */
        private final long createdAt;

        /**
         * 构造气泡索引项。
         *
         * @param peer      对端用户名
         * @param group     是否属于群聊会话
         * @param content   消息正文
         * @param mine      是否本人发送
         * @param createdAt 记录时刻
         */
        private BubbleRef(String peer, boolean group, String content, boolean mine, long createdAt) {
            this.peer = peer;
            this.group = group;
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

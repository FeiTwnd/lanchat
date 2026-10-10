package com.chat.client.ui;

import com.chat.client.ui.theme.AvatarFactory;
import com.chat.client.ui.theme.Badge;
import com.chat.client.ui.theme.Glyphs;
import com.chat.client.ui.theme.Theme;
import com.chat.common.User;

import javax.swing.BorderFactory;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线用户树。
 *
 * <p>结构：根节点（在线用户 N 人）→ 分组节点（管理员 / 普通用户 / 离线）→ 用户叶子节点。</p>
 *
 * <p>叶子节点用默认头像区分状态：在线用户头像底色较深并带绿色状态点，
 * 管理员额外带橙色描边；本次会话出现过、随后从服务器列表中消失的用户归入"离线"分组并置灰。
 * 之所以保留离线用户，是因为"谁刚刚还在"对使用者有信息价值，也便于说明服务器推送的是
 * 实时在线列表而不是好友关系（本项目没有好友关系表）。</p>
 *
 * <p>线程约束：所有方法都必须在事件分发线程调用，调用方需自行通过
 * {@link BaseUI#onEdt(Runnable)} 调度。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class OnlineUserTree extends JTree {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20260919L;

    /** 管理员分组名 */
    private static final String GROUP_ADMIN = "管理员";

    /** 普通用户分组名 */
    private static final String GROUP_USER = "普通用户";

    /** 离线分组名 */
    private static final String GROUP_OFFLINE = "离线";

    /** 未读角标与正文之间的间距（像素） */
    private static final int BADGE_GAP = 8;

    /** 根节点 */
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("在线用户");

    /** 树模型 */
    private final transient DefaultTreeModel model = new DefaultTreeModel(root);

    /** 当前在线用户：用户名 -> 用户 */
    private final transient Map<String, User> online = new LinkedHashMap<>();

    /** 本次会话出现过但当前已离线的用户 */
    private final transient Map<String, User> offline = new LinkedHashMap<>();

    /**
     * 各用户的私聊未读条数：用户名 -> 未读条数，没有未读的用户不出现在表中。
     *
     * <p>必须存放在树模型之外：{@link #rebuild()} 每次刷新都会重建全部节点，
     * 未读若挂在节点上，任何一次用户列表推送都会把它清零。</p>
     */
    private final transient Map<String, Integer> unread = new LinkedHashMap<>();

    /** 过滤关键字 */
    private transient String filter = "";

    /**
     * 构造用户树。
     */
    public OnlineUserTree() {
        setModel(model);
        setRootVisible(true);
        setShowsRootHandles(true);
        setRowHeight(46);
        setBackground(Theme.CARD);
        setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        UserNodeRenderer renderer = new UserNodeRenderer();
        renderer.setBackgroundSelectionColor(Theme.SELECTION_BG);
        renderer.setTextSelectionColor(Theme.TEXT);
        renderer.setBorderSelectionColor(null);
        renderer.setBackgroundNonSelectionColor(Theme.CARD);
        setCellRenderer(renderer);
        ToolTipManager.sharedInstance().registerComponent(this);
        rebuild();
    }

    /**
     * 依据服务器推送的用户列表刷新整棵树。
     *
     * @param users 在线用户列表
     * @return 在线人数
     */
    public int update(List<User> users) {
        List<String> current = new ArrayList<>();
        Map<String, User> incoming = new LinkedHashMap<>();
        for (User user : users) {
            incoming.put(user.getUsername(), user);
            current.add(user.getUsername());
        }
        // 从在线转为离线的用户移入离线分组；重新上线的用户移回在线分组
        for (Map.Entry<String, User> entry : online.entrySet()) {
            if (!incoming.containsKey(entry.getKey())) {
                offline.put(entry.getKey(), entry.getValue());
            }
        }
        online.clear();
        online.putAll(incoming);
        for (String username : current) {
            offline.remove(username);
        }
        // 未读表与在线/离线表是两套数据，这里先清掉树自己那份副本里的孤儿条目：
        // 它们既画不出红点，又会随下一次 setUnread 被整份灌回来。真正生效的裁剪在
        // ClientUI.refreshUserList 里（按"树上认识谁"裁剪 unreadPrivate 再刷新角标），
        // 本行只保证 update 之后树上不会残留一个查不到主人的计数
        unread.keySet().removeIf(username -> !online.containsKey(username)
                && !offline.containsKey(username));
        rebuild();
        return online.size();
    }

    /**
     * 设置各用户的私聊未读条数。
     *
     * <p><b>为什么必须连带重建节点</b>：角标是画在行尾之外的，渲染器的
     * {@code getPreferredSize} 会为它预留宽度，但行宽早已按"没有角标"的尺寸算好并缓存在
     * 树里——只 {@code repaint()} 的话，角标会直接压在正文末尾（例如"账号 · 角色 · 在线"
     * 那几个字上）。实测 {@code revalidate()} 与 {@code doLayout()} 都改不了已缓存的行宽，
     * 只有 {@link #rebuild()}（内部 {@code model.reload()} 后重新展开）会让尺寸重新计算。</p>
     *
     * @param counts 用户名 -> 未读条数；计数为 0 或负数的条目会被忽略
     */
    public void setUnread(Map<String, Integer> counts) {
        unread.clear();
        if (counts != null) {
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    unread.put(entry.getKey(), entry.getValue());
                }
            }
        }
        rebuild();
    }

    /**
     * 获取某用户的私聊未读条数。
     *
     * @param username 用户名
     * @return 未读条数，没有未读时返回 0
     */
    public int unreadCount(String username) {
        Integer count = username == null ? null : unread.get(username);
        return count == null ? 0 : count;
    }

    /**
     * 判断某用户是否在线。
     *
     * @param username 用户名
     * @return 在线返回 true
     */
    public boolean isOnline(String username) {
        return username != null && online.containsKey(username);
    }

    /**
     * 按用户名查找用户，先查在线表再查离线表。
     *
     * <p>{@link #onlineUsers()} 只覆盖在线用户，而资料卡对离线用户同样要能打开——
     * "谁刚刚还在"正是列表保留离线分组的价值所在。</p>
     *
     * @param username 用户名
     * @return 用户对象；两张表都没有时返回 null
     */
    public User findUser(String username) {
        if (username == null) {
            return null;
        }
        User user = online.get(username);
        return user != null ? user : offline.get(username);
    }

    /**
     * 设置过滤关键字并重建树。
     *
     * @param keyword 关键字，为空表示不过滤
     */
    public void setFilter(String keyword) {
        this.filter = keyword == null ? "" : keyword.trim().toLowerCase();
        rebuild();
    }

    /**
     * 获取当前选中的用户。
     *
     * @return 用户对象；未选中用户节点时返回 null
     */
    public User selectedUser() {
        TreePath path = getSelectionPath();
        if (path == null) {
            return null;
        }
        Object last = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return last instanceof Entry ? ((Entry) last).user : null;
    }

    /**
     * 获取指定路径对应的用户。
     *
     * <p>与 {@link #selectedUser()} 的区别是不看选中态、只看路径：鼠标点击发生在选中态更新之前，
     * 单击弹资料卡时若读选中态，拿到的会是上一次点中的那一行。</p>
     *
     * @param path 节点路径
     * @return 用户对象；路径为空、指向分组节点或根节点时返回 null
     */
    public User selectedUserOn(TreePath path) {
        if (path == null) {
            return null;
        }
        Object last = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return last instanceof Entry ? ((Entry) last).user : null;
    }

    /**
     * 判断当前选中的用户是否在线。
     *
     * @return 选中且在线返回 true
     */
    public boolean selectedIsOnline() {
        TreePath path = getSelectionPath();
        if (path == null) {
            return false;
        }
        Object last = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return last instanceof Entry && ((Entry) last).online;
    }

    /**
     * 获取当前在线用户。
     *
     * @return 用户名到用户的只读视图
     */
    public Map<String, User> onlineUsers() {
        return java.util.Collections.unmodifiableMap(online);
    }

    /**
     * 在线人数。
     *
     * @return 人数
     */
    public int onlineCount() {
        return online.size();
    }

    /**
     * 离线人数。
     *
     * @return 人数
     */
    public int offlineCount() {
        return offline.size();
    }

    /**
     * 依据当前数据与过滤条件重建节点树。
     *
     * <p>带过滤条件时换一套画法：空的分组不再挂出来，"管理员（0）""普通用户（0）"
     * 这类空壳子在最需要看结果的时候占着位置；命中数为 0 时补一行说明。
     * 这是搜索"看起来没反应"的主要来源——关键字没命中时，旧画法下的树
     * （三个空分组 + 一行都没变的根节点）与搜索前几乎一模一样，
     * 使用者只能判断成"搜索坏了"。根节点同时报出命中人数，
     * 让"筛掉了多少"这件事有处可看。</p>
     */
    private void rebuild() {
        root.removeAllChildren();
        boolean filtering = !filter.isEmpty();
        DefaultMutableTreeNode adminNode = new DefaultMutableTreeNode(GROUP_ADMIN);
        DefaultMutableTreeNode userNode = new DefaultMutableTreeNode(GROUP_USER);
        DefaultMutableTreeNode offlineNode = new DefaultMutableTreeNode(GROUP_OFFLINE);
        int hits = 0;
        hits += fill(adminNode, online.values(), true, true);
        hits += fill(userNode, online.values(), false, true);
        hits += fill(offlineNode, offline.values(), false, false);
        if (!filtering) {
            root.add(adminNode);
            root.add(userNode);
            root.add(offlineNode);
        } else {
            addIfNotEmpty(adminNode);
            addIfNotEmpty(userNode);
            addIfNotEmpty(offlineNode);
            if (hits == 0) {
                root.add(new DefaultMutableTreeNode(new Hint("没有匹配“" + filter + "”的用户")));
            }
        }
        root.setUserObject(filtering
                ? "在线用户 " + online.size() + " 人 · 命中 " + hits + " 人"
                : "在线用户 " + online.size() + " 人");
        model.reload();
        for (int i = 0; i < getRowCount(); i++) {
            expandRow(i);
        }
    }

    /**
     * 挂上分组节点，但空分组不挂。
     *
     * @param node 分组节点
     */
    private void addIfNotEmpty(DefaultMutableTreeNode node) {
        if (node.getChildCount() > 0) {
            root.add(node);
        }
    }

    /**
     * 把符合条件的用户挂到分组节点下。
     *
     * @param parent  分组节点
     * @param users   候选用户
     * @param admin   是否只收管理员
     * @param isOnline 是否为在线分组
     * @return 实际挂上去的用户数
     */
    private int fill(DefaultMutableTreeNode parent, java.util.Collection<User> users,
                     boolean admin, boolean isOnline) {
        for (User user : users) {
            if (user.isAdmin() != admin || !matches(user)) {
                continue;
            }
            parent.add(new DefaultMutableTreeNode(new Entry(user, isOnline)));
        }
        parent.setUserObject(parent.getUserObject() + "（" + parent.getChildCount() + "）");
        return parent.getChildCount();
    }

    /**
     * 判断用户是否命中过滤关键字。
     *
     * @param user 用户
     * @return 命中或无过滤条件时返回 true
     */
    private boolean matches(User user) {
        if (filter.isEmpty()) {
            return true;
        }
        String nickname = user.getNickname() == null ? "" : user.getNickname().toLowerCase();
        return user.getUsername().toLowerCase().contains(filter) || nickname.contains(filter);
    }

    /**
     * 过滤无结果时的说明行载荷。
     *
     * <p>单独用一个类型而不是直接塞字符串：渲染器要靠它把这一行与
     * "管理员""普通用户"这类分组节点区分开——写成字符串就只能靠文字内容去猜。</p>
     */
    private static final class Hint {

        /** 说明文字 */
        private final String text;

        /**
         * 构造说明行。
         *
         * @param text 说明文字
         */
        Hint(String text) {
            this.text = text;
        }
    }

    /**
     * 用户叶子节点载荷：用户对象 + 在线标记。
     */
    private static final class Entry {

        /** 用户 */
        private final User user;

        /** 是否在线 */
        private final boolean online;

        /**
         * 构造载荷。
         *
         * @param user   用户
         * @param online 是否在线
         */
        Entry(User user, boolean online) {
            this.user = user;
            this.online = online;
        }
    }

    /**
     * 节点渲染器：分组节点显示人数与群组头像，用户节点显示头像与两行文本。
     *
     * <p>选中态不依赖外观默认行为：整行背景由本类自己在 {@link #paint(Graphics)} 中填满。
     * 默认实现只从图标之后开始填充，头像所在的图标区不会被覆盖，于是露出底层外观的
     * 选中色——系统外观是 GTK 时那是一块橙色，看起来就像"选中效果坏了"。
     * 文字配色不随选中与否改变，是为了让浅色头像与主色分组图标在浅蓝选中底上依然清晰。</p>
     */
    private static final class UserNodeRenderer extends DefaultTreeCellRenderer {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20260920L;

        /** 当前渲染的行是否处于选中态 */
        private transient boolean selectedRow;

        /**
         * 当前渲染的行的未读条数。
         *
         * <p>渲染器是静态嵌套类、不持有外层树的引用，未读数只能由
         * {@link #getTreeCellRendererComponent} 从传入的 {@code tree} 参数上取一次，
         * 再留给随后的 {@link #paint} 使用——渲染与绘制是两个先后发生的调用。</p>
         */
        private transient int unreadCount;

        /**
         * 渲染节点。
         *
         * @param tree     树
         * @param value    节点
         * @param selected 是否选中
         * @param expanded 是否展开
         * @param leaf     是否叶子
         * @param row      行号
         * @param hasFocus 是否获得焦点
         * @return 渲染组件
         */
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                     boolean expanded, boolean leaf, int row,
                                                     boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            selectedRow = selected;
            unreadCount = 0;
            Object payload = ((DefaultMutableTreeNode) value).getUserObject();
            setFont(Theme.fontBase());
            setIconTextGap(6);
            if (payload instanceof Entry) {
                Entry entry = (Entry) payload;
                User user = entry.user;
                String nickname = user.getNickname() == null || user.getNickname().isEmpty()
                        ? user.getUsername() : user.getNickname();
                String state = entry.online ? "在线" : "离线";
                String role = user.isAdmin() ? "管理员" : "普通用户";
                // 昵称与用户名都必须转义后再拼进富文本：它们都来自使用者输入，
                // 一个叫 "<b>" 的昵称足以让整行渲染错乱，含 "<table>" 的更会把列表撑坏
                setText("<html><body style='margin:0'>"
                        + "<font color='" + hex(entry.online ? Theme.TEXT : Theme.TEXT_WEAK) + "'>"
                        + HtmlEscape.escape(nickname) + "</font><br>"
                        + "<font color='" + hex(entry.online ? Theme.TEXT_WEAK : Theme.OFFLINE) + "'>"
                        + HtmlEscape.escape(user.getUsername()) + " · " + role + " · " + state
                        + "</font></body></html>");
                setIcon(AvatarFactory.avatar(user.getUsername(), entry.online, user.isAdmin(), 34));
                // 提示与正文走同一套转义：昵称以 "<html>" 开头时，不转义会让整条提示被当成
                // 富文本解析（工具提示没有换行余地，因此用 oneLine 连换行一起折掉）
                setToolTipText("<html>" + HtmlEscape.oneLine(nickname + "（" + user.getUsername()
                        + "）· " + role + " · " + state) + "</html>");
                // 未读只认树上的那份数据：渲染器自己不保存任何计数，避免与树的状态各记一份
                if (tree instanceof OnlineUserTree) {
                    unreadCount = ((OnlineUserTree) tree).unreadCount(user.getUsername());
                }
            } else if (payload instanceof Hint) {
                // 说明行按正文样式画，不套分组的粗体与群组头像：
                // 它是一句提示，不是一个可以点开的分组
                String hint = ((Hint) payload).text;
                setText(hint);
                setFont(Theme.fontBase());
                setIcon(Glyphs.search(18, Theme.TEXT_WEAK));
                setForeground(Theme.TEXT_WEAK);
                setToolTipText(hint);
            } else {
                setText(String.valueOf(payload));
                setFont(Theme.fontBold());
                setIcon(AvatarFactory.groupAvatar(26));
                setForeground(Theme.TEXT);
                setToolTipText(String.valueOf(payload));
            }
            return this;
        }

        /**
         * 绘制单元格：选中行先填满整行浅主色底，再交给父类绘制图标与文字。
         *
         * @param g 画笔
         */
        @Override
        public void paint(Graphics g) {
            if (selectedRow) {
                g.setColor(Theme.SELECTION_BG);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            super.paint(g);
            if (unreadCount > 0) {
                // 画在父类之后：图标与文字由 super.paint 铺好，角标必须压在最上层。
                // 宽度已在 getPreferredSize 里为角标预留好，这里贴着行右端画即可
                Graphics2D g2 = (Graphics2D) g.create();
                Badge.paint(g2, getWidth(), getHeight() / 2, unreadCount);
                g2.dispose();
            }
        }

        /**
         * 在首选宽度里为未读角标预留位置。
         *
         * <p>必须在这里加宽而不是在绘制时往右画：树的行宽取自渲染器的首选尺寸，
         * 绘制会被限制在这个宽度内，直接画到行尾只会把正文尾巴盖住。</p>
         *
         * <p>加宽量只取决于角标自身宽度，不参考树或视口的宽度，因此不会与树的布局
         * 形成互相撑大的循环；没有未读的行宽度分毫不变。</p>
         *
         * @return 首选尺寸
         */
        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            if (unreadCount > 0) {
                size.width += BADGE_GAP + Badge.widthOf(unreadCount);
            }
            return size;
        }

        /**
         * 把颜色转换为 HTML 十六进制表示。
         *
         * @param color 颜色
         * @return 形如 {@code #8A97A5} 的字符串
         */
        private String hex(java.awt.Color color) {
            return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        }
    }
}

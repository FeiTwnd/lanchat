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
 * 在线用户树，按管理员、普通用户和离线状态分组显示用户，并提供过滤、未读角标与选中查询。
 */
public class OnlineUserTree extends JTree {
    private static final long serialVersionUID = 20260919L;

    private static final String GROUP_ADMIN = "管理员";

    private static final String GROUP_USER = "普通用户";

    private static final String GROUP_OFFLINE = "离线";

    /** 未读角标与文字之间的间距，单位像素。 */
    private static final int BADGE_GAP = 8;

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("在线用户");

    private final transient DefaultTreeModel model = new DefaultTreeModel(root);

    private final transient Map<String, User> online = new LinkedHashMap<>();

    private final transient Map<String, User> offline = new LinkedHashMap<>();

    private final transient Map<String, Integer> unread = new LinkedHashMap<>();

    /** 当前过滤关键词，已转为小写。 */
    private transient String filter = "";

    /**
     * 创建在线用户树并安装节点渲染器。
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
     * 用最新的在线用户列表刷新分组，掉线的用户保留在离线组。
     *
     * @param users 服务端返回的在线用户
     * @return 在线用户数
     */
    public int update(List<User> users) {
        List<String> current = new ArrayList<>();
        Map<String, User> incoming = new LinkedHashMap<>();
        for (User user : users) {
            incoming.put(user.getUsername(), user);
            current.add(user.getUsername());
        }

        for (Map.Entry<String, User> entry : online.entrySet()) {
            // 本轮未出现的在线用户转入离线组
            if (!incoming.containsKey(entry.getKey())) {
                offline.put(entry.getKey(), entry.getValue());
            }
        }
        online.clear();
        online.putAll(incoming);
        for (String username : current) {
            offline.remove(username);
        }

        unread.keySet().removeIf(username -> !online.containsKey(username)
                && !offline.containsKey(username));
        rebuild();
        return online.size();
    }

    /**
     * 设置各用户的未读消息数并刷新角标。
     *
     * @param counts 用户名到未读数的映射
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

    public int unreadCount(String username) {
        Integer count = username == null ? null : unread.get(username);
        return count == null ? 0 : count;
    }

    public boolean isOnline(String username) {
        return username != null && online.containsKey(username);
    }

    /**
     * 按用户名查找用户，先查在线组再查离线组。
     *
     * @param username 用户名
     * @return 对应的用户，未找到时返回 null
     */
    public User findUser(String username) {
        if (username == null) {
            return null;
        }
        User user = online.get(username);
        return user != null ? user : offline.get(username);
    }

    /**
     * 设置过滤关键词并重建树。
     *
     * @param keyword 关键词，null 或空串表示不过滤
     */
    public void setFilter(String keyword) {
        this.filter = keyword == null ? "" : keyword.trim().toLowerCase();
        rebuild();
    }

    /**
     * 返回当前选中的用户。
     *
     * @return 选中的用户，未选中用户节点时返回 null
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
     * 返回指定树路径上的用户。
     *
     * @param path 树路径
     * @return 该路径上的用户，不是用户节点时返回 null
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
     * @return 选中在线用户时返回 true
     */
    public boolean selectedIsOnline() {
        TreePath path = getSelectionPath();
        if (path == null) {
            return false;
        }
        Object last = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return last instanceof Entry && ((Entry) last).online;
    }

    public Map<String, User> onlineUsers() {
        return java.util.Collections.unmodifiableMap(online);
    }

    public int onlineCount() {
        return online.size();
    }

    public int offlineCount() {
        return offline.size();
    }

    /**
     * 重建分组节点、应用过滤并展开所有行。
     */
    private void rebuild() {
        root.removeAllChildren();
        boolean filtering = !filter.isEmpty();
        DefaultMutableTreeNode adminNode = new DefaultMutableTreeNode(GROUP_ADMIN);
        DefaultMutableTreeNode userNode = new DefaultMutableTreeNode(GROUP_USER);
        DefaultMutableTreeNode offlineNode = new DefaultMutableTreeNode(GROUP_OFFLINE);
        int hits = 0;
        // 按管理员、普通用户和离线三类填充节点
        hits += fill(adminNode, online.values(), true, true);
        hits += fill(userNode, online.values(), false, true);
        hits += fill(offlineNode, offline.values(), false, false);
        if (!filtering) {
            root.add(adminNode);
            root.add(userNode);
            root.add(offlineNode);
        } else {
            // 有过滤词时隐藏空分组
            addIfNotEmpty(adminNode);
            addIfNotEmpty(userNode);
            addIfNotEmpty(offlineNode);
            if (hits == 0) {
                root.add(new DefaultMutableTreeNode(new Hint("没有匹配“" + filter + "”的用户")));
            }
        }
        // 根节点显示在线人数与过滤命中人数
        root.setUserObject(filtering
                ? "在线用户 " + online.size() + " 人 · 命中 " + hits + " 人"
                : "在线用户 " + online.size() + " 人");
        model.reload();
        for (int i = 0; i < getRowCount(); i++) {
            expandRow(i);
        }
    }

    private void addIfNotEmpty(DefaultMutableTreeNode node) {
        if (node.getChildCount() > 0) {
            root.add(node);
        }
    }

    /**
     * 把属于目标分组且匹配关键词的用户加入分组，并在标题后追加人数。
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
     * 判断用户是否匹配当前关键词，用户名与昵称都参与匹配。
     */
    private boolean matches(User user) {
        if (filter.isEmpty()) {
            return true;
        }
        String nickname = user.getNickname() == null ? "" : user.getNickname().toLowerCase();
        return user.getUsername().toLowerCase().contains(filter) || nickname.contains(filter);
    }

    /**
     * 过滤无结果时的提示节点。
     */
    private static final class Hint {
        private final String text;

        Hint(String text) {
            this.text = text;
        }
    }

    /**
     * 用户节点携带的用户对象和在线状态。
     */
    private static final class Entry {
        private final User user;

        private final boolean online;

        Entry(User user, boolean online) {
            this.user = user;
            this.online = online;
        }
    }

    /**
     * 用户树节点渲染器，绘制头像、双行文字、提示文本与未读角标。
     */
    private static final class UserNodeRenderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 20260920L;

        private transient boolean selectedRow;

        private transient int unreadCount;

        /**
         * 根据节点类型渲染用户、提示或分组标题。
         *
         * @param tree 所属树
         * @param value 节点对象
         * @param selected 是否选中
         * @param expanded 是否展开
         * @param leaf 是否叶子节点
         * @param row 行号
         * @param hasFocus 是否拥有焦点
         * @return 本渲染器
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
                // 昵称为空时回退到用户名
                String nickname = user.getNickname() == null || user.getNickname().isEmpty()
                        ? user.getUsername() : user.getNickname();
                String state = entry.online ? "在线" : "离线";
                String role = user.isAdmin() ? "管理员" : "普通用户";

                setText("<html><body style='margin:0'>"
                        + "<font color='" + hex(entry.online ? Theme.TEXT : Theme.TEXT_WEAK) + "'>"
                        + HtmlEscape.escape(nickname) + "</font><br>"
                        + "<font color='" + hex(entry.online ? Theme.TEXT_WEAK : Theme.OFFLINE) + "'>"
                        + HtmlEscape.escape(user.getUsername()) + " · " + role + " · " + state
                        + "</font></body></html>");
                setIcon(AvatarFactory.avatar(user.getUsername(), entry.online, user.isAdmin(), 34));

                setToolTipText("<html>" + HtmlEscape.oneLine(nickname + "（" + user.getUsername()
                        + "）· " + role + " · " + state) + "</html>");

                if (tree instanceof OnlineUserTree) {
                    // 读取未读数供绘制角标
                    unreadCount = ((OnlineUserTree) tree).unreadCount(user.getUsername());
                }
            } else if (payload instanceof Hint) {
                // 过滤无结果时的提示节点
                String hint = ((Hint) payload).text;
                setText(hint);
                setFont(Theme.fontBase());
                setIcon(Glyphs.search(18, Theme.TEXT_WEAK));
                setForeground(Theme.TEXT_WEAK);
                setToolTipText(hint);
            } else {
                // 剩余节点按分组标题显示
                setText(String.valueOf(payload));
                setFont(Theme.fontBold());
                setIcon(AvatarFactory.groupAvatar(26));
                setForeground(Theme.TEXT);
                setToolTipText(String.valueOf(payload));
            }
            return this;
        }

        /**
         * 绘制选中行背景与未读角标。
         *
         * @param g 绘图上下文
         */
        @Override
        public void paint(Graphics g) {
            if (selectedRow) {
                // 绘制选中行背景
                g.setColor(Theme.SELECTION_BG);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            super.paint(g);
            if (unreadCount > 0) {
                // 在行尾绘制未读角标
                Graphics2D g2 = (Graphics2D) g.create();
                Badge.paint(g2, getWidth(), getHeight() / 2, unreadCount);
                g2.dispose();
            }
        }

        /**
         * 在存在未读角标时加宽节点。
         *
         * @return 节点首选尺寸
         */
        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            if (unreadCount > 0) {
                size.width += BADGE_GAP + Badge.widthOf(unreadCount);
            }
            return size;
        }

        private String hex(java.awt.Color color) {
            return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        }
    }
}

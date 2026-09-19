package com.chat.client.ui;

import com.chat.client.ui.theme.AvatarFactory;
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
import java.awt.Graphics;
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

    /** 根节点 */
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("在线用户");

    /** 树模型 */
    private final transient DefaultTreeModel model = new DefaultTreeModel(root);

    /** 当前在线用户：用户名 -> 用户 */
    private final transient Map<String, User> online = new LinkedHashMap<>();

    /** 本次会话出现过但当前已离线的用户 */
    private final transient Map<String, User> offline = new LinkedHashMap<>();

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
        rebuild();
        return online.size();
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
     */
    private void rebuild() {
        root.removeAllChildren();
        DefaultMutableTreeNode adminNode = new DefaultMutableTreeNode(GROUP_ADMIN);
        DefaultMutableTreeNode userNode = new DefaultMutableTreeNode(GROUP_USER);
        DefaultMutableTreeNode offlineNode = new DefaultMutableTreeNode(GROUP_OFFLINE);
        fill(adminNode, online.values(), true, true);
        fill(userNode, online.values(), false, true);
        fill(offlineNode, offline.values(), false, false);
        root.add(adminNode);
        root.add(userNode);
        root.add(offlineNode);
        root.setUserObject("在线用户 " + online.size() + " 人");
        model.reload();
        for (int i = 0; i < getRowCount(); i++) {
            expandRow(i);
        }
    }

    /**
     * 把符合条件的用户挂到分组节点下。
     *
     * @param parent  分组节点
     * @param users   候选用户
     * @param admin   是否只收管理员
     * @param isOnline 是否为在线分组
     */
    private void fill(DefaultMutableTreeNode parent, java.util.Collection<User> users,
                      boolean admin, boolean isOnline) {
        for (User user : users) {
            if (user.isAdmin() != admin || !matches(user)) {
                continue;
            }
            parent.add(new DefaultMutableTreeNode(new Entry(user, isOnline)));
        }
        parent.setUserObject(parent.getUserObject() + "（" + parent.getChildCount() + "）");
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
                setText("<html><body style='margin:0'>"
                        + "<font color='" + hex(entry.online ? Theme.TEXT : Theme.TEXT_WEAK) + "'>"
                        + nickname + "</font><br>"
                        + "<font color='" + hex(entry.online ? Theme.TEXT_WEAK : Theme.OFFLINE) + "'>"
                        + user.getUsername() + " · " + role + " · " + state + "</font></body></html>");
                setIcon(AvatarFactory.avatar(entry.online, user.isAdmin(), 34));
                setToolTipText(nickname + "（" + user.getUsername() + "）· " + role + " · " + state);
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

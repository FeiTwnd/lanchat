package com.chat.client.ui;

import com.chat.client.ui.theme.AvatarFactory;
import com.chat.client.ui.theme.SkinButton;
import com.chat.client.ui.theme.Theme;
import com.chat.common.User;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;

/**
 * 用户资料卡：点开某个用户时展示其身份信息的小窗。
 *
 * <p>职责：把好友树行里被两行 HTML 压扁的信息展开成一张卡片——大头像、昵称、用户名、
 * 角色与在线状态，并直接给出"发消息"入口。</p>
 *
 * <p>展示的字段严格限定在客户端已有的数据内（{@code USER_LIST} 只下发用户名、昵称、角色，
 * 在线状态由好友树比对得出）。注册时间、最近登录时间、来源地址这些字段服务端虽然有，
 * 但协议里没有对应消息类型，本卡片不为此新增协议——一个纯展示的卡片不值得让两端一起改。</p>
 *
 * <p>非模态：资料卡是"看一眼"的辅助信息，模态会挡住使用者正在进行的输入。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class UserProfileCard {

    /** 头像尺寸 */
    private static final int AVATAR_SIZE = 56;

    /** 卡片宽度 */
    private static final int CARD_WIDTH = 260;

    /**
     * 当前已打开的资料卡。
     *
     * <p>同一时刻只留一张：连续点开几个用户时，若每次都不回收上一张，屏幕上会叠起一摞卡片，
     * 使用者还得逐个关掉。</p>
     */
    private static JDialog openCard;

    /** 私有构造，禁止实例化工具类 */
    private UserProfileCard() {
    }

    /**
     * 弹出用户资料卡。
     *
     * @param owner     父窗口
     * @param user      被展示的用户
     * @param online    是否在线
     * @param onMessage 点击"发消息"时的动作
     */
    public static void show(Window owner, User user, boolean online, Runnable onMessage) {
        if (user == null) {
            return;
        }
        String nickname = user.getNickname() == null || user.getNickname().isEmpty()
                ? user.getUsername() : user.getNickname();
        String role = user.isAdmin() ? "管理员" : "普通用户";
        String state = online ? "在线" : "离线";

        if (openCard != null) {
            openCard.dispose();
            openCard = null;
        }

        JDialog dialog = new JDialog(owner, "用户资料", Dialog.ModalityType.MODELESS);
        openCard = dialog;

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.CARD);
        content.setBorder(BorderFactory.createEmptyBorder(18, 20, 12, 20));

        JLabel avatar = new JLabel(AvatarFactory.avatar(online, user.isAdmin(), AVATAR_SIZE));
        avatar.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(avatar);
        content.add(Box.createVerticalStrut(10));

        content.add(centered(nickname, Theme.font(16, Font.BOLD), Theme.TEXT));
        content.add(Box.createVerticalStrut(6));
        content.add(centered(user.getUsername(), Theme.fontBase(), Theme.TEXT_WEAK));
        content.add(Box.createVerticalStrut(6));
        content.add(centered(role + " · " + state, Theme.fontSmall(),
                online ? Theme.ONLINE : Theme.OFFLINE));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 10));
        actions.setBackground(Theme.CARD);
        actions.add(SkinButton.normal("发消息", event -> {
            dialog.dispose();
            if (onMessage != null) {
                onMessage.run();
            }
        }));
        actions.add(SkinButton.normal("关闭", event -> dialog.dispose()));

        dialog.setLayout(new BorderLayout());
        dialog.add(content, BorderLayout.CENTER);
        dialog.add(actions, BorderLayout.SOUTH);
        // 高度交给布局算：昵称长短、中文字体度量在不同平台上都不同，写死高度会截字
        dialog.pack();
        dialog.setMinimumSize(new Dimension(CARD_WIDTH, dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * 构造一个居中且左对齐基线一致的单行标签。
     *
     * @param text  文本
     * @param font  字体
     * @param color 颜色
     * @return 标签
     */
    private static JLabel centered(String text, Font font, java.awt.Color color) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }
}

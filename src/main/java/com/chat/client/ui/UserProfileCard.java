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
 * 用户资料卡片，以无模式对话框显示昵称、账号、角色与在线状态。
 */
public final class UserProfileCard {
    /** 头像边长，单位像素。 */
    private static final int AVATAR_SIZE = 56;

    /** 卡片最小宽度，单位像素。 */
    private static final int CARD_WIDTH = 260;

    /** 当前打开的卡片，打开新卡片前会关闭它。 */
    private static JDialog openCard;

    private UserProfileCard() {
    }

    /**
     * 显示指定用户的资料卡片。
     *
     * @param owner 所属窗口
     * @param user 要显示的用户，为 null 时直接返回
     * @param online 用户是否在线
     * @param onMessage 点击发消息时的回调，可为 null
     */
    public static void show(Window owner, User user, boolean online, Runnable onMessage) {
        if (user == null) {
            return;
        }
        // 昵称为空时显示用户名
        String nickname = user.getNickname() == null || user.getNickname().isEmpty()
                ? user.getUsername() : user.getNickname();
        String role = user.isAdmin() ? "管理员" : "普通用户";
        String state = online ? "在线" : "离线";

        if (openCard != null) {
            // 同时只保留一张资料卡片
            openCard.dispose();
            openCard = null;
        }

        JDialog dialog = new JDialog(owner, "用户资料", Dialog.ModalityType.MODELESS);
        openCard = dialog;

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.CARD);
        content.setBorder(BorderFactory.createEmptyBorder(18, 20, 12, 20));

        JLabel avatar = new JLabel(
                AvatarFactory.avatar(user.getUsername(), online, user.isAdmin(), AVATAR_SIZE));
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
        // 点击发消息后关闭卡片并执行回调
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

        dialog.pack();
        dialog.setMinimumSize(new Dimension(CARD_WIDTH, dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static JLabel centered(String text, Font font, java.awt.Color color) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }
}

package com.chat.client.ui;

import com.chat.client.ChatClient;
import com.chat.client.ui.theme.AvatarFactory;
import com.chat.client.ui.theme.SkinButton;
import com.chat.client.ui.theme.Theme;
import com.chat.common.Constants;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.function.BiConsumer;

/**
 * 设置窗口，提供昵称、头像、密码修改以及管理员删除用户的操作。
 */
public class SettingsUI extends BaseUI {
    private static final long serialVersionUID = 20260922L;

    /** 头像预览边长，单位像素。 */
    private static final int AVATAR_PREVIEW = Constants.AVATAR_SIDE;

    /** 编码超过长度上限时使用的备用头像边长，单位像素。 */
    private static final int AVATAR_FALLBACK_SIDE = 64;

    private final transient ChatClient client;

    /** 昵称或头像修改成功后的回调，参数依次为新昵称与新头像。 */
    private final transient BiConsumer<String, String> onChanged;

    private final transient boolean admin;

    /** 管理员删除用户时的回调。 */
    private final transient java.util.function.Consumer<String> onDeleteUser;

    private final transient JTextField nicknameField = new JTextField();

    private final transient JLabel avatarPreview = new JLabel();

    private final transient JLabel nicknameStatus = statusLabel(" ");

    private final transient JLabel avatarStatus = statusLabel(" ");

    private final transient JLabel passwordStatus = statusLabel(" ");

    private final transient JPasswordField oldPasswordField = new JPasswordField();

    private final transient JPasswordField newPasswordField = new JPasswordField();

    private final transient JPasswordField confirmPasswordField = new JPasswordField();

    private final transient JTextField deleteUserField = new JTextField();

    private final transient JLabel deleteStatus = statusLabel(" ");

    /** 待提交的头像编码，空串表示恢复默认头像，null 表示未修改。 */
    private transient String pendingAvatar;

    /**
     * 创建普通用户的设置窗口。
     *
     * @param client 已登录的客户端
     * @param onChanged 昵称或头像修改成功后的回调
     */
    public SettingsUI(ChatClient client, BiConsumer<String, String> onChanged) {
        this(client, onChanged, false, null);
    }

    /**
     * 创建设置窗口。
     *
     * @param client 已登录的客户端
     * @param onChanged 昵称或头像修改成功后的回调
     * @param admin 是否为管理员
     * @param onDeleteUser 删除用户的回调，为空时不显示删除分区
     */
    public SettingsUI(ChatClient client, BiConsumer<String, String> onChanged, boolean admin,
                      java.util.function.Consumer<String> onDeleteUser) {
        super("设置 - " + Constants.APP_NAME);
        this.client = client;
        this.onChanged = onChanged;
        this.admin = admin;
        this.onDeleteUser = onDeleteUser;
        nicknameField.setText(client.getNickname());
        body().setLayout(new BorderLayout());
        body().add(buildContent(), BorderLayout.CENTER);
        body().add(buildFooter(), BorderLayout.SOUTH);
        refreshPreview();

        pack();
        Dimension packed = getSize();
        setSize(Math.max(560, packed.width + 8), Math.min(Math.max(520, packed.height + 8), 760));
        centerOnScreen();
    }

    /**
     * 组装各设置分区并放入滚动容器。
     */
    private javax.swing.JComponent buildContent() {
        JPanel content = new JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setBackground(Theme.BG);
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(buildNicknameSection());
        content.add(javax.swing.Box.createVerticalStrut(8));
        content.add(buildAvatarSection());
        content.add(javax.swing.Box.createVerticalStrut(8));
        content.add(buildPasswordSection());
        if (admin && onDeleteUser != null) {
            // 管理员且提供回调时显示删除分区
            content.add(javax.swing.Box.createVerticalStrut(8));
            content.add(buildDeleteUserSection());
        }
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.BG);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setOpaque(false);
        return scroll;
    }

    private JPanel buildDeleteUserSection() {
        JPanel inner = new JPanel(new BorderLayout(6, 6));
        inner.setOpaque(false);
        inner.add(deleteUserField, BorderLayout.CENTER);
        deleteUserField.setToolTipText("要删除的账号用户名（不是昵称）");
        SkinButton delete = new SkinButton("删除该用户", SkinButton.Kind.DANGER);
        delete.addActionListener(event -> submitDeleteUser());
        inner.add(delete, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(Theme.CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JLabel title = new JLabel("删除用户（管理员）");
        title.setFont(Theme.fontBold());
        title.setForeground(Theme.TEXT);
        panel.add(title, BorderLayout.NORTH);
        panel.add(inner, BorderLayout.CENTER);
        panel.add(deleteStatus, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildNicknameSection() {
        JPanel inner = new JPanel(new BorderLayout(6, 6));
        inner.setOpaque(false);
        inner.add(nicknameField, BorderLayout.CENTER);
        inner.add(SkinButton.primary("保存昵称", event -> submitNickname()), BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(Theme.CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JLabel title = new JLabel("昵称（最长 " + Constants.NICKNAME_MAX_LENGTH + " 个字符）");
        title.setFont(Theme.fontBold());
        title.setForeground(Theme.TEXT);
        panel.add(title, BorderLayout.NORTH);
        panel.add(inner, BorderLayout.CENTER);
        panel.add(nicknameStatus, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildAvatarSection() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(SkinButton.normal("选择图片…", event -> chooseAvatar()));
        buttons.add(SkinButton.normal("恢复默认头像", event -> resetAvatar()));
        buttons.add(SkinButton.primary("保存头像", event -> submitAvatar()));

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setOpaque(false);
        right.add(buttons, BorderLayout.NORTH);

        JPanel panel = new JPanel(new BorderLayout(10, 4));
        panel.setBackground(Theme.CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JLabel title = new JLabel("头像");
        title.setFont(Theme.fontBold());
        title.setForeground(Theme.TEXT);
        panel.add(title, BorderLayout.NORTH);
        panel.add(avatarPreview, BorderLayout.WEST);
        panel.add(right, BorderLayout.CENTER);
        panel.add(avatarStatus, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildPasswordSection() {
        JPanel fields = new JPanel(new GridLayout(3, 2, 6, 6));
        fields.setOpaque(false);
        fields.add(label("原密码:"));
        fields.add(oldPasswordField);
        fields.add(label("新密码:"));
        fields.add(newPasswordField);
        fields.add(label("确认新密码:"));
        fields.add(confirmPasswordField);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(Theme.CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JLabel title = new JLabel("修改密码（" + Constants.PASSWORD_MIN_LENGTH + "-"
                + Constants.PASSWORD_MAX_LENGTH + " 位，改完当前会话不会掉线）");
        title.setFont(Theme.fontBold());
        title.setForeground(Theme.TEXT);
        panel.add(title, BorderLayout.NORTH);

        JPanel inner = new JPanel(new BorderLayout(0, 6));
        inner.setOpaque(false);
        inner.add(fields, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        actions.add(SkinButton.primary("保存密码", event -> submitPassword()));
        inner.add(actions, BorderLayout.SOUTH);
        panel.add(inner, BorderLayout.CENTER);
        panel.add(passwordStatus, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildFooter() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        panel.setBackground(Theme.BG);
        JLabel hint = new JLabel("忘记密码请联系管理员删除账号后重新注册；"
                + "昵称与头像改完立即生效，无需管理员审核。");
        hint.setFont(Theme.fontSmall());
        hint.setForeground(Theme.TEXT_WEAK);
        panel.add(hint);
        return panel;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Theme.TEXT);
        return label;
    }

    private void submitNickname() {
        String nickname = nicknameField.getText() == null ? "" : nicknameField.getText().trim();
        // 校验非空与长度上限
        if (nickname.isEmpty()) {
            showSectionResult(nicknameStatus, false, "昵称不能为空");
            return;
        }
        if (nickname.length() > Constants.NICKNAME_MAX_LENGTH) {
            showSectionResult(nicknameStatus, false,
                    "昵称最长 " + Constants.NICKNAME_MAX_LENGTH + " 个字符");
            return;
        }
        if (nickname.equals(client.getNickname())) {
            showSectionResult(nicknameStatus, false, "新昵称与当前昵称相同");
            return;
        }
        if (!client.requestNicknameChange(nickname)) {
            showSectionResult(nicknameStatus, false, "发送失败，请检查与服务端的连接");
            return;
        }
        showSectionResult(nicknameStatus, true, "已提交，等待服务端确认……");
    }

    private void submitAvatar() {
        if (pendingAvatar == null) {
            showSectionResult(avatarStatus, false, "请先选择一张图片，或点“恢复默认头像”");
            return;
        }
        if (!client.requestAvatarChange(pendingAvatar.isEmpty() ? null : pendingAvatar)) {
            showSectionResult(avatarStatus, false, "发送失败，请检查与服务端的连接");
            return;
        }
        showSectionResult(avatarStatus, true, "已提交，等待服务端确认……");
    }

    private void submitPassword() {
        String oldPassword = new String(oldPasswordField.getPassword());
        String newPassword = new String(newPasswordField.getPassword());
        String confirm = new String(confirmPasswordField.getPassword());
        if (oldPassword.isEmpty()) {
            showSectionResult(passwordStatus, false, "请输入原密码");
            return;
        }
        // 校验新密码长度与两次输入是否一致
        if (newPassword.length() < Constants.PASSWORD_MIN_LENGTH
                || newPassword.length() > Constants.PASSWORD_MAX_LENGTH) {
            showSectionResult(passwordStatus, false, "新密码长度需在 "
                    + Constants.PASSWORD_MIN_LENGTH + "-" + Constants.PASSWORD_MAX_LENGTH + " 位之间");
            return;
        }
        if (!newPassword.equals(confirm)) {
            showSectionResult(passwordStatus, false, "两次输入的新密码不一致");
            return;
        }
        if (newPassword.equals(oldPassword)) {
            showSectionResult(passwordStatus, false, "新密码不能与原密码相同");
            return;
        }
        if (!client.requestPasswordChange(oldPassword, newPassword)) {
            showSectionResult(passwordStatus, false, "发送失败，请检查与服务端的连接");
            return;
        }
        showSectionResult(passwordStatus, true, "已提交，等待服务端确认……");
    }

    private void submitDeleteUser() {
        if (!admin || onDeleteUser == null) {
            return;
        }
        String target = deleteUserField.getText() == null ? "" : deleteUserField.getText().trim();
        if (target.isEmpty()) {
            showSectionResult(deleteStatus, false, "请先填写要删除的用户名");
            return;
        }
        if (target.equals(client.getUsername())) {
            // 不能删除当前登录的账号
            showSectionResult(deleteStatus, false, "不能删除当前登录的账号");
            return;
        }
        if (!confirm("确定要删除用户 " + target + " 吗？\n该操作不可恢复，该用户的账号与资料将被移除。")) {
            return;
        }
        onDeleteUser.accept(target);
        showSectionResult(deleteStatus, true, "删除请求已发送：" + target + "，等待服务端处理……");
    }

    /**
     * 显示管理员删除用户的处理结果。
     *
     * @param text 服务端返回的结果文本
     */
    public void onDeleteResult(String text) {
        if (text == null || text.isEmpty() || onDeleteUser == null) {
            return;
        }
        onEdt(() -> showSectionResult(deleteStatus, text.contains("成功"), text));
    }

    private void chooseAvatar() {
        File file = NativeFileDialog.open(this, "选择头像图片", null, Constants.IMAGE_EXTENSIONS);
        if (file == null) {
            return;
        }
        String encoded = encodeAvatar(file);
        if (encoded == null) {
            return;
        }
        pendingAvatar = encoded;
        refreshPreview();
        showSectionResult(avatarStatus, true,
                "已选择 " + file.getName() + "，点“保存头像”后生效");
    }

    private void resetAvatar() {
        pendingAvatar = "";
        refreshPreview();
        showSectionResult(avatarStatus, true, "将恢复为默认头像，点“保存头像”后生效");
    }

    private void refreshPreview() {
        String username = client.getUsername();
        if (pendingAvatar == null) {
            // 尚未选择新头像时显示当前头像
            avatarPreview.setIcon(AvatarFactory.avatar(username, true, false, AVATAR_PREVIEW));
        } else {
            avatarPreview.setIcon(AvatarFactory.previewAvatar(
                    pendingAvatar.isEmpty() ? null : pendingAvatar, true, false, AVATAR_PREVIEW));
        }
        avatarPreview.revalidate();
        avatarPreview.repaint();
    }

    /**
     * 读取图片并编码为限定长度的 Base64 头像。
     *
     * @param file 图片文件
     * @return 编码结果，读取或校验失败时返回 null
     */
    private String encodeAvatar(File file) {
        try {
            // 先读取图片头中的尺寸
            Dimension source = readImageSize(file);
            if (source == null) {
                showSectionResult(avatarStatus, false, "无法识别该文件，请换一张图片");
                return null;
            }
            if (source.width > Constants.AVATAR_IMAGE_MAX_SIDE
                    || source.height > Constants.AVATAR_IMAGE_MAX_SIDE) {
                showSectionResult(avatarStatus, false, "图片过大（" + source.width + "×"
                        + source.height + "），请先缩小到 "
                        + Constants.AVATAR_IMAGE_MAX_SIDE + " 像素以内");
                return null;
            }
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                showSectionResult(avatarStatus, false, "无法解码该图片，请换一张图片");
                return null;
            }
            String encoded = encodeSquare(image, Constants.AVATAR_SIDE);
            if (encoded.length() > Constants.AVATAR_MAX_LENGTH) {
                // 超过长度上限时改用更小的边长重新编码
                encoded = encodeSquare(image, AVATAR_FALLBACK_SIDE);
            }
            if (encoded.length() > Constants.AVATAR_MAX_LENGTH) {
                showSectionResult(avatarStatus, false, "图片内容过于复杂，编码后仍超过 "
                        + Constants.AVATAR_MAX_LENGTH + " 字节，请换一张更简洁的图片");
                return null;
            }
            return encoded;
        } catch (IOException e) {
            showSectionResult(avatarStatus, false, "读取图片失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 只读取图片尺寸而不解码整张图。
     *
     * @param file 图片文件
     * @return 图片宽高，无法识别时返回 null
     * @throws IOException 读取文件失败
     */
    private static Dimension readImageSize(File file) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(file)) {
            if (stream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                return new Dimension(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * 居中裁剪为正方形，缩放后编码为 PNG 的 Base64 文本。
     *
     * @param source 原图
     * @param side 输出边长
     * @return Base64 编码的 PNG
     * @throws IOException 编码写入失败
     */
    private static String encodeSquare(BufferedImage source, int side) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        int crop = Math.min(width, height);
        // 居中裁剪出最大正方形
        BufferedImage square = source.getSubimage((width - crop) / 2, (height - crop) / 2,
                crop, crop);
        BufferedImage scaled = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(square, 0, 0, side, side, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * 处理服务端返回的资料修改结果。
     *
     * @param content 以竖线分隔的结果文本，依次为成功标记、分区和原因
     */
    public void onProfileResult(String content) {
        String[] fields = content == null ? new String[0]
                : content.split("\\|", Constants.PROFILE_RESULT_FIELD_COUNT);
        if (fields.length < Constants.PROFILE_RESULT_FIELD_COUNT) {
            // 字段数不足视为回执格式异常
            showSectionResult(passwordStatus, false, "服务端回执格式异常：" + content);
            return;
        }
        boolean success = "1".equals(fields[0].trim());
        String section = fields[1].trim();
        String reason = fields[2].trim();
        JLabel target = statusLabelFor(section);
        if (success) {
            applySuccess(section);
            showSectionResult(target, true, sectionName(section) + "修改成功");
        } else {
            showSectionResult(target, false, sectionName(section) + "修改失败："
                    + (reason.isEmpty() ? "未知原因" : reason));
        }
    }

    /**
     * 修改成功后更新界面并触发回调。
     */
    private void applySuccess(String section) {
        if (Constants.PROFILE_SECTION_NICK.equals(section)) {
            // 昵称修改成功后通知主窗口刷新
            String nickname = nicknameField.getText() == null ? "" : nicknameField.getText().trim();
            if (onChanged != null) {
                onChanged.accept(nickname, null);
            }
            return;
        }
        if (Constants.PROFILE_SECTION_AVATAR.equals(section)) {
            if (onChanged != null) {
                onChanged.accept(null, pendingAvatar == null ? "" : pendingAvatar);
            }
            pendingAvatar = null;
            refreshPreview();
            return;
        }
        if (Constants.PROFILE_SECTION_PASSWORD.equals(section)) {
            // 密码修改成功后清空输入框
            oldPasswordField.setText("");
            newPasswordField.setText("");
            confirmPasswordField.setText("");
        }
    }

    private JLabel statusLabelFor(String section) {
        if (Constants.PROFILE_SECTION_NICK.equals(section)) {
            return nicknameStatus;
        }
        if (Constants.PROFILE_SECTION_AVATAR.equals(section)) {
            return avatarStatus;
        }
        return passwordStatus;
    }

    /**
     * 把分区标识转换为中文名称。
     *
     * @param section 分区标识
     * @return 分区的中文名称
     */
    static String sectionName(String section) {
        if (Constants.PROFILE_SECTION_NICK.equals(section)) {
            return "昵称";
        }
        if (Constants.PROFILE_SECTION_AVATAR.equals(section)) {
            return "头像";
        }
        if (Constants.PROFILE_SECTION_PASSWORD.equals(section)) {
            return "密码";
        }
        return section == null || section.isEmpty() ? "资料" : section;
    }

    private void showSectionResult(JLabel target, boolean success, String text) {
        if (target == null) {
            return;
        }
        target.setText(text);
        target.setForeground(success ? Theme.ONLINE : Theme.DANGER);
    }
}

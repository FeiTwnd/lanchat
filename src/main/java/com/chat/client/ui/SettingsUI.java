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
 * 设置窗口：昵称、头像与密码的统一入口。
 *
 * <p>为什么要有这个窗口：此前"改昵称"在主窗口右栏、"改密码"在登录界面、
 * "换头像"根本没有入口——三件同属"修改我自己的资料"的事散在三处，
 * 使用者必须先猜"这件事该去哪个窗口做"，猜错就只能得到一句"请到别处办理"。
 * 合并之后三者共用一条回执通道（{@link com.chat.common.MessageType#PROFILE_RESULT}）
 * 与一套结果展示。</p>
 *
 * <p>三条链路都不需要管理员审核：服务端校验的是"原密码对不对""昵称长度是否合法"
 * 这类客观规则，改的是自己的资料，没有需要人为裁量的部分。</p>
 *
 * <p>与主窗口的分工：本窗口只负责"收集输入、做本地校验、把请求交给
 * {@link ChatClient}"，服务端回执由 {@link ClientUI} 转交给
 * {@link #onProfileResult(String)} 展示——窗口不做自己的网络监听，
 * 否则每开一次设置就要在客户端上挂一份监听器，关掉时还得记得摘。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class SettingsUI extends BaseUI {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20260922L;

    /** 头像预览的显示边长（像素） */
    private static final int AVATAR_PREVIEW = Constants.AVATAR_SIDE;

    /** 头像超出长度上限时的降级边长：先降到 64 再重编一次 */
    private static final int AVATAR_FALLBACK_SIDE = 64;

    /** 客户端实例 */
    private final transient ChatClient client;

    /** 资料变更成功后的回调：参数为（新昵称, 新头像 Base64），未变更的传 null */
    private final transient BiConsumer<String, String> onChanged;

    /** 是否为管理员：决定本窗口是否出现"删除用户"分区 */
    private final transient boolean admin;

    /** 删除用户请求的回调，参数为待删除的用户名；为 null 表示本窗口不提供该功能 */
    private final transient java.util.function.Consumer<String> onDeleteUser;

    /** 昵称输入框 */
    private final transient JTextField nicknameField = new JTextField();

    /** 头像预览标签 */
    private final transient JLabel avatarPreview = new JLabel();

    /** 昵称分区的结果提示 */
    private final transient JLabel nicknameStatus = statusLabel(" ");

    /** 头像分区的结果提示 */
    private final transient JLabel avatarStatus = statusLabel(" ");

    /** 密码分区的结果提示 */
    private final transient JLabel passwordStatus = statusLabel(" ");

    /** 原密码 */
    private final transient JPasswordField oldPasswordField = new JPasswordField();

    /** 新密码 */
    private final transient JPasswordField newPasswordField = new JPasswordField();

    /** 确认新密码 */
    private final transient JPasswordField confirmPasswordField = new JPasswordField();

    /** 待删除的用户名（仅管理员可见的分区使用） */
    private final transient JTextField deleteUserField = new JTextField();

    /** 删除用户分区的结果提示 */
    private final transient JLabel deleteStatus = statusLabel(" ");

    /**
     * 待提交的头像 Base64。
     *
     * <p>选择图片后并不立即上传，而是先在这里放一份预览：换头像是个"看一眼再决定"的操作，
     * 选错一张立刻上传、失败再传一次，对使用者与服务器都是多余的往返。
     * 恢复默认头像时置为 {@code ""}——空串表示"清除"，与 {@code null}（什么都不改）区分开。</p>
     */
    private transient String pendingAvatar;

    /**
     * 构造设置窗口。
     *
     * @param client    客户端实例
     * @param onChanged 资料变更成功后的回调，参数为（新昵称, 新头像 Base64），未变更的为 null
     */
    public SettingsUI(ChatClient client, BiConsumer<String, String> onChanged) {
        this(client, onChanged, false, null);
    }

    /**
     * 构造设置窗口，并决定是否提供"删除用户"分区。
     *
     * <p>为什么删除用户挪到这里：它原本是主窗口右栏的一枚按钮，和"改我自己的资料"并列。
     * 但这两件事的性质完全不同——一个是人人可用的自助操作，一个是管理员对别人账号的
     * 处置，放在同一列里既容易误点，也让普通用户看到一个自己用不了的功能。
     * 收进设置窗口后，普通用户根本看不到这个分区。</p>
     *
     * @param client       客户端实例
     * @param onChanged    资料变更成功后的回调，参数为（新昵称, 新头像 Base64），未变更的为 null
     * @param admin        当前用户是否为管理员
     * @param onDeleteUser 删除用户请求的回调，参数为待删除用户名；非管理员传 null
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
        // 高度按内容实算而不是写死：写死的高度在"某个分区长得比预期高"时会把下面
        // 那块直接切掉（此前头像预览与密码框被截断正是这个原因），而截断的部分
        // 使用者既看不到也没有滚动条可拉。包一层滚动面板作为第二道保险，
        // 屏幕放不下时至少还能滚，而不是永远看不到
        pack();
        Dimension packed = getSize();
        setSize(Math.max(560, packed.width + 8), Math.min(Math.max(520, packed.height + 8), 760));
        centerOnScreen();
    }

    /**
     * 组装各分区。
     *
     * <p>用纵向 {@link BoxLayout} 而不是等分网格：三个分区的自然高度本来就不一样，
     * 等分会让"内容高的那个被裁掉、内容矮的那个留一片空白"。</p>
     *
     * @return 内容面板
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

    /**
     * 删除用户分区（仅管理员）。
     *
     * @return 面板
     */
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

    /**
     * 昵称分区。
     *
     * @return 面板
     */
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

    /**
     * 头像分区。
     *
     * @return 面板
     */
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

    /**
     * 密码分区。
     *
     * @return 面板
     */
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

    /**
     * 底部说明栏。
     *
     * @return 面板
     */
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

    /**
     * 构造一个左对齐的字段标签。
     *
     * @param text 文本
     * @return 标签
     */
    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Theme.TEXT);
        return label;
    }

    /**
     * 提交昵称修改。
     */
    private void submitNickname() {
        String nickname = nicknameField.getText() == null ? "" : nicknameField.getText().trim();
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

    /**
     * 提交头像修改。
     */
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

    /**
     * 提交密码修改。
     */
    private void submitPassword() {
        String oldPassword = new String(oldPasswordField.getPassword());
        String newPassword = new String(newPasswordField.getPassword());
        String confirm = new String(confirmPasswordField.getPassword());
        if (oldPassword.isEmpty()) {
            showSectionResult(passwordStatus, false, "请输入原密码");
            return;
        }
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

    /**
     * 提交删除用户请求。
     *
     * <p>本地只做"填了没有、是不是自己"这类一眼可辨的检查，其余交给服务端：
     * 是不是管理员、目标存不存在，都只有服务端说了算，客户端抢着判会给出与真实结果
     * 不一致的结论。</p>
     */
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
     * 展示服务端对删除用户的处理结果。
     *
     * <p>删除的结果由服务端以系统消息下发、经主窗口转到这里：请求是从本窗口发出的，
     * 结果却弹在主窗口的状态栏上，使用者很可能没看见。</p>
     *
     * @param text 结果文本
     */
    public void onDeleteResult(String text) {
        if (text == null || text.isEmpty() || onDeleteUser == null) {
            return;
        }
        onEdt(() -> showSectionResult(deleteStatus, text.contains("成功"), text));
    }

    /**
     * 选择一张本地图片作为头像。
     *
     * <p>用系统原生对话框（见 {@link NativeFileDialog}）：换头像是"随手挑一张图"的动作，
     * 使用者对系统自己的选图窗口最熟悉；扩展名限制交给它顺带校验，
     * 图片能不能解码仍由 {@link #encodeAvatar(File)} 说了算。</p>
     */
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

    /**
     * 把待提交的头像设置为"恢复默认"。
     */
    private void resetAvatar() {
        pendingAvatar = "";
        refreshPreview();
        showSectionResult(avatarStatus, true, "将恢复为默认头像，点“保存头像”后生效");
    }

    /**
     * 重画头像预览。
     *
     * <p>预览与聊天窗口里的头像走同一套绘制逻辑，但<b>不共用同一份状态</b>：
     * 待提交的图片通过 {@link AvatarFactory#previewAvatar} 画成一张只挂在本标签上的图标。
     * 早先的做法是先把待提交图片 {@code putAvatar} 进工厂再取图标，看起来省事，
     * 代价是"选了图片"这一步就已经把全局头像换掉了——主窗口、用户列表、聊天气泡
     * 立刻全变成新头像，此时不点保存也退不回去，等于保存按钮形同虚设。
     * 预览只画在本地，未点“保存头像”前，换头像这件事对外界零影响。</p>
     */
    private void refreshPreview() {
        String username = client.getUsername();
        if (pendingAvatar == null) {
            // 还没选过任何东西：显示服务端下发的当前头像
            avatarPreview.setIcon(AvatarFactory.avatar(username, true, false, AVATAR_PREVIEW));
        } else {
            avatarPreview.setIcon(AvatarFactory.previewAvatar(
                    pendingAvatar.isEmpty() ? null : pendingAvatar, true, false, AVATAR_PREVIEW));
        }
        avatarPreview.revalidate();
        avatarPreview.repaint();
    }

    /**
     * 读取并编码头像图片。
     *
     * <p>三步都有各自的拒绝理由：先只读图片头拿到尺寸（不整图解码，否则一张
     * "解压炸弹"就足以把客户端撑爆），超限直接拒绝；再按<b>短边</b>等比缩放并居中裁切，
     * 不能直接拉成正方形——那会把人物压扁；最后编码后若仍超出长度上限，降一级边长重编一次。</p>
     *
     * @param file 用户选择的图片文件
     * @return 头像的 Base64 文本；失败时返回 null（原因已提示给使用者）
     */
    private String encodeAvatar(File file) {
        try {
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
                // 细节特别多的照片在 96×96 下也可能编出大文件，降一级再试；
                // 仍然超限就说明这张图不适合做头像，明确报错好过让它被服务端静默拒绝
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
     * 只读取图片的尺寸信息，不做整图解码。
     *
     * @param file 图片文件
     * @return 尺寸；格式不被支持时返回 null
     * @throws IOException 读取失败
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
     * 把图片按短边居中裁成正方形并缩放，再编码为 Base64 PNG。
     *
     * @param source 源图
     * @param side   目标边长
     * @return Base64 文本
     * @throws IOException 编码失败
     */
    private static String encodeSquare(BufferedImage source, int side) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        int crop = Math.min(width, height);
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
     * 处理方法端回执。
     *
     * <p>由 {@link ClientUI} 转交：本窗口不做客户端监听，因此回执的入口只有这一处。
     * 正文格式为 {@code 成败|分区|中文原因}，按分区把结果写到对应的提示行，
     * 让使用者一眼看出"是哪一项成功了"。</p>
     *
     * @param content 回执正文
     */
    public void onProfileResult(String content) {
        String[] fields = content == null ? new String[0]
                : content.split("\\|", Constants.PROFILE_RESULT_FIELD_COUNT);
        if (fields.length < Constants.PROFILE_RESULT_FIELD_COUNT) {
            // 解析不了就原样显示：宁可让人看到一段看不懂的文本，也不要假装"没收到"
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
     * 回执成功后就地同步界面状态。
     *
     * @param section 分区标识
     */
    private void applySuccess(String section) {
        if (Constants.PROFILE_SECTION_NICK.equals(section)) {
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
            oldPasswordField.setText("");
            newPasswordField.setText("");
            confirmPasswordField.setText("");
        }
    }

    /**
     * 取分区标识对应的提示行。
     *
     * @param section 分区标识
     * @return 提示标签；未知分区用密码分区那一行兜底
     */
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
     * 把分区标识翻译成中文名。
     *
     * <p>之所以放在这里而不是各调用点各写一份：主窗口的状态栏与设置窗口的分区提示
     * 都要翻译同一个标识，两处各写一份就会出现"同一个回执在两处说法不同"。</p>
     *
     * @param section 分区标识，例如 {@code NICK}
     * @return 中文名；未知标识原样返回
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

    /**
     * 在指定提示行上显示结果。
     *
     * @param target  提示标签
     * @param success 是否成功
     * @param text    文本
     */
    private void showSectionResult(JLabel target, boolean success, String text) {
        if (target == null) {
            return;
        }
        target.setText(text);
        target.setForeground(success ? Theme.ONLINE : Theme.DANGER);
    }
}

package com.chat.client.ui.theme;

import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 界面主题：集中定义调色板、字体与全局外观默认值。
 *
 * <p>职责：把"界面长什么样"这件事收敛到一处，使各窗口只关心布局与交互，
 * 不再各自硬编码颜色与字号。</p>
 *
 * <p>之所以全部使用代码定义而不加载图片资源：跨平台运行时不必依赖资源目录与工作目录，
 * 也不会把第三方素材带入课程设计（参考项目中直接复用聊天软件皮肤的做法存在版权与
 * 原创性问题）。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class Theme {

    /** 主色调：标题栏渐变起点、主按钮、选中态 */
    public static final Color PRIMARY = new Color(0x12B7F5);

    /** 主色调深色：渐变终点、按钮按下态 */
    public static final Color PRIMARY_DARK = new Color(0x0A8FC4);

    /** 主色调浅色：按钮悬停底、浅色强调底 */
    public static final Color PRIMARY_LIGHT = new Color(0xE4F5FD);

    /** 列表与表格的选中背景：比 PRIMARY_LIGHT 略深，保证选中行一眼可辨，又不淹没浅色头像 */
    public static final Color SELECTION_BG = new Color(0xCFE9FA);

    /** 窗口背景色 */
    public static final Color BG = new Color(0xF2F5F9);

    /** 卡片背景色 */
    public static final Color CARD = Color.WHITE;

    /** 描边色 */
    public static final Color BORDER = new Color(0xD9E1E8);

    /** 正文颜色 */
    public static final Color TEXT = new Color(0x27374D);

    /** 次要文字颜色 */
    public static final Color TEXT_WEAK = new Color(0x8A97A5);

    /** 在线状态色 */
    public static final Color ONLINE = new Color(0x2ECC71);

    /** 离线状态色 */
    public static final Color OFFLINE = new Color(0xB6C2CD);

    /** 管理员标识色 */
    public static final Color ADMIN = new Color(0xF5A623);

    /** 默认头像底色（在线） */
    public static final Color AVATAR_BG = new Color(0xD7E1EB);

    /** 默认头像人形颜色（在线） */
    public static final Color AVATAR_FG = new Color(0x8FA3B8);

    /** 默认头像底色（离线，整体更浅以体现"不在线"） */
    public static final Color AVATAR_BG_OFFLINE = new Color(0xEDF1F5);

    /** 默认头像人形颜色（离线） */
    public static final Color AVATAR_FG_OFFLINE = new Color(0xC2CCD6);

    /** 危险操作色 */
    public static final Color DANGER = new Color(0xE05B5B);

    /** 字体族候选，按优先级探测 */
    private static final String[] FONT_CANDIDATES = {
            "Microsoft YaHei", "微软雅黑", "PingFang SC", "Noto Sans CJK SC",
            "Source Han Sans SC", "WenQuanYi Micro Hei", "SansSerif"};

    /** 解析出的可用字体族 */
    private static final String FAMILY = resolveFamily();

    /** 全局外观是否已初始化 */
    private static boolean ready;

    /** 私有构造，禁止实例化工具类 */
    private Theme() {
    }

    /**
     * 常规字号字体。
     *
     * @return 字体
     */
    public static Font fontBase() {
        return font(13, Font.PLAIN);
    }

    /**
     * 常规加粗字体。
     *
     * @return 字体
     */
    public static Font fontBold() {
        return font(13, Font.BOLD);
    }

    /**
     * 标题字体。
     *
     * @return 字体
     */
    public static Font fontTitle() {
        return font(14, Font.BOLD);
    }

    /**
     * 小号字体。
     *
     * @return 字体
     */
    public static Font fontSmall() {
        return font(12, Font.PLAIN);
    }

    /**
     * 按需构造字体。
     *
     * @param size  字号
     * @param style 字形（{@link Font#PLAIN} 等）
     * @return 字体
     */
    public static Font font(int size, int style) {
        return new Font(FAMILY, style, size);
    }

    /**
     * 安装全局外观与默认字体颜色。
     *
     * <p>只执行一次；外观设置属于表现层增强，失败时回退默认外观而不是中断启动。</p>
     */
    public static void installDefaults() {
        if (ready) {
            return;
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                 | UnsupportedLookAndFeelException e) {
            System.err.println("系统外观设置失败，使用默认外观: " + e.getMessage());
        }
        Font base = fontBase();
        UIManager.put("Label.font", base);
        UIManager.put("Button.font", base);
        UIManager.put("TextField.font", base);
        UIManager.put("PasswordField.font", base);
        UIManager.put("TextArea.font", base);
        UIManager.put("TextPane.font", base);
        UIManager.put("ComboBox.font", base);
        UIManager.put("CheckBox.font", base);
        UIManager.put("Table.font", base);
        UIManager.put("TableHeader.font", fontBold());
        UIManager.put("Tree.font", base);
        UIManager.put("Tree.rowHeight", 46);
        UIManager.put("Tree.background", CARD);
        UIManager.put("Tree.paintLines", Boolean.FALSE);
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.messageFont", base);
        UIManager.put("OptionPane.buttonFont", base);
        UIManager.put("TitledBorder.font", fontBold());
        // 进度条文字颜色取自外观默认值（Ocean 主题下是暗红与白色），
        // 与主题蓝冲突；实测基础外观下填充区用 selectionForeground、
        // 空槽用 selectionBackground，故按“蓝底白字、灰底深字”设置
        UIManager.put("ProgressBar.selectionForeground", java.awt.Color.WHITE);
        UIManager.put("ProgressBar.selectionBackground", TEXT);
        // 选中色必须显式覆盖：系统外观在带 GTK 的 Linux 上是 GTK 主题色（Ubuntu 为橙色 #E95420），
        // 与蓝色主题冲突
        UIManager.put("Tree.selectionBackground", SELECTION_BG);
        UIManager.put("Tree.selectionForeground", TEXT);
        UIManager.put("Table.selectionBackground", SELECTION_BG);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("List.selectionBackground", SELECTION_BG);
        UIManager.put("List.selectionForeground", TEXT);
        UIManager.put("TextField.selectionBackground", SELECTION_BG);
        UIManager.put("TextField.selectionForeground", TEXT);
        UIManager.put("PasswordField.selectionBackground", SELECTION_BG);
        UIManager.put("PasswordField.selectionForeground", TEXT);
        UIManager.put("TextArea.selectionBackground", SELECTION_BG);
        UIManager.put("TextArea.selectionForeground", TEXT);
        UIManager.put("TextPane.selectionBackground", SELECTION_BG);
        UIManager.put("TextPane.selectionForeground", TEXT);
        // 树与表格改用基础外观实现，这是好友树"选中行颜色不对"的根因修复：
        // 系统外观若为 GTK，会用自己的主题强调色填充整行选中背景（Ubuntu 是橙色）与行内空隙，
        // 而树单元格渲染器只覆盖"头像 + 文字"这一块，于是两侧露出橙边；
        // 基础实现把行背景完全交给渲染器，配色才由主题说了算。
        // 代价是系统外观自带的展开/收起图标不再可用，因此改为自绘三角。
        UIManager.put("TreeUI", "javax.swing.plaf.basic.BasicTreeUI");
        UIManager.put("TableUI", "javax.swing.plaf.basic.BasicTableUI");
        UIManager.put("Tree.collapsedIcon", Glyphs.treeArrow(false, 12, TEXT_WEAK));
        UIManager.put("Tree.expandedIcon", Glyphs.treeArrow(true, 12, TEXT_WEAK));
        ready = true;
    }

    /**
     * 把进度条统一成主题风格。
     *
     * <p>系统外观（无 GTK 的 Linux 上为 Metal/Ocean）会用橙红渐变绘制进度条，
     * 与整体蓝色主题不协调，因此改为基础外观 + 主题配色。</p>
     *
     * @param bar 进度条
     */
    public static void styleProgressBar(javax.swing.JProgressBar bar) {
        bar.setUI(new javax.swing.plaf.basic.BasicProgressBarUI());
        bar.setForeground(PRIMARY);
        bar.setBackground(BORDER);
        bar.setBorderPainted(false);
        bar.setStringPainted(true);
        bar.setFont(fontSmall());
    }

    /**
     * 探测第一个可用字体族。
     *
     * <p>Linux 环境通常没有微软雅黑，若直接使用会被替换成默认字体且度量不可控，
     * 因此按候选列表探测可用字体，全部不可用时回退逻辑字体。</p>
     *
     * @return 字体族名
     */
    private static String resolveFamily() {
        try {
            Set<String> available = new HashSet<>(Arrays.asList(GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String candidate : FONT_CANDIDATES) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (RuntimeException e) {
            System.err.println("字体探测失败，使用逻辑字体: " + e.getMessage());
        }
        return Font.SANS_SERIF;
    }
}

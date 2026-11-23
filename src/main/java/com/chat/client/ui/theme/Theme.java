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
 * 客户端统一的配色、字体与 Swing 全局外观设置。
 */
public final class Theme {
    public static final Color PRIMARY = new Color(0x12B7F5);

    public static final Color PRIMARY_DARK = new Color(0x0A8FC4);

    public static final Color PRIMARY_LIGHT = new Color(0xE4F5FD);

    public static final Color SELECTION_BG = new Color(0xCFE9FA);

    public static final Color BG = new Color(0xF2F5F9);

    public static final Color CARD = Color.WHITE;

    public static final Color BORDER = new Color(0xD9E1E8);

    public static final Color QUOTE_BG = new Color(0xF0F3F6);

    public static final Color TEXT = new Color(0x27374D);

    public static final Color TEXT_WEAK = new Color(0x8A97A5);

    public static final Color ONLINE = new Color(0x2ECC71);

    public static final Color OFFLINE = new Color(0xB6C2CD);

    public static final Color ADMIN = new Color(0xF5A623);

    public static final Color AVATAR_BG = new Color(0xD7E1EB);

    public static final Color AVATAR_FG = new Color(0x8FA3B8);

    public static final Color AVATAR_BG_OFFLINE = new Color(0xEDF1F5);

    public static final Color AVATAR_FG_OFFLINE = new Color(0xC2CCD6);

    public static final Color DANGER = new Color(0xE05B5B);

    /** 按优先级排列的界面字体候选。 */
    private static final String[] FONT_CANDIDATES = {
            "Microsoft YaHei", "微软雅黑", "PingFang SC", "Noto Sans CJK SC",
            "Source Han Sans SC", "WenQuanYi Micro Hei", "SansSerif"};

    /** 本机实际可用的界面字体族。 */
    private static final String FAMILY = resolveFamily();

    /** 标记全局外观是否已经安装。 */
    private static boolean ready;

    private Theme() {
    }

    /** 返回 13 号常规字体。 */
    public static Font fontBase() {
        return font(13, Font.PLAIN);
    }

    /** 返回 13 号加粗字体。 */
    public static Font fontBold() {
        return font(13, Font.BOLD);
    }

    /** 返回 14 号加粗标题字体。 */
    public static Font fontTitle() {
        return font(14, Font.BOLD);
    }

    /** 返回 12 号常规小字体。 */
    public static Font fontSmall() {
        return font(12, Font.PLAIN);
    }

    /**
     * 按指定字号与字型创建界面字体。
     *
     * @param size 字号
     * @param style 字型，取 Font 中定义的常量
     * @return 使用当前界面字体族的字体对象
     */
    public static Font font(int size, int style) {
        return new Font(FAMILY, style, size);
    }

    /**
     * 安装系统外观，并设置全局字体、配色与树形控件样式。
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
        // 统一各组件的基础字体
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

        UIManager.put("ProgressBar.selectionForeground", java.awt.Color.WHITE);
        UIManager.put("ProgressBar.selectionBackground", TEXT);

        // 统一列表、表格与输入框的选中配色
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

        // 树和表格改用基础 UI 实现，以便应用自定义行高与展开图标
        UIManager.put("TreeUI", "javax.swing.plaf.basic.BasicTreeUI");
        UIManager.put("TableUI", "javax.swing.plaf.basic.BasicTableUI");
        UIManager.put("Tree.collapsedIcon", Glyphs.treeArrow(false, 12, TEXT_WEAK));
        UIManager.put("Tree.expandedIcon", Glyphs.treeArrow(true, 12, TEXT_WEAK));
        ready = true;
    }

    /**
     * 把进度条改为扁平样式并显示百分比文字。
     *
     * @param bar 待设置的进度条
     */
    public static void styleProgressBar(javax.swing.JProgressBar bar) {
        bar.setUI(new javax.swing.plaf.basic.BasicProgressBarUI());
        bar.setForeground(PRIMARY);
        bar.setBackground(BORDER);
        bar.setBorderPainted(false);
        bar.setStringPainted(true);
        bar.setFont(fontSmall());
    }

    /** 按候选列表挑选本机已安装的第一个字体族。 */
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

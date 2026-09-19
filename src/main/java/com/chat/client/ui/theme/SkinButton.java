package com.chat.client.ui.theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 扁平风格按钮。
 *
 * <p>职责：替代系统默认按钮外观，提供主色、常规、幽灵、危险四种变体，
 * 并自带悬停与按下反馈。之所以自绘而不使用图片：按钮状态组合多（悬停、按下、禁用、选中），
 * 代码绘制可以精确控制每个状态的配色，且不产生资源文件。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public class SkinButton extends JButton {

    /** 序列化版本号 */
    private static final long serialVersionUID = 20260919L;

    /** 按钮变体 */
    public enum Kind {
        /** 主色实心按钮，用于主操作 */
        PRIMARY,
        /** 白底描边按钮，用于常规操作 */
        NORMAL,
        /** 无边框按钮，用于次要操作 */
        GHOST,
        /** 危险色按钮，用于删除等不可恢复操作 */
        DANGER
    }

    /** 变体 */
    private final Kind kind;

    /**
     * 构造按钮。
     *
     * @param text 按钮文本
     * @param kind 变体
     */
    public SkinButton(String text, Kind kind) {
        super(text);
        this.kind = kind;
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(Theme.fontBase());
        setForeground(foregroundFor());
        setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
    }

    /**
     * 创建主色按钮。
     *
     * @param text   文本
     * @param action 点击事件
     * @return 按钮
     */
    public static SkinButton primary(String text, java.awt.event.ActionListener action) {
        SkinButton button = new SkinButton(text, Kind.PRIMARY);
        button.addActionListener(action);
        return button;
    }

    /**
     * 创建常规按钮。
     *
     * @param text   文本
     * @param action 点击事件
     * @return 按钮
     */
    public static SkinButton normal(String text, java.awt.event.ActionListener action) {
        SkinButton button = new SkinButton(text, Kind.NORMAL);
        button.addActionListener(action);
        return button;
    }

    /**
     * 创建左侧对齐的功能按钮（用于功能菜单列）。
     *
     * @param text   文本
     * @param icon   图标
     * @param action 点击事件
     * @return 按钮
     */
    public static SkinButton menu(String text, javax.swing.Icon icon,
                                 java.awt.event.ActionListener action) {
        return menu(text, icon, Kind.NORMAL, action);
    }

    /**
     * 创建左侧对齐的功能按钮。
     *
     * @param text   文本
     * @param icon   图标
     * @param kind   变体
     * @param action 点击事件
     * @return 按钮
     */
    public static SkinButton menu(String text, javax.swing.Icon icon, Kind kind,
                                 java.awt.event.ActionListener action) {
        SkinButton button = new SkinButton(text, kind);
        button.setIcon(icon);
        button.setIconTextGap(10);
        button.setHorizontalAlignment(LEFT);
        button.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
        button.addActionListener(action);
        return button;
    }

    /**
     * 为按钮补充图标。
     *
     * @param icon 图标
     * @return 当前按钮，便于链式调用
     */
    public SkinButton withIcon(javax.swing.Icon icon) {
        setIcon(icon);
        setIconTextGap(8);
        return this;
    }

    /**
     * 依据变体取文字颜色。
     *
     * @return 颜色
     */
    private Color foregroundFor() {
        switch (kind) {
            case PRIMARY:
                return Color.WHITE;
            case DANGER:
                return Theme.DANGER;
            case GHOST:
                return Theme.PRIMARY;
            default:
                return Theme.TEXT;
        }
    }

    /**
     * 依据变体与当前状态取填充色。
     *
     * @return 填充色；返回 null 表示不绘制底色
     */
    private Color fillFor() {
        boolean pressed = getModel().isPressed();
        boolean rollover = getModel().isRollover();
        switch (kind) {
            case PRIMARY:
                return pressed ? Theme.PRIMARY_DARK : rollover ? Theme.PRIMARY_DARK : Theme.PRIMARY;
            case DANGER:
                return pressed ? new Color(0xFBE3E3) : rollover ? new Color(0xFDF0F0) : Theme.CARD;
            case GHOST:
                return rollover ? Theme.PRIMARY_LIGHT : null;
            default:
                return pressed ? Theme.PRIMARY_LIGHT : rollover ? new Color(0xF7FBFF) : Theme.CARD;
        }
    }

    /**
     * 自绘按钮背景，再交由父类绘制文本与图标。
     *
     * @param g 画笔
     */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth();
        int height = getHeight();
        int arc = 8;
        Color fill = isEnabled() ? fillFor() : new Color(0xEDF1F5);
        if (fill != null) {
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc);
        }
        if (kind != Kind.PRIMARY && kind != Kind.GHOST) {
            g2.setColor(kind == Kind.DANGER ? new Color(0xF3C9C9) : Theme.BORDER);
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);
        }
        g2.dispose();
        if (!isEnabled()) {
            setForeground(Theme.TEXT_WEAK);
        } else {
            setForeground(foregroundFor());
        }
        super.paintComponent(g);
    }
}

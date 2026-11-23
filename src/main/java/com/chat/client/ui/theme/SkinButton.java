package com.chat.client.ui.theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 统一样式的按钮，按用途分为主要、普通、幽灵和危险四种外观。
 */
public class SkinButton extends JButton {
    private static final long serialVersionUID = 20260919L;

    /**
     * 按钮的用途分类，决定背景、文字与边框的配色。
     */
    public enum Kind {
        PRIMARY,

        NORMAL,

        GHOST,

        DANGER
    }

    private final Kind kind;

    /**
     * 创建按钮并设置统一的字体、边距与鼠标样式。
     *
     * @param text 按钮文字
     * @param kind 按钮用途
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
     * 创建主要按钮并绑定点击动作。
     *
     * @param text 按钮文字
     * @param action 点击后执行的动作
     * @return 新建的按钮
     */
    public static SkinButton primary(String text, java.awt.event.ActionListener action) {
        SkinButton button = new SkinButton(text, Kind.PRIMARY);
        button.addActionListener(action);
        return button;
    }

    /**
     * 创建普通按钮并绑定点击动作。
     *
     * @param text 按钮文字
     * @param action 点击后执行的动作
     * @return 新建的按钮
     */
    public static SkinButton normal(String text, java.awt.event.ActionListener action) {
        SkinButton button = new SkinButton(text, Kind.NORMAL);
        button.addActionListener(action);
        return button;
    }

    /**
     * 创建普通外观的菜单按钮。
     *
     * @param text 按钮文字
     * @param icon 按钮图标，可以为 null
     * @param action 点击后执行的动作
     * @return 新建的按钮
     */
    public static SkinButton menu(String text, javax.swing.Icon icon,
                                 java.awt.event.ActionListener action) {
        return menu(text, icon, Kind.NORMAL, action);
    }

    /**
     * 创建左对齐、带图标的菜单按钮。
     *
     * @param text 按钮文字
     * @param icon 按钮图标，可以为 null
     * @param kind 按钮用途
     * @param action 点击后执行的动作
     * @return 新建的按钮
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
     * 设置按钮图标并调整图标与文字的间距。
     *
     * @param icon 按钮图标
     * @return 当前按钮，便于链式调用
     */
    public SkinButton withIcon(javax.swing.Icon icon) {
        setIcon(icon);
        setIconTextGap(8);
        return this;
    }

    /** 返回当前用途对应的文字颜色。 */
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

    /** 返回当前用途与按压、悬停状态对应的背景色，幽灵按钮未悬停时为 null。 */
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

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth();
        int height = getHeight();
        int arc = 8;
        // 先自绘圆角背景，再交给基类绘制文字与图标
        Color fill = isEnabled() ? fillFor() : new Color(0xEDF1F5);
        if (fill != null) {
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc);
        }
        if (kind != Kind.PRIMARY && kind != Kind.GHOST) {
            // 普通与危险按钮额外绘制边框
            g2.setColor(kind == Kind.DANGER ? new Color(0xF3C9C9) : Theme.BORDER);
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);
        }
        g2.dispose();
        // 禁用状态使用弱化文字颜色
        if (!isEnabled()) {
            setForeground(Theme.TEXT_WEAK);
        } else {
            setForeground(foregroundFor());
        }
        super.paintComponent(g);
    }
}

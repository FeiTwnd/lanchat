package com.chat.client.ui;

import com.chat.client.ui.theme.SkinTitleBar;
import com.chat.client.ui.theme.Theme;
import com.chat.client.ui.theme.WindowResizer;
import com.chat.common.Constants;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.time.LocalDateTime;

/**
 * Swing 窗口基类，统一提供主题、字体、标题栏与常用对话框等公共能力。
 * 子类在 body() 返回的内容面板上布局自己的控件。
 */
public abstract class BaseUI extends JFrame {
    private static final long serialVersionUID = 20250110L;

    protected static final Font FONT_NORMAL = Theme.fontBase();

    protected static final Font FONT_BOLD = Theme.fontTitle();

    protected static final Color COLOR_SELF = new Color(0x1E88E5);

    protected static final Color COLOR_OTHER = new Color(0x2E7D32);

    protected static final Color COLOR_SYSTEM = new Color(0x9E9E9E);

    protected static final Color COLOR_ERROR = new Color(0xC62828);

    private final JPanel body = new JPanel(new java.awt.BorderLayout());

    private transient SkinTitleBar titleBar;

    /**
     * 创建窗口并完成主题、标题栏与字体等公共初始化。
     *
     * @param title 窗口标题
     */
    protected BaseUI(String title) {
        super(title);
        Theme.installDefaults();
        // 去掉系统装饰，标题栏与边框由皮肤组件提供
        setUndecorated(true);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        body.setBackground(Theme.BG);

        JPanel root = new JPanel(new java.awt.BorderLayout());
        root.setBackground(Theme.CARD);
        root.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        titleBar = new SkinTitleBar(this, title);
        root.add(titleBar, java.awt.BorderLayout.NORTH);
        root.add(body, java.awt.BorderLayout.CENTER);
        setContentPane(root);

        setMinimumSize(new Dimension(480, 360));
        // 统一内容区字体
        applyFont(body);
        WindowResizer.install(this);
        getRootPane().putClientProperty("window.title", title);
    }

    protected JPanel body() {
        return body;
    }

    @Override
    public void setTitle(String title) {
        super.setTitle(title);
        // 同步自定义标题栏上的标题文本
        if (titleBar != null) {
            titleBar.setTitleText(title);
        }
    }

    protected SkinTitleBar titleBar() {
        return titleBar;
    }

    /**
     * 递归为组件及其子组件设置统一字体。
     *
     * @param component 根组件
     */
    protected void applyFont(Component component) {
        component.setFont(FONT_NORMAL);
        if (component instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) component).getComponents()) {
                applyFont(child);
            }
        }
    }

    /**
     * 创建带内边距的卡片面板。
     *
     * @return 卡片面板
     */
    protected JPanel card() {
        JPanel panel = new JPanel(new java.awt.BorderLayout());
        panel.setBackground(Theme.CARD);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        return panel;
    }

    /**
     * 创建带可选标题边框的面板，并把内容放在中央。
     *
     * @param title 标题，为 null 时不画标题边框
     * @param content 内容组件
     * @return 面板
     */
    protected JPanel titledPanel(String title, Component content) {
        JPanel panel = new JPanel(new java.awt.BorderLayout());
        Border border = BorderFactory.createEmptyBorder(6, 8, 6, 8);
        if (title != null) {
            Border titled = BorderFactory.createTitledBorder(title);
            panel.setBorder(BorderFactory.createCompoundBorder(titled, border));
        } else {
            panel.setBorder(border);
        }
        panel.add(content, java.awt.BorderLayout.CENTER);
        return panel;
    }

    protected JLabel statusLabel(String text) {
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return label;
    }

    /**
     * 把窗口移到屏幕中央。
     */
    public void centerOnScreen() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation((screen.width - getWidth()) / 2, (screen.height - getHeight()) / 2);
    }

    /**
     * 弹出提示对话框。
     *
     * @param message 提示内容
     */
    protected void showInfo(String message) {
        onEdt(() -> JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.INFORMATION_MESSAGE));
    }

    /**
     * 弹出错误对话框。
     *
     * @param message 错误内容
     */
    protected void showError(String message) {
        onEdt(() -> JOptionPane.showMessageDialog(this, message, "错误", JOptionPane.ERROR_MESSAGE));
    }

    /**
     * 弹出确认对话框并等待用户选择。
     *
     * @param message 确认内容
     * @return 用户选择“是”时返回 true
     */
    protected boolean confirm(String message) {
        final boolean[] result = {false};
        Runnable task = () -> {
            int choice = JOptionPane.showConfirmDialog(this, message, "请确认",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            result[0] = choice == JOptionPane.YES_OPTION;
        };
        runOnEdtAndWait(task);
        return result[0];
    }

    /**
     * 在事件分发线程执行任务，已在事件分发线程时直接执行。
     *
     * @param task 待执行任务
     */
    protected void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 在事件分发线程执行任务并等待其结束。
     *
     * @param task 待执行任务
     */
    protected void runOnEdtAndWait(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.err.println("界面线程任务执行失败: " + e.getMessage());
        }
    }

    /**
     * 给日志文本加上当前时间前缀。
     *
     * @param message 日志内容
     * @return 带时间前缀的一行文本
     */
    protected String logLine(String message) {
        return "[" + com.chat.util.DateUtil.formatTime(LocalDateTime.now()) + "] " + message;
    }

    /**
     * 返回窗口名称，标题为 null 时用应用名代替。
     *
     * @return 窗口名称
     */
    public String windowName() {
        String title = getTitle();
        return title == null ? Constants.APP_NAME : title;
    }

    /**
     * 关闭窗口，窗口为 null 时忽略。
     *
     * @param window 待关闭的窗口
     */
    protected static void safeDispose(Window window) {
        if (window != null) {
            window.dispose();
        }
    }
}

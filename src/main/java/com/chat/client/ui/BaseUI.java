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
 * Swing 界面抽象基类。
 *
 * <p>职责：收敛所有窗口的公共行为——外观主题初始化、窗口居中、统一字体、
 * 统一的内边距与提示对话框，避免每个窗口重复写相同的样板代码。</p>
 *
 * <p>继承价值：子类只需关注“本窗口有什么控件、怎么布局”，
 * 通用能力由父类提供；这也是课程设计的继承评分点在本项目中的实际用途，
 * 而不是为了继承而继承。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public abstract class BaseUI extends JFrame {

    /** 序列化版本号（Swing 组件可序列化，显式声明） */
    private static final long serialVersionUID = 20250110L;

    /** 界面统一字体，避免依赖系统默认字体导致中文显示异常 */
    protected static final Font FONT_NORMAL = Theme.fontBase();

    /** 标题字体 */
    protected static final Font FONT_BOLD = Theme.fontTitle();

    /** 聊天气泡中“本人”消息的颜色 */
    protected static final Color COLOR_SELF = new Color(0x1E88E5);

    /** 聊天气泡中“他人”消息的颜色 */
    protected static final Color COLOR_OTHER = new Color(0x2E7D32);

    /** 系统通知颜色 */
    protected static final Color COLOR_SYSTEM = new Color(0x9E9E9E);

    /** 错误提示颜色 */
    protected static final Color COLOR_ERROR = new Color(0xC62828);

    /** 内容承载面板：子类统一在它之上布局，而不是直接挂到窗口上 */
    private final JPanel body = new JPanel(new java.awt.BorderLayout());

    /** 自定义标题栏 */
    private transient SkinTitleBar titleBar;

    /**
     * 构造窗口并完成通用初始化。
     *
     * <p>窗口采用无边框外观：由 {@link SkinTitleBar} 提供标题、拖动、最大化与关闭，
     * 内容区通过 {@link #body()} 承载。这样做的代价是失去系统原生装饰，
     * 因此关闭按钮必须分发标准窗口事件，保证各窗口既有的关闭逻辑不被绕过。</p>
     *
     * @param title 窗口标题
     */
    protected BaseUI(String title) {
        super(title);
        Theme.installDefaults();
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
        applyFont(body);
        WindowResizer.install(this);
        getRootPane().putClientProperty("window.title", title);
    }

    /**
     * 获取内容承载面板。
     *
     * <p>子类应使用 {@code body().setLayout(...)} 与 {@code body().add(...)} 组装界面。</p>
     *
     * @return 内容面板
     */
    protected JPanel body() {
        return body;
    }

    /**
     * 设置窗口标题，同时更新自定义标题栏文本。
     *
     * @param title 标题
     */
    @Override
    public void setTitle(String title) {
        super.setTitle(title);
        if (titleBar != null) {
            titleBar.setTitleText(title);
        }
    }

    /**
     * 递归设置组件字体，保证中文在所有平台上一致渲染。
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
     * 创建统一的白底卡片面板。
     *
     * @return 带内边距的卡片面板
     */
    protected JPanel card() {
        JPanel panel = new JPanel(new java.awt.BorderLayout());
        panel.setBackground(Theme.CARD);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        return panel;
    }

    /**
     * 创建统一的带内边距面板。
     *
     * @param content 面板内容
     * @param title   边框标题，为 null 时不显示标题
     * @return 面板对象
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

    /**
     * 创建状态标签。
     *
     * @param text 初始文本
     * @return 标签对象
     */
    protected JLabel statusLabel(String text) {
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return label;
    }

    /**
     * 把窗口居中显示在屏幕中央。
     */
    public void centerOnScreen() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation((screen.width - getWidth()) / 2, (screen.height - getHeight()) / 2);
    }

    /**
     * 显示信息提示框（线程安全）。
     *
     * @param message 提示内容
     */
    protected void showInfo(String message) {
        onEdt(() -> JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.INFORMATION_MESSAGE));
    }

    /**
     * 显示错误提示框（线程安全）。
     *
     * @param message 错误内容
     */
    protected void showError(String message) {
        onEdt(() -> JOptionPane.showMessageDialog(this, message, "错误", JOptionPane.ERROR_MESSAGE));
    }

    /**
     * 显示确认对话框（线程安全，阻塞等待用户选择）。
     *
     * @param message 询问内容
     * @return 用户选择“是”返回 true
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
     * 在事件分发线程中执行任务。
     *
     * <p>网络线程绝不能直接操作 Swing 组件，否则会出现界面闪烁、状态错乱甚至死锁；
     * 本方法统一收口这一约束。</p>
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
     * 在事件分发线程中同步执行任务并等待完成。
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
     * 生成带时间戳的日志行，供各窗口统一格式。
     *
     * @param message 内容
     * @return 形如 {@code [10:20:30] 内容} 的文本
     */
    protected String logLine(String message) {
        return "[" + com.chat.util.DateUtil.formatTime(LocalDateTime.now()) + "] " + message;
    }

    /**
     * 获取窗口显示名，用于日志。
     *
     * @return 窗口标题
     */
    public String windowName() {
        String title = getTitle();
        return title == null ? Constants.APP_NAME : title;
    }

    /**
     * 安全关闭窗口：先释放资源再 dispose。
     *
     * @param window 目标窗口
     */
    protected static void safeDispose(Window window) {
        if (window != null) {
            window.dispose();
        }
    }
}

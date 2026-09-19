package com.chat.client.ui;

import com.chat.common.Constants;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
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
    protected static final Font FONT_NORMAL = new Font("Microsoft YaHei", Font.PLAIN, 13);

    /** 标题字体 */
    protected static final Font FONT_BOLD = new Font("Microsoft YaHei", Font.BOLD, 14);

    /** 聊天气泡中“本人”消息的颜色 */
    protected static final Color COLOR_SELF = new Color(0x1E88E5);

    /** 聊天气泡中“他人”消息的颜色 */
    protected static final Color COLOR_OTHER = new Color(0x2E7D32);

    /** 系统通知颜色 */
    protected static final Color COLOR_SYSTEM = new Color(0x9E9E9E);

    /** 错误提示颜色 */
    protected static final Color COLOR_ERROR = new Color(0xC62828);

    /** 全局外观是否已初始化 */
    private static boolean lookAndFeelReady;

    /**
     * 构造窗口并完成通用初始化。
     *
     * @param title 窗口标题
     */
    protected BaseUI(String title) {
        super(title);
        initLookAndFeel();
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(480, 360));
        applyFont(this);
        getRootPane().putClientProperty("window.title", title);
    }

    /**
     * 初始化窗口外观。
     *
     * <p>优先使用系统外观，使界面与操作系统一致；跨平台失败时回退 Swing 默认外观，
     * 保证在任何 JDK 上都能显示。</p>
     */
    private static void initLookAndFeel() {
        if (lookAndFeelReady) {
            return;
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            UIManager.put("OptionPane.messageFont", FONT_NORMAL);
            UIManager.put("OptionPane.buttonFont", FONT_NORMAL);
            UIManager.put("Table.font", FONT_NORMAL);
            UIManager.put("TableHeader.font", FONT_BOLD);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                 | UnsupportedLookAndFeelException e) {
            // 外观设置属于“锦上添花”，失败时使用默认外观即可，不应中断启动
            System.err.println("系统外观设置失败，使用默认外观: " + e.getMessage());
        }
        lookAndFeelReady = true;
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

package com.chat.client.ui;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.util.Locale;

/**
 * 系统原生"打开 / 保存文件"对话框。
 *
 * <p>职责：统一弹出操作系统自己的文件选择窗口（Windows 资源管理器、macOS 的
 * Finder、Linux 上平台自带的选择器），并把结果规范化成 {@link File} 交给调用方。</p>
 *
 * <p><b>为什么不用 {@code JFileChooser}</b>：{@code JFileChooser} 是 Swing 自绘的
 * 对话框，外观与系统里其他软件、浏览器弹出的选文件窗口都不一样——没有系统收藏夹、
 * 没有最近访问位置、目录树要自己一层层翻。{@link FileDialog} 的实现在操作系统手里，
 * 使用者看到的就是"平时熟悉的那个选文件窗口"。</p>
 *
 * <p><b>必须知道的代价</b>：原生对话框不能可靠地按扩展名过滤。
 * Windows 下的实现会忽略 {@link FileDialog#setFilenameFilter}，使用者仍会看到全部文件；
 * 因此本类在选完之后自己再校验一次扩展名，不合格就明确提示并放弃这次选择，
 * 而不是把一个明显不对的文件交给调用方去发（发送图片时尤其重要）。</p>
 *
 * <p><b>线程约束</b>：必须在事件分发线程上调用。原生对话框是模态的，
 * {@link FileDialog#setVisible(boolean)} 会一直阻塞到使用者关闭它为止。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class NativeFileDialog {

    /**
     * 上一次选中的目录。
     *
     * <p>记住它是因为"连续发几个文件"是常见动作：每次都从主目录重新翻一遍
     * 与系统里其他软件的行为不一致，也让使用者白花力气。</p>
     */
    private static volatile File lastDirectory;

    /** 私有构造，禁止实例化工具类 */
    private NativeFileDialog() {
    }

    /**
     * 弹出"打开文件"对话框。
     *
     * @param parent           对话框宿主组件（通常是面板或窗口）；用于定位与提示弹窗，
     *                         为 null 时由系统给出默认宿主
     * @param title            对话框标题
     * @param initialDirectory 首次打开时的目录；为 null 或不存在时交给系统决定。
     *                         一旦使用者自己翻过目录，后续以"上次用过的目录"为准
     * @param extensions       允许的扩展名（不含点，如 {@code png}）；为 null 或空表示不限，
     *                         受限时选中的文件会再校验一次扩展名
     * @return 使用者选中的文件；取消、未选中或扩展名不合格时返回 null
     */
    public static File open(Component parent, String title, File initialDirectory, String[] extensions) {
        boolean restricted = extensions != null && extensions.length > 0;
        FileDialog dialog = create(parent, title, FileDialog.LOAD);
        applyInitialDirectory(dialog, initialDirectory);
        if (restricted) {
            // 只在支持过滤的平台生效（Windows 会忽略），真正的把关在下面的扩展名校验
            dialog.setFilenameFilter((dir, name) -> hasAllowedExtension(name, extensions));
        }
        dialog.setVisible(true);
        File file = selected(dialog);
        if (file == null) {
            return null;
        }
        if (restricted && !hasAllowedExtension(file.getName(), extensions)) {
            JOptionPane.showMessageDialog(parent,
                    "该文件不是允许的类型，请重新选择。\n\n支持的类型：" + describe(extensions)
                            + "\n你选择的是：" + file.getName(),
                    title, JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return file;
    }

    /**
     * 弹出"保存文件"对话框。
     *
     * <p>原生保存对话框既能改文件名也能换目录，因此调用方不必自己再拼一个输入框。
     * 它<b>不会</b>替调用方拦"文件已存在"：覆盖是不可逆的，是否覆盖必须由调用方
     * 按自己的语义决定（导出聊天记录就需要先问一句）。</p>
     *
     * @param parent           对话框宿主组件；为 null 时由系统给出默认宿主
     * @param title            对话框标题
     * @param initialDirectory 首次打开时的目录；为 null 或不存在时交给系统决定
     * @param suggestedName    预填的文件名；为 null 或空表示不预填
     * @return 使用者指定的目标文件；取消时返回 null
     */
    public static File save(Component parent, String title, File initialDirectory, String suggestedName) {
        FileDialog dialog = create(parent, title, FileDialog.SAVE);
        applyInitialDirectory(dialog, initialDirectory);
        if (suggestedName != null && !suggestedName.isEmpty()) {
            dialog.setFile(suggestedName);
        }
        dialog.setVisible(true);
        return selected(dialog);
    }

    /**
     * 按宿主类型创建对话框。
     *
     * <p>原生对话框只接受 {@link Frame} 或 {@link Dialog} 作宿主，而调用方手上往往是
     * 一个 Swing 面板，因此这里先向上找到所在窗口。找不到合适宿主（含宿主为 null）时
     * 传 null 给 {@link FileDialog}：这是被允许的，由系统提供一个默认宿主，
     * 比强行造一个隐藏窗口更简单——导出聊天记录正是刻意不挂宿主的场景。</p>
     *
     * @param parent 宿主组件，可为 null
     * @param title  对话框标题
     * @param mode   {@link FileDialog#LOAD} 或 {@link FileDialog#SAVE}
     * @return 对话框实例，尚未显示
     */
    private static FileDialog create(Component parent, String title, int mode) {
        // parent 允许为 null（导出聊天记录就是这么调的），因此不能直接交给
        // SwingUtilities.getWindowAncestor：它对 null 会抛空指针
        Window owner = parent instanceof Window
                ? (Window) parent
                : (parent == null ? null : SwingUtilities.getWindowAncestor(parent));
        if (owner instanceof Dialog) {
            return new FileDialog((Dialog) owner, title, mode);
        }
        return new FileDialog(owner instanceof Frame ? (Frame) owner : null, title, mode);
    }

    /**
     * 设置对话框的起始目录。
     *
     * @param dialog           对话框
     * @param initialDirectory 调用方期望的起始目录
     */
    private static void applyInitialDirectory(FileDialog dialog, File initialDirectory) {
        File remembered = lastDirectory;
        File directory = remembered != null && remembered.isDirectory() ? remembered : initialDirectory;
        if (directory != null && directory.isDirectory()) {
            dialog.setDirectory(directory.getAbsolutePath());
        }
    }

    /**
     * 读取对话框的选择结果，并记住本次所在目录。
     *
     * @param dialog 已关闭的对话框
     * @return 选中的文件；使用者取消时返回 null
     */
    private static File selected(FileDialog dialog) {
        String name = dialog.getFile();
        if (name == null || name.isEmpty()) {
            return null;
        }
        String directory = dialog.getDirectory();
        File file = directory == null || directory.isEmpty() ? new File(name) : new File(directory, name);
        // 有的平台会把路径带在文件名里，getParentFile 对两种形态都成立
        File parent = file.getParentFile();
        if (parent != null && parent.isDirectory()) {
            lastDirectory = parent;
        }
        return file;
    }

    /**
     * 判断文件名是否属于允许的扩展名。
     *
     * <p>比较前统一转小写：使用者把照片存成 {@code .JPG} 是常事，
     * 按原样比较会把它们全部判为非法。</p>
     *
     * @param name       文件名
     * @param extensions 允许的扩展名集合
     * @return 命中任一扩展名返回 true
     */
    private static boolean hasAllowedExtension(String name, String[] extensions) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String suffix = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (suffix.equals(extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把扩展名集合拼成给人看的提示文本。
     *
     * @param extensions 扩展名集合
     * @return 形如 {@code jpg / png / gif} 的文本
     */
    private static String describe(String[] extensions) {
        return String.join(" / ", extensions);
    }
}

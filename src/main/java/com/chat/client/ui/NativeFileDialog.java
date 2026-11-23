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
 * 本地文件选择对话框，提供打开与保存的静态入口，并记住上次使用的目录。
 */
public final class NativeFileDialog {
    /** 最近一次选择的目录，下次打开对话框时优先使用。 */
    private static volatile File lastDirectory;

    private NativeFileDialog() {
    }

    /**
     * 打开文件选择对话框，可限定允许的扩展名。
     *
     * @param parent 父组件，用于确定对话框属主
     * @param title 对话框标题
     * @param initialDirectory 初始目录
     * @param extensions 允许的扩展名，为空表示不过滤
     * @return 选中的文件，取消或类型不符时返回 null
     */
    public static File open(Component parent, String title, File initialDirectory, String[] extensions) {
        boolean restricted = extensions != null && extensions.length > 0;
        FileDialog dialog = create(parent, title, FileDialog.LOAD);
        applyInitialDirectory(dialog, initialDirectory);
        if (restricted) {
            // 限定类型时只显示符合扩展名的文件
            dialog.setFilenameFilter((dir, name) -> hasAllowedExtension(name, extensions));
        }
        dialog.setVisible(true);
        File file = selected(dialog);
        if (file == null) {
            return null;
        }
        if (restricted && !hasAllowedExtension(file.getName(), extensions)) {
            // 文件名由用户手输时再校验一次扩展名
            JOptionPane.showMessageDialog(parent,
                    "该文件不是允许的类型，请重新选择。\n\n支持的类型：" + describe(extensions)
                            + "\n你选择的是：" + file.getName(),
                    title, JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return file;
    }

    /**
     * 打开保存对话框并预填建议的文件名。
     *
     * @param parent 父组件，用于确定对话框属主
     * @param title 对话框标题
     * @param initialDirectory 初始目录
     * @param suggestedName 预填的文件名，可为 null
     * @return 选定的文件，取消时返回 null
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
     * 创建以父组件所属窗口为属主的本地对话框。
     */
    private static FileDialog create(Component parent, String title, int mode) {
        Window owner = parent instanceof Window
                ? (Window) parent
                : (parent == null ? null : SwingUtilities.getWindowAncestor(parent));
        if (owner instanceof Dialog) {
            return new FileDialog((Dialog) owner, title, mode);
        }
        return new FileDialog(owner instanceof Frame ? (Frame) owner : null, title, mode);
    }

    private static void applyInitialDirectory(FileDialog dialog, File initialDirectory) {
        File remembered = lastDirectory;
        File directory = remembered != null && remembered.isDirectory() ? remembered : initialDirectory;
        if (directory != null && directory.isDirectory()) {
            dialog.setDirectory(directory.getAbsolutePath());
        }
    }

    /**
     * 返回对话框选中的文件，并记录其所在目录。
     */
    private static File selected(FileDialog dialog) {
        String name = dialog.getFile();
        if (name == null || name.isEmpty()) {
            return null;
        }
        String directory = dialog.getDirectory();
        File file = directory == null || directory.isEmpty() ? new File(name) : new File(directory, name);

        File parent = file.getParentFile();
        if (parent != null && parent.isDirectory()) {
            // 记录本次所在目录
            lastDirectory = parent;
        }
        return file;
    }

    private static boolean hasAllowedExtension(String name, String[] extensions) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String suffix = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 逐个比对扩展名，忽略大小写
        for (String extension : extensions) {
            if (suffix.equals(extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String describe(String[] extensions) {
        return String.join(" / ", extensions);
    }
}

package com.chat.client.ui.theme;

import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 在无边框窗口的右侧、底部与右下角放置透明拖拽区，实现窗口缩放。
 */
public final class WindowResizer {
    /** 右侧与底部拖拽条的厚度，单位像素。 */
    private static final int EDGE = 5;

    /** 右下角拖拽区的边长，单位像素。 */
    private static final int CORNER = 16;

    private WindowResizer() {
    }

    /**
     * 为指定窗口安装缩放手柄。
     *
     * @param frame 目标窗口
     */
    public static void install(JFrame frame) {
        JLayeredPane pane = frame.getRootPane().getLayeredPane();
        // 分别覆盖右边、底边与右下角
        ResizeHandle right = new ResizeHandle(frame, true, false);
        ResizeHandle bottom = new ResizeHandle(frame, false, true);
        ResizeHandle corner = new ResizeHandle(frame, true, true);
        pane.add(right, JLayeredPane.PALETTE_LAYER);
        pane.add(bottom, JLayeredPane.PALETTE_LAYER);
        pane.add(corner, JLayeredPane.PALETTE_LAYER);
        Runnable place = () -> {
            int width = pane.getWidth();
            int height = pane.getHeight();
            right.setBounds(width - EDGE, 0, EDGE, Math.max(0, height - CORNER));
            bottom.setBounds(0, height - EDGE, Math.max(0, width - CORNER), EDGE);
            corner.setBounds(width - CORNER, height - CORNER, CORNER, CORNER);
        };
        place.run();
        pane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // 窗口尺寸变化后重新贴合拖拽区
                place.run();
            }
        });
    }

    /**
     * 透明拖拽面板，按鼠标拖动距离调整窗口大小。
     */
    private static final class ResizeHandle extends JPanel {
        private static final long serialVersionUID = 20260921L;

        private final transient JFrame frame;

        private final boolean horizontal;

        private final boolean vertical;

        private transient Point pressPoint;

        private transient Dimension startSize;

        ResizeHandle(JFrame frame, boolean horizontal, boolean vertical) {
            this.frame = frame;
            this.horizontal = horizontal;
            this.vertical = vertical;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(horizontal && vertical
                    ? Cursor.SE_RESIZE_CURSOR
                    : horizontal ? Cursor.E_RESIZE_CURSOR : Cursor.S_RESIZE_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    pressPoint = e.getLocationOnScreen();
                    startSize = frame.getSize();
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (pressPoint == null || startSize == null) {
                        return;
                    }
                    Point now = e.getLocationOnScreen();
                    Dimension min = frame.getMinimumSize();
                    int width = horizontal
                            ? Math.max(min.width, startSize.width + now.x - pressPoint.x)
                            : startSize.width;
                    int height = vertical
                            ? Math.max(min.height, startSize.height + now.y - pressPoint.y)
                            : startSize.height;
                    frame.setSize(width, height);
                }
            });
        }
    }
}

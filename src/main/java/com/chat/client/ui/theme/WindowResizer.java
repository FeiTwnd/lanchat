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
 * 无边框窗口的缩放支持。
 *
 * <p>职责：窗口去掉系统装饰后无法再用边框拖拽缩放，本类在根面板的分层窗格上叠加
 * 三条透明拖拽带（右边、底边、右下角），把鼠标拖动换算成窗口尺寸变化。</p>
 *
 * <p>之所以放在分层窗格而不是内容面板上：内容面板会被各窗口的业务组件完全覆盖，
 * 放在其上层的拖拽带才能可靠地接收到鼠标事件。</p>
 *
 * @author Java 课程设计
 * @version 1.0
 */
public final class WindowResizer {

    /** 边缘拖拽带宽度 */
    private static final int EDGE = 5;

    /** 右下角拖拽区尺寸 */
    private static final int CORNER = 16;

    /** 私有构造，禁止实例化工具类 */
    private WindowResizer() {
    }

    /**
     * 为窗口安装缩放支持。
     *
     * @param frame 目标窗口
     */
    public static void install(JFrame frame) {
        JLayeredPane pane = frame.getRootPane().getLayeredPane();
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
                place.run();
            }
        });
    }

    /**
     * 单条拖拽带：按方向把鼠标位移换算为窗口尺寸变化。
     */
    private static final class ResizeHandle extends JPanel {

        /** 序列化版本号 */
        private static final long serialVersionUID = 20260921L;

        /** 所属窗口 */
        private final transient JFrame frame;

        /** 是否响应横向缩放 */
        private final boolean horizontal;

        /** 是否响应纵向缩放 */
        private final boolean vertical;

        /** 按下时的屏幕坐标 */
        private transient Point pressPoint;

        /** 按下时的窗口尺寸 */
        private transient Dimension startSize;

        /**
         * 构造拖拽带。
         *
         * @param frame      所属窗口
         * @param horizontal 是否响应横向缩放
         * @param vertical   是否响应纵向缩放
         */
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

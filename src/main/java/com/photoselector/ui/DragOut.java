package com.photoselector.ui;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

/**
 * 「把照片拖到目标文件夹面板上」这件事的统一实现。
 *
 * <p>为什么不用 {@code java.awt.dnd} 那套完整的拖放框架：我们要的只是
 * "从这个组件拖出去、松手时看落在谁身上"，用不着跨进程拖放，也用不着自定义数据格式。
 * 用 Swing 自带的组件命中判断就够，少一层机器，代码也好读得多。
 *
 * <p>拖动过程中鼠标事件仍然发给<b>按下的那个组件</b>（Swing 的鼠标捕获语义），
 * 所以松手时的坐标可能落在别的组件上——那正是我们要判断的东西。
 */
final class DragOut {

    /** 动超过这么多像素才算拖动，否则当单击处理——不然轻轻一点也会被当成拖。 */
    private static final int THRESHOLD = 5;

    private DragOut() {
    }

    /**
     * 让某个组件可以往外拖照片。
     *
     * @param source    从哪个组件拖
     * @param photo     拖的是哪一张（松手时才问，避免拖动过程中照片已经换了）
     * @param onDropped 落点在目标文件夹面板上时回调
     */
    static void install(JComponent source, Supplier<Path> photo, Consumer<Path> onDropped) {
        install(source, photo, () -> true, onDropped);
    }

    /**
     * 同上，但可以加一道开关。
     *
     * <p>大图那边就靠它：图片能平移时，拖动是在平移而不是在往外拖——
     * 不加这道判断，平移完松手还会顺手把照片丢进目标文件夹。
     */
    static void install(JComponent source, Supplier<Path> photo, BooleanSupplier enabled,
                        Consumer<Path> onDropped) {
        source.addMouseListener(new MouseAdapter() {

            private Point pressedAt;

            @Override
            public void mousePressed(MouseEvent e) {
                pressedAt = SwingUtilities.isLeftMouseButton(e) ? e.getPoint() : null;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Point start = pressedAt;
                pressedAt = null;
                source.setCursor(Cursor.getDefaultCursor());
                if (start == null || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (e.getPoint().distance(start) <= THRESHOLD || !enabled.getAsBoolean()) {
                    return; // 没怎么动，那是单击，不是拖
                }
                Path dragged = photo.get();
                if (dragged == null) {
                    return;
                }
                TargetPanel target = targetPanelAt(source, e.getPoint());
                if (target != null) {
                    onDropped.accept(dragged);
                }
            }
        });
    }

    /** 松手那一点底下是不是目标文件夹面板。 */
    private static TargetPanel targetPanelAt(JComponent source, Point pointInSource) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root == null) {
            return null; // 还没挂到窗口上（比如离屏渲染），自然没有落点
        }
        Point inRoot = SwingUtilities.convertPoint(source, pointInSource, root);
        return panelOf(SwingUtilities.getDeepestComponentAt(root, inRoot.x, inRoot.y));
    }

    /**
     * 从某个组件往上找，看它是不是（或包在）目标文件夹面板里。
     *
     * <p>要往上找是因为命中的是面板内部的格子之类的深一层组件。
     */
    static TargetPanel panelOf(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (current instanceof TargetPanel panel) {
                return panel;
            }
        }
        return null;
    }
}

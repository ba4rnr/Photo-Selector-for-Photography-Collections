package com.photoselector.ui;

import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

import com.photoselector.ui.theme.Theme;

/**
 * 「把照片从素材拖到目标文件夹面板上」这件事的统一实现。
 *
 * <p>为什么不用 {@code java.awt.dnd} 那套完整的拖放框架：我们要的只是
 * "从这个组件拖出去、松手时看落在谁身上"，用不着跨进程拖放，也用不着自定义数据格式。
 * 用 Swing 自带的组件命中判断就够，少一层机器，代码也好读得多。
 *
 * <p>拖动过程中要让用户看得见两件事：
 * <ul>
 *   <li><b>正在拖</b>：一张缩略图跟着鼠标走。没有它，用户根本不知道自己拖起来没有。</li>
 *   <li><b>会落在哪</b>：目标面板上画一条白线，标出将插进哪个缝。</li>
 * </ul>
 */
final class DragOut {

    /** 动超过这么多像素才算拖动，否则当单击处理。 */
    private static final int THRESHOLD = 5;

    /** 跟着鼠标走的那张缩略图有多宽。 */
    private static final int GHOST_WIDTH = 140;

    private DragOut() {
    }

    /**
     * 让某个组件可以往外拖照片。
     *
     * @param source    从哪个组件拖
     * @param photo     拖的是哪一张（松手时才问，避免拖动过程中照片已经换了）
     * @param ghost     跟着鼠标走的那张图（拿不到就给 null，退化成一个小方块）
     * @param enabled   现在允不允许往外拖（大图那边由"选择 / 平移"工具决定）
     * @param onDropped 落在目标面板上时回调，第二个参数是"插在第几项之前"
     */
    static void install(JComponent source, Supplier<Path> photo, Supplier<BufferedImage> ghost,
                        BooleanSupplier enabled, BiConsumer<Path, Integer> onDropped) {
        source.addMouseListener(new MouseAdapter() {

            private Point pressedAt;
            private TargetPanel hinted;

            @Override
            public void mousePressed(MouseEvent e) {
                pressedAt = SwingUtilities.isLeftMouseButton(e) ? e.getPoint() : null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressedAt == null || e.getPoint().distance(pressedAt) <= THRESHOLD) {
                    return;
                }
                TargetPanel target = targetPanelAt(source, e.getPoint());
                showHint(target, e.getPoint());
                showGhost(source, e.getPoint(), ghost.get());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Point start = pressedAt;
                pressedAt = null;
                hideGhost(source);
                TargetPanel target = targetPanelAt(source, e.getPoint());
                int index = target == null ? -1 : target.dropIndexAt(toTargetPoint(target, e.getPoint()));
                clearHint();
                if (start == null || !SwingUtilities.isLeftMouseButton(e)
                        || e.getPoint().distance(start) <= THRESHOLD || !enabled.getAsBoolean()) {
                    return;
                }
                Path dragged = photo.get();
                if (dragged != null && index >= 0) {
                    onDropped.accept(dragged, index);
                }
            }

            /** 白线只画在当前悬停的那块面板上，换目标就把上一块擦掉。 */
            private void showHint(TargetPanel target, Point pointInSource) {
                if (hinted != null && hinted != target) {
                    hinted.clearDropHint();
                }
                hinted = target;
                if (target != null) {
                    target.setDropHint(target.dropIndexAt(toTargetPoint(target, pointInSource)));
                }
            }

            private void clearHint() {
                if (hinted != null) {
                    hinted.clearDropHint();
                    hinted = null;
                }
            }

            private Point toTargetPoint(TargetPanel target, Point pointInSource) {
                return SwingUtilities.convertPoint(source, pointInSource, target);
            }
        });
    }

    // ─────────────────────────── 跟着鼠标的那张图 ───────────────────────────

    private static void showGhost(JComponent source, Point pointInSource, BufferedImage image) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root == null) {
            return; // 还没挂到窗口上（离屏渲染），没有玻璃板可用
        }
        GhostLayer layer = layerOf(root);
        layer.show(image, SwingUtilities.convertPoint(source, pointInSource, layer));
    }

    private static void hideGhost(JComponent source) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root != null && root.getGlassPane() instanceof GhostLayer layer) {
            layer.hideGhost();
        }
    }

    /** 一块窗口只装一块玻璃板，两次拖动共用。 */
    private static GhostLayer layerOf(JRootPane root) {
        if (root.getGlassPane() instanceof GhostLayer layer) {
            return layer;
        }
        GhostLayer layer = new GhostLayer();
        root.setGlassPane(layer);
        return layer;
    }

    /**
     * 画拖动提示的那块玻璃板。
     *
     * <p>玻璃板盖在所有组件之上，所以拖到哪儿都看得见。它只在拖动期间显示，
     * 松手就藏起来——显示着的话，下一次按下会被它吃掉。
     */
    @SuppressWarnings("serial")
    private static final class GhostLayer extends JComponent {

        private BufferedImage image;
        private Point at;

        void show(BufferedImage ghost, Point where) {
            this.image = ghost;
            this.at = where;
            setVisible(true);
            repaint();
        }

        void hideGhost() {
            image = null;
            at = null;
            setVisible(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (at == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));

            if (image == null) {
                // 还没有缩略图可拿：画个小方块意思一下，总比什么都看不见强
                g2.setColor(Theme.HIGHLIGHT);
                g2.fillRoundRect(at.x + 12, at.y + 12, 60, 40, 6, 6);
            } else {
                int width = GHOST_WIDTH;
                int height = Math.max(1,
                        Math.round(image.getHeight() * (float) GHOST_WIDTH / image.getWidth()));
                g2.drawImage(image, at.x + 12, at.y + 12, width, height, null);
            }
            g2.dispose();
        }
    }

    // ─────────────────────────── 落点判断 ───────────────────────────

    /** 松手那一点底下是不是目标文件夹面板。 */
    private static TargetPanel targetPanelAt(JComponent source, Point pointInSource) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root == null) {
            return null;
        }
        Point inRoot = SwingUtilities.convertPoint(source, pointInSource, root);
        return panelOf(SwingUtilities.getDeepestComponentAt(root, inRoot.x, inRoot.y));
    }

    /**
     * 从某个组件往上找，看它是不是（或包在）目标文件夹面板里。
     *
     * <p>要往上找是因为命中的是面板内部组件。
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

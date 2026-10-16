package com.photoselector.ui;

import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.JViewport;
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
     * @param source      从哪个组件拖
     * @param ghost       跟着鼠标走的那张图：**按正在拖的那张照片**现取，
     *                    拿不到就返回 null（退化成一个小方块）。
     *                    参数是照片而不是"当前选中的那张"——从展开排序模式拖时，
     *                    两块面板的"选中"可能不是同一张，按参数取才不会张冠李戴。
     * @param enabled     现在允不允许往外拖（大图那边由"选择 / 平移"工具决定）
     * @param onDropped   落在目标面板上时回调，第二个参数是"插在第几项之前"
     * @param onTrashed   落在主大图区上时回调（选图模式：拖到那儿＝丢垃圾桶）；
     *                    不需要这个落点就传 null
     * @param trashTarget 那个"垃圾桶"大图面板——悬上去时要给它亮边框。
     *                    不认这个落点时传 null
     */
    static void install(JComponent source, Supplier<Path> photo, Function<Path, BufferedImage> ghost,
                        BooleanSupplier enabled, BiConsumer<Path, Integer> onDropped,
                        Consumer<Path> onTrashed, ViewerPanel trashTarget) {
        MouseAdapter handler = new MouseAdapter() {

            private Point pressedAt;
            private TargetPanel hintedPanel;
            private boolean hintedTrash;

            @Override
            public void mousePressed(MouseEvent e) {
                pressedAt = SwingUtilities.isLeftMouseButton(e) ? e.getPoint() : null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressedAt == null || e.getPoint().distance(pressedAt) <= THRESHOLD) {
                    return;
                }
                Path dragging = photo.get();
                if (dragging == null) {
                    return;
                }
                Point at = e.getPoint();
                showHint(resolveDropSink(source, at, onTrashed != null), at);
                showGhost(source, e.getPoint(), ghost.apply(dragging));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Point start = pressedAt;
                pressedAt = null;
                hideGhost(source);
                Point at = e.getPoint();
                DropSink sink = resolveDropSink(source, at, onTrashed != null);
                int index = sink.kind() == DropKind.PANEL
                        ? sink.panel().dropIndexAt(toTargetPoint(sink.panel(), at))
                        : -1;
                clearHint();
                if (start == null || !SwingUtilities.isLeftMouseButton(e)
                        || e.getPoint().distance(start) <= THRESHOLD || !enabled.getAsBoolean()) {
                    return;
                }
                Path dragged = photo.get();
                if (dragged == null) {
                    return;
                }
                if (sink.kind() == DropKind.PANEL && index >= 0) {
                    onDropped.accept(dragged, index);
                } else if (sink.kind() == DropKind.TRASH) {
                    onTrashed.accept(dragged);
                }
            }

            /** 落点提示只画在当前悬停的那个落点上，换目标就把上一处擦掉。 */
            private void showHint(DropSink sink, Point pointInSource) {
                TargetPanel panel = sink.kind() == DropKind.PANEL ? sink.panel() : null;
                if (hintedPanel != null && hintedPanel != panel) {
                    hintedPanel.clearDropHint();
                }
                hintedPanel = panel;
                if (panel != null) {
                    panel.setDropHint(panel.dropIndexAt(toTargetPoint(panel, pointInSource)));
                }

                boolean trash = sink.kind() == DropKind.TRASH;
                if (trash != hintedTrash) {
                    hintedTrash = trash;
                    if (trashTarget != null) {
                        trashTarget.setTrashHint(trash);
                    }
                }
            }

            private void clearHint() {
                if (hintedPanel != null) {
                    hintedPanel.clearDropHint();
                    hintedPanel = null;
                }
                if (hintedTrash) {
                    hintedTrash = false;
                    if (trashTarget != null) {
                        trashTarget.setTrashHint(false);
                    }
                }
            }

            private Point toTargetPoint(TargetPanel target, Point pointInSource) {
                JRootPane root = SwingUtilities.getRootPane(source);
                return fromWindow(root, toWindow(source, pointInSource, root), target);
            }
        };
        source.addMouseListener(handler);
        // 这一行是必须的：鼠标一动一动的那种事件走的是"鼠标移动监听器"，
        // 不注册它，下面的 mouseDragged 一次都不会被调用——
        // 表现就是"拖着照片什么都没发生"：没有跟着鼠标的缩略图，没有白线，松手也放不进去。
        source.addMouseMotionListener(handler);
    }

    // ─────────────────────────── 跟着鼠标的那张图 ───────────────────────────

    private static void showGhost(JComponent source, Point pointInSource, BufferedImage image) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root == null) {
            return; // 还没挂到窗口上（离屏渲染），没有玻璃板可用
        }
        GhostLayer layer = layerOf(root);
        // 位置一路换成"窗口内部"的坐标：玻璃板自己就长在窗口原点，
        // 所以这个位置可以直接拿来画，中间不再经过屏幕坐标（那条路会绕远、还会错位）
        layer.show(image, toWindow(source, pointInSource, root));
    }

    /**
     * 把一个组件内部的坐标换算成"相对某个祖先"的坐标，并且把沿途的滚动也算进去。
     *
     * <p>为什么不直接用 {@code SwingUtilities.convertPoint}：那个方法会绕道"屏幕坐标"
     * （多显示器、缩放这些情况下屏幕坐标和窗口坐标并不是简单相加），
     * 实测在本机能把点算出去一千多像素，跟着鼠标的缩略图就整个跑到窗口外面了。
     * 这里只沿着父链累加位置，不碰屏幕坐标。
     *
     * <p>滚动为什么也要算：素材条比它的窗口大，它挂在滚动面板里、自己并不跟着滚，
     * 滚的是外层的视口。视口里显示的是组件的哪一段，要由 {@code viewPosition} 说。
     */
    private static Point toWindow(Component from, Point pointInFrom, Component ancestor) {
        Point inAncestor = new Point(pointInFrom);
        for (Component c = from; c != null && c != ancestor; c = c.getParent()) {
            inAncestor.translate(c.getX(), c.getY());
            if (c instanceof JViewport viewport) {
                // 往上走：出视口要把滚出去的那一段减掉
                inAncestor.translate(-viewport.getViewPosition().x, -viewport.getViewPosition().y);
            }
        }
        return inAncestor;
    }

    /** 反过来：把"相对某个祖先"的坐标换算成组件内部的坐标。 */
    private static Point fromWindow(Component ancestor, Point inAncestor, Component to) {
        Point inTo = new Point(inAncestor);
        for (Component c = to; c != null && c != ancestor; c = c.getParent()) {
            inTo.translate(-c.getX(), -c.getY());
            if (c instanceof JViewport viewport) {
                // 往下走：进视口要把滚出去的那一段加回来
                inTo.translate(viewport.getViewPosition().x, viewport.getViewPosition().y);
            }
        }
        return inTo;
    }

    private static void hideGhost(JComponent source) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root != null && root.getGlassPane() instanceof GhostLayer layer) {
            layer.hideGhost();
        }
    }

    /**
     * 一块窗口只装一块玻璃板，两次拖动共用。
     *
     * <p>必须显式给它定大小：这块板子是程序运行中途才换上去了，
     * 那会儿窗口早就布局完了，不需要再布局一次——等于是没人给它尺寸，
     * 它就一直停在 0×0，画什么都画不出来（拖动时"什么都看不见"就是这个原因）。
     * 之后窗口再变大小，靠监听器跟着调。
     */
    private static GhostLayer layerOf(JRootPane root) {
        if (root.getGlassPane() instanceof GhostLayer layer) {
            return layer;
        }
        GhostLayer layer = new GhostLayer();
        root.setGlassPane(layer);
        layer.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                layer.setBounds(0, 0, root.getWidth(), root.getHeight());
            }
        });
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

        /** 拖到哪儿、画多大；没在拖返回 null。 */
        Rectangle boundsOfGhost() {
            if (at == null) {
                return null;
            }
            int width = image == null ? 60 : GHOST_WIDTH;
            int height = image == null ? 40
                    : Math.max(1, Math.round(image.getHeight() * (float) GHOST_WIDTH / image.getWidth()));
            return new Rectangle(at.x + 12, at.y + 12, width, height);
        }

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

        /**
         * 对"这一点上是谁"的提问永远回答"不是我"。
         *
         * <p>玻璃板盖着整扇窗口。它一旦显示出来，命中判断就会先撞上它——那样
         * `getDeepestComponentAt` 永远只找到这块板子，落点面板就成了"找不到"，
         * 白线也就无从谈起（正是"没有白线"的第二个原因）。
         * 这里让它彻底透明：鼠标事件、命中判断都当它不存在，它只负责画画。
         */
        @Override
        public boolean contains(int x, int y) {
            return false;
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

    /** 拖动松手时能落在哪一类地方。 */
    private enum DropKind { NONE, PANEL, TRASH }

    /** 解析出来的落点：目标面板，或者主大图区（＝垃圾桶）。 */
    private record DropSink(DropKind kind, TargetPanel panel) {

        static final DropSink NONE = new DropSink(DropKind.NONE, null);

        static DropSink of(TargetPanel panel) {
            return panel == null ? NONE : new DropSink(DropKind.PANEL, panel);
        }

        static final DropSink TRASH = new DropSink(DropKind.TRASH, null);
    }

    /**
     * 松手那一点落在哪儿。
     *
     * <p>两类落点：目标文件夹面板（白线标插到哪个缝），以及**主大图区**——
     * 选图模式下从目标栏把照片拖到那里，意思是"这张不要了"，丢进垃圾桶
     * （垃圾桶只记指针，保存时才真删；关程序不保存就全当没发生）。
     *
     * @param withTrash 这次拖动认不认"大图区＝垃圾桶"这个落点。
     *                  从素材条拖过来的照片本来就不在目标序列里，没有可扔的，
     *                  所以那种情况不认，免得误伤。
     */
    private static DropSink resolveDropSink(JComponent source, Point pointInSource,
                                            boolean withTrash) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root == null) {
            return DropSink.NONE;
        }
        Point inRoot = toWindow(source, pointInSource, root);
        Component deepest = SwingUtilities.getDeepestComponentAt(root, inRoot.x, inRoot.y);
        TargetPanel panel = panelOf(deepest);
        if (panel != null) {
            return DropSink.of(panel);
        }
        return withTrash && viewerOf(deepest) != null ? DropSink.TRASH : DropSink.NONE;
    }

    /** 从某个组件往上找，看它是不是（或包在）主大图区里。 */
    private static ViewerPanel viewerOf(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (current instanceof ViewerPanel viewer) {
                return viewer;
            }
        }
        return null;
    }

    /** 从某个组件往上找，看它是不是（或包在）主大图区里。给测试用。 */
    static ViewerPanel viewerOfForTest(Component component) {
        return viewerOf(component);
    }

    /** 松手那一点底下是不是目标文件夹面板。 */
    private static TargetPanel targetPanelAt(JComponent source, Point pointInSource) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root == null) {
            return null;
        }
        Point inRoot = toWindow(source, pointInSource, root);
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

    /** 跟着鼠标走的那张图现在画在哪、画的是什么尺寸；没在拖就是 null。给测试用。 */
    static Rectangle ghostBoundsForTest(JComponent source) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root == null || !(root.getGlassPane() instanceof GhostLayer layer)) {
            return null;
        }
        return layer.boundsOfGhost();
    }
}

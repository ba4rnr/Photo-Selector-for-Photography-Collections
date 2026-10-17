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
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import com.photoselector.core.TargetEntry;
import com.photoselector.ui.theme.Theme;

/**
 * 「照片在面板之间拖来拖去」这件事的统一实现。
 *
 * <p>为什么不用 {@code java.awt.dnd} 那套完整的拖放框架：我们要的只是
 * "从这个组件拖出去、松手时看落在谁身上"，用不着跨进程拖放，也用不着自定义数据格式。
 * 用 Swing 自带的组件命中判断就够，少一层机器，代码也好读得多。
 *
 * <p>拖动过程中要让用户看得见两件事：
 * <ul>
 *   <li><b>正在拖</b>：一张缩略图跟着鼠标走。没有它，用户根本不知道自己拖起来没有。</li>
 *   <li><b>会落在哪</b>：目标面板上画一条白线，标出将插进哪个缝；
 *       拖到主大图区时，整块大图区亮一圈边框，表示"松手＝丢垃圾桶"。</li>
 * </ul>
 */
final class DragOut {

    /** 动超过这么多像素才算拖动，否则当单击处理。 */
    private static final int THRESHOLD = 5;

    /** 跟着鼠标走的那张缩略图有多宽。 */
    private static final int GHOST_WIDTH = 140;

    /** 一次拖动最多记几处白线提示（扫过好几块面板时用）。 */
    private static final int MAX_HINTS = 4;

    private DragOut() {
    }

    /** 能当落点的两类面板：目标文件夹面板、垃圾桶面板。 */
    record DropTarget(TargetPanel panel, TrashPanel trash) {
    }

    /**
     * 让某个组件可以往外拖照片。
     *
     * @param source      从哪个组件拖
     * @param photo       拖的是哪一张（松手时才问，避免拖动过程中照片已经换了）
     * @param ghost       跟着鼠标走的那张图：**按正在拖的那张照片**现取，
     *                    拿不到就返回 null（退化成一个小方块）。
     *                    参数是照片而不是"当前选中的那张"——从展开排序模式拖时，
     *                    两块面板的"选中"可能不是同一张，按参数取才不会张冠李戴。
     * @param enabled     现在允不允许往外拖（大图那边由"选择 / 平移"工具决定）
     * @param onDropped   落在目标文件夹面板上时回调，第二个参数是"插在第几项之前"
     * @param accept      某块目标面板认不认这一张（参数是"正在拖的那张"和那块面板）；
     *                    不认的落点连白线都不画。传 null 表示哪块都认
     * @param onTrashed   落在主大图区上时回调（选图模式：拖到那儿＝丢垃圾桶）；
     *                    不认这个落点就传 null
     * @param trashTarget 那个"垃圾桶"大图面板——悬上去时要给它亮边框。
     *                    不认这个落点时传 null
     */
    static void install(JComponent source, Supplier<Path> photo, Function<Path, BufferedImage> ghost,
                        BooleanSupplier enabled, BiConsumer<Path, Integer> onDropped,
                        BiPredicate<Path, TargetPanel> accept, Consumer<Path> onTrashed,
                        ViewerPanel trashTarget) {
        MouseAdapter handler = new MouseAdapter() {

            private Point pressedAt;
            private Path pressedPhoto;
            private final DropTarget[] hinted = new DropTarget[MAX_HINTS];
            private int hintedCount;
            private boolean hintedTrash;
            private Path ghostPhoto;
            private BufferedImage ghostImage;

            @Override
            public void mousePressed(MouseEvent e) {
                pressedAt = SwingUtilities.isLeftMouseButton(e) ? e.getPoint() : null;
                // 面板自己的 mousePressed 先执行，选中项已经更新；此刻锁定被拖照片，
                // 后续即便当前项被别的事件改掉，松手也不会换成另一张。
                pressedPhoto = pressedAt == null ? null : photo.get();
                ghostPhoto = null;
                ghostImage = null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressedAt == null || e.getPoint().distance(pressedAt) <= THRESHOLD) {
                    return;
                }
                Path dragging = pressedPhoto;
                if (dragging == null) {
                    return;
                }
                showHint(dragging, e.getPoint());
                // 整次拖动只缩一次图。大图那边缩的是全尺寸原图，
                // 每个 mouseDragged 都重做一遍会明显拖慢界面线程。
                if (!dragging.equals(ghostPhoto)) {
                    ghostPhoto = dragging;
                    ghostImage = ghost.apply(dragging);
                }
                showGhost(source, e.getPoint(), ghostImage);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Point start = pressedAt;
                pressedAt = null;
                hideGhost(source);
                Path dragging = pressedPhoto;
                DropTarget target = dragging == null
                        ? null : resolveTarget(source, e.getPoint(), dragging, accept);
                int index = target == null
                        ? -1 : dropIndexAt(target, toTargetPoint(source, target, e.getPoint()));
                boolean overTrash = dragging != null
                        && resolveViewer(source, e.getPoint(), onTrashed != null) != null;
                clearHint();
                ghostPhoto = null;
                ghostImage = null;
                pressedPhoto = null;
                if (start == null || !SwingUtilities.isLeftMouseButton(e)
                        || e.getPoint().distance(start) <= THRESHOLD || !enabled.getAsBoolean()
                        || dragging == null) {
                    return;
                }
                if (target != null && target.trash() != null && onTrashed != null) {
                    // 落在垃圾桶面板上：和"拖到中间大图区"是一回事
                    onTrashed.accept(dragging);
                } else if (target != null && index >= 0) {
                    onDropped.accept(dragging, index);
                } else if (overTrash && onTrashed != null) {
                    onTrashed.accept(dragging);
                }
            }

            /**
             * 落点提示：当前悬停的那块面板画白线，别的擦掉；悬在主大图区上时亮边框。
             *
             * <p>一次拖动可能扫过好几块面板，所以提示是"一列"而不是"一个"。
             */
            private void showHint(Path dragging, Point pointInSource) {
                DropTarget hovered = resolveTarget(source, pointInSource, dragging, accept);
                if (hovered != null) {
                    setHint(hovered, dropIndexAt(hovered, toTargetPoint(source, hovered, pointInSource)));
                }
                for (int i = 0; i < hintedCount; i++) {
                    // 按"值"比，不能按身份比：每次鼠标一动都会新建一个 DropTarget，
                    // 身份永远不相等——那样刚设上的白线会被自己立刻擦掉（踩过这个坑）
                    if (!hinted[i].equals(hovered)) {
                        clearHint(hinted[i]);
                    }
                }
                applyTrashHint(hovered == null
                        && resolveViewer(source, pointInSource, onTrashed != null) != null);
            }

            private void setHint(DropTarget target, int index) {
                if (target.panel() != null) {
                    target.panel().setDropHint(index);
                } else {
                    target.trash().setDropHint(index);
                }
                if (!contains(target) && hintedCount < MAX_HINTS) {
                    hinted[hintedCount++] = target;
                }
            }

            private void clearHint(DropTarget target) {
                if (target.panel() != null) {
                    target.panel().clearDropHint();
                } else {
                    target.trash().clearDropHint();
                }
            }

            private boolean contains(DropTarget target) {
                for (int i = 0; i < hintedCount; i++) {
                    if (hinted[i] == target) {
                        return true;
                    }
                }
                return false;
            }

            /** 悬在"垃圾桶"大图区上时给出边框提示。 */
            private void applyTrashHint(boolean trash) {
                if (trash != hintedTrash) {
                    hintedTrash = trash;
                    if (trashTarget != null) {
                        trashTarget.setTrashHint(trash);
                    }
                }
            }

            private void clearHint() {
                for (int i = 0; i < hintedCount; i++) {
                    clearHint(hinted[i]);
                }
                hintedCount = 0;
                applyTrashHint(false);
            }

            private Point toTargetPoint(JComponent source, DropTarget target, Point pointInSource) {
                JRootPane root = SwingUtilities.getRootPane(source);
                Component to = target.panel() != null ? target.panel() : target.trash();
                return fromWindow(root, toWindow(source, pointInSource, root), to);
            }
        };
        source.addMouseListener(handler);
        // 这一行是必须的：鼠标一动一动的那种事件走的是"鼠标移动监听器"，
        // 不注册它，下面的 mouseDragged 一次都不会被调用——
        // 表现就是"拖着照片什么都没发生"：没有跟着鼠标的缩略图，没有白线，松手也放不进去。
        source.addMouseMotionListener(handler);
    }

    // ─────────────────────────── 落点判断 ───────────────────────────

    /**
     * 松手那一点底下是哪块"能接照片"的面板（目标文件夹面板，或者垃圾桶面板）。
     *
     * <p>{@code accept} 是调用方给的"这块面板收不收这张照片"的判据：
     * 垃圾桶往回拖时，目标面板要收；而拖的是素材照片时就别在目标面板上乱开白线。
     */
    private static DropTarget resolveTarget(JComponent source, Point pointInSource,
                                            Path dragging, BiPredicate<Path, TargetPanel> accept) {
        Component deepest = deepestAt(source, pointInSource);
        TargetPanel panel = panelOf(deepest);
        if (panel != null) {
            return accept == null || accept.test(dragging, panel)
                    ? new DropTarget(panel, null) : null;
        }
        TrashPanel trash = trashOf(deepest);
        return trash == null ? null : new DropTarget(null, trash);
    }

    /** 松手那一点底下是不是主大图区（＝垃圾桶落点）；不认这个落点就返回 null。 */
    private static ViewerPanel resolveViewer(JComponent source, Point pointInSource,
                                             boolean withTrash) {
        return withTrash ? viewerOf(deepestAt(source, pointInSource)) : null;
    }

    private static Component deepestAt(JComponent source, Point pointInSource) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root == null) {
            return null;
        }
        Point inRoot = toWindow(source, pointInSource, root);
        return SwingUtilities.getDeepestComponentAt(root, inRoot.x, inRoot.y);
    }

    /** 落点面板上"要插到哪条白线"；两块面板各有一套，语义相同。 */
    private static int dropIndexAt(DropTarget target, Point pointOnTarget) {
        return target.panel() != null
                ? target.panel().dropIndexAt(pointOnTarget)
                : target.trash().dropIndexAt(pointOnTarget);
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

    /** 从某个组件往上找，看它是不是（或包在）垃圾桶面板里。 */
    static TrashPanel trashOf(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (current instanceof TrashPanel trash) {
                return trash;
            }
        }
        return null;
    }

    /** 从某个组件往上找，看它是不是（或包在）主大图区里。 */
    static ViewerPanel viewerOf(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (current instanceof ViewerPanel viewer) {
                return viewer;
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

    // ─────────────────────────── 跟着鼠标的那张图 ───────────────────────────

    private static void showGhost(JComponent source, Point pointInSource, BufferedImage image) {
        JRootPane root = SwingUtilities.getRootPane(source);
        if (root == null) {
            return; // 还没挂到窗口上（离屏渲染），没有玻璃板可用
        }
        GhostLayer layer = layerOf(root);
        layer.show(image, toWindow(source, pointInSource, root));
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
         * 白线也就无从谈起。这里让它彻底透明：鼠标事件、命中判断都当它不存在，
         * 它只负责画画。
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

    // ─────────────────────────── 垃圾桶往回拖 ───────────────────────────

    /**
     * 让垃圾桶里的照片可以拖回目标文件夹面板。
     *
     * <p>松手落点就用 {@link TargetPanel} 那套白线："插在第几项之前"。
     * 拖到别处（大图区、素材条……）什么都不做——照片留在垃圾桶里。
     *
     * @param source    垃圾桶面板
     * @param photo     拖的是哪一张（松手时才问）
     * @param ghost     跟着鼠标走的那张图（按正在拖的照片现取）
     * @param onDropped 落在某块目标面板上时回调：那张照片、落在哪块面板上、插到第几项之前
     */
    static void installTrashBack(JComponent source, Supplier<Path> photo,
                                 Function<Path, BufferedImage> ghost,
                                 DroppedInPanel onDropped) {
        MouseAdapter handler = new MouseAdapter() {

            private Point pressedAt;
            private Path pressedPhoto;
            private TargetPanel hinted;
            private Path ghostPhoto;
            private BufferedImage ghostImage;

            @Override
            public void mousePressed(MouseEvent e) {
                pressedAt = SwingUtilities.isLeftMouseButton(e) ? e.getPoint() : null;
                pressedPhoto = pressedAt == null ? null : photo.get();
                ghostPhoto = null;
                ghostImage = null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressedAt == null || e.getPoint().distance(pressedAt) <= THRESHOLD) {
                    return;
                }
                Path dragging = pressedPhoto;
                if (dragging == null) {
                    return;
                }
                showHint(dragging, e.getPoint());
                if (!dragging.equals(ghostPhoto)) {
                    ghostPhoto = dragging;
                    ghostImage = ghost.apply(dragging);
                }
                showGhost(source, e.getPoint(), ghostImage);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Point start = pressedAt;
                pressedAt = null;
                hideGhost(source);
                Path dragging = pressedPhoto;
                TargetPanel panel = dragging == null ? null : resolvePanel(source, e.getPoint());
                int index = panel == null
                        ? -1 : panel.dropIndexAt(toPanelPoint(source, panel, e.getPoint()));
                clearHint();
                ghostPhoto = null;
                ghostImage = null;
                pressedPhoto = null;
                if (start == null || !SwingUtilities.isLeftMouseButton(e)
                        || e.getPoint().distance(start) <= THRESHOLD || dragging == null) {
                    return;
                }
                if (panel != null && index >= 0) {
                    onDropped.accept(dragging, panel, index);
                }
            }

            private void showHint(Path dragging, Point pointInSource) {
                TargetPanel panel = resolvePanel(source, pointInSource);
                if (hinted != null && hinted != panel) {
                    hinted.clearDropHint();
                }
                hinted = panel;
                if (panel != null) {
                    panel.setDropHint(panel.dropIndexAt(toPanelPoint(source, panel, pointInSource)));
                }
            }

            private void clearHint() {
                if (hinted != null) {
                    hinted.clearDropHint();
                    hinted = null;
                }
            }

            private Point toPanelPoint(JComponent source, TargetPanel panel, Point pointInSource) {
                JRootPane root = SwingUtilities.getRootPane(source);
                return fromWindow(root, toWindow(source, pointInSource, root), panel);
            }
        };
        source.addMouseListener(handler);
        source.addMouseMotionListener(handler);
    }

    /** 「从垃圾桶拖回目标面板」落下时的回调。 */
    @FunctionalInterface
    interface DroppedInPanel {

        /**
         * @param photo 哪一张
         * @param panel 落在哪块目标面板上
         * @param index 插到第几项之前
         */
        void accept(Path photo, TargetPanel panel, int index);
    }

    private static TargetPanel resolvePanel(JComponent source, Point pointInSource) {
        return panelOf(deepestAt(source, pointInSource));
    }

    // ─────────────────────────── 坐标换算 ───────────────────────────

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
}

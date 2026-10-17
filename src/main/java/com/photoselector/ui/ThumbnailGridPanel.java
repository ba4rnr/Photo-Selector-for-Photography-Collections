package com.photoselector.ui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import com.photoselector.core.Session;
import com.photoselector.core.PhotoTransforms;
import com.photoselector.ui.theme.Painters;
import com.photoselector.ui.theme.Theme;

/**
 * 缩略图网格：一块"按格子排照片、只画看得见的那些"的通用面板。
 *
 * <p>目标栏和垃圾桶长得几乎一样——同样的留白、同样的列数计算、同样的虚拟化、
 * 同样的取图与落点白线；不同的只有"数据从哪来"和"每一格里画什么"。
 * 所以把共用的那套收进这里，两块面板只实现差异部分（模板方法）。
 *
 * <p>几个约定：格子是<b>方框</b>（横竖照片都放得下）；缩略图尺寸由
 * "查看 → 缩略图大小"决定，面板变宽时变的是间距，不是照片大小；
 * 只给看得见的格子取图，滚远了的从手里丢掉。
 */
@SuppressWarnings("serial")
abstract class ThumbnailGridPanel extends JPanel {

    /** 四周留白的最小值；有多余宽度时会一起变宽。 */
    protected static final int PADDING = 12;

    /** 格子之间、行之间的最小间隔；有多余宽度时横向间隔会一起变宽。 */
    protected static final int GAP = 8;

    /** 表头占的高度。 */
    protected static final int HEADER_HEIGHT = 34;

    /** 一格下面那行文字占的高度。 */
    protected static final int CAPTION_HEIGHT = 18;

    /** 缩略图的默认宽度（"查看 → 缩略图大小"里可以改）。 */
    protected static final int DEFAULT_BOX_WIDTH = 150;

    /**
     * 一格的高 : 宽比。取方框：横照片满宽排，竖照片也能占满宽度立起来。
     */
    protected static final float BOX_ASPECT = 1.0f;

    protected final ThumbnailLoader loader;
    protected final Map<Path, BufferedImage> thumbnails = new HashMap<>();

    protected Session session;

    /** 缩略图宽度由"查看 → 缩略图大小"决定，不随面板宽度变化。 */
    protected int boxWidth = DEFAULT_BOX_WIDTH;

    // 下面三个由 updateMetrics() 按当前宽度算出来
    protected int columns = 1;
    protected int extraSpace;
    protected int thumbnailHeight = Math.round(DEFAULT_BOX_WIDTH * BOX_ASPECT);

    /** 当前点中的那一格；-1 表示没点过。 */
    private int chosenIndex = -1;

    /** 拖动中的落点：插在第几项之前；-1 表示没在拖。 */
    private int dropHint = -1;

    /** 面板内容变过之后通知外部（撤销 / 重做按钮的灰亮靠它）。 */
    private Runnable onChanged = () -> { };

    protected ThumbnailGridPanel(ThumbnailLoader loader) {
        this.loader = loader;
        setBackground(Theme.PANEL);
        setOpaque(true);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateMetrics();
                updatePreferredSize();
                requestVisibleThumbnails();
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (onHeaderClick(e.getPoint())) {
                    return;
                }
                int index = indexAt(e.getPoint());
                if (index >= 0) {
                    chosenIndex = index;
                    repaint();
                    onCellActivated(index);
                }
            }
        });
    }

    // ─────────────────────────── 子类要补的部分 ───────────────────────────

    /** 现在有几格。 */
    protected abstract int itemCount();

    /** 第 {@code index} 格是哪张照片。 */
    protected abstract Path sourceAt(int index);

    /** 画表头（路径、张数、"垃圾桶"这类）。 */
    protected abstract void paintHeader(Graphics2D g);

    /** 画一格的正文：通常是 {@link Painters#thumbnailCell} 加一行自己的文字。 */
    protected abstract void paintCellContent(Graphics2D g, int index, Rectangle cell,
                                             BufferedImage image, boolean chosen);

    /** 一格都没有时画什么提示。 */
    protected abstract void paintEmptyHint(Graphics2D g);

    /** 点中一格之后干什么（换大图 / 只是选中）。默认什么都不做。 */
    protected void onCellActivated(int index) {
    }

    /** 表头上的点击（比如「展开」按钮）是不是已经被处理掉了。默认没有表头按钮。 */
    protected boolean onHeaderClick(Point point) {
        return false;
    }

    /** 判定"这一格是不是选中"的默认依据；子类也可以覆盖成按照片判。 */
    protected boolean isChosen(int index) {
        return index == chosenIndex;
    }

    // ─────────────────────────── 共用实现 ───────────────────────────

    /** 换一个中间商；传 null 表示还没选目标文件夹。 */
    void setSession(Session newSession) {
        this.session = newSession;
        chosenIndex = -1;
        thumbnails.clear();
        updateMetrics();
        updatePreferredSize();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
        // 换中间商也是"历史换了"：撤销 / 重做的灰亮要跟着新中间商走
        onChanged.run();
    }

    Session session() {
        return session;
    }

    /** 某张照片手里有没有缩略图；拖动时拿它当"跟着鼠标的那张图"。 */
    BufferedImage thumbnailOf(Path photo) {
        return thumbnails.get(photo);
    }

    /** 点中的那张照片；还没点过、或者换了中间商就是 null。 */
    Path selectedPhoto() {
        if (session == null || chosenIndex < 0 || chosenIndex >= itemCount()) {
            return null;
        }
        return sourceAt(chosenIndex);
    }

    /**
     * 让"点中的那张"改指向新的位置。
     *
     * <p>拖动重排之后要调：照片挪了位置，而"选中"是按位置记的——
     * 不跟着挪，高亮就会留在原地，指着另一张照片。
     */
    void moveSelectionTo(int index) {
        if (session != null && index >= 0 && index < itemCount()) {
            chosenIndex = index;
            repaint();
        }
    }

    /** 按照片路径选中；照片不在当前序列里就返回 false。 */
    boolean selectPhoto(Path photo) {
        int index = session == null || photo == null ? -1 : session.indexOf(photo);
        if (index < 0) {
            return false;
        }
        chosenIndex = index;
        scrollToIndex(index);
        repaint();
        return true;
    }

    /** 把某一格滚进视野；换行处按实际行列计算。 */
    void scrollToIndex(int index) {
        if (session == null || index < 0 || index >= itemCount()) {
            return;
        }
        updateMetrics();
        int row = index / columns;
        int column = index % columns;
        Rectangle cell = new Rectangle(cellX(column), HEADER_HEIGHT + row * rowHeight(),
                boxWidth, thumbnailHeight);
        SwingUtilities.invokeLater(() -> scrollRectToVisible(cell));
    }

    /** 取消当前点选；照片被外部删除、位置已经不存在时用。 */
    void clearSelection() {
        if (chosenIndex != -1) {
            chosenIndex = -1;
            repaint();
        }
    }

    /** 内容变了（挑入、撤销、挪动、丢垃圾桶）之后调用：重新取图并重画。 */
    void refresh() {
        updatePreferredSize();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
        onChanged.run();
    }

    /**
     * 外部文件内容变了：清掉手里的缩略图再重新取。
     *
     * <p>普通 {@link #refresh()} 会保留已有缩略图，因为多数操作只改顺序；
     * 但外部替换了同名文件时，保留旧图就会一直显示旧内容。
     */
    void reloadThumbnails() {
        thumbnails.clear();
        updatePreferredSize();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    /** 只让某一张的缩略图失效，例如它刚被旋转、裁切或重置。 */
    void reloadThumbnail(Path photo) {
        if (photo != null && thumbnails.remove(photo) != null) {
            repaint();
        }
        if (photo != null) {
            SwingUtilities.invokeLater(this::requestVisibleThumbnails);
        }
    }

    /** 面板内容变过之后通知外部。 */
    void setOnChanged(Runnable listener) {
        this.onChanged = listener == null ? () -> { } : listener;
    }

    int entryCount() {
        return itemCount();
    }

    /** 已经拿到缩略图的张数。给测试和排查用。 */
    int loadedCount() {
        return thumbnails.size();
    }

    /** 还在路上的取图请求数。测试用它等后台收工。 */
    int inFlightForTest() {
        return loader.inFlightCount();
    }

    /** 某一张的缩略图在不在手里。测试用。 */
    boolean isLoaded(Path photo) {
        return thumbnails.containsKey(photo);
    }

    /**
     * 改缩略图大小（"查看 → 缩略图大小"里选）。
     *
     * <p>尺寸一变，手里那些缩略图就不合用了——先清空再重新取。
     */
    void setThumbnailWidth(int width) {
        this.boxWidth = Math.max(60, width);
        thumbnails.clear();
        updateMetrics();
        updatePreferredSize();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    int thumbnailWidth() {
        return boxWidth;
    }

    /** 拖动的落点：插在第几项之前。 */
    void setDropHint(int index) {
        if (dropHint != index) {
            dropHint = index;
            repaint();
        }
    }

    void clearDropHint() {
        setDropHint(-1);
    }

    /** 当前标出的落点（没标就是 -1）。给测试和排查用。 */
    int dropHintIndex() {
        return dropHint;
    }

    /**
     * 光标落在哪两个格子之间，返回"插在第几项之前"。
     *
     * <p>判据是每一格的中点：偏左插在这一格前面，偏右插到它后面。
     * 换行处（本行开头）算作上一行的末尾，和实际插入的位置对得上。
     */
    int dropIndexAt(Point point) {
        if (session == null) {
            return 0;
        }
        updateMetrics();
        int row = Math.max(0, (point.y - HEADER_HEIGHT) / rowHeight());
        int column = -1;
        for (int c = 0; c < columns; c++) {
            if (point.x < cellX(c) + boxWidth / 2) {
                column = c;
                break;
            }
        }
        if (column == 0 && row > 0) {
            row--;
            column = -1;
        }
        if (column < 0) {
            column = columns;
        }
        return Math.max(0, Math.min(itemCount(), row * columns + column));
    }

    /** 某一格对应的下标；点在空白处返回 -1。 */
    int indexAt(Point point) {
        if (session == null || itemCount() == 0 || point.y < HEADER_HEIGHT) {
            return -1;
        }
        updateMetrics();
        int row = (point.y - HEADER_HEIGHT) / rowHeight();
        int column = -1;
        for (int c = 0; c < columns; c++) {
            int left = cellX(c);
            if (point.x >= left && point.x < left + boxWidth) {
                column = c;
                break;
            }
        }
        if (column < 0) {
            return -1;
        }
        int index = row * columns + column;
        return index < itemCount() ? index : -1;
    }

    /**
     * 装进滚动面板。
     *
     * <p>两个监听缺一不可：视口滚动、以及视口大小变化，都要重新算"哪些格子看得见"。
     */
    JScrollPane wrapInScrollPane() {
        JScrollPane scroll = new JScrollPane(this,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // 不摘掉左右键，焦点在这块面板上时方向键只会让画面滑动
        ScrollKeys.dropArrowKeys(scroll);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        Theme.themeScrollBar(scroll.getVerticalScrollBar());

        scroll.getViewport().addChangeListener(e -> requestVisibleThumbnails());
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                requestVisibleThumbnails();
            }
        });
        return scroll;
    }

    // ─────────────────────────── 尺寸计算 ───────────────────────────

    /**
     * 按当前宽度算排几列、多余的宽度怎么分。
     *
     * <p>缩略图尺寸固定，宽度变化时变的是间距：先定列数，剩下的宽度平均分给
     * 每条间隔和左右留白——拖动左边框看到的是"照片松了/紧了"，不是照片忽大忽小。
     */
    protected void updateMetrics() {
        thumbnailHeight = Math.max(16, Math.round(boxWidth * BOX_ASPECT));
        int available = Math.max(40, getWidth() - PADDING * 2);
        columns = Math.max(1, (available + GAP) / (boxWidth + GAP));
        int used = columns * boxWidth + (columns - 1) * GAP;
        extraSpace = Math.max(0, (available - used) / (columns + 1));
    }

    /** 第 column 列的左边缘：留白、间隔都加上分到的那份多余宽度。 */
    protected int cellX(int column) {
        return PADDING + extraSpace + column * (boxWidth + GAP + extraSpace);
    }

    protected int rowHeight() {
        return thumbnailHeight + CAPTION_HEIGHT + GAP;
    }

    private int contentHeight() {
        if (session == null || itemCount() == 0) {
            return HEADER_HEIGHT + 40;
        }
        int rows = (itemCount() + columns - 1) / columns;
        return HEADER_HEIGHT + rows * rowHeight() + PADDING;
    }

    private void updatePreferredSize() {
        // 宽度交给滚动面板去撑，高度按内容算，内容多了垂直滚动条才会出现
        setPreferredSize(new Dimension(120, contentHeight()));
        revalidate();
    }

    // ─────────────────────────── 绘制 ───────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        paintHeader(g2);
        if (session == null || itemCount() == 0) {
            paintEmptyHint(g2);
        } else {
            paintCells(g2);
            paintDropHint(g2);
        }
        g2.dispose();
    }

    /** 只画看得见的那几行。 */
    private void paintCells(Graphics2D g) {
        updateMetrics();
        Rectangle clip = g.getClipBounds();
        int firstRow = Math.max(0, (clip.y - HEADER_HEIGHT) / rowHeight());
        int lastRow = (clip.y + clip.height - HEADER_HEIGHT) / rowHeight();

        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= itemCount()) {
                    return;
                }
                Rectangle cell = new Rectangle(cellX(column), HEADER_HEIGHT + row * rowHeight(),
                        boxWidth, thumbnailHeight);
                paintCellContent(g, index, cell, thumbnails.get(sourceAt(index)), isChosen(index));
            }
        }
    }

    /** 拖动时标出落点的那条白线。 */
    private void paintDropHint(Graphics2D g) {
        Rectangle line = dropHintRect();
        if (line != null) {
            Painters.dropLine(g, line);
        }
    }

    /** 那条落点白线占的位置；没有落点时返回 null。 */
    private Rectangle dropHintRect() {
        if (dropHint < 0 || columns <= 0) {
            return null;
        }
        updateMetrics();
        int row = dropHint / columns;
        int column = dropHint % columns;
        if (dropHint > 0 && column == 0) {
            // 换行处：这个落点等于"上一行的最后一张之后"，白线画在上一行末尾
            row--;
            column = columns;
        }
        int x = column < columns ? cellX(column) : cellX(columns - 1) + boxWidth + GAP / 2;
        int y = HEADER_HEIGHT + row * rowHeight();
        return new Rectangle(x - 4, y, 3, thumbnailHeight);
    }

    // ─────────────────────────── 取图 ───────────────────────────

    /**
     * 为看得见的格子请求缩略图；滚远了的丢掉，免得攥着内存不放。
     *
     * <p>不可见时直接返回：浏览模式右栏和整理模式中间各有一块目标面板，
     * 同一时刻只有一块可见；不挡的话隐藏的那块会按整块尺寸白取一批图。
     */
    private void requestVisibleThumbnails() {
        if (!isVisible() || session == null || itemCount() == 0) {
            return;
        }
        updateMetrics();
        Rectangle visible = getVisibleRect();
        if (visible.height <= 0) {
            visible = new Rectangle(0, 0, getWidth(), getHeight());
        }

        // 可见范围上下各多取一行，滚动时不至于先看到空白
        int firstRow = Math.max(0, (visible.y - HEADER_HEIGHT) / rowHeight() - 1);
        int lastRow = (visible.y + visible.height - HEADER_HEIGHT) / rowHeight() + 1;
        int first = firstRow * columns;
        int last = Math.min(itemCount() - 1, (lastRow + 1) * columns - 1);

        Set<Path> keep = new HashSet<>();
        for (int index = first; index <= last; index++) {
            Path source = sourceAt(index);
            keep.add(source);
            if (!thumbnails.containsKey(source)) {
                loader.load(source, thumbnailHeight, image -> onThumbnail(source, image));
            }
        }
        if (!thumbnails.isEmpty() && thumbnails.keySet().removeIf(path -> !keep.contains(path))) {
            repaint();
        }
    }

    private void onThumbnail(Path source, BufferedImage image) {
        BufferedImage display = session == null ? image
                : PhotoTransforms.apply(image, session.editsOf(source));
        thumbnails.put(source, display);
        repaint();
    }

    // ─────────────────────────── 给测试的量尺 ───────────────────────────

    /** 一行有多高。 */
    int rowHeightForTest() {
        updateMetrics();
        return rowHeight();
    }

    /** 第 column 格左边缘的 x。 */
    int cellXForTest(int column) {
        updateMetrics();
        return cellX(column);
    }

    /** 第 column 格右边缘的 x。 */
    int cellRightForTest(int column) {
        updateMetrics();
        return cellX(column) + boxWidth;
    }

    /** 一格缩略图多宽。 */
    int thumbnailWidthForTest() {
        return boxWidth;
    }

    /** 一行排几列。 */
    int columnsForTest() {
        updateMetrics();
        return columns;
    }

    /** 每处间隔分到的多余宽度。 */
    int extraSpaceForTest() {
        updateMetrics();
        return extraSpace;
    }

    /** 那条白线现在画在哪。 */
    java.awt.Rectangle dropHintRectForTest() {
        return dropHintRect();
    }
}

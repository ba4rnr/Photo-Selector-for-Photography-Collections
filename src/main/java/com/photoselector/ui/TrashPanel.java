package com.photoselector.ui;

import java.awt.Dimension;
import java.awt.FontMetrics;
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
import com.photoselector.core.TrashedItem;
import com.photoselector.ui.theme.Theme;

/**
 * 展开排序模式的右栏：垃圾桶。
 *
 * <p>里面装的只是<b>指针</b>——被移出目标序列的那些条目的引用，不是文件的复制品。
 * 每格角上的小号是它<b>原来</b>的编号，用来对照；拖回来之后编号会按新位置现算。
 *
 * <p>行为：照片可以从目标栏拖进来（选图模式下拖到中间大图区也算），
 * 也可以从这儿<b>拖回</b>目标栏——拖的时候一张缩略图跟着鼠标走，
 * 目标面板上照旧有白线标出会插到哪个缝。
 */
@SuppressWarnings("serial")
final class TrashPanel extends JPanel {

    private static final int PADDING = 12;
    private static final int GAP = 8;
    private static final int HEADER_HEIGHT = 34;
    private static final int CAPTION_HEIGHT = 18;

    /** 一格的宽度。垃圾桶里不需要"精细筛选"，固定一个够看清的尺寸就好。 */
    private static final int BOX_WIDTH = 150;

    private final ThumbnailLoader loader;
    private final Map<Path, BufferedImage> thumbnails = new HashMap<>();

    private Session session;
    private Path chosen;
    private int dropHint = -1;

    private int columns = 1;
    private int extraSpace;

    TrashPanel(ThumbnailLoader loader) {
        this.loader = loader;
        setBackground(Theme.PANEL);
        setOpaque(true);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                requestVisibleThumbnails();
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int index = indexAt(e.getPoint());
                if (index >= 0) {
                    chosen = sourceAt(index);
                    repaint();
                }
            }
        });
    }

    /** 换一个中间商；传 null 表示还没选目标文件夹。 */
    void setSession(Session newSession) {
        this.session = newSession;
        chosen = null;
        thumbnails.clear();
        updatePreferredSize();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    /** 垃圾桶的内容变了（丢了新的进去、或者捡回来一张）之后调用。 */
    void refresh() {
        updatePreferredSize();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    int entryCount() {
        return session == null ? 0 : session.trash().size();
    }

    void setOnPhotoChosen(java.util.function.Consumer<Path> listener) {
        this.onPhotoChosen = listener == null ? photo -> { } : listener;
    }

    private java.util.function.Consumer<Path> onPhotoChosen = photo -> { };

    /** 点中的那张（拖出时拖的就是它）；没点过就是 null。 */
    Path selectedPhoto() {
        return chosen;
    }

    /** 某张照片手里的缩略图；没解好就是 null。拖出提示图用。 */
    BufferedImage thumbnailOf(Path photo) {
        return thumbnails.get(photo);
    }

    /** 第 index 格那条记录（按下标取，下标按"最近丢的排前面"数）。 */
    TrashedItem itemAt(int index) {
        if (session == null || index < 0 || index >= session.trash().size()) {
            return null;
        }
        return session.trash().recentFirst().get(index);
    }

    private Path sourceAt(int index) {
        TrashedItem item = itemAt(index);
        return item == null ? null : item.entry().source();
    }

    /**
     * 这一格对应的下标；点在空白处返回 -1。
     */
    int indexAt(Point point) {
        if (entryCount() == 0 || point.y < HEADER_HEIGHT) {
            return -1;
        }
        updateMetrics();
        int row = (point.y - HEADER_HEIGHT) / rowHeight();
        int column = -1;
        for (int c = 0; c < columns; c++) {
            int left = cellX(c);
            if (point.x >= left && point.x < left + BOX_WIDTH) {
                column = c;
                break;
            }
        }
        if (column < 0) {
            return -1;
        }
        int index = row * columns + column;
        return index < entryCount() ? index : -1;
    }

    /**
     * 光标落在哪两个格子之间，返回"捡回来之后插在第几项之前"。
     *
     * <p>和 {@link TargetPanel#dropIndexAt} 同一套判据：每一格的中点，偏左插前面、偏右插后面。
     */
    int dropIndexAt(Point point) {
        if (session == null) {
            return 0;
        }
        updateMetrics();
        int row = Math.max(0, (point.y - HEADER_HEIGHT) / rowHeight());
        int column = -1;
        for (int c = 0; c < columns; c++) {
            if (point.x < cellX(c) + BOX_WIDTH / 2) {
                column = c;
                break;
            }
        }
        if (column == 0 && row > 0) {
            // 换行处：等价于上一行的末尾，和 TargetPanel 保持同一套观感
            row--;
            column = -1;
        }
        if (column < 0) {
            column = columns;
        }
        return Math.max(0, Math.min(session.size(), row * columns + column));
    }

    /** 落入白线的位置画在这儿。 */
    void setDropHint(int index) {
        if (dropHint != index) {
            dropHint = index;
            repaint();
        }
    }

    void clearDropHint() {
        setDropHint(-1);
    }

    /** 当前标出的落点（没标就是 -1）。给测试用。 */
    int dropHintIndex() {
        return dropHint;
    }

    JScrollPane wrapInScrollPane() {
        JScrollPane scroll = new JScrollPane(this,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
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

    // ─────────────────────────── 尺寸 ───────────────────────────

    private void updateMetrics() {
        int available = Math.max(40, getWidth() - PADDING * 2);
        columns = Math.max(1, (available + GAP) / (BOX_WIDTH + GAP));
        int used = columns * BOX_WIDTH + (columns - 1) * GAP;
        extraSpace = Math.max(0, (available - used) / (columns + 1));
    }

    private int cellX(int column) {
        return PADDING + extraSpace + column * (BOX_WIDTH + GAP + extraSpace);
    }

    private int rowHeight() {
        return BOX_WIDTH + CAPTION_HEIGHT + GAP; // 方框：高 = 宽
    }

    private int contentHeight() {
        if (entryCount() == 0) {
            return HEADER_HEIGHT + 40;
        }
        int rows = (entryCount() + columns - 1) / columns;
        return HEADER_HEIGHT + rows * rowHeight() + PADDING;
    }

    private void updatePreferredSize() {
        updateMetrics();
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
        if (entryCount() == 0) {
            paintEmptyHint(g2);
        } else {
            paintCells(g2);
            paintDropHint(g2);
        }
        g2.dispose();
    }

    private void paintHeader(Graphics2D g) {
        g.setFont(Theme.FONT_SMALL);
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(Theme.TEXT);
        g.drawString("垃圾桶", PADDING, 22);
        String count = entryCount() == 0 ? "" : "共 " + entryCount() + " 张";
        g.setColor(Theme.TEXT_MUTED);
        g.drawString(count, PADDING + metrics.stringWidth("垃圾桶") + 10, 22);
    }

    private void paintEmptyHint(Graphics2D g) {
        g.setFont(Theme.FONT_SMALL);
        g.setColor(Theme.TEXT_MUTED);
        g.drawString("空空的。把不要的照片拖到这儿，或者拖到中间的大图区。",
                PADDING, HEADER_HEIGHT + 20);
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
                if (index >= entryCount()) {
                    return;
                }
                paintCell(g, index, cellX(column), HEADER_HEIGHT + row * rowHeight());
            }
        }
    }

    private void paintCell(Graphics2D g, int index, int x, int y) {
        TrashedItem item = itemAt(index);
        if (item == null) {
            return;
        }
        Path photo = item.entry().source();
        BufferedImage image = thumbnails.get(photo);
        boolean current = photo.equals(chosen);

        if (current) {
            g.setColor(Theme.HIGHLIGHT);
            g.fillRect(x - 2, y - 2, BOX_WIDTH + 4, BOX_WIDTH + 4);
        }
        if (image == null) {
            g.setColor(Theme.PANEL);
            g.fillRect(x, y, BOX_WIDTH, BOX_WIDTH);
            g.setColor(Theme.BORDER);
            g.drawRect(x, y, BOX_WIDTH - 1, BOX_WIDTH - 1);
        } else {
            double scale = Math.min((double) BOX_WIDTH / image.getWidth(),
                    (double) BOX_WIDTH / image.getHeight());
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            g.drawImage(image, x + (BOX_WIDTH - width) / 2, y + (BOX_WIDTH - height) / 2,
                    width, height, null);
        }

        // 名字那一行：原来的编号 + 文件名——一眼看清"扔掉的是哪一张"
        g.setFont(Theme.FONT_SMALL);
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(current ? Theme.TEXT : Theme.TEXT_MUTED);
        // 显示它"扔掉之前"的编号，用来对照；捡回来之后编号会按新位置现算。
        // 编号不存条目里（见 TargetEntry 的注释），所以按记下的原位置现算。
        String was = session.numbering().nameFor(item.originalIndex());
        String label = was + "  " + photo.getFileName();
        g.drawString(shorten(label, metrics, BOX_WIDTH), x, y + BOX_WIDTH + 13);
    }

    /** 拖动时标出落点的白线。 */
    private void paintDropHint(Graphics2D g) {
        if (dropHint < 0) {
            return;
        }
        Rectangle line = dropHintRect();
        g.setColor(Theme.DIVIDER_HINT);
        g.fillRect(line.x, line.y, line.width, line.height);
    }

    private Rectangle dropHintRect() {
        updateMetrics();
        int row = dropHint / columns;
        int column = dropHint % columns;
        if (dropHint > 0 && column == 0) {
            row--;
            column = columns;
        }
        int x = column < columns ? cellX(column) : cellX(columns - 1) + BOX_WIDTH + GAP / 2;
        int yy = HEADER_HEIGHT + row * rowHeight();
        return new Rectangle(x - 4, yy, 3, BOX_WIDTH);
    }

    /** 那条白线现在画在哪。给测试用。 */
    Rectangle dropHintRectForTest() {
        return dropHintRect();
    }

    private static String shorten(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int width = maxWidth - metrics.stringWidth(ellipsis);
        int end = text.length();
        while (end > 1 && metrics.stringWidth(text.substring(0, end)) > width) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    // ─────────────────────────── 取图 ───────────────────────────

    /** 为看得见的格子请求缩略图；滚远了的丢掉，免得攥着内存不放。 */
    private void requestVisibleThumbnails() {
        if (!isVisible() || entryCount() == 0 || getWidth() <= 0) {
            return;
        }
        Rectangle visible = getVisibleRect();
        if (visible.width <= 0) {
            return;
        }
        updateMetrics();
        int margin = Math.max(BOX_WIDTH, visible.height * KEEP_SCREENS);
        int firstRow = Math.max(0, (visible.y - margin - HEADER_HEIGHT) / rowHeight());
        int lastRow = (visible.y + visible.height + margin - HEADER_HEIGHT) / rowHeight();

        Set<Path> keep = new HashSet<>();
        int firstIndex = firstRow * columns;
        int lastIndex = Math.min(entryCount() - 1, (lastRow + 1) * columns - 1);
        for (int index = Math.max(0, firstIndex); index <= lastIndex; index++) {
            Path photo = sourceAt(index);
            if (photo == null) {
                continue;
            }
            keep.add(photo);
            if (!thumbnails.containsKey(photo)) {
                loader.load(photo, BOX_WIDTH, image -> {
                    thumbnails.put(photo, image);
                    repaint();
                });
            }
        }
        if (!thumbnails.isEmpty() && thumbnails.keySet().removeIf(photo -> !keep.contains(photo))) {
            repaint();
        }
    }

    /** 保留多远之外的缩略图就先丢开（量一下，省内存）。 */
    private static final int KEEP_SCREENS = 2;

    /** 垃圾桶里现在有几张。给测试用。 */
    int loadedCount() {
        return thumbnails.size();
    }

    /** 一共能滚多高。给测试用。 */
    int contentHeightForTest() {
        return contentHeight();
    }

    /** 第 column 格左边缘的 x。给测试算格位用。 */
    int cellXForTest(int column) {
        updateMetrics();
        return cellX(column);
    }

    /** 一行有多高。给测试算格位用。 */
    int rowHeightForTest() {
        return rowHeight();
    }
}

package com.photoselector.ui;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
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

import com.photoselector.core.Origin;
import com.photoselector.core.Session;
import com.photoselector.core.TargetEntry;
import com.photoselector.ui.theme.Theme;

/**
 * 右栏：目标文件夹。
 *
 * <p>它显示的是中间商里的<b>目标序列</b>——也就是"假如现在按保存，目标文件夹会变成什么样"。
 * 每一格下面写的是它<b>将来</b>在磁盘上的名字，由编号规则按当前位置现算；
 * 挪动位置，名字跟着变，不需要谁去手工同步。
 *
 * <p>两个几何上的决定：
 * <ul>
 *   <li><b>格子撑满可用宽度</b>，而不是写死尺寸。右栏宽度是可以拖的，
 *       写死的话窄了会挤、宽了右侧留一大片空白。</li>
 *   <li><b>只画看得见的格子</b>，目标文件夹里可能有上千张。</li>
 * </ul>
 */
@SuppressWarnings("serial")
final class TargetPanel extends JPanel {

    /** 四周留白的最小值；有多余宽度时会一起变宽。 */
    private static final int PADDING = 12;

    /** 格子之间、行之间的最小间隔；有多余宽度时横向间隔会一起变宽。 */
    private static final int GAP = 8;

    /** 表头（目标文件夹路径 + 张数）占的高度。 */
    private static final int HEADER_HEIGHT = 34;

    /** 表头右侧给「展开」按钮预留的宽度；路径再长也不许占过来。 */
    private static final int HEADER_BUTTON_SPACE = 76;

    /** 名字那一行占的高度。 */
    private static final int CAPTION_HEIGHT = 18;

    /** 缩略图的默认宽度（"查看 → 缩略图大小"里可以改）。 */
    private static final int DEFAULT_BOX_WIDTH = 150;

    private final ThumbnailLoader loader;
    private final Map<Path, BufferedImage> thumbnails = new HashMap<>();

    private Session session;

    /** 缩略图宽度由"查看 → 缩略图大小"决定，不随面板宽度变化。 */
    private int boxWidth = DEFAULT_BOX_WIDTH;

    /** 表头右侧那个「展开 / 收起」按钮的位置；绘制时算出来，点击时用它做命中判断。 */
    private Rectangle expandButtonBounds;

    /** 现在是不是展开状态，决定按钮上写"展开"还是"收起"。 */
    private boolean expanded;

    /** 点「展开 / 收起」时通知外部去切换模式。 */
    private Runnable onToggleExpanded = () -> { };

    // 下面三个由 updateMetrics() 按当前宽度算出来
    private int columns = 1;
    private int extraSpace;
    private int thumbnailHeight = Math.round(DEFAULT_BOX_WIDTH / 1.5f);

    TargetPanel(ThumbnailLoader loader) {
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
                if (SwingUtilities.isLeftMouseButton(e)
                        && expandButtonBounds != null
                        && expandButtonBounds.contains(e.getPoint())) {
                    onToggleExpanded.run();
                }
            }
        });
    }

    /** 点表头那个按钮时通知外部。 */
    void setOnToggleExpanded(Runnable listener) {
        this.onToggleExpanded = listener == null ? () -> { } : listener;
    }

    /** 告诉面板现在是展开还是收起，好让按钮上的字跟着变。 */
    void setExpanded(boolean expanded) {
        this.expanded = expanded;
        repaint();
    }

    /** 表头按钮的位置，测试用。 */
    Rectangle expandButtonBoundsForTest() {
        return expandButtonBounds;
    }

    /** 换一个中间商；传 null 表示还没选目标文件夹。 */
    void setSession(Session newSession) {
        this.session = newSession;
        thumbnails.clear();
        updateMetrics();
        updatePreferredSize();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    Session session() {
        return session;
    }

    /** 序列变了（挑入、撤销、挪动）之后调用：重新取图并重画。 */
    void refresh() {
        updatePreferredSize();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    int entryCount() {
        return session == null ? 0 : session.size();
    }

    /** 已经拿到缩略图的张数。给测试和排查用。 */
    int loadedCount() {
        return thumbnails.size();
    }

    /** 某一张的缩略图在不在手里。测试用。 */
    boolean isLoaded(Path photo) {
        return thumbnails.containsKey(photo);
    }

    /** 第 index 条将来在磁盘上叫什么名字。测试用。 */
    String nameAt(int index) {
        return session == null ? null : session.nameAt(index);
    }

    /**
     * 改缩略图大小（"查看 → 缩略图大小"里选）。
     *
     * <p>尺寸一变，手里那些缩略图就不合用了——先清空再重新取。
     * 缩略图服务的缓存键里带着高度，所以换尺寸不会和旧的那批打架。
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

    /** 一行排几列。测试用。 */
    int columnsForTest() {
        updateMetrics();
        return columns;
    }

    /** 每处间隔分到的多余宽度。测试用。 */
    int extraSpaceForTest() {
        updateMetrics();
        return extraSpace;
    }

    /**
     * 装进滚动面板。
     *
     * <p>两个监听缺一不可，和素材条是同一个道理：视口滚动、以及视口大小变化，
     * 都要重新算"哪些格子看得见"。
     */
    JScrollPane wrapInScrollPane() {
        JScrollPane scroll = new JScrollPane(this,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
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
     * 按当前宽度算排几列、以及多余的宽度怎么分。
     *
     * <p>缩略图尺寸是固定的（由"查看 → 缩略图大小"定），宽度变化时变的是<b>间距</b>：
     * 先把能放下的列数定下来，剩下的宽度平均分给每条间隔和左右留白。
     * 这样拖动左边框看到的是"照片之间松了/紧了"，而不是照片忽大忽小。
     */
    private void updateMetrics() {
        thumbnailHeight = Math.max(16, Math.round(boxWidth / 1.5f));
        int available = Math.max(40, getWidth() - PADDING * 2);
        columns = Math.max(1, (available + GAP) / (boxWidth + GAP));
        int used = columns * boxWidth + (columns - 1) * GAP;
        extraSpace = Math.max(0, (available - used) / (columns + 1));
    }

    /** 第 column 列的左边缘：留白、间隔都加上分到的那份多余宽度。 */
    private int cellX(int column) {
        return PADDING + extraSpace + column * (boxWidth + GAP + extraSpace);
    }

    private int rowHeight() {
        return thumbnailHeight + CAPTION_HEIGHT + GAP;
    }

    private int contentHeight() {
        if (session == null || session.isEmpty()) {
            return HEADER_HEIGHT + 40;
        }
        int rows = (session.size() + columns - 1) / columns;
        return HEADER_HEIGHT + rows * rowHeight() + PADDING;
    }

    private void updatePreferredSize() {
        // 宽度交给滚动面板去撑（ViewportLayout 会把视图拉到至少和视口一样宽），
        // 高度按内容算，这样内容多了垂直滚动条才会出现
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
        if (session == null || session.isEmpty()) {
            paintEmptyHint(g2);
        } else {
            paintCells(g2);
        }
        g2.dispose();
    }

    private void paintHeader(Graphics2D g) {
        g.setFont(Theme.FONT_SMALL);
        FontMetrics metrics = g.getFontMetrics();
        int baseline = 22;

        String label = "目标文件夹：";
        String path = session == null ? "还没选" : session.targetFolder().toString();
        String count = session == null ? "" : "共 " + session.size() + " 张";

        // 右侧先给「展开」按钮留一块地方，张数贴着它左边放、永远看得见；
        // 路径只能占"标签之后、张数之前"的那一段，超长就在中间用省略号掐掉
        int pathX = PADDING + metrics.stringWidth(label);
        int rightEdge = Math.max(pathX + 24, getWidth() - PADDING - HEADER_BUTTON_SPACE);
        int countWidth = count.isEmpty() ? 0 : metrics.stringWidth(count) + GAP;
        boolean showCount = rightEdge - countWidth - pathX >= 40;
        if (!showCount) {
            countWidth = 0; // 面板窄到摆不下，先让张数让位，别和路径叠在一起
        }

        g.setColor(Theme.TEXT_MUTED);
        g.drawString(label, PADDING, baseline);
        g.setColor(Theme.TEXT);
        g.drawString(shortenMiddle(path, metrics, rightEdge - countWidth - pathX), pathX, baseline);
        if (showCount) {
            g.setColor(Theme.TEXT_MUTED);
            g.drawString(count, rightEdge - countWidth + GAP, baseline);
        }

        g.setColor(Theme.BORDER);
        g.fillRect(PADDING, HEADER_HEIGHT - 7, Math.max(0, getWidth() - PADDING * 2), 1);
        paintExpandButton(g, metrics);
    }

    /**
     * 画表头右侧那个「展开 / 收起」按钮。
     *
     * <p>用一块实心底色加文字、不描边——一像素的边框在缩放显示下左右宽度会不一样，
     * 这个坑在交接说明里记着。位置正好落在表头为它留出的 {@code HEADER_BUTTON_SPACE} 里。
     */
    private void paintExpandButton(Graphics2D g, FontMetrics metrics) {
        String label = expanded ? "收起" : "展开";
        int width = metrics.stringWidth(label) + 30;
        int height = 20;
        int x = getWidth() - PADDING - Math.max(width, HEADER_BUTTON_SPACE - 12);
        int y = (HEADER_HEIGHT - 8 - height) / 2 + 2;
        expandButtonBounds = new Rectangle(x, y, width, height);

        g.setColor(Theme.HIGHLIGHT);
        g.fillRoundRect(x, y, width, height, 7, 7);

        // 小三角：展开时指向右（意思是"推出去"），收起时指向左
        int arrowX = x + 9;
        int arrowY = y + height / 2;
        g.setColor(Theme.TEXT_MUTED);
        if (expanded) {
            g.fillPolygon(new int[] {arrowX, arrowX, arrowX + 6},
                    new int[] {arrowY - 5, arrowY + 5, arrowY}, 3);
        } else {
            g.fillPolygon(new int[] {arrowX + 6, arrowX + 6, arrowX},
                    new int[] {arrowY - 5, arrowY + 5, arrowY}, 3);
        }

        g.setColor(Theme.TEXT);
        g.drawString(label, arrowX + 14, arrowY + metrics.getAscent() / 2 - 1);
    }

    /** 只画看得见的那几行。 */
    private void paintCells(Graphics2D g) {
        updateMetrics(); // 宽度可能刚被拖动过，画之前先按当前宽度算一遍
        Rectangle clip = g.getClipBounds();
        int firstRow = Math.max(0, (clip.y - HEADER_HEIGHT) / rowHeight());
        int lastRow = (clip.y + clip.height - HEADER_HEIGHT) / rowHeight();

        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= session.size()) {
                    return;
                }
                paintCell(g, index, cellX(column), HEADER_HEIGHT + row * rowHeight());
            }
        }
    }

    private void paintCell(Graphics2D g, int index, int x, int y) {
        TargetEntry entry = session.entryAt(index);
        Path source = entry.source();
        BufferedImage image = thumbnails.get(source);

        g.setColor(Theme.CANVAS);
        g.fillRect(x, y, boxWidth, thumbnailHeight);

        if (image == null) {
            g.setColor(Theme.BORDER);
            g.drawRect(x, y, boxWidth - 1, thumbnailHeight - 1);
        } else {
            double scale = Math.min((double) boxWidth / image.getWidth(),
                    (double) thumbnailHeight / image.getHeight());
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            g.drawImage(image, x + (boxWidth - width) / 2, y + (thumbnailHeight - height) / 2,
                    width, height, null);
        }

        // 名字用编号规则按位置现算，而不是文件现在叫什么。
        // 已有的用普通色、新挑进来的用暖橙——一眼看出哪些还没落到磁盘上。
        g.setFont(Theme.FONT_SMALL);
        g.setColor(entry.origin() == Origin.EXISTING ? Theme.TEXT : Theme.ACCENT);
        g.drawString(session.nameAt(index), x, y + thumbnailHeight + 14);
    }

    private void paintEmptyHint(Graphics2D g) {
        g.setFont(Theme.FONT);
        g.setColor(Theme.TEXT_MUTED);
        String hint = session == null
                ? "先在「文件 → 选择工作区…」里指定一个目标文件夹"
                : "在下方素材条里点中一张照片，按空格键加入这里";
        FontMetrics metrics = g.getFontMetrics();
        for (String line : hint.split("\n")) {
            g.drawString(shorten(line, metrics, getWidth() - PADDING * 2), PADDING,
                    HEADER_HEIGHT + metrics.getAscent() + 6);
        }
    }

    // ─────────────────────────── 取图 ───────────────────────────

    private void requestVisibleThumbnails() {
        // 不可见时直接返回：浏览模式右栏和整理模式中间各有一块目标面板，
        // 同一时刻只有一块可见；不挡的话隐藏的那块会按整块尺寸白取一批图
        if (!isVisible() || session == null || session.isEmpty()) {
            return;
        }
        updateMetrics(); // 同上：算可见范围要依赖列数和行高
        Rectangle visible = getVisibleRect();
        if (visible.height <= 0) {
            visible = new Rectangle(0, 0, getWidth(), getHeight());
        }

        // 可见范围上下各多取一行，滚动时不至于先看到空白
        int firstRow = Math.max(0, (visible.y - HEADER_HEIGHT) / rowHeight() - 1);
        int lastRow = (visible.y + visible.height - HEADER_HEIGHT) / rowHeight() + 1;
        int first = firstRow * columns;
        int last = Math.min(session.size() - 1, (lastRow + 1) * columns - 1);

        Set<Path> keep = new HashSet<>();
        for (int index = first; index <= last; index++) {
            Path source = session.entryAt(index).source();
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
        thumbnails.put(source, image);
        repaint();
    }

    /**
     * 路径太长就在中间截断：头（盘符）和尾（文件夹名）都留着，中间用省略号省掉。
     *
     * <p>尾部最有用——认出"这是哪个文件夹"靠的就是最后一段，所以头最多只占
     * 三分之一的宽度，剩下的尽量都留给尾部。
     */
    static String shortenMiddle(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int headMax = Math.max(1, maxWidth / 3);
        int headEnd = 1;
        while (headEnd < text.length()
                && metrics.stringWidth(text.substring(0, headEnd + 1) + ellipsis) <= headMax) {
            headEnd++;
        }
        int tailStart = text.length() - 1;
        while (tailStart > headEnd
                && metrics.stringWidth(text.substring(0, headEnd) + ellipsis
                        + text.substring(tailStart - 1)) <= maxWidth) {
            tailStart--;
        }
        return text.substring(0, headEnd) + ellipsis + text.substring(tailStart);
    }

    /** 文字太长就截断成"前面…"。 */
    private static String shorten(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        int end = text.length();
        while (end > 1 && metrics.stringWidth(text.substring(0, end) + "…") > maxWidth) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}

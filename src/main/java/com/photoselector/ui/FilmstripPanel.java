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
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import com.photoselector.core.PhotoGroup;
import com.photoselector.ui.theme.Theme;

/**
 * 底部的素材照片条：按子文件夹分组，照片横向排成一条，可以左右滚动。
 *
 * <p>三件事合在一起看：
 * <ul>
 *   <li><b>分组</b>。一个子文件夹一组，组头写着名字和张数，点组头能收起；
 *       收起后只留一条窄边，名字还看得见。</li>
 *   <li><b>自绘 + 虚拟化</b>。一个档案夹可能有几千张照片，
 *       如果每张都摆一个 Swing 组件，光维护这些组件就够呛。这里只画看得见的那十几格。</li>
 *   <li><b>按需取图</b>。只为看得见的格子请求缩略图；滚远了就把手里的图丢掉
 *       （缩略图服务那边还有缓存，回头再取很快）。</li>
 * </ul>
 */
@SuppressWarnings("serial")
final class FilmstripPanel extends JPanel {

    /** 组与组之间、以及边缘留白的宽度。 */
    private static final int GAP = 8;

    /** 文件名那一行占的高度。 */
    private static final int CAPTION_HEIGHT = 18;

    /** 组头占的高度：箭头 + 文件夹名 + 张数。 */
    private static final int HEADER_HEIGHT = 24;

    /** 可见范围之外再多要几屏，滚动时不至于出现大片空白。 */
    private static final int KEEP_SCREENS = 3;

    private final ThumbnailLoader loader;

    /** 每一组的横向位置与宽度，每次绘制前按当前折叠状态算一遍。 */
    private record GroupBox(int index, int x, int width, boolean collapsed) {

        boolean containsX(int px) {
            return px >= x && px < x + width;
        }

        boolean inHeader(int py) {
            return py < HEADER_HEIGHT;
        }

        int right() {
            return x + width;
        }
    }

    private final Map<Path, BufferedImage> thumbnails = new HashMap<>();
    private final Set<Path> collapsed = new HashSet<>();
    private final AtomicInteger loaded = new AtomicInteger();

    private List<PhotoGroup> groups = List.of();
    private int cellWidth = 188;
    private int thumbnailHeight = 120;

    FilmstripPanel(ThumbnailLoader loader) {
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
                if (SwingUtilities.isLeftMouseButton(e)) {
                    handleClick(e.getX(), e.getY());
                }
            }
        });
    }

    /** 换一批照片。会清空手上已有的缩略图和折叠状态。 */
    void setGroups(List<PhotoGroup> newGroups, int height) {
        this.groups = List.copyOf(newGroups);
        this.thumbnailHeight = height;
        this.cellWidth = Math.round(height * 1.5f) + GAP;
        thumbnails.clear();
        collapsed.clear();
        loaded.set(0);
        loader.restart();

        updatePreferredSize();
        repaint();
        // 此刻组件可能还没被摆好位置，等这一次事件循环走完再算可见范围
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    /** 把某一组滚到视野里。目录树里点文件夹时用。 */
    void scrollToGroup(Path folder) {
        for (GroupBox box : computeLayout()) {
            if (groups.get(box.index()).folder().equals(folder)) {
                scrollRectToVisible(new Rectangle(box.x(), 0, 1, 1));
                return;
            }
        }
    }

    int groupCount() {
        return groups.size();
    }

    int photoCount() {
        int total = 0;
        for (PhotoGroup group : groups) {
            total += group.size();
        }
        return total;
    }

    /** 已经拿到缩略图的张数。测试和排查用。 */
    int loadedCount() {
        return loaded.get();
    }

    boolean isCollapsed(Path folder) {
        return collapsed.contains(folder);
    }

    /**
     * 把素材条装进一个配好滚动条的滚动面板。
     *
     * <p>这里挂了两个监听，缺一不可：
     * <ul>
     *   <li>视口<b>滚动</b>时要重算可见范围。</li>
     *   <li>视口<b>大小</b>变化时也要重算。这一条最容易漏：素材条自身比视口宽得多，
     *       窗口横向拉宽时它的尺寸根本没变、收不到自己的 resize 事件，
     *       新露出来的格子就会一直空着，直到用户滚一下才补上。</li>
     * </ul>
     */
    JScrollPane wrapInScrollPane() {
        JScrollPane scroll = new JScrollPane(this,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.getHorizontalScrollBar().setUnitIncrement(40); // 滚一格别只挪几个像素
        Theme.themeScrollBar(scroll.getHorizontalScrollBar());

        scroll.getViewport().addChangeListener(e -> requestVisibleThumbnails());
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                requestVisibleThumbnails();
            }
        });
        return scroll;
    }

    /** 一条素材条按给定缩略图高度算出来要有多少高（含组头、文件名和滚动条）。 */
    static int preferredHeight(int thumbnailHeight) {
        return HEADER_HEIGHT + GAP + thumbnailHeight + CAPTION_HEIGHT + 24;
    }

    /** 根据当前可见范围，为看得见的格子请求缩略图。滚动或改大小之后由外部调用。 */
    void requestVisibleThumbnails() {
        Rectangle visible = getVisibleRect();
        if (visible.width <= 0 || groups.isEmpty()) {
            return;
        }

        int margin = Math.max(cellWidth, visible.width * KEEP_SCREENS);
        int windowStart = visible.x - margin;
        int windowEnd = visible.x + visible.width + margin;

        Set<Path> keep = new HashSet<>();
        for (GroupBox box : computeLayout()) {
            if (box.collapsed() || box.right() < windowStart || box.x() > windowEnd) {
                continue;
            }
            List<Path> photos = groups.get(box.index()).photos();
            int first = Math.max(0, (windowStart - box.x()) / cellWidth);
            int last = Math.min(photos.size() - 1, (windowEnd - box.x()) / cellWidth);
            for (int i = first; i <= last; i++) {
                Path photo = photos.get(i);
                keep.add(photo);
                if (!thumbnails.containsKey(photo)) {
                    loader.load(photo, thumbnailHeight, image -> onThumbnail(photo, image));
                }
            }
        }
        forgetExcept(keep);
    }

    private void onThumbnail(Path photo, BufferedImage image) {
        thumbnails.put(photo, image);
        loaded.incrementAndGet();
        repaint();
    }

    /** 离可见范围太远的图从手里丢掉；缩略图服务那边还有缓存，再要很快。 */
    private void forgetExcept(Set<Path> keep) {
        if (!thumbnails.isEmpty() && thumbnails.keySet().removeIf(photo -> !keep.contains(photo))) {
            repaint();
        }
    }

    private void handleClick(int x, int y) {
        for (GroupBox box : computeLayout()) {
            if (box.containsX(x) && box.inHeader(y)) {
                Path folder = groups.get(box.index()).folder();
                if (!collapsed.remove(folder)) {
                    collapsed.add(folder);
                }
                updatePreferredSize();
                repaint();
                SwingUtilities.invokeLater(this::requestVisibleThumbnails);
                return;
            }
        }
    }

    private void updatePreferredSize() {
        int width = GAP;
        for (GroupBox box : computeLayout()) {
            width = Math.max(width, box.right() + GAP);
        }
        setPreferredSize(new Dimension(Math.max(1, width), preferredHeight(thumbnailHeight)));
        revalidate();
    }

    /** 按当前折叠状态，算出每一组摆在哪儿、有多宽。 */
    private List<GroupBox> computeLayout() {
        List<GroupBox> boxes = new ArrayList<>(groups.size());
        int x = GAP;
        for (int i = 0; i < groups.size(); i++) {
            PhotoGroup group = groups.get(i);
            boolean isCollapsed = collapsed.contains(group.folder());
            int width = isCollapsed
                    ? collapsedWidth(group)
                    : GAP + group.size() * cellWidth;
            boxes.add(new GroupBox(i, x, width, isCollapsed));
            x += width + GAP;
        }
        return boxes;
    }

    /** 收起时只留一条窄边，宽度刚好放得下组头那行字。 */
    private int collapsedWidth(PhotoGroup group) {
        FontMetrics metrics = getFontMetrics(Theme.FONT_SMALL);
        return 26 + metrics.stringWidth(headerText(group)) + 8;
    }

    private static String headerText(PhotoGroup group) {
        return group.name() + "  " + group.size() + " 张";
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (groups.isEmpty()) {
            paintEmptyHint(g);
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        Rectangle clip = g2.getClipBounds();
        for (GroupBox box : computeLayout()) {
            if (box.right() >= clip.x && box.x() <= clip.x + clip.width) {
                paintGroup(g2, box);
            }
        }
        g2.dispose();
    }

    private void paintGroup(Graphics2D g, GroupBox box) {
        PhotoGroup group = groups.get(box.index());

        paintChevron(g, box.x() + 6, 8, !box.collapsed());
        g.setFont(Theme.FONT_SMALL);
        g.setColor(box.collapsed() ? Theme.TEXT : Theme.TEXT_MUTED);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(shorten(headerText(group), metrics, box.width() - 30), box.x() + 22, 16);

        if (!box.collapsed()) {
            for (int i = 0; i < group.size(); i++) {
                paintCell(g, group, i, box.x() + i * cellWidth, HEADER_HEIGHT + 4);
            }
        }

        // 组与组之间画一条淡淡的分隔线，好看出"组"的边界
        g.setColor(Theme.BORDER);
        g.drawLine(box.right(), HEADER_HEIGHT, box.right(),
                getHeight() - 4);
    }

    private void paintCell(Graphics2D g, PhotoGroup group, int index, int x, int y) {
        Path photo = group.photos().get(index);
        int boxWidth = cellWidth - GAP;
        BufferedImage image = thumbnails.get(photo);

        if (image == null) {
            // 还没解出来：先占个位，图到了再重画
            g.setColor(Theme.CANVAS);
            g.fillRect(x, y, boxWidth, thumbnailHeight);
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

        g.setColor(Theme.TEXT_MUTED);
        g.setFont(Theme.FONT_SMALL);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(shorten(photo.getFileName().toString(), metrics, boxWidth),
                x, y + thumbnailHeight + 13);
    }

    /** 组头上那个表示展开 / 收起的三角。 */
    private void paintChevron(Graphics2D g, int x, int y, boolean pointingDown) {
        Path2D shape = new Path2D.Double();
        if (pointingDown) {
            shape.moveTo(x, y);
            shape.lineTo(x + 10, y);
            shape.lineTo(x + 5, y + 6);
        } else {
            shape.moveTo(x, y);
            shape.lineTo(x, y + 10);
            shape.lineTo(x + 6, y + 5);
        }
        shape.closePath();
        g.setColor(Theme.TEXT_MUTED);
        g.fill(shape);
    }

    private void paintEmptyHint(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(Theme.TEXT_MUTED);
        g2.setFont(Theme.FONT);
        String hint = "还没有打开素材文件夹 —— 文件 → 打开素材文件夹…";
        FontMetrics metrics = g2.getFontMetrics();
        g2.drawString(hint, Math.max(8, (getWidth() - metrics.stringWidth(hint)) / 2),
                Math.max(metrics.getAscent(), (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent()));
        g2.dispose();
    }

    /** 文字太长就截断成"前面…"，保证一行放得下。 */
    private static String shorten(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 1 && metrics.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }
}


package com.photoselector.ui;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
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

    /** 组内四周的留白：外圈画了边框之后，照片不贴着框线。 */
    private static final int PAD = 6;

    /** 收起时那张"文件夹缩略图"的尺寸：照片按"盖满"的方式取中间一块。 */
    private static final int COLLAPSED_PREVIEW_WIDTH = 132;
    private static final int COLLAPSED_PREVIEW_HEIGHT = 92;

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

    /** 当前点中的那张照片。加入目标文件夹时用的就是它。 */
    private Path currentPhoto;

    /** 当前照片变了的时候通知外部（大图视图据此换图）。 */
    private java.util.function.Consumer<Path> onCurrentPhotoChanged = photo -> { };

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
        currentPhoto = null; // 换了一批照片，之前点中的那张就不算数了
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

    /** 当前点中的照片；还没点过时是 null。 */
    Path currentPhoto() {
        return currentPhoto;
    }

    /** 当前照片的缩略图，给"跟着鼠标走的那张图"用；还没解好就是 null。 */
    BufferedImage currentThumbnail() {
        return currentPhoto == null ? null : thumbnails.get(currentPhoto);
    }

    /** 直接指定当前照片（测试用，等价于在那张照片上点一下）。 */
    void setCurrentPhoto(Path photo) {
        this.currentPhoto = photo;
        repaint();
        onCurrentPhotoChanged.accept(photo);
    }

    /** 点中或换片时通知外部。 */
    void setOnCurrentPhotoChanged(java.util.function.Consumer<Path> listener) {
        this.onCurrentPhotoChanged = listener == null ? photo -> { } : listener;
    }

    /**
     * 前后挪一张，并把那一格滚进视野——左右方向键过片用的就是它。
     *
     * <p>跨组连续：走到一组的最后一张，再往右就进下一组的第一张。
     */
    void moveCurrent(int delta) {
        List<Path> flat = new ArrayList<>();
        for (PhotoGroup group : groups) {
            flat.addAll(group.photos());
        }
        if (flat.isEmpty()) {
            return;
        }
        int index = currentPhoto == null ? -1 : flat.indexOf(currentPhoto);
        int next = index < 0 ? (delta >= 0 ? 0 : flat.size() - 1) : index + delta;
        if (next < 0 || next >= flat.size()) {
            return; // 到头了就不动，不绕回去
        }
        setCurrentPhoto(flat.get(next));
        scrollToPhoto(flat.get(next));
    }

    /** 把某张照片所在的那一格滚进视野。 */
    private void scrollToPhoto(Path photo) {
        for (GroupBox box : computeLayout()) {
            if (box.collapsed()) {
                continue;
            }
            int index = groups.get(box.index()).photos().indexOf(photo);
            if (index >= 0) {
                scrollRectToVisible(new Rectangle(box.x() + index * cellWidth, 0, cellWidth, 1));
                return;
            }
        }
    }

    /** 只留给测试用：每一组当前占的矩形（外圈边框的范围）。 */
    List<Rectangle> groupBoundsForTest() {
        List<Rectangle> bounds = new ArrayList<>();
        for (GroupBox box : computeLayout()) {
            bounds.add(new Rectangle(box.x(), 1, box.width(), contentBottom(box.collapsed()) - 1));
        }
        return bounds;
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
            if (box.right() < windowStart || box.x() > windowEnd) {
                continue;
            }
            List<Path> photos = groups.get(box.index()).photos();
            if (box.collapsed()) {
                // 收起的那一组也要图：缩略图卡上要显示第一张照片
                if (!photos.isEmpty()) {
                    Path first = photos.get(0);
                    keep.add(first);
                    if (!thumbnails.containsKey(first)) {
                        loader.load(first, thumbnailHeight, image -> onThumbnail(first, image));
                    }
                }
                continue;
            }
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
            if (box.containsX(x) && !box.collapsed()) {
                // 落在照片格里：算出是这一组的第几张，把它记为"当前"
                PhotoGroup group = groups.get(box.index());
                int index = (x - box.x()) / cellWidth;
                if (index >= 0 && index < group.size()) {
                    setCurrentPhoto(group.photos().get(index));
                }
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
                    ? collapsedWidth()
                    : PAD * 2 + group.size() * cellWidth - GAP;
            boxes.add(new GroupBox(i, x, width, isCollapsed));
            x += width + GAP;
        }
        return boxes;
    }

    /** 收起时留一张"文件夹缩略图"的宽度。 */
    private static int collapsedWidth() {
        return PAD * 2 + COLLAPSED_PREVIEW_WIDTH;
    }

    /** 一组下端那条边框线的高度：收起的贴着缩略图收口，展开的到文件名下面一点。 */
    private int contentBottom(boolean collapsed) {
        return collapsed
                ? HEADER_HEIGHT + 4 + COLLAPSED_PREVIEW_HEIGHT + PAD
                : HEADER_HEIGHT + 4 + thumbnailHeight + CAPTION_HEIGHT + PAD;
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
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON); // 圆角边框与文件夹缩略图都靠它

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

        // 每个文件夹一圈灰色细边框，先把"一组到哪儿为止"交代清楚
        g.setColor(Theme.GROUP_BORDER);
        g.drawRoundRect(box.x(), 1, box.width() - 1, contentBottom(box.collapsed()) - 1, 6, 6);

        paintChevron(g, box.x() + PAD + 2, 8, !box.collapsed());
        g.setFont(Theme.FONT_SMALL);
        g.setColor(box.collapsed() ? Theme.TEXT : Theme.TEXT_MUTED);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(shorten(headerText(group), metrics, box.width() - PAD * 2 - 24),
                box.x() + PAD + 18, 16);

        if (box.collapsed()) {
            paintCollapsedPreview(g, group, box);
        } else {
            for (int i = 0; i < group.size(); i++) {
                paintCell(g, group, i, box.x() + PAD + i * cellWidth, HEADER_HEIGHT + 4);
            }
        }
    }

    /**
     * 收起时的样子：一个文件夹形状，里面嵌着第一张照片。
     *
     * <p>照片按"盖满"的方式缩放再取中间一部分——就像 Windows 资源管理器里
     * 那个能预览内容的文件夹大图标，扫一眼就知道这组拍的是什么。
     */
    private void paintCollapsedPreview(Graphics2D g, PhotoGroup group, GroupBox box) {
        int x = box.x() + PAD;
        int y = HEADER_HEIGHT + 4;
        int width = COLLAPSED_PREVIEW_WIDTH;
        int height = COLLAPSED_PREVIEW_HEIGHT;
        int tabWidth = Math.max(30, width / 3);
        int tabHeight = 12;

        RoundRectangle2D tab = new RoundRectangle2D.Double(x, y, tabWidth, tabHeight * 2.0, 6, 6);
        RoundRectangle2D body = new RoundRectangle2D.Double(x, y + tabHeight, width, height - tabHeight, 6, 6);

        // 耳朵和身体先并成一个形状，再整体填一次、描一次。
        // 分别画两个矩形的话，身体的顶边会横在耳朵底下——那条灰线就是这么来的。
        Area folder = new Area(tab);
        folder.add(new Area(body));
        g.setColor(Theme.CANVAS);
        g.fill(folder);
        g.setColor(Theme.GROUP_BORDER);
        g.draw(folder);

        if (group.photos().isEmpty()) {
            return;
        }
        BufferedImage image = thumbnails.get(group.photos().get(0));
        if (image == null) {
            return; // 图还没解出来：先留一个空的文件夹轮廓
        }

        Shape clip = g.getClip();
        g.setClip(new RoundRectangle2D.Double(x + 3, y + tabHeight + 3,
                width - 6, height - tabHeight - 6, 4, 4));
        paintCover(g, image, x + 3, y + tabHeight + 3, width - 6, height - tabHeight - 6);
        g.setClip(clip);
    }

    /** 等比放大到刚好盖满给定方框，多出来的部分裁掉（居中）。 */
    private static void paintCover(Graphics2D g, BufferedImage image, int x, int y,
                                   int width, int height) {
        double scale = Math.max((double) width / image.getWidth(),
                (double) height / image.getHeight());
        int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
        g.drawImage(image, x + (width - drawWidth) / 2, y + (height - drawHeight) / 2,
                drawWidth, drawHeight, null);
    }

    private void paintCell(Graphics2D g, PhotoGroup group, int index, int x, int y) {
        Path photo = group.photos().get(index);
        int boxWidth = cellWidth - GAP;
        BufferedImage image = thumbnails.get(photo);
        boolean current = photo.equals(currentPhoto);

        // 当前选中用一块更亮的底色标出来。
        // 特意不用细边框：一像素的线在缩放显示下左右宽度会不一样。
        if (current) {
            g.setColor(Theme.HIGHLIGHT);
            g.fillRect(x - 2, y - 2, boxWidth + 4, thumbnailHeight + 4);
        }

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

        g.setColor(current ? Theme.TEXT : Theme.TEXT_MUTED);
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


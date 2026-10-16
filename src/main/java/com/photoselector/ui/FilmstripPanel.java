package com.photoselector.ui;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JPanel;
import javax.swing.JScrollPane;

import com.photoselector.ui.theme.Theme;

/**
 * 底部的素材照片条：照片横向排成一排，可以左右滚动。
 *
 * <p>关键做法是<b>自绘 + 虚拟化</b>。一个文件夹可能有几百张照片，
 * 如果每张都摆一个 Swing 组件，光维护这几百个组件就够呛。
 * 这里改成重写 {@code paintComponent} 自己画，而且<b>只画当前看得见的那十几格</b>——
 * 几百张和十几张的开销几乎一样。
 *
 * <p>取图也顺着这个思路：只为看得见的格子请求缩略图，
 * 滚远了就把手里那些图丢掉（缩略图服务那边还有缓存，回头再取很快）。
 */
@SuppressWarnings("serial")
final class FilmstripPanel extends JPanel {

    /** 格子之间、以及边缘留白的宽度。 */
    private static final int GAP = 8;

    /** 文件名那一行占的高度。 */
    private static final int CAPTION_HEIGHT = 18;

    /** 可见范围之外再多要几屏，滚动时不至于出现大片空白。 */
    private static final int KEEP_SCREENS = 3;

    private final ThumbnailLoader loader;

    /** 已经拿到的缩略图。键是文件路径。 */
    private final Map<Path, BufferedImage> thumbnails = new HashMap<>();

    /** 已到位的张数。单独记一份，方便测试从别的线程安全地读。 */
    private final AtomicInteger loaded = new AtomicInteger();

    private List<Path> photos = List.of();

    /** 每个格子的宽度；缩略图按高度定尺寸，宽度固定，居中摆放。 */
    private int cellWidth = 180;
    private int thumbnailHeight = 120;

    FilmstripPanel(ThumbnailLoader loader) {
        this.loader = loader;
        setBackground(Theme.PANEL);
        setOpaque(true);

        // 组件大小变了（窗口缩放、第一次显示）就重新算一遍该取哪些图
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                requestVisibleThumbnails();
            }
        });
    }

    /** 换一组照片。会清空手上已有的缩略图。 */
    void setPhotos(List<Path> newPhotos, int height) {
        this.photos = List.copyOf(newPhotos);
        this.thumbnailHeight = height;
        this.cellWidth = Math.round(height * 1.5f) + GAP;
        thumbnails.clear();
        loaded.set(0);
        loader.restart();

        int width = GAP + photos.size() * cellWidth;
        setPreferredSize(new Dimension(Math.max(1, width), cellHeight()));
        revalidate();
        repaint();

        // 此刻组件可能还没被摆好位置，等这一次事件循环走完再算可见范围
        javax.swing.SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    int photoCount() {
        return photos.size();
    }

    /** 已经拿到缩略图的张数。测试和排查用。 */
    int loadedCount() {
        return loaded.get();
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

    /** 根据当前可见范围，为看得见的格子请求缩略图。滚动时由外部调用。 */
    void requestVisibleThumbnails() {
        Rectangle visible = getVisibleRect();
        if (visible.width <= 0 || photos.isEmpty()) {
            return;
        }
        int margin = cellWidth * KEEP_SCREENS;
        int first = Math.max(0, (visible.x - margin) / cellWidth);
        int last = Math.min(photos.size() - 1, (visible.x + visible.width + margin) / cellWidth);

        for (int i = first; i <= last; i++) {
            Path photo = photos.get(i);
            if (!thumbnails.containsKey(photo)) {
                loader.load(photo, thumbnailHeight, image -> onThumbnail(photo, image));
            }
        }
        forgetDistant(first, last);
    }

    private void onThumbnail(Path photo, BufferedImage image) {
        thumbnails.put(photo, image);
        loaded.incrementAndGet();
        repaint();
    }

    /** 离可见范围太远的图从手里丢掉；缩略图服务那边还有缓存，再要很快。 */
    private void forgetDistant(int first, int last) {
        if (thumbnails.size() <= last - first + 1) {
            return;
        }
        Set<Path> keep = new HashSet<>(photos.subList(first, last + 1));
        if (thumbnails.keySet().removeIf(photo -> !keep.contains(photo))) {
            repaint();
        }
    }

    private int cellHeight() {
        return thumbnailHeight + CAPTION_HEIGHT + GAP;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (photos.isEmpty()) {
            paintEmptyHint(g);
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 只画裁剪区域内的那几格——这就是虚拟化的全部秘密
        Rectangle clip = g2.getClipBounds();
        int first = Math.max(0, (clip.x - GAP) / cellWidth);
        int last = Math.min(photos.size() - 1, (clip.x + clip.width) / cellWidth);
        for (int i = first; i <= last; i++) {
            paintCell(g2, i, GAP + i * cellWidth, GAP);
        }
        g2.dispose();
    }

    private void paintCell(Graphics2D g, int index, int x, int y) {
        Path photo = photos.get(index);
        int boxWidth = cellWidth - GAP;
        BufferedImage image = thumbnails.get(photo);

        if (image == null) {
            // 还没解出来：先占个位，图到了再重画
            g.setColor(Theme.CANVAS);
            g.fillRect(x, y, boxWidth, thumbnailHeight);
            g.setColor(Theme.BORDER);
            g.drawRect(x, y, boxWidth - 1, thumbnailHeight - 1);
        } else {
            // 等比缩放后居中放进格子里
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

    /** 文件名太长就截断成"前面…"，保证一行放得下。 */
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

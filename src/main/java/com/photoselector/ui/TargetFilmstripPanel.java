package com.photoselector.ui;

import java.awt.Component;
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
import java.util.function.Consumer;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import com.photoselector.core.Origin;
import com.photoselector.core.PhotoTransforms;
import com.photoselector.core.Session;
import com.photoselector.core.TargetEntry;
import com.photoselector.ui.theme.Painters;
import com.photoselector.ui.theme.TextStyles;
import com.photoselector.ui.theme.Theme;

/**
 * 整理模式底部的目标照片带。
 *
 * <p>和素材条一样是横向、自绘、虚拟化；但数据来源是目标序列而不是素材分组，
 * 所以单独实现。每格下面显示它保存后的编号，点击会同步选中并定位中间的大网格。
 */
@SuppressWarnings("serial")
final class TargetFilmstripPanel extends JPanel {

    private static final int THUMBNAIL_HEIGHT = 120;
    private static final int PAD = 8;
    private static final int GAP = 8;
    private static final int CAPTION_HEIGHT = 18;
    private static final int KEEP_SCREENS = 2;

    private final ThumbnailLoader loader;
    private final Map<Path, BufferedImage> thumbnails = new HashMap<>();

    private Session session;
    private Path selectedPhoto;
    private Consumer<Path> onPhotoChosen = photo -> { };

    TargetFilmstripPanel(ThumbnailLoader loader) {
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
                int index = indexAt(e.getX(), e.getY());
                if (index >= 0) {
                    Path photo = session.entryAt(index).source();
                    selectPhoto(photo);
                    onPhotoChosen.accept(photo);
                }
            }
        });
    }

    void setSession(Session newSession) {
        this.session = newSession;
        selectedPhoto = null;
        thumbnails.clear();
        updatePreferredSize();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    Session session() {
        return session;
    }

    /** 目标序列内容变化后调用；保留已解好的图，只更新宽度和可见请求。 */
    void refresh() {
        if (selectedPhoto != null
                && (session == null || session.indexOf(selectedPhoto) < 0)) {
            selectedPhoto = null;
        }
        updatePreferredSize();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    /** 外部文件内容变了：先丢掉旧缩略图再取。 */
    void reloadThumbnails() {
        thumbnails.clear();
        refresh();
    }

    void reloadThumbnail(Path photo) {
        if (photo != null && thumbnails.remove(photo) != null) {
            repaint();
        }
        if (photo != null) {
            SwingUtilities.invokeLater(this::requestVisibleThumbnails);
        }
    }

    void setOnPhotoChosen(Consumer<Path> listener) {
        this.onPhotoChosen = listener == null ? photo -> { } : listener;
    }

    void selectPhoto(Path photo) {
        selectedPhoto = photo;
        if (photo != null) {
            scrollToPhoto(photo);
        }
        repaint();
    }

    Path selectedPhoto() {
        return selectedPhoto;
    }

    /** 装进横向滚动面板；滚动或视口变宽时重新请求可见缩略图。 */
    JScrollPane wrapInScrollPane() {
        JScrollPane scroll = new JScrollPane(this,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        ScrollKeys.dropArrowKeys(scroll);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.getHorizontalScrollBar().setUnitIncrement(40);
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

    static int preferredHeight() {
        return FilmstripPanel.preferredHeight(THUMBNAIL_HEIGHT);
    }

    private int count() {
        return session == null ? 0 : session.size();
    }

    private int cellWidth() {
        return THUMBNAIL_HEIGHT + GAP;
    }

    private Rectangle cellRect(int index) {
        return new Rectangle(PAD + index * cellWidth(), PAD, THUMBNAIL_HEIGHT,
                THUMBNAIL_HEIGHT);
    }

    private int indexAt(int x, int y) {
        if (y < PAD || y > PAD + THUMBNAIL_HEIGHT + CAPTION_HEIGHT || session == null) {
            return -1;
        }
        int index = (x - PAD) / cellWidth();
        if (index < 0 || index >= count()) {
            return -1;
        }
        int within = (x - PAD) % cellWidth();
        return within < THUMBNAIL_HEIGHT ? index : -1;
    }

    private void updatePreferredSize() {
        int width = count() == 0
                ? 1
                : PAD * 2 + count() * THUMBNAIL_HEIGHT + (count() - 1) * GAP;
        setPreferredSize(new Dimension(Math.max(1, width), preferredHeight()));
        revalidate();
    }

    private void scrollToPhoto(Path photo) {
        int index = session == null ? -1 : session.indexOf(photo);
        Component parent = getParent();
        if (index >= 0 && parent instanceof JViewport) {
            Rectangle cell = cellRect(index);
            SwingUtilities.invokeLater(() ->
                    scrollRectToVisible(new Rectangle(cell.x, 0, cell.width, 1)));
        }
    }

    private void requestVisibleThumbnails() {
        if (!isVisible() || session == null || count() == 0) {
            return;
        }
        Rectangle visible = getVisibleRect();
        if (visible.width <= 0) {
            return;
        }
        int margin = Math.max(cellWidth(), visible.width * KEEP_SCREENS);
        int first = Math.max(0, (visible.x - margin - PAD) / cellWidth());
        int last = Math.min(count() - 1,
                (visible.x + visible.width + margin - PAD) / cellWidth());

        Set<Path> keep = new HashSet<>();
        for (int i = first; i <= last; i++) {
            Path photo = session.entryAt(i).source();
            keep.add(photo);
            if (!thumbnails.containsKey(photo)) {
                loader.load(photo, THUMBNAIL_HEIGHT, image -> {
                    BufferedImage display = session == null ? image
                            : PhotoTransforms.apply(image, session.editsOf(photo));
                    thumbnails.put(photo, display);
                    repaint();
                });
            }
        }
        if (thumbnails.keySet().removeIf(photo -> !keep.contains(photo))) {
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (session == null || count() == 0) {
            paintEmptyHint(g);
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Rectangle clip = g2.getClipBounds();
        int first = Math.max(0, (clip.x - PAD) / cellWidth());
        int last = Math.min(count() - 1, (clip.x + clip.width - PAD) / cellWidth());
        for (int i = first; i <= last; i++) {
            TargetEntry entry = session.entryAt(i);
            Rectangle cell = cellRect(i);
            boolean chosen = entry.source().equals(selectedPhoto);
            Painters.thumbnailCell(g2, cell, thumbnails.get(entry.source()),
                    chosen, null, false);

            g2.setFont(Theme.FONT_SMALL);
            g2.setColor(entry.origin() == Origin.EXISTING ? Theme.TEXT : Theme.ACCENT);
            FontMetrics metrics = g2.getFontMetrics();
            String name = session.nameAt(i);
            g2.drawString(TextStyles.end(name, metrics, THUMBNAIL_HEIGHT),
                    cell.x, cell.y + cell.height + 14);
        }
        g2.dispose();
    }

    private void paintEmptyHint(Graphics g) {
        String hint = session == null
                ? "还没有选择目标文件夹"
                : "目标文件夹里还没有照片";
        g.setFont(Theme.FONT_SMALL);
        g.setColor(Theme.TEXT_MUTED);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(hint, PAD,
                PAD + Math.max(metrics.getAscent(),
                        (THUMBNAIL_HEIGHT - metrics.getHeight()) / 2));
    }

    int loadedCount() {
        return thumbnails.size();
    }

    int photoCount() {
        return count();
    }

    int cellXForTest(int index) {
        return cellRect(index).x;
    }

    int cellWidthForTest() {
        return cellWidth();
    }

    boolean isLoaded(Path photo) {
        return thumbnails.containsKey(photo);
    }

    BufferedImage thumbnailOf(Path photo) {
        return thumbnails.get(photo);
    }

    int viewPositionXForTest() {
        return getParent() instanceof JViewport viewport ? viewport.getViewPosition().x : -1;
    }
}

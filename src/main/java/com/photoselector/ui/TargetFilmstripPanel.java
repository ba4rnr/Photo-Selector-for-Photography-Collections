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
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import com.photoselector.core.Origin;
import com.photoselector.core.Session;
import com.photoselector.core.TargetEntry;
import com.photoselector.io.ThumbnailService;
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
    private final Map<Path, ThumbnailTile> thumbnails = new HashMap<>();
    private long thumbnailGeneration;

    private Session session;
    private Path selectedPhoto;
    private Consumer<Path> onPhotoChosen = photo -> { };
    private BiConsumer<Path, Integer> onReorder = (photo, slot) -> { };
    private Point dragStart;
    private Path draggingPhoto;
    private int dropIndex = -1;

    TargetFilmstripPanel(ThumbnailLoader loader) {
        this.loader = loader;
        setBackground(Theme.PANEL);
        setOpaque(true);
        setFocusable(true);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                requestVisibleThumbnails();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                clearThumbnails();
                repaint();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                requestVisibleThumbnails();
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int index = indexAt(e.getX(), e.getY());
                if (index >= 0) {
                    requestFocusInWindow();
                    Path photo = session.entryAt(index).source();
                    selectPhoto(photo);
                    onPhotoChosen.accept(photo);
                    dragStart = e.getPoint();
                    draggingPhoto = photo;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart == null || draggingPhoto == null
                        || e.getPoint().distance(dragStart) <= 5) {
                    return;
                }
                dropIndex = dropIndexAt(e.getX());
                DragOut.showGhost(TargetFilmstripPanel.this, e.getPoint(),
                        thumbnailImage(draggingPhoto));
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Path moved = draggingPhoto;
                int slot = dropIndex;
                boolean dragged = dragStart != null
                        && e.getPoint().distance(dragStart) > 5;
                dragStart = null;
                draggingPhoto = null;
                dropIndex = -1;
                DragOut.hideGhost(TargetFilmstripPanel.this);
                repaint();
                if (dragged && moved != null && slot >= 0) {
                    onReorder.accept(moved, slot);
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        bindArrowKeys();
    }

    private void bindArrowKeys() {
        getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "target-strip-previous");
        getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "target-strip-next");
        getActionMap().put("target-strip-previous", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                moveSelection(-1);
            }
        });
        getActionMap().put("target-strip-next", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                moveSelection(1);
            }
        });
    }

    void setSession(Session newSession) {
        this.session = newSession;
        selectedPhoto = null;
        clearThumbnails();
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
        clearThumbnails();
        refresh();
    }

    void reloadThumbnail(Path photo) {
        if (photo != null && removeThumbnail(photo)) {
            repaint();
        }
        if (photo != null) {
            SwingUtilities.invokeLater(this::requestVisibleThumbnails);
        }
    }

    void setOnPhotoChosen(Consumer<Path> listener) {
        this.onPhotoChosen = listener == null ? photo -> { } : listener;
    }

    void setOnReorder(BiConsumer<Path, Integer> listener) {
        this.onReorder = listener == null ? (photo, slot) -> { } : listener;
    }

    void selectPhoto(Path photo) {
        selectPhoto(photo, false);
    }

    /**
     * 选中照片并把它的缩略图放到视口正中；只给启动恢复用。
     *
     * <p>普通点击和键盘选片仍走 {@link #selectPhoto(Path)} 的最小滚动，
     * 否则每换一张照片，整条底栏都会大幅移动。
     */
    void selectPhotoCentered(Path photo) {
        selectPhoto(photo, true);
    }

    private void selectPhoto(Path photo, boolean center) {
        selectedPhoto = photo;
        if (photo != null) {
            if (center) {
                centerPhoto(photo);
            } else {
                scrollToPhoto(photo);
            }
        }
        repaint();
    }

    Path selectedPhoto() {
        return selectedPhoto;
    }

    /** 用键盘在目标照片带里前后选择。 */
    private void moveSelection(int delta) {
        if (session == null || count() == 0) {
            return;
        }
        int current = selectedPhoto == null ? -1 : session.indexOf(selectedPhoto);
        int next = current < 0 ? (delta >= 0 ? 0 : count() - 1)
                : Math.max(0, Math.min(count() - 1, current + delta));
        if (next == current) {
            return;
        }
        Path photo = session.entryAt(next).source();
        selectPhoto(photo);
        onPhotoChosen.accept(photo);
    }

    private int dropIndexAt(int x) {
        if (count() == 0) {
            return 0;
        }
        int relative = x - PAD;
        if (relative <= 0) {
            return 0;
        }
        int index = relative / cellWidth();
        int within = relative % cellWidth();
        if (index >= count()) {
            return count();
        }
        return within < THUMBNAIL_HEIGHT / 2 ? index : index + 1;
    }

    private Rectangle dropHintRect() {
        if (dropIndex < 0 || count() == 0) {
            return null;
        }
        int x;
        if (dropIndex >= count()) {
            Rectangle last = cellRect(count() - 1);
            x = last.x + last.width + GAP / 2;
        } else {
            x = cellRect(dropIndex).x - GAP / 2;
        }
        return new Rectangle(x - 1, PAD, 3, THUMBNAIL_HEIGHT);
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

    /** 把目标照片的格子中心对准视口中心，再夹进合法的横向滚动范围。 */
    private void centerPhoto(Path photo) {
        int index = session == null ? -1 : session.indexOf(photo);
        if (index < 0 || !(getParent() instanceof JViewport viewport)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            Rectangle cell = cellRect(index);
            int extent = viewport.getExtentSize().width;
            int content = getPreferredSize().width;
            int center = cell.x + cell.width / 2;
            int x = Math.max(0, Math.min(Math.max(0, content - extent),
                    center - extent / 2));
            viewport.setViewPosition(new Point(x, viewport.getViewPosition().y));
        });
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
        long generation = ++thumbnailGeneration;
        for (int i = first; i <= last; i++) {
            Path photo = session.entryAt(i).source();
            keep.add(photo);
            if (!thumbnails.containsKey(photo)) {
                loader.loadLease(photo, THUMBNAIL_HEIGHT,
                        lease -> onThumbnail(photo, lease, generation));
            }
        }
        boolean removed = false;
        for (Path photo : Set.copyOf(thumbnails.keySet())) {
            if (!keep.contains(photo)) {
                removed |= removeThumbnail(photo);
            }
        }
        if (removed) {
            repaint();
        }
    }

    private void onThumbnail(Path photo, ThumbnailService.Lease lease,
                             long generation) {
        if (generation != thumbnailGeneration || session == null) {
            lease.close();
            return;
        }
        ThumbnailTile tile = ThumbnailTile.of(lease, session.editsOf(photo));
        ThumbnailTile previous = thumbnails.put(photo, tile);
        if (previous != null) {
            previous.close();
        }
        repaint();
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
            Painters.thumbnailCell(g2, cell, thumbnailImage(entry.source()),
                    chosen, null, false);

            g2.setFont(Theme.FONT_SMALL);
            g2.setColor(entry.origin() == Origin.EXISTING ? Theme.TEXT : Theme.ACCENT);
            FontMetrics metrics = g2.getFontMetrics();
            String name = session.nameAt(i);
            g2.drawString(TextStyles.end(name, metrics, THUMBNAIL_HEIGHT),
                    cell.x, cell.y + cell.height + 14);
        }
        Rectangle dropLine = dropHintRect();
        if (dropLine != null) {
            Painters.dropLine(g2, dropLine);
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

    int photoCenterInViewForTest(Path photo) {
        int index = session == null ? -1 : session.indexOf(photo);
        return index < 0 ? -1 : cellRect(index).x + THUMBNAIL_HEIGHT / 2;
    }

    boolean isLoaded(Path photo) {
        return thumbnails.containsKey(photo);
    }

    BufferedImage thumbnailOf(Path photo) {
        return thumbnailImage(photo);
    }

    private BufferedImage thumbnailImage(Path photo) {
        ThumbnailTile tile = thumbnails.get(photo);
        return tile == null ? null : tile.image();
    }

    private void clearThumbnails() {
        thumbnailGeneration++;
        for (ThumbnailTile tile : thumbnails.values()) {
            tile.close();
        }
        thumbnails.clear();
    }

    private boolean removeThumbnail(Path photo) {
        ThumbnailTile removed = thumbnails.remove(photo);
        if (removed != null) {
            removed.close();
            return true;
        }
        return false;
    }

    int viewPositionXForTest() {
        return getParent() instanceof JViewport viewport ? viewport.getViewPosition().x : -1;
    }

    Rectangle dropHintRectForTest() {
        return dropHintRect();
    }
}

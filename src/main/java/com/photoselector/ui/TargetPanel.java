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

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

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
 * <p>取图和素材条同一个套路：只画看得见的格子，只为它们请求缩略图。
 */
@SuppressWarnings("serial")
final class TargetPanel extends JPanel {

    private static final int PADDING = 8;
    private static final int GAP = 6;
    private static final int HEADER_HEIGHT = 26;
    private static final int CAPTION_HEIGHT = 16;

    private final ThumbnailLoader loader;
    private final Map<Path, BufferedImage> thumbnails = new HashMap<>();

    private Session session;
    private int thumbnailHeight = 88;
    private int cellWidth = 132;
    private int columns = 1;

    TargetPanel(ThumbnailLoader loader) {
        this.loader = loader;
        setBackground(Theme.PANEL);
        setOpaque(true);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                requestVisibleThumbnails();
            }
        });
    }

    /** 换一个中间商；传 null 表示还没选目标文件夹。 */
    void setSession(Session newSession) {
        this.session = newSession;
        thumbnails.clear();
        repaint();
        SwingUtilities.invokeLater(this::requestVisibleThumbnails);
    }

    Session session() {
        return session;
    }

    /** 序列变了（挑入、撤销、挪动）之后调用：重新取图并重画。 */
    void refresh() {
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

    /** 第 index 条将来在磁盘上叫什么名字。测试用。 */
    String nameAt(int index) {
        return session == null ? null : session.nameAt(index);
    }

    /** 一格里缩略图的高度。 */
    void setThumbnailHeight(int height) {
        this.thumbnailHeight = height;
        this.cellWidth = Math.round(height * 1.5f) + GAP;
        repaint();
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
        g.setColor(Theme.TEXT_MUTED);
        String text = session == null
                ? "目标文件夹：还没选"
                : "目标文件夹：" + session.targetFolder() + "   共 " + session.size() + " 张";
        g.drawString(shorten(text, g.getFontMetrics(), getWidth() - PADDING * 2), PADDING, 17);

        g.setColor(Theme.BORDER);
        g.fillRect(PADDING, HEADER_HEIGHT - 6, Math.max(0, getWidth() - PADDING * 2), 1);
    }

    /** 只画看得见的那几行——目标文件夹里可能有上千张。 */
    private void paintCells(Graphics2D g) {
        columns = Math.max(1, (getWidth() - PADDING * 2 + GAP) / (cellWidth + GAP));
        Rectangle clip = g.getClipBounds();
        int rowHeight = thumbnailHeight + CAPTION_HEIGHT + GAP;
        int firstRow = Math.max(0, (clip.y - HEADER_HEIGHT) / rowHeight);
        int lastRow = (clip.y + clip.height - HEADER_HEIGHT) / rowHeight;

        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= session.size()) {
                    return;
                }
                paintCell(g, index, PADDING + column * (cellWidth + GAP),
                        HEADER_HEIGHT + row * rowHeight);
            }
        }
    }

    private void paintCell(Graphics2D g, int index, int x, int y) {
        TargetEntry entry = session.entryAt(index);
        Path source = entry.source();
        int boxWidth = cellWidth - GAP;
        BufferedImage image = thumbnails.get(source);

        // 底色比面板稍亮一点，把每一格的范围划出来
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

        // 名字用编号规则按位置现算，而不是文件现在叫什么
        g.setFont(Theme.FONT_SMALL);
        g.setColor(entry.origin() == com.photoselector.core.Origin.EXISTING
                ? Theme.TEXT
                : Theme.ACCENT);
        g.drawString(session.nameAt(index), x, y + thumbnailHeight + 13);
    }

    private void paintEmptyHint(Graphics2D g) {
        g.setFont(Theme.FONT);
        g.setColor(Theme.TEXT_MUTED);
        String hint = session == null
                ? "先在「文件 → 选择工作区…」里指定一个目标文件夹"
                : "在下方素材条里点中一张照片，按空格键加入这里";
        FontMetrics metrics = g.getFontMetrics();
        int y = HEADER_HEIGHT + Math.max(metrics.getAscent(),
                (getHeight() - HEADER_HEIGHT - metrics.getHeight()) / 2);
        g.drawString(shorten(hint, metrics, getWidth() - PADDING * 2), PADDING, y);
    }

    // ─────────────────────────── 取图 ───────────────────────────

    private void requestVisibleThumbnails() {
        if (session == null || session.isEmpty()) {
            return;
        }
        columns = Math.max(1, (getWidth() - PADDING * 2 + GAP) / (cellWidth + GAP));
        Rectangle visible = getVisibleRect();
        if (visible.height <= 0) {
            visible = new Rectangle(0, 0, getWidth(), getHeight());
        }

        int rowHeight = thumbnailHeight + CAPTION_HEIGHT + GAP;
        int firstRow = Math.max(1, (visible.y - HEADER_HEIGHT) / rowHeight - 1);
        int lastRow = (visible.y + visible.height - HEADER_HEIGHT) / rowHeight + 1;

        Set<Path> keep = new HashSet<>();
        int first = (firstRow - 1) * columns;
        int last = Math.min(session.size() - 1, lastRow * columns + columns - 1);
        for (int index = Math.max(0, first); index <= last; index++) {
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

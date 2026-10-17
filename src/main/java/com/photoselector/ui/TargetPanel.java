package com.photoselector.ui;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import com.photoselector.core.Origin;
import com.photoselector.core.TargetEntry;
import com.photoselector.ui.theme.ChipGroup;
import com.photoselector.ui.theme.Painters;
import com.photoselector.ui.theme.TextStyles;
import com.photoselector.ui.theme.Theme;

/**
 * 右栏：目标文件夹。
 *
 * <p>它显示的是中间商里的<b>目标序列</b>——也就是"假如现在按保存，目标文件夹会变成什么样"。
 * 每一格下面写的是它<b>将来</b>在磁盘上的名字，由编号规则按当前位置现算；
 * 挪动位置，名字跟着变，不需要谁去手工同步。
 *
 * <p>几何、虚拟化、取图、落点白线这些都在基类 {@link ThumbnailGridPanel} 里；
 * 这里只负责"数据来自目标序列"和"每格怎么画"。
 */
@SuppressWarnings("serial")
final class TargetPanel extends ThumbnailGridPanel {

    /** 表头右侧给「展开」按钮预留的宽度；路径再长也不许占过来。 */
    private static final int HEADER_BUTTON_SPACE = 76;

    /** 表头右侧那个「展开 / 收起」按钮；绘制与命中共用这一组几何。 */
    private ChipGroup expandButton;

    /** 现在是不是展开状态，决定按钮上写"展开"还是"收起"。 */
    private boolean expanded;

    /** 点「展开 / 收起」时通知外部去切换模式。 */
    private Runnable onToggleExpanded = () -> { };

    /** 指针当前悬停的表头胶囊；-1 表示没悬停。 */
    private int hoveredHeaderChip = -1;

    /** 点中某一格时通知外部（大图视图据此显示它）。 */
    private java.util.function.Consumer<Path> onPhotoChosen = photo -> { };

    TargetPanel(ThumbnailLoader loader) {
        super(loader);
        java.awt.event.MouseAdapter hover = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                setHoveredHeaderChip(expandButton == null ? -1 : expandButton.indexAt(e.getPoint()));
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                setHoveredHeaderChip(-1);
            }
        };
        addMouseListener(hover);
        addMouseMotionListener(hover);
    }

    // ─────────────────────────── 基类要的"数据从哪来" ───────────────────────────

    @Override
    protected int itemCount() {
        return session == null ? 0 : session.size();
    }

    @Override
    protected Path sourceAt(int index) {
        return session.entryAt(index).source();
    }

    @Override
    protected void onCellActivated(int index) {
        onPhotoChosen.accept(session.entryAt(index).source());
    }

    @Override
    protected boolean onHeaderClick(Point point) {
        if (expandButton != null && expandButton.indexAt(point) == 0) {
            onToggleExpanded.run();
            return true;
        }
        return false;
    }

    /** 点中某一格时通知外部。 */
    void setOnPhotoChosen(java.util.function.Consumer<Path> listener) {
        this.onPhotoChosen = listener == null ? photo -> { } : listener;
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
        return expandButton == null ? null : expandButton.boundsAt(0);
    }

    /** 第 index 条将来在磁盘上叫什么名字。测试用。 */
    String nameAt(int index) {
        return session == null ? null : session.nameAt(index);
    }

    // ─────────────────────────── 每一格画什么 ───────────────────────────

    @Override
    protected void paintCellContent(Graphics2D g, int index, Rectangle cell,
                                    BufferedImage image, boolean chosen) {
        TargetEntry entry = session.entryAt(index);

        // 高亮底、占位框、等比居中——这一套规矩在组件库里（Painters.thumbnailCell），
        // 和素材条、垃圾桶用的是同一个画法
        Painters.thumbnailCell(g, cell, image, chosen, null, false);

        // 名字用编号规则按位置现算，而不是文件现在叫什么。
        // 已有的用普通色、新挑进来的用暖橙——一眼看出哪些还没落到磁盘上。
        g.setFont(Theme.FONT_SMALL);
        g.setColor(entry.origin() == Origin.EXISTING ? Theme.TEXT : Theme.ACCENT);
        g.drawString(session.nameAt(index), cell.x, cell.y + cell.height + 14);
    }

    @Override
    protected void paintEmptyHint(Graphics2D g) {
        g.setFont(Theme.FONT);
        g.setColor(Theme.TEXT_MUTED);
        String hint = session == null
                ? "先在「文件 → 选择工作区…」里指定一个目标文件夹"
                : "在下方素材条里点中一张照片，按空格键加入这里";
        FontMetrics metrics = g.getFontMetrics();
        for (String line : hint.split("\n")) {
            g.drawString(TextStyles.end(line, metrics, getWidth() - PADDING * 2), PADDING,
                    HEADER_HEIGHT + metrics.getAscent() + 6);
        }
    }

    // ─────────────────────────── 表头 ───────────────────────────

    @Override
    protected void paintHeader(Graphics2D g) {
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
        g.drawString(TextStyles.middle(path, metrics, rightEdge - countWidth - pathX), pathX, baseline);
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
        int y = (HEADER_HEIGHT - 8 - ChipGroup.HEIGHT) / 2 + 2;
        // 34 像素的横向留白比普通胶囊多，左边空出的位置正好放小三角。
        // 胶囊底、激活态和命中矩形都由 ChipGroup 统一管理。
        expandButton = ChipGroup.rightAligned(metrics, getWidth() - PADDING, y, 34, label);
        expandButton.paint(g, metrics, 0, hoveredHeaderChip);
        Rectangle bounds = expandButton.boundsAt(0);
        int arrowX = bounds.x + 9;
        int arrowY = bounds.y + bounds.height / 2;
        g.setColor(Theme.TEXT_MUTED);
        if (expanded) {
            g.fillPolygon(new int[] {arrowX, arrowX, arrowX + 6},
                    new int[] {arrowY - 5, arrowY + 5, arrowY}, 3);
        } else {
            g.fillPolygon(new int[] {arrowX + 6, arrowX + 6, arrowX},
                    new int[] {arrowY - 5, arrowY + 5, arrowY}, 3);
        }
    }

    private void setHoveredHeaderChip(int index) {
        if (hoveredHeaderChip != index) {
            hoveredHeaderChip = index;
            repaint();
        }
    }

    /** 路径太长就在中间截断。组件库里的通用实现，保留这个方法名给旧测试用。 */
    static String shortenMiddle(String text, FontMetrics metrics, int maxWidth) {
        return TextStyles.middle(text, metrics, maxWidth);
    }
}

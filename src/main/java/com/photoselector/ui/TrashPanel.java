package com.photoselector.ui;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import com.photoselector.core.TrashedItem;
import com.photoselector.ui.theme.Painters;
import com.photoselector.ui.theme.TextStyles;
import com.photoselector.ui.theme.Theme;

/**
 * 展开排序模式的右栏：垃圾桶。
 *
 * <p>里面装的只是<b>指针</b>——被移出目标序列的那些条目的引用，不是文件的复制品。
 * 每格角上的小号是它<b>原来</b>的编号，用来对照；拖回来之后编号会按新位置现算。
 *
 * <p>几何、虚拟化、取图、落点白线都在基类 {@link ThumbnailGridPanel} 里；
 * 这里只负责"数据来自垃圾桶"和"每格怎么画"。
 */
@SuppressWarnings("serial")
final class TrashPanel extends ThumbnailGridPanel {

    TrashPanel(ThumbnailLoader loader) {
        super(loader);
    }

    // ─────────────────────────── 基类要的"数据从哪来" ───────────────────────────

    @Override
    protected int itemCount() {
        return session == null ? 0 : session.trash().size();
    }

    @Override
    protected Path sourceAt(int index) {
        TrashedItem item = itemAt(index);
        return item == null ? null : item.entry().source();
    }

    /**
     * 第 index 格那条记录。
     *
     * <p>下标按"最近丢的排前面"数——界面上第一眼看到的，就是刚刚扔掉的。
     */
    TrashedItem itemAt(int index) {
        if (session == null || index < 0 || index >= session.trash().size()) {
            return null;
        }
        return session.trash().recentFirst().get(index);
    }

    // ─────────────────────────── 每一格画什么 ───────────────────────────

    @Override
    protected void paintCellContent(Graphics2D g, int index, Rectangle cell,
                                    BufferedImage image, boolean chosen) {
        TrashedItem item = itemAt(index);
        if (item == null) {
            return;
        }
        Path photo = item.entry().source();

        // 高亮底、占位框、等比居中都走组件库，和目标栏、素材条同一套画法
        Painters.thumbnailCell(g, cell, image, chosen, null, false);

        // 名字那一行：原来的编号 + 文件名——一眼看清"扔掉的是哪一张"。
        // 编号不存条目里（见 TargetEntry 的注释），所以按记下的原位置现算。
        String was = session.numbering().nameFor(item.originalIndex());
        String label = was + "  " + photo.getFileName();
        g.setFont(Theme.FONT_SMALL);
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(chosen ? Theme.TEXT : Theme.TEXT_MUTED);
        g.drawString(TextStyles.end(label, metrics, cell.width), cell.x, cell.y + cell.height + 13);
    }

    @Override
    protected void paintHeader(Graphics2D g) {
        g.setFont(Theme.FONT_SMALL);
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(Theme.TEXT);
        g.drawString("垃圾桶", PADDING, 22);
        String count = itemCount() == 0 ? "" : "共 " + itemCount() + " 张";
        g.setColor(Theme.TEXT_MUTED);
        g.drawString(count, PADDING + metrics.stringWidth("垃圾桶") + 10, 22);
    }

    @Override
    protected void paintEmptyHint(Graphics2D g) {
        g.setFont(Theme.FONT_SMALL);
        g.setColor(Theme.TEXT_MUTED);
        g.drawString("空空的。把不要的照片拖到这儿，或者拖到中间的大图区。",
                PADDING, HEADER_HEIGHT + 20);
    }
}

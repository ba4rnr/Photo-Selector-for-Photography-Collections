package com.photoselector.ui.theme;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * 组件库的画笔集：把几个面板反复在画的"小零件"收在一处。
 *
 * <p>收藏的标准不是"看起来像"，而是**规矩相同**：
 * <ul>
 *   <li>选中一格 → 四周外扩 2 像素的亮底（不用细边框：一像素线在缩放显示下宽度不稳）</li>
 *   <li>还没解出图 → 只画一圈暗边框占位（不铺黑底，露出面板的深灰）</li>
 *   <li>缩略图 → 等比缩进格子、居中，宁可留白也不裁切</li>
 *   <li>分组标题 → 标题 + 张数 + 右侧操作位 + 下方一条分隔线</li>
 * </ul>
 *
 * <p>颜色一律从 {@link Theme} 取，这里不写死任何一个色值。
 */
public final class Painters {

    /** 选中高亮比格子向外扩多少像素。比 1 像素的描边宽容，缩放显示下也稳定。 */
    public static final int SELECTION_PADDING = 2;

    /** 标题栏下方那条分隔线离标题基线多远。 */
    private static final int RULE_GAP = 7;

    private Painters() {
    }

    /**
     * 选中一格的亮底：在格子外面铺一圈更亮的颜色。
     *
     * <p>为什么不用描边：一像素的线在缩放/非整数倍显示下左右宽度会不一样，
     * 看起来像"歪了"；铺一块面就没有这个问题。
     */
    public static void selection(Graphics2D g, Rectangle cell) {
        g.setColor(Theme.HIGHLIGHT);
        g.fillRect(cell.x - SELECTION_PADDING, cell.y - SELECTION_PADDING,
                cell.width + SELECTION_PADDING * 2, cell.height + SELECTION_PADDING * 2);
    }

    /**
     * 缩略图还没解出来时的占位：只画一圈暗边框。
     *
     * <p>不铺底色是有意的——照片区就用面板自己的深灰。铺一层更深的颜色
     * （比如大图区的近黑）会让横照片的上下、竖照片的左右多出一条黑边，
     * 像给每张照片套了个黑框。
     */
    public static void placeholder(Graphics2D g, Rectangle cell) {
        g.setColor(Theme.BORDER);
        g.drawRect(cell.x, cell.y, cell.width - 1, cell.height - 1);
    }

    /**
     * 把图等比缩进一个格子并居中：宁可四周留白，也不裁切画面。
     *
     * <p>这个方法就是"缩略图怎么放"的唯一定义。素材条、目标栏、垃圾桶
     * 都调它，所以三处的留白规矩永远一致。图片为 null 时什么都不画。
     */
    public static void contain(Graphics2D g, BufferedImage image, Rectangle cell) {
        if (image == null) {
            return;
        }
        double scale = Math.min((double) cell.width / image.getWidth(),
                (double) cell.height / image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        g.drawImage(image, cell.x + (cell.width - width) / 2,
                cell.y + (cell.height - height) / 2, width, height, null);
    }

    /**
     * 缩略图格子的完整画法：选中亮底 → 占位框或照片 → 底下那行文字。
     *
     * <p>三个面板以前各写一遍（高亮、占位、等比缩放、画字），改一处就得改三处。
     * 现在规矩统一在这里；各面板只管"这一格是哪张照片、格子在哪"。
     *
     * @param caption 格子下方那行字（文件名或编号）；传 null 就只画图
     * @param muted   文字是否用次要颜色（没选中时）
     */
    public static void thumbnailCell(Graphics2D g, Rectangle cell, BufferedImage image,
                                     boolean selected, String caption, boolean muted) {
        if (selected) {
            selection(g, cell);
        }
        if (image == null) {
            placeholder(g, cell);
        } else {
            contain(g, image, cell);
        }
        if (caption != null && !caption.isEmpty()) {
            g.setFont(Theme.FONT_SMALL);
            FontMetrics metrics = g.getFontMetrics();
            g.setColor(selected || !muted ? Theme.TEXT : Theme.TEXT_MUTED);
            g.drawString(TextStyles.end(caption, metrics, cell.width), cell.x,
                    cell.y + cell.height + 13);
        }
    }

    /**
     * 面板标题栏：左边标题、紧跟张数，右边留一块"操作按钮"的位置，底下一条分隔线。
     *
     * <p>{@code actionSpace} 是右侧要给按钮留多宽；0 表示不留。
     * 标题太长时从末尾截断，张数永远贴着按钮左边、始终看得见。
     */
    public static void header(Graphics2D g, int width, String title, String count,
                              int padding, int baseline, int actionSpace, boolean showRule) {
        g.setFont(Theme.FONT_SMALL);
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(Theme.TEXT);
        g.drawString(title, padding, baseline);

        int countWidth = count == null || count.isEmpty() ? 0 : metrics.stringWidth(count) + 10;
        int countX = width - padding - actionSpace - countWidth + 10;
        if (count != null && !count.isEmpty()) {
            g.setColor(Theme.TEXT_MUTED);
            g.drawString(count, countX, baseline);
        }
        if (showRule) {
            g.setColor(Theme.BORDER);
            g.fillRect(padding, baseline + RULE_GAP, Math.max(0, width - padding * 2), 1);
        }
    }

    /**
     * 小胶囊按钮（「展开 / 收起」「适应 / 100%」这类）。
     *
     * <p>三种状态统一在这里定：激活时用亮一点的底 + 亮字，悬停时用中间色，
     * 平时就是面板底色。调用方自己存着这个矩形做命中判断——画和判必须用同一个矩形。
     */
    public static void chip(Graphics2D g, Rectangle bounds, FontMetrics metrics,
                            String label, boolean active, boolean hovered) {
        int textWidth = metrics.stringWidth(label);
        g.setColor(active ? Theme.HIGHLIGHT : hovered ? Theme.HOVER : Theme.PANEL);
        g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 7, 7);
        g.setColor(active ? Theme.TEXT : Theme.TEXT_MUTED);
        g.drawString(label, bounds.x + (bounds.width - textWidth) / 2,
                bounds.y + (bounds.height + metrics.getAscent()) / 2 - 2);
    }

    /** 拖动排序时那条白色落点线：宽 3 像素，贴在被插位置的缝上。 */
    public static void dropLine(Graphics2D g, Rectangle line) {
        g.setColor(Theme.DIVIDER_HINT);
        g.fillRect(line.x, line.y, line.width, line.height);
    }

    /** 面板中央的"什么都没有"提示。 */
    public static void emptyHint(Graphics2D g, int width, int y, String text) {
        g.setFont(Theme.FONT_SMALL);
        g.setColor(Theme.TEXT_MUTED);
        g.drawString(text, 12, y);
    }

    /**
     * 给拖动时跟着鼠标的那张图加一点半透明：一眼看出"这是跟着鼠标的预览"，
     * 而不是屏幕上多出来的一张真照片。
     */
    public static void ghostAlpha(Graphics2D g) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
    }

    /** 一条 1 像素的细边（分隔条、拖拽提示这类）。 */
    public static void hairline(Graphics2D g, Color color, int x, int y, int width, int height) {
        g.setColor(color);
        g.fillRect(x, y, width, height);
    }

    /** 把画笔恢复成默认的 1.4 像素圆头描边（画完虚线之类的之后用）。 */
    public static void normalStroke(Graphics2D g) {
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }
}

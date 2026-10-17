package com.photoselector.ui.theme;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一组横向排列的小胶囊按钮。
 *
 * <p>这里管三件必须一致的事：<b>位置怎么排、点击算不算命中、激活时怎么画</b>。
 * 如果调用方画的时候用一套矩形、点击的时候又自己量一遍，窗口缩放或字体变化后
 * 很容易出现"看得到按钮却点不中"的问题。因此每个胶囊的矩形只在这里算一次，
 * 绘制和命中判断共用同一个结果。
 *
 * <p>目前用于大图视图的「适应 / 100%」和目标文件夹的「展开 / 收起」。
 * 胶囊本身的外观仍由 {@link Painters#chip} 定义，这里不另造一套颜色或圆角。
 */
public final class ChipGroup {

    /** 胶囊高度。和表头里的其他控件保持同一档大小。 */
    public static final int HEIGHT = 20;

    /** 相邻两个胶囊之间的固定空隙。 */
    public static final int GAP = 6;

    /** 文字两侧一共多留的宽度，默认值让短标签看起来不局促。 */
    public static final int DEFAULT_CONTENT_PADDING = 18;

    /** 一个胶囊的标签与它最终落在屏幕上的矩形。 */
    private record Chip(String label, Rectangle bounds) {
    }

    private final List<Chip> chips;
    private final int left;
    private final int width;

    private ChipGroup(List<Chip> chips) {
        this.chips = List.copyOf(chips);
        if (chips.isEmpty()) {
            this.left = 0;
            this.width = 0;
            return;
        }
        Chip first = chips.getFirst();
        Chip last = chips.getLast();
        this.left = first.bounds().x;
        this.width = last.bounds().x + last.bounds().width - first.bounds().x;
    }

    /**
     * 从右边缘向左排一组胶囊，标签顺序仍按传入顺序从左到右显示。
     *
     * @param metrics    当前字体量尺；同一个组绘制与命中都要用同一个字体
     * @param rightEdge  最右一个胶囊的右边缘
     * @param top        这一行胶囊的上边缘
     * @param labels     从左到右的标签
     */
    public static ChipGroup rightAligned(FontMetrics metrics, int rightEdge, int top,
                                         String... labels) {
        return rightAligned(metrics, rightEdge, top, DEFAULT_CONTENT_PADDING, labels);
    }

    /**
     * 从右边缘向左排一组胶囊，并允许调整标签两侧的留白。
     *
     * <p>「展开 / 收起」左边要放一枚小三角，固定留白调大一些即可，
     * 不需要为它再造一种按钮。
     */
    public static ChipGroup rightAligned(FontMetrics metrics, int rightEdge, int top,
                                         int contentPadding, String... labels) {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(labels, "labels");

        List<Chip> reversed = new ArrayList<>(labels.length);
        int right = rightEdge;
        for (int i = labels.length - 1; i >= 0; i--) {
            String label = Objects.requireNonNull(labels[i], "label");
            int chipWidth = metrics.stringWidth(label) + Math.max(0, contentPadding);
            int x = right - chipWidth;
            reversed.add(new Chip(label, new Rectangle(x, top, chipWidth, HEIGHT)));
            right = x - GAP;
        }
        Collections.reverse(reversed);
        return new ChipGroup(reversed);
    }

    /** 第 {@code index} 个胶囊的矩形；越界返回 null。返回值是副本，调用方改不到内部状态。 */
    public Rectangle boundsAt(int index) {
        if (index < 0 || index >= chips.size()) {
            return null;
        }
        return new Rectangle(chips.get(index).bounds());
    }

    /** 第 {@code index} 个胶囊的标签；越界返回 null。 */
    public String labelAt(int index) {
        return index < 0 || index >= chips.size() ? null : chips.get(index).label();
    }

    /** 这一组一共有几个胶囊。 */
    public int size() {
        return chips.size();
    }

    /** 最左边胶囊的左边缘。 */
    public int left() {
        return left;
    }

    /** 这一组占用的总宽（包含组内空隙）。 */
    public int width() {
        return width;
    }

    /**
     * 某个点命中了第几个胶囊；落在空隙或组外时返回 -1。
     *
     * <p>命中和绘制用同一批 {@link #boundsAt} 矩形，所以外观改了，
     * 点击范围也会自动跟着改。
     */
    public int indexAt(Point point) {
        if (point == null) {
            return -1;
        }
        for (int i = 0; i < chips.size(); i++) {
            if (chips.get(i).bounds().contains(point)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 画整组胶囊。
     *
     * @param activeIndex  当前激活的胶囊；-1 表示都没激活
     * @param hoveredIndex 指针正悬停的胶囊；-1 表示没有悬停
     */
    public void paint(Graphics2D g, FontMetrics metrics, int activeIndex, int hoveredIndex) {
        for (int i = 0; i < chips.size(); i++) {
            Chip chip = chips.get(i);
            Painters.chip(g, chip.bounds(), metrics, chip.label(),
                    i == activeIndex, i == hoveredIndex);
        }
    }
}

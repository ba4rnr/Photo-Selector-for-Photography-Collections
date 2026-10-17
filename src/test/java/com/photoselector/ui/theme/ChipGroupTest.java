package com.photoselector.ui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import javax.swing.JLabel;

import org.junit.jupiter.api.Test;

class ChipGroupTest {

    @Test
    void 胶囊组从右往左排_标签顺序仍是从左到右() {
        Theme.install();
        FontMetrics metrics = new JLabel().getFontMetrics(Theme.FONT_SMALL);
        ChipGroup group = ChipGroup.rightAligned(metrics, 380, 7, "适应", "100%");

        Rectangle fit = group.boundsAt(0);
        Rectangle actual = group.boundsAt(1);
        assertEquals("适应", group.labelAt(0));
        assertEquals("100%", group.labelAt(1));
        assertEquals(380, actual.x + actual.width, "最右边的胶囊要正好贴住指定右边缘");
        assertEquals(actual.x - ChipGroup.GAP, fit.x + fit.width, "两枚按钮之间留固定空隙");
        assertEquals(fit.x, group.left());
        assertEquals(actual.x + actual.width - fit.x, group.width());
        assertEquals(7, fit.y);
        assertEquals(ChipGroup.HEIGHT, fit.height);
    }

    @Test
    void 绘制矩形就是命中矩形_空隙和外侧都不算命中() {
        Theme.install();
        FontMetrics metrics = new JLabel().getFontMetrics(Theme.FONT_SMALL);
        ChipGroup group = ChipGroup.rightAligned(metrics, 300, 4, "适应", "100%");
        Rectangle fit = group.boundsAt(0);
        Rectangle actual = group.boundsAt(1);

        assertEquals(0, group.indexAt(center(fit)));
        assertEquals(1, group.indexAt(center(actual)));
        assertEquals(-1, group.indexAt(new Point(fit.x + fit.width + ChipGroup.GAP / 2, fit.y + 5)),
                "两枚胶囊之间的空隙不属于任何按钮");
        assertEquals(-1, group.indexAt(new Point(actual.x + actual.width + 1, actual.y + 5)));
        assertEquals(-1, group.indexAt(null));

        Rectangle copy = group.boundsAt(0);
        copy.x = -999;
        assertEquals(0, group.indexAt(center(group.boundsAt(0))),
                "boundsAt 返回副本，外部改不到组里的真实命中区");
    }

    @Test
    void 激活和悬停状态用组件库的统一底色() {
        Theme.install();
        FontMetrics metrics = new JLabel().getFontMetrics(Theme.FONT_SMALL);
        ChipGroup group = ChipGroup.rightAligned(metrics, 300, 4, "适应", "100%");
        Rectangle actual = group.boundsAt(1);

        BufferedImage image = new BufferedImage(320, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Theme.PANEL);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        group.paint(g, metrics, 1, 0);
        g.dispose();

        assertEquals(Theme.HIGHLIGHT.getRGB(), image.getRGB(actual.x + 4, actual.y + 4),
                "激活的胶囊用 HIGHLIGHT");
        Rectangle fit = group.boundsAt(0);
        assertEquals(Theme.HOVER.getRGB(), image.getRGB(fit.x + 4, fit.y + 4),
                "悬停但未激活的胶囊用 HOVER");
    }

    @Test
    void 越界查询安全返回空值() {
        Theme.install();
        FontMetrics metrics = new JLabel().getFontMetrics(Theme.FONT_SMALL);
        ChipGroup group = ChipGroup.rightAligned(metrics, 100, 0);

        assertEquals(0, group.size());
        assertEquals(0, group.width());
        assertNull(group.boundsAt(0));
        assertNull(group.labelAt(0));
        assertEquals(-1, group.indexAt(new Point(10, 10)));
    }

    private static Point center(Rectangle bounds) {
        assertNotNull(bounds);
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }
}

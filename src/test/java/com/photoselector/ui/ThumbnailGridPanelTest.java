package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.io.ThumbnailService;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/**
 * 缩略图网格基类：不依赖"目标序列"也能用。
 *
 * <p>用一个假数据源直接测基类——如果能跑通，说明"几何 + 虚拟化 + 落点"
 * 确实抽干净了，而不是偷偷和目标面板绑在一起。
 */
class ThumbnailGridPanelTest {

    @TempDir
    Path tempDir;

    /** 最小的子类：一张图列表，别的什么都不做。 */
    private static final class FakeGrid extends ThumbnailGridPanel {

        private final List<Path> items;

        FakeGrid(ThumbnailLoader loader, List<Path> items) {
            super(loader);
            this.items = items;
        }

        @Override
        protected int itemCount() {
            return items.size();
        }

        @Override
        protected Path sourceAt(int index) {
            return items.get(index);
        }

        @Override
        protected void paintHeader(Graphics2D g) {
            // 给“粘性表头”测试留一个明显的色块。
            g.setColor(java.awt.Color.RED);
            g.fillRect(0, 0, Math.min(120, getWidth()), HEADER_HEIGHT);
        }

        @Override
        protected void paintCellContent(Graphics2D g, int index, Rectangle cell,
                                        BufferedImage image, boolean chosen) {
            com.photoselector.ui.theme.Painters.thumbnailCell(g, cell, image, chosen, null, false);
        }

        @Override
        protected void paintEmptyHint(Graphics2D g) {
            // 假面板不需要空提示
        }
    }

    @Test
    void 不依赖目标序列也能排格子和算落点() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        List<Path> items = List.of(
                TestImages.writeJpeg(tempDir.resolve("a.jpg"), 300, 200),
                TestImages.writeJpeg(tempDir.resolve("b.jpg"), 300, 200),
                TestImages.writeJpeg(tempDir.resolve("c.jpg"), 300, 200));
        FakeGrid grid = new FakeGrid(loader, items);

        SwingUtilities.invokeAndWait(() -> {
            grid.setSize(360, 500);
            grid.setSession(new com.photoselector.core.Session(
                    tempDir, com.photoselector.core.NumberingRule.DEFAULT));
            grid.doLayout();
        });

        assertEquals(2, grid.columnsForTest(), "360 宽应当排两列");
        int firstRowMid = 34 + grid.rowHeightForTest() / 2;
        assertEquals(0, grid.indexAt(new Point(grid.cellXForTest(0) + 10, firstRowMid)));
        assertEquals(1, grid.indexAt(new Point(grid.cellXForTest(1) + 10, firstRowMid)));

        // 第一格右半边 → 插到它后面（下标 1）；第二格左半边 → 也插在它前面（下标 1）
        assertEquals(1, grid.dropIndexAt(new Point(grid.cellRightForTest(0) - 5, firstRowMid)));
        assertEquals(1, grid.dropIndexAt(new Point(grid.cellXForTest(1) + 5, firstRowMid)));

        // 白线：落在第二张之前时，画在第二格左边那个缝
        SwingUtilities.invokeAndWait(() -> grid.setDropHint(1));
        Rectangle line = grid.dropHintRectForTest();
        assertTrue(line != null && line.x <= grid.cellXForTest(1),
                "白线应当画在要插入的那个缝上");

        loader.shutdown();
    }

    @Test
    void 改缩略图大小_列数跟着变_内容高也跟着变() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        List<Path> items = List.of(
                TestImages.writeJpeg(tempDir.resolve("a.jpg"), 300, 200),
                TestImages.writeJpeg(tempDir.resolve("b.jpg"), 300, 200));
        FakeGrid grid = new FakeGrid(loader, items);
        SwingUtilities.invokeAndWait(() -> {
            grid.setSize(360, 500);
            grid.setSession(new com.photoselector.core.Session(
                    tempDir, com.photoselector.core.NumberingRule.DEFAULT));
            grid.doLayout();
        });
        int before = grid.columnsForTest();

        SwingUtilities.invokeAndWait(() -> grid.setThumbnailWidth(210));

        assertTrue(grid.columnsForTest() < before, "缩略图变大之后一列放不下那么多了");
        assertEquals(210, grid.thumbnailWidth());
        loader.shutdown();
    }

    @Test
    void 滚下缩略图网格后_表头仍粘在视口顶部() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        java.util.ArrayList<Path> items = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(TestImages.writeJpeg(tempDir.resolve("sticky-" + i + ".jpg"), 300, 200));
        }
        FakeGrid grid = new FakeGrid(loader, items);
        javax.swing.JScrollPane scroll = grid.wrapInScrollPane();
        SwingUtilities.invokeAndWait(() -> {
            scroll.setSize(360, 300);
            grid.setSession(new com.photoselector.core.Session(
                    tempDir, com.photoselector.core.NumberingRule.DEFAULT));
            layoutTree(scroll);
            grid.dispatchEvent(new ComponentEvent(grid, ComponentEvent.COMPONENT_RESIZED));
        });

        int before = scroll.getViewport().getViewPosition().y;
        assertTrue(before >= 0);
        SwingUtilities.invokeAndWait(() -> scroll.getViewport().setViewPosition(new java.awt.Point(0, 300)));
        int scrolled = scroll.getViewport().getViewPosition().y;
        assertTrue(scrolled > 0, "前置条件：网格必须能向下滚动");

        javax.swing.JViewport viewport = scroll.getViewport();
        BufferedImage image = new BufferedImage(
                Math.max(1, viewport.getWidth()), Math.max(1, viewport.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        viewport.paint(g);
        g.dispose();

        assertEquals(java.awt.Color.RED.getRGB() & 0xFFFFFF,
                image.getRGB(3, 3) & 0xFFFFFF,
                "向下滚动后，视口顶部仍应是固定在顶部的表头");
        loader.shutdown();
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) {
                layoutTree(nested);
            }
        }
    }
}

package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.PhotoGroup;
import com.photoselector.io.ThumbnailService;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

class ViewerPanelTest {

    @TempDir
    Path tempDir;

    @Test
    void 选中一张素材之后大图会显示它的原图() throws Exception {
        Theme.install();
        Path folder = Files.createDirectories(tempDir.resolve("素材"));
        Path photo = TestImages.writeJpeg(folder.resolve("p0.jpg"), 900, 600);

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(760, 520);
            layoutTree(viewer);
        });

        // 一开始什么都没有
        assertTrue(!viewer.hasImage());
        BufferedImage blank = render(viewer);
        assertTrue(countColors(blank) > 1, "空状态也该有一行提示");

        SwingUtilities.invokeAndWait(() -> viewer.showPhoto(photo));
        waitUntil(viewer::hasImage, "原图解码完成");

        BufferedImage rendered = render(viewer);
        Path output = Path.of("target", "ui-viewer.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(rendered, "png", output.toFile());
        assertTrue(countColors(rendered) > 3, "应当真的画出照片");
        loader.shutdown();
    }

    @Test
    void 方向键前后过片_走到头就停下() throws Exception {
        Theme.install();
        Path folder = Files.createDirectories(tempDir.resolve("素材"));
        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            photos.add(TestImages.writeJpeg(folder.resolve("p" + i + ".jpg"), 300, 200));
        }

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(600, 200);
            filmstrip.setGroups(List.of(new PhotoGroup("一组", folder, photos)), 120);
            layoutTree(filmstrip);
        });

        filmstrip.setCurrentPhoto(photos.get(0));
        filmstrip.moveCurrent(1);
        assertEquals(photos.get(1), filmstrip.currentPhoto());
        filmstrip.moveCurrent(1);
        assertEquals(photos.get(2), filmstrip.currentPhoto());

        filmstrip.moveCurrent(1); // 已经是最后一张
        assertEquals(photos.get(2), filmstrip.currentPhoto(), "到头了就不该再动，也不该绕回第一张");

        filmstrip.moveCurrent(-1);
        assertEquals(photos.get(1), filmstrip.currentPhoto());
        loader.shutdown();
    }

    @Test
    void 适应窗口与一比一的切换() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 900, 600);

        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        // 默认不是"适应窗口"：照片按固定倍率显示，连着翻几张时比例一致，方便横向比较
        assertTrue(!viewer.isFitToWindow(), "默认不该是适应窗口");
        assertEquals(14, viewer.zoomPercent(), "默认放大倍率是 14%");

        SwingUtilities.invokeAndWait(viewer::actualSize);
        assertTrue(!viewer.isFitToWindow());
        assertEquals(100, viewer.zoomPercent());

        SwingUtilities.invokeAndWait(viewer::fitToWindow);
        assertTrue(viewer.isFitToWindow());
        loader.shutdown();
    }

    @Test
    void 滚轮在整百倍率之间跳() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 900, 600);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        // 从默认的 14% 起，第一格必须直接落到 100%——不然靠滚轮到不了 100%
        assertEquals(14, viewer.zoomPercent());
        SwingUtilities.invokeAndWait(() -> viewer.zoomStep(1));
        assertEquals(100, viewer.zoomPercent(), "第一格应当跳到 100%");
        viewer.zoomStep(1);
        assertEquals(200, viewer.zoomPercent());
        viewer.zoomStep(1);
        assertEquals(300, viewer.zoomPercent());

        viewer.zoomStep(-1);
        assertEquals(200, viewer.zoomPercent());
        viewer.zoomStep(-1);
        assertEquals(100, viewer.zoomPercent());
        viewer.zoomStep(-1);
        assertEquals(14, viewer.zoomPercent(), "再往下回到默认那档");
        viewer.zoomStep(-1);
        assertEquals(14, viewer.zoomPercent(), "已经在最低档，再滚也不动");

        // 从"适应窗口"算出来的零碎比例滚下去，也要先落到最近的那个整百档
        viewer.fitToWindow();
        int fitted = viewer.zoomPercent();
        assertTrue(fitted != 100, "900 宽的图塞进 400 宽的窗口，适应出来的不是整百档");
        viewer.zoomStep(1);
        assertEquals(100, viewer.zoomPercent(), "不管从哪儿来，往上第一格都该是 100%");
        loader.shutdown();
    }

    @Test
    void 图比窗口大时能平移_但拖不出视野() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 1200, 800);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");
        SwingUtilities.invokeAndWait(viewer::actualSize);

        SwingUtilities.invokeAndWait(() -> viewer.panBy(10000, 10000));
        int limitX = viewer.offsetXForTest();
        int limitY = viewer.offsetYForTest();
        assertTrue(limitX > 0 && limitY > 0, "图比窗口大，应当能平移");

        SwingUtilities.invokeAndWait(() -> viewer.panBy(10000, 10000));
        assertEquals(limitX, viewer.offsetXForTest(), "再往外拖也拖不动了");
        assertEquals(limitY, viewer.offsetYForTest());

        SwingUtilities.invokeAndWait(() -> viewer.panBy(-100000, -100000));
        assertEquals(-limitX, viewer.offsetXForTest(), "反方向是同一个界限");
        assertEquals(-limitY, viewer.offsetYForTest());

        // 换回适应窗口：图变小了，偏移归零居中
        SwingUtilities.invokeAndWait(viewer::fitToWindow);
        assertEquals(0, viewer.offsetXForTest());
        assertEquals(0, viewer.offsetYForTest());
        loader.shutdown();
    }

    @Test
    void 放大到超出窗口时_表头不会被照片盖住() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        ViewerPanel viewer = new ViewerPanel(loader);
        Path photo = TestImages.writeJpeg(
                Files.createDirectories(tempDir.resolve("素材")).resolve("p.jpg"), 1200, 800);
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(400, 300);
            viewer.showPhoto(photo);
        });
        waitUntil(viewer::hasImage, "原图");

        // 放大到远超窗口：连滚七格到 700%，照片必然溢出图片区
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 7; i++) {
                viewer.zoomStep(1);
            }
        });

        BufferedImage rendered = render(viewer);
        // 表头底部那条分隔线（HEADER_HEIGHT 26，往上 7 像素）应当还在。
        // 照片要是盖上来，这条线就没有了。
        int lineY = 19;
        int rgb = rendered.getRGB(rendered.getWidth() / 2, lineY) & 0xFFFFFF;
        assertEquals(Theme.BORDER.getRGB() & 0xFFFFFF, rgb,
                "放大之后表头那条分隔线应当还在，说明照片被限制在图片区里");
        loader.shutdown();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }

    private static BufferedImage render(javax.swing.JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        component.paint(g);
        g.dispose();
        return image;
    }

    private static int countColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                colors.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return colors.size();
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }
}

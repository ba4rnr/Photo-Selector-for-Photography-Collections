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

        assertTrue(viewer.isFitToWindow(), "默认是适应窗口");
        assertTrue(viewer.zoomPercent() < 100, "900 宽的图塞进 400 宽的窗口，比例必然小于 100%");

        SwingUtilities.invokeAndWait(viewer::actualSize);
        assertTrue(!viewer.isFitToWindow());
        assertEquals(100, viewer.zoomPercent());

        SwingUtilities.invokeAndWait(viewer::fitToWindow);
        assertTrue(viewer.isFitToWindow());
        loader.shutdown();
    }

    @Test
    void 滚轮缩放有上下限() throws Exception {
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

        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 40; i++) {
                viewer.zoomBy(2.0);
            }
        });
        assertTrue(viewer.zoomPercent() <= 800, "放大到 800% 就该停住");

        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 80; i++) {
                viewer.zoomBy(0.5);
            }
        });
        assertTrue(viewer.zoomPercent() >= 5, "缩小到 5% 就该停住");
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

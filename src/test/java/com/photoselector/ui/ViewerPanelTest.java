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

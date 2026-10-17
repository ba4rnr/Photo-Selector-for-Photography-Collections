package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.NumberingRule;
import com.photoselector.core.Session;
import com.photoselector.core.Workspace;
import com.photoselector.io.ThumbnailService;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

class TargetFilmstripPanelTest {

    @TempDir
    Path tempDir;

    @Test
    void 只加载可见目标照片并能滚到最后一张() throws Exception {
        Theme.install();
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            photos.add(TestImages.writeJpeg(target.resolve("p" + i + ".jpg"), 300, 200));
        }
        Session session = new Session(target, NumberingRule.DEFAULT);
        session.seedExisting(photos);

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        TargetFilmstripPanel strip = new TargetFilmstripPanel(loader);
        JScrollPane scroll = strip.wrapInScrollPane();
        SwingUtilities.invokeAndWait(() -> {
            scroll.setSize(500, TargetFilmstripPanel.preferredHeight());
            strip.setSession(session);
            layoutTree(scroll);
            strip.dispatchEvent(new java.awt.event.ComponentEvent(
                    strip, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
        });

        waitUntil(() -> strip.loadedCount() >= 4, "首屏缩略图");
        assertTrue(strip.loadedCount() < photos.size(),
                "30 张不该一次性全部解码，底部条也要虚拟化");

        Path last = photos.getLast();
        SwingUtilities.invokeAndWait(() -> strip.selectPhoto(last));
        waitUntil(() -> strip.isLoaded(last), "滚动后最后一张的缩略图");
        assertTrue(strip.viewPositionXForTest() > 0, "底部条应当横向滚到最后一张");

        BufferedImage image = new BufferedImage(scroll.getWidth(), scroll.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        scroll.paint(g);
        g.dispose();
        Path output = Path.of("target", "ui-target-strip.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
        loader.shutdown();
    }

    @Test
    void 点目标照片会通知外部() throws Exception {
        Theme.install();
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        List<Path> photos = List.of(
                TestImages.writeJpeg(target.resolve("a.jpg"), 300, 200),
                TestImages.writeJpeg(target.resolve("b.jpg"), 300, 200));
        Session session = new Session(target, NumberingRule.DEFAULT);
        session.seedExisting(photos);

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        TargetFilmstripPanel strip = new TargetFilmstripPanel(loader);
        JScrollPane scroll = strip.wrapInScrollPane();
        AtomicReference<Path> chosen = new AtomicReference<>();
        strip.setOnPhotoChosen(chosen::set);
        SwingUtilities.invokeAndWait(() -> {
            scroll.setSize(500, TargetFilmstripPanel.preferredHeight());
            strip.setSession(session);
            layoutTree(scroll);
            int x = strip.cellXForTest(1) + 20;
            strip.dispatchEvent(new MouseEvent(strip, MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                    x, 30, 1, false, MouseEvent.BUTTON1));
        });

        assertEquals(photos.get(1), chosen.get());
        assertEquals(photos.get(1), strip.selectedPhoto());
        loader.shutdown();
    }

    @Test
    void 主界面底栏点图会同步选中整理网格() throws Exception {
        Theme.install();
        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        List<Path> photos = List.of(
                TestImages.writeJpeg(target.resolve("a.jpg"), 300, 200),
                TestImages.writeJpeg(target.resolve("b.jpg"), 300, 200),
                TestImages.writeJpeg(target.resolve("c.jpg"), 300, 200));

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { });
        SwingUtilities.invokeAndWait(() -> {
            panel.applyWorkspace(new Workspace(source, Set.of(), target));
            panel.setSize(1200, 800);
            panel.setMode(ViewMode.ARRANGE);
            layoutTree(panel);
        });
        waitUntil(() -> panel.targetPanelForTest().session() != null,
                "目标序列就位");
        SwingUtilities.invokeAndWait(() -> {
            panel.setMode(ViewMode.ARRANGE);
            layoutTree(panel);
        });

        TargetFilmstripPanel strip = panel.targetFilmstripForTest();
        waitUntil(() -> strip.loadedCount() > 0, "底栏缩略图");
        Path expected = strip.session().entryAt(1).source();
        int x = strip.cellXForTest(1) + 20;
        SwingUtilities.invokeAndWait(() -> strip.dispatchEvent(new MouseEvent(strip,
                MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK, x, 30, 1, false, MouseEvent.BUTTON1)));

        assertEquals(expected, strip.selectedPhoto());
        assertEquals(expected, panel.arrangePanelForTest().selectedPhoto());
        assertEquals(expected, panel.viewerForTest().photo());
        panel.shutdown();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
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

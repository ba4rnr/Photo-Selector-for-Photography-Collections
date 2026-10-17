package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.NumberingRule;
import com.photoselector.core.PhotoGroup;
import com.photoselector.core.Session;
import com.photoselector.io.ThumbnailService;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/** 不同面板显示同一张缩略图时，像素对象只能有一份。 */
class ThumbnailSharedCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void 素材条和目标照片带共享同一份缩略图像素() throws Exception {
        Theme.install();
        Path photo = TestImages.writeJpeg(tempDir.resolve("shared.jpg"), 400, 300);
        Path folder = photo.getParent();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        TargetFilmstripPanel targetStrip = new TargetFilmstripPanel(loader);
        Session session = new Session(folder, NumberingRule.DEFAULT);
        session.add(photo);

        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(600, 200);
            filmstrip.setGroups(List.of(new PhotoGroup("一组", folder, List.of(photo))), 120);
            targetStrip.setSize(600, TargetFilmstripPanel.preferredHeight());
            targetStrip.setSession(session);
            layoutTree(filmstrip);
            layoutTree(targetStrip);
        });
        SwingUtilities.invokeAndWait(filmstrip::requestVisibleThumbnails);

        waitUntil(() -> filmstrip.thumbnailOf(photo) != null && targetStrip.isLoaded(photo),
                "两个面板都拿到缩略图");
        assertSame(filmstrip.thumbnailOf(photo), targetStrip.thumbnailOf(photo),
                "同一张缩略图不能在两个面板里各存一份像素");
        assertNotNull(filmstrip.thumbnailOf(photo));
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

    private static void layoutTree(Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }
}

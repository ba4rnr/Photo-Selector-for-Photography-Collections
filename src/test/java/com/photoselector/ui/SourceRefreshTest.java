package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.Workspace;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/** 外部改素材文件后，素材条和正在看的大图要自己跟上。 */
class SourceRefreshTest {

    @TempDir
    Path tempDir;

    @Test
    void 外部新增和覆盖素材照片会自动刷新() throws Exception {
        Theme.install();
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Path child = Files.createDirectories(root.resolve("001_拍摄"));
        Path first = TestImages.writeJpeg(child.resolve("a.jpg"), 300, 200);
        TestImages.writeJpeg(child.resolve("b.jpg"), 300, 200);

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        SwingUtilities.invokeAndWait(() -> {
            panel.setOnWorkspaceChanged(workspace -> { });
            panel.applyWorkspace(new Workspace(root, Set.of(), null));
        });

        try {
            waitUntil(() -> panel.filmstripGroupCount() == 1
                            && panel.filmstripForTest().photoCount() == 2,
                    "首次扫描");
            SwingUtilities.invokeAndWait(() -> panel.filmstripForTest().selectPhoto(first));
            waitUntil(() -> first.toAbsolutePath().normalize()
                            .equals(panel.viewerForTest().photo())
                            && panel.viewerForTest().hasImage(),
                    "首张照片显示");

            Path added = TestImages.writeJpeg(child.resolve("c.jpg"), 320, 240);
            waitUntil(() -> panel.filmstripForTest().photoCount() == 3,
                    "外部新增照片进入素材条");
            assertEquals(first.toAbsolutePath().normalize(),
                    panel.filmstripForTest().currentPhoto(),
                    "刷新不能丢掉正在看的照片");
            assertEquals(first.toAbsolutePath().normalize(),
                    panel.viewerForTest().photo());
            assertTrue(panel.filmstripForTest().containsPhoto(added));

            TestImages.writeJpeg(first, 520, 360);
            waitUntil(() -> panel.viewerForTest().imageSizeForTest() != null
                            && panel.viewerForTest().imageSizeForTest().width == 520
                            && panel.viewerForTest().imageSizeForTest().height == 360,
                    "同名文件被覆盖后大图重读");
        } finally {
            SwingUtilities.invokeAndWait(panel::shutdown);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }
}

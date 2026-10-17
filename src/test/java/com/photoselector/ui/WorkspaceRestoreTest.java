package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.Workspace;
import com.photoselector.io.AppSettings;
import com.photoselector.io.WorkspaceStore;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

class WorkspaceRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 重新打开工作区会恢复上次浏览的素材照片() throws Exception {
        Theme.install();
        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path folder = Files.createDirectories(source.resolve("001_拍摄"));
        TestImages.writeJpeg(folder.resolve("a.jpg"), 300, 200);
        Path last = TestImages.writeJpeg(folder.resolve("b.jpg"), 300, 200);
        Path target = Files.createDirectories(tempDir.resolve("成片"));

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { });
        SwingUtilities.invokeAndWait(() -> panel.applyWorkspace(
                new Workspace(source, Set.of(), target, last)));

        waitUntil(() -> last.toAbsolutePath().normalize()
                        .equals(panel.viewerForTest().photo())
                        && panel.viewerForTest().hasImage(),
                "恢复素材照片");
        assertEquals(last.toAbsolutePath().normalize(),
                panel.filmstripForTest().currentPhoto());
        panel.shutdown();
    }

    @Test
    void 上次看的是目标文件时也恢复并同步选中() throws Exception {
        Theme.install();
        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        TestImages.writeJpeg(target.resolve("a.jpg"), 300, 200);
        Path last = TestImages.writeJpeg(target.resolve("b.jpg"), 300, 200);

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { });
        SwingUtilities.invokeAndWait(() -> panel.applyWorkspace(
                new Workspace(source, Set.of(), target, last)));

        waitUntil(() -> last.toAbsolutePath().normalize()
                        .equals(panel.viewerForTest().photo())
                        && panel.viewerForTest().hasImage(),
                "恢复目标照片");
        assertEquals(last.toAbsolutePath().normalize(),
                panel.targetPanelForTest().selectedPhoto());
        assertEquals(last.toAbsolutePath().normalize(),
                panel.targetFilmstripForTest().selectedPhoto());
        panel.shutdown();
    }

    @Test
    void 正常关闭时会把最后浏览的照片写进配置() throws Exception {
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(),
                "创建主窗口需要图形环境");
        Theme.install();
        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path folder = Files.createDirectories(source.resolve("001_拍摄"));
        Path last = TestImages.writeJpeg(folder.resolve("b.jpg"), 300, 200);
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        WorkspaceStore store = new WorkspaceStore(tempDir.resolve("workspace.properties"));

        MainFrame[] frameRef = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame(store,
                    new AppSettings(tempDir.resolve("preferences.properties")),
                    tempDir.resolve("cache"));
            frameRef[0] = frame;
            MainPanel panel = (MainPanel) frame.getContentPane();
            panel.applyWorkspace(new Workspace(source, Set.of(), target, null));
        });

        MainFrame frame = frameRef[0];
        MainPanel panel = (MainPanel) frame.getContentPane();
        waitUntil(() -> panel.filmstripGroupCount() == 1, "素材条加载");
        SwingUtilities.invokeAndWait(() -> panel.filmstripForTest().selectPhoto(last));
        waitUntil(() -> last.toAbsolutePath().normalize()
                        .equals(panel.viewerForTest().photo())
                        && panel.viewerForTest().hasImage(),
                "最后浏览照片");

        SwingUtilities.invokeAndWait(panel::requestClose);
        waitUntil(() -> !frame.isDisplayable(), "窗口关闭");

        assertEquals(last.toAbsolutePath().normalize(),
                store.load().orElseThrow().lastViewedPhoto(),
                "关闭时必须把恢复指针落盘");
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

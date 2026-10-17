package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.io.AppSettings;
import com.photoselector.io.WorkspaceStore;
import com.photoselector.ui.theme.Theme;

/** 主窗口的"上次关在哪儿、多大"要能跨进程恢复。 */
class WindowStateRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void 重新打开主窗口时恢复上次的位置和大小() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "创建主窗口需要图形环境");
        Theme.install();

        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        Assumptions.assumeTrue(screen.width >= 800 && screen.height >= 600,
                "屏幕太小，放不下用于测试的窗口");
        Path config = tempDir.resolve("preferences.properties");
        AtomicReference<Rectangle> expected = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MainFrame first = new MainFrame(
                    new WorkspaceStore(tempDir.resolve("workspace.properties")),
                    new AppSettings(config),
                    tempDir.resolve("cache"));

            int width = Math.min(1000, screen.width - 160);
            int height = Math.min(700, screen.height - 160);
            Rectangle bounds = new Rectangle(screen.x + 80, screen.y + 60, width, height);
            expected.set(bounds);
            first.setBounds(bounds);

            // 没有打开工作区时不需要保存提示，会直接走真正的退出收尾。
            ((MainPanel) first.getContentPane()).requestClose();
        });

        AtomicReference<MainFrame> reopened = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> reopened.set(new MainFrame(
                new WorkspaceStore(tempDir.resolve("workspace.properties")),
                new AppSettings(config),
                tempDir.resolve("cache"))));

        try {
            assertEquals(expected.get(), reopened.get().getBounds(),
                    "第二次打开应当回到上次关闭时的位置和大小");
        } finally {
            SwingUtilities.invokeAndWait(reopened.get()::dispose);
        }
    }
}

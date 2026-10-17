package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
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

    @Test
    void 实际最大化之后底栏仍保持默认高度() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "创建主窗口需要图形环境");
        Theme.install();

        AtomicReference<MainFrame> frameRef = new AtomicReference<>();
        int[] before = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame(
                    new WorkspaceStore(tempDir.resolve("workspace.properties")),
                    new AppSettings(tempDir.resolve("preferences.properties")),
                    tempDir.resolve("cache"));
            frameRef.set(frame);
            frame.setSize(1100, 700);
            frame.setVisible(true);
            frame.validate();
            before[0] = ((MainPanel) frame.getContentPane()).bottomAreaHeightForTest();
        });

        MainFrame frame = frameRef.get();
        try {
            int normalHeight = frame.getHeight();
            SwingUtilities.invokeAndWait(() -> frame.setExtendedState(JFrame.MAXIMIZED_BOTH));
            long deadline = System.currentTimeMillis() + 5_000;
            while ((frame.getHeight() <= normalHeight
                    || (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(30);
            }
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(before[0],
                    ((MainPanel) frame.getContentPane()).bottomAreaHeightForTest(),
                    "最大化新增的高度应当分给大图，而不是塞进底栏");
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test
    void 最大化关闭时保存的是还原前的普通窗口大小() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "创建主窗口需要图形环境");
        Theme.install();

        Path config = tempDir.resolve("preferences.properties");
        AtomicReference<MainFrame> frameRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame(
                    new WorkspaceStore(tempDir.resolve("workspace.properties")),
                    new AppSettings(config),
                    tempDir.resolve("cache"));
            frameRef.set(frame);
            frame.setSize(1050, 720);
            frame.setVisible(true);
            frame.validate();
        });

        SwingUtilities.invokeAndWait(() -> { }); // 让普通边界的延迟捕获先跑完
        MainFrame frame = frameRef.get();
        Rectangle normal = frame.getBounds();
        SwingUtilities.invokeAndWait(() -> frame.setExtendedState(JFrame.MAXIMIZED_BOTH));
        long deadline = System.currentTimeMillis() + 5_000;
        while ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }

        SwingUtilities.invokeAndWait(((MainPanel) frame.getContentPane())::requestClose);
        deadline = System.currentTimeMillis() + 5_000;
        while (frame.isDisplayable() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }

        AppSettings.WindowState saved = new AppSettings(config).windowState().orElseThrow();
        assertTrue(saved.maximized(), "下次仍应以最大化打开");
        assertEquals(normal, new Rectangle(saved.x(), saved.y(), saved.width(), saved.height()),
                "保存的应是最大化前的普通边界，方便以后点“还原”");
    }
}

package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.Origin;
import com.photoselector.core.Workspace;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/**
 * 目标文件夹那一侧的端到端测试：打开工作区 → 目标文件夹里已有的照片成为底色 →
 * 从素材条挑一张进来 → 编号按位置现算。
 */
class TargetPanelTest {

    @TempDir
    Path tempDir;

    @Test
    void 目标文件夹里已有的照片变成底色_新挑的排在后面并按位置编号() throws Exception {
        Theme.install();

        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path shots = Files.createDirectories(source.resolve("001_拍摄"));
        Path picked = TestImages.writeJpeg(shots.resolve("p0.jpg"), 300, 200);
        TestImages.writeJpeg(shots.resolve("p1.jpg"), 300, 200);

        Path target = Files.createDirectories(tempDir.resolve("成片"));
        TestImages.writeJpeg(target.resolve("旧照片A.jpg"), 300, 200);
        TestImages.writeJpeg(target.resolve("旧照片B.jpg"), 300, 200);

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { }); // 别往真实配置里写
        SwingUtilities.invokeAndWait(() ->
                panel.applyWorkspace(new Workspace(source, Set.of(), target)));

        // 扫描和建中间商都在后台，等它们到位
        waitUntil(() -> panel.filmstripGroupCount() == 1, "素材条分组");
        waitUntil(() -> panel.targetPanelForTest().session() != null, "目标文件夹读进来");

        TargetPanel targetPanel = panel.targetPanelForTest();
        assertEquals(2, targetPanel.entryCount(), "目标文件夹里原有的两张成为底色");
        assertEquals("010", targetPanel.nameAt(0), "名字按位置现算，而不是文件现在叫什么");
        assertEquals("020", targetPanel.nameAt(1));
        assertEquals(Origin.EXISTING, targetPanel.session().entryAt(0).origin());

        // 在素材条里点中一张，然后按空格（等价于按下绑定好的那个动作）
        panel.filmstripForTest().setCurrentPhoto(picked);
        SwingUtilities.invokeAndWait(() -> panel.getActionMap().get("addToTarget")
                .actionPerformed(new ActionEvent(panel, 0, "addToTarget")));

        assertEquals(3, targetPanel.entryCount());
        assertEquals("030", targetPanel.nameAt(2), "新挑的接在末尾");
        assertEquals(Origin.PENDING, targetPanel.session().entryAt(2).origin(), "还没复制到磁盘");

        // 撤销把它拿掉
        SwingUtilities.invokeAndWait(() -> panel.getActionMap().get("undo")
                .actionPerformed(new ActionEvent(panel, 0, "undo")));
        assertEquals(2, targetPanel.entryCount());

        // 顺手把目标面板单独渲染一张图，方便看效果
        SwingUtilities.invokeAndWait(() -> {
            targetPanel.setSize(360, 480);
            layoutTree(targetPanel);
        });
        waitUntil(() -> targetPanel.loadedCount() >= 2, "目标面板的缩略图");
        render(targetPanel, Path.of("target", "ui-target-panel.png"));

        panel.shutdown();
    }

    @Test
    void 没选目标文件夹时挑不进去也不会崩() throws Exception {
        Theme.install();
        Path source = Files.createDirectories(tempDir.resolve("素材"));

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { });
        SwingUtilities.invokeAndWait(() ->
                panel.applyWorkspace(new Workspace(source, Set.of(), null)));

        panel.getActionMap().get("addToTarget")
                .actionPerformed(new ActionEvent(panel, 0, "addToTarget"));

        assertEquals(0, panel.targetPanelForTest().entryCount());
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

    private static void render(javax.swing.JComponent component, Path output) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                component.getWidth(), component.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        component.paint(g);
        g.dispose();
        Files.createDirectories(output.getParent());
        javax.imageio.ImageIO.write(image, "png", output.toFile());
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

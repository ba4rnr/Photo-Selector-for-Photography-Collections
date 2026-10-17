package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.EditState;
import com.photoselector.core.Workspace;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/**
 * 图像编辑：点工具栏上的"旋转 / 翻转"真正改变画面，并且能撤销、能重置。
 */
class ImageEditTest {

    @TempDir
    Path tempDir;

    private MainPanel panelWithOnePhoto() throws Exception {
        Theme.install();
        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path shots = Files.createDirectories(source.resolve("001_拍摄"));
        TestImages.writeJpeg(shots.resolve("p0.jpg"), 300, 200);
        Path target = Files.createDirectories(tempDir.resolve("成片"));

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { });
        SwingUtilities.invokeAndWait(() ->
                panel.applyWorkspace(new Workspace(source, Set.of(), target)));
        waitUntil(() -> panel.filmstripGroupCount() == 1
                && panel.targetPanelForTest().session() != null, "扫描完成");

        // 点中素材条里那张，让大图显示它
        FilmstripPanel strip = panel.filmstripForTest();
        strip.setCurrentPhoto(strip.photoAtForTest(0));
        waitUntil(() -> panel.viewerForTest().imageSizeForTest() != null, "大图解码出来");
        return panel;
    }

    @Test
    void 点旋转_画面转90度() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        ViewerPanel viewer = panel.viewerForTest();
        Dimension before = viewer.imageSizeForTest();

        SwingUtilities.invokeAndWait(() ->
                panel.toolBar().toolButton(CursorTool.ROTATE).doClick(0));

        Dimension after = viewer.imageSizeForTest();
        assertEquals(before.height, after.width, "转 90° 后宽高互换");
        assertEquals(before.width, after.height);
        assertEquals(1, viewer.edits().quarters());
        assertEquals(CursorTool.ROTATE, panel.toolBar().activeTool(), "按钮进入旋转状态");
        panel.shutdown();
    }

    @Test
    void 再点一次旋转_再转90度() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        ViewerPanel viewer = panel.viewerForTest();

        SwingUtilities.invokeAndWait(() -> {
            panel.toolBar().toolButton(CursorTool.ROTATE).doClick(0);
            panel.toolBar().toolButton(CursorTool.ROTATE).doClick(0);
        });

        assertEquals(2, viewer.edits().quarters(), "同一颗按钮点两次就是转两个直角");
        panel.shutdown();
    }

    @Test
    void 翻转只影响画面_不动文件() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        Path photo = panel.viewerForTest().photo();
        long sizeBefore = Files.size(photo);

        SwingUtilities.invokeAndWait(panel::flipCurrentPhotoHorizontally);

        assertEquals(new EditState(2, true), panel.viewerForTest().edits(),
                "左右翻按「转 180° + 上下翻」记");
        assertEquals(sizeBefore, Files.size(photo), "只是虚拟编辑，磁盘上的文件一个字节都没动");
        panel.shutdown();
    }

    @Test
    void 撤销编辑_画面跟着还原() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        ViewerPanel viewer = panel.viewerForTest();
        Dimension before = viewer.imageSizeForTest();

        SwingUtilities.invokeAndWait(() -> panel.rotateCurrentPhoto(true));
        assertEquals(1, viewer.edits().quarters());

        SwingUtilities.invokeAndWait(panel::undo);
        assertEquals(0, viewer.edits().quarters(), "撤销之后编辑状态回到原样");
        assertEquals(before, viewer.imageSizeForTest(), "画面也转回来了");
        panel.shutdown();
    }

    @Test
    void 重置_清空这张照片的全部编辑() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        ViewerPanel viewer = panel.viewerForTest();

        SwingUtilities.invokeAndWait(() -> {
            panel.rotateCurrentPhoto(true);
            panel.flipCurrentPhotoHorizontally();
        });
        assertTrue(!viewer.edits().isNone(), "先造一点编辑");

        SwingUtilities.invokeAndWait(() -> panel.toolBar().resetButton().doClick(0));

        assertTrue(viewer.edits().isNone(), "重置之后回到原样");
        panel.shutdown();
    }

    @Test
    void 点任何功能按钮都不会改变鼠标形状() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        ViewerPanel viewer = panel.viewerForTest();

        for (CursorTool tool : CursorTool.values()) {
            SwingUtilities.invokeAndWait(() ->
                    panel.toolBar().setActiveToolForTest(tool));
            assertEquals(java.awt.Cursor.DEFAULT_CURSOR, viewer.getCursor().getType(),
                    tool.label() + " 按钮也不该再换掉系统默认光标");
        }
        panel.shutdown();
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }
}

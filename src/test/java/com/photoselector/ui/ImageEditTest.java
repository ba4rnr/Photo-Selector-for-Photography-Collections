package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.EditState;
import com.photoselector.core.CropRect;
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
        TestImages.writeJpeg(target.resolve("旧.jpg"), 300, 200);

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
        assertEquals(CursorTool.SELECT, panel.toolBar().activeTool(),
                "动作完成后应当回到选择，主视图还能继续拖照片");
        assertTrue(panel.toolBar().undoButton().isEnabled(),
                "旋转压入历史后，撤销按钮应当立刻可用");
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
    void 旋转后即使原来不是适应窗口_也自动收进窗口() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        ViewerPanel viewer = panel.viewerForTest();

        SwingUtilities.invokeAndWait(viewer::actualSize);
        assertTrue(!viewer.isFitToWindow(), "先让这张处在固定倍率");

        SwingUtilities.invokeAndWait(() -> panel.rotateCurrentPhoto(true));

        assertTrue(viewer.isFitToWindow(),
                "旋转交换宽高后，应自动回到适应窗口，不能只看到画面的一角");
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

    @Test
    void 点右栏照片后_编辑的是眼前这一张() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        TargetPanel target = panel.targetPanelForTest();
        Path sourcePhoto = panel.filmstripForTest().currentPhoto();

        SwingUtilities.invokeAndWait(() -> {
            target.setSize(360, 480);
            layoutTree(target);
        });
        paint(target);
        Point firstCell = new Point(30, 60);
        assertTrue(target.indexAt(firstCell) >= 0, "目标栏第一张应当可点");
        Path targetPhoto = target.session().entryAt(target.indexAt(firstCell)).source();

        SwingUtilities.invokeAndWait(() -> target.dispatchEvent(new MouseEvent(target,
                MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                firstCell.x, firstCell.y, 1, false, MouseEvent.BUTTON1)));
        waitUntil(() -> targetPhoto.equals(panel.viewerForTest().photo()), "大图切到目标照片");

        SwingUtilities.invokeAndWait(() -> panel.rotateCurrentPhoto(true));

        assertTrue(!target.session().editsOf(targetPhoto).isNone(),
                "旋转应当记在大图正在看的这张目标照片上");
        assertTrue(target.session().editsOf(sourcePhoto).isNone(),
                "素材条里上一张不能被误编辑");
        panel.shutdown();
    }

    @Test
    void 工具栏裁切完成后进入中间商历史_重置和撤销都能处理() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        ViewerPanel viewer = panel.viewerForTest();
        Path photo = viewer.photo();
        SwingUtilities.invokeAndWait(() -> {
            viewer.setSize(600, 500);
            layoutTree(viewer);
            panel.beginCrop();
        });
        assertTrue(viewer.isCropModeForTest(), "菜单 / 按钮进入裁切模式");

        java.awt.Rectangle image = viewer.imageDrawRectForTest();
        Point from = new Point(image.x + image.width / 4, image.y + image.height / 4);
        Point to = new Point(image.x + image.width * 3 / 4,
                image.y + image.height * 3 / 4);
        SwingUtilities.invokeAndWait(() -> {
            sendMouse(viewer, from, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1_DOWN_MASK);
            sendMouse(viewer, to, MouseEvent.MOUSE_DRAGGED, MouseEvent.BUTTON1_DOWN_MASK);
            sendMouse(viewer, to, MouseEvent.MOUSE_RELEASED, 0);
        });

        CropRect crop = panel.targetPanelForTest().session().editsOf(photo).crop();
        assertTrue(crop != null, "裁切要记在中间商里");
        assertEquals(CursorTool.SELECT, panel.toolBar().activeTool(),
                "完成裁切后回到选择工具");

        SwingUtilities.invokeAndWait(panel::undo);
        assertTrue(panel.targetPanelForTest().session().editsOf(photo).crop() == null,
                "撤销裁切应当恢复整张画面");
        panel.shutdown();
    }

    @Test
    void 工具栏网格按钮切换三分辅助线() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        ViewerPanel viewer = panel.viewerForTest();

        SwingUtilities.invokeAndWait(() -> panel.toolBar().gridButton().doClick(0));
        assertTrue(viewer.isGridVisible(), "第一次点击打开网格");
        SwingUtilities.invokeAndWait(() -> panel.toolBar().gridButton().doClick(0));
        assertTrue(!viewer.isGridVisible(), "再点一次关闭网格");
        panel.shutdown();
    }

    @Test
    void 保存后大图切到最终文件_下一轮编辑不会落到旧路径() throws Exception {
        MainPanel panel = panelWithOnePhoto();
        Path source = panel.viewerForTest().photo();
        panel.addToTarget(source);
        SwingUtilities.invokeAndWait(() -> panel.rotateCurrentPhoto(true));

        SwingUtilities.invokeAndWait(panel::save);
        Path finalPhoto = panel.targetPanelForTest().session().targetFolder().resolve("020.jpg");
        waitUntil(() -> finalPhoto.equals(panel.viewerForTest().photo())
                        && panel.viewerForTest().hasImage(), "保存后的大图重新载入");

        assertTrue(panel.viewerForTest().edits().isNone(),
                "已经烧进文件的编辑不能继续挂在虚拟编辑上");
        SwingUtilities.invokeAndWait(() -> panel.rotateCurrentPhoto(true));
        assertTrue(!panel.targetPanelForTest().session().editsOf(finalPhoto).isNone(),
                "保存后的下一次编辑必须记在最终文件路径上");
        assertTrue(panel.targetPanelForTest().session().editsOf(source).isNone(),
                "不能继续编辑保存前的旧路径");
        panel.shutdown();
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }

    private static void paint(javax.swing.JComponent component) {
        BufferedImage image = new BufferedImage(Math.max(1, component.getWidth()),
                Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        component.paint(g);
        g.dispose();
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) {
                layoutTree(nested);
            }
        }
    }

    private static void sendMouse(ViewerPanel viewer, Point point, int id, int modifiers) {
        int button = id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1;
        viewer.dispatchEvent(new MouseEvent(viewer, id, System.currentTimeMillis(), modifiers,
                point.x, point.y, id == MouseEvent.MOUSE_PRESSED ? 1 : 0, false, button));
    }
}

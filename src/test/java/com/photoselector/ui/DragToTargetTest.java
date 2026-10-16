package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.Workspace;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/**
 * 选图模式的拖动与点选：从素材拖到目标面板、点目标面板里的照片让大图显示它。
 */
class DragToTargetTest {

    @TempDir
    Path tempDir;

    private MainPanel panelWithPhotos() throws Exception {
        Theme.install();
        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path shots = Files.createDirectories(source.resolve("001_拍摄"));
        for (int i = 0; i < 3; i++) {
            TestImages.writeJpeg(shots.resolve("p" + i + ".jpg"), 300, 200);
        }
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        TestImages.writeJpeg(target.resolve("旧A.jpg"), 300, 200);
        TestImages.writeJpeg(target.resolve("旧B.jpg"), 300, 200);

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { }); // 别往真实配置里写
        SwingUtilities.invokeAndWait(() ->
                panel.applyWorkspace(new Workspace(source, Set.of(), target)));
        waitUntil(() -> panel.targetPanelForTest().session() != null
                && panel.filmstripGroupCount() == 1, "扫描完成");
        return panel;
    }

    @Test
    void 拖到目标面板上就加进去_和空格键走同一条路() throws Exception {
        MainPanel panel = panelWithPhotos();
        TargetPanel target = panel.targetPanelForTest();
        Path dropped = Files.walk(tempDir.resolve("素材"))
                .filter(path -> path.getFileName().toString().equals("p1.jpg"))
                .findFirst().orElseThrow();

        assertEquals(2, target.entryCount(), "目标文件夹里原有的两张成了底色");
        panel.addToTarget(dropped); // 拖过去和按空格，落到的是同一个方法
        assertEquals(3, target.entryCount());
        assertEquals("030", target.nameAt(2));
        panel.shutdown();
    }

    @Test
    void 落点判断能认出目标面板_认不出别的组件() throws Exception {
        MainPanel panel = panelWithPhotos();

        assertNotNull(DragOut.panelOf(panel.targetPanelForTest()), "自己应当被认出来");
        assertNotNull(DragOut.panelOf(panel.arrangePanelForTest()), "整理模式那块也算");
        assertNull(DragOut.panelOf(panel.filmstripForTest()), "素材条不是落点");
        assertNull(DragOut.panelOf(null));
        panel.shutdown();
    }

    @Test
    void 点目标面板里的照片_大图就显示它() throws Exception {
        MainPanel panel = panelWithPhotos();
        TargetPanel target = panel.targetPanelForTest();
        SwingUtilities.invokeAndWait(() -> {
            target.setSize(360, 480);
            layoutTree(target);
        });
        paint(target); // 画一遍才会算出格子的位置

        // 第 0 格的中心：留白 + 半格宽，纵向在表头下面一点点
        Point inFirstCell = new Point(30, 60);
        int index = target.indexAt(inFirstCell);
        assertEquals(0, index, "这一点应当落在第一格里");

        Path expected = target.session().entryAt(0).source();
        SwingUtilities.invokeAndWait(() -> target.dispatchEvent(new MouseEvent(target,
                MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                inFirstCell.x, inFirstCell.y, 1, false, MouseEvent.BUTTON1)));

        assertEquals(expected, panel.viewerForTest().photo(), "大图应当换成点中的那张");
        panel.shutdown();
    }

    @Test
    void 点在空白处不会误选() throws Exception {
        MainPanel panel = panelWithPhotos();
        TargetPanel target = panel.targetPanelForTest();
        SwingUtilities.invokeAndWait(() -> {
            target.setSize(360, 480);
            layoutTree(target);
        });
        paint(target);

        assertEquals(-1, target.indexAt(new Point(200, 400)), "下面一大片空白不该算某一格");
        assertEquals(-1, target.indexAt(new Point(200, 5)), "表头上也不算");
        panel.shutdown();
    }

    @Test
    void 拖动是拖照片还是搬画面_由工具栏上的工具决定() throws Exception {
        MainPanel panel = panelWithPhotos();
        ViewerPanel viewer = panel.viewerForTest();

        assertEquals(CursorTool.SELECT, panel.toolBar().activeTool(), "默认选中\"选择\"");
        assertTrue(viewer.isDragOutEnabled(), "选择工具下，拖动是把照片拖出去");
        assertTrue(!viewer.canPan(), "选择工具下不搬画面");

        // 换成"平移"：拖动改成搬画面，不再往外拖照片
        SwingUtilities.invokeAndWait(() -> {
            ToolBarPanel toolBar = panel.toolBar();
            toolBar.setOnToolChanged(viewer::setCursorTool);
            toolBar.setActiveToolForTest(CursorTool.PAN);
        });
        assertTrue(!viewer.isDragOutEnabled(), "平移工具下拖动不往外拖照片");
        assertTrue(viewer.canPan() || !viewer.hasImage(), "平移工具下才允许搬画面");
        panel.shutdown();
    }

    @Test
    void 拖到两张之间时落点是插在它们中间() throws Exception {
        MainPanel panel = panelWithPhotos();
        TargetPanel target = panel.targetPanelForTest();
        SwingUtilities.invokeAndWait(() -> {
            target.setSize(360, 480);
            layoutTree(target);
        });
        paint(target); // 画一遍才会算出格子位置

        // 第一格中点偏右 → 插到它后面；偏左 → 插到它前面
        assertEquals(1, target.dropIndexAt(new Point(120, 60)), "第一格右半边：插在它后面");
        assertEquals(0, target.dropIndexAt(new Point(20, 60)), "第一格左半边：插在它前面");

        Path dropped = Files.walk(tempDir.resolve("素材"))
                .filter(path -> path.getFileName().toString().equals("p2.jpg"))
                .findFirst().orElseThrow();
        panel.addToTargetAt(dropped, 1);
        assertEquals("020", target.nameAt(1), "插在第 1 位，就该是这个位置的编号");
        assertEquals(3, target.entryCount());
        panel.shutdown();
    }

    @Test
    void 拖动过程中会画白线标出落点_松手后擦掉() throws Exception {
        MainPanel panel = panelWithPhotos();
        TargetPanel target = panel.targetPanelForTest();
        SwingUtilities.invokeAndWait(() -> {
            target.setSize(360, 480);
            layoutTree(target);
        });

        target.setDropHint(1);
        BufferedImage withLine = render(target);
        assertTrue(hasWhiteLine(withLine), "拖动时该有一条白线标出落点");

        target.clearDropHint();
        assertTrue(!hasWhiteLine(render(target)), "松手之后白线要擦掉");
        panel.shutdown();
    }

    /** 面板里有没有那条纯白的竖线（DIVIDER_HINT 那个色）。 */
    private static boolean hasWhiteLine(BufferedImage image) {
        int wanted = Theme.DIVIDER_HINT.getRGB() & 0xFFFFFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == wanted) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BufferedImage render(javax.swing.JComponent component) {
        BufferedImage image = new BufferedImage(Math.max(1, component.getWidth()),
                Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        component.paint(g);
        g.dispose();
        return image;
    }

    private static void paint(javax.swing.JComponent component) {
        BufferedImage image = new BufferedImage(Math.max(1, component.getWidth()),
                Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        component.paint(g);
        g.dispose();
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

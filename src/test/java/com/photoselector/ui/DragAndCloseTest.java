package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.Workspace;
import com.photoselector.io.WorkspaceStore;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/**
 * 真窗口、真鼠标事件：这几条在离屏渲染里测不出来，只有把窗口显示出来才作数。
 *
 * <p>覆盖两件事：
 * <ul>
 *   <li>从素材条拖一张到目标面板：拖动期间要有跟着鼠标的缩略图和白线，松手要真的插进去</li>
 *   <li>点窗口的 × 后选"不保存"：窗口要真的关掉</li>
 * </ul>
 */
class DragAndCloseTest {

    @TempDir
    Path tempDir;

    @Test
    void 从素材条拖到目标面板_有缩略图跟着鼠标_有白线_松手插进去() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "没有显示器就测不了真窗口");
        TestWindow window = new TestWindow();
        try {
            window.show();
            FilmstripPanel filmstrip = window.panel.filmstripForTest();
            TargetPanel target = window.panel.targetPanelForTest();
            Path dragged = filmstrip.photoAtForTest(1);
            assertNotNull(dragged, "素材条里应当有第二张");
            filmstrip.setCurrentPhoto(dragged); // 就像点中了它，缩略图得先在手里

            java.util.concurrent.atomic.AtomicReference<Point> draggedOnFilmstrip =
                    new java.util.concurrent.atomic.AtomicReference<>();
            int before = target.entryCount();
            SwingUtilities.invokeAndWait(() -> {
                // 坐标全部现算：布局会随窗口尺寸变，先算好的坐标配不上下一次事件那一刻的布局
                Point from = cellCenter(filmstrip, 1);
                Point overTarget = convertLocal(target,
                        new Point(target.getWidth() / 2, 200), filmstrip);
                // 真实鼠标的一次拖动：按住之后，后续事件一直发给按住的那个组件——
                // 所以下面的事件都发给素材条，落在哪儿由坐标说话
                press(filmstrip, from);
                drag(filmstrip, new Point(from.x + 12, from.y));
                drag(filmstrip, overTarget);
                draggedOnFilmstrip.set(overTarget);
            });

            // 跟着鼠标走的那张缩略图：玻璃板上得真有东西画出来
            Rectangle ghost = DragOut.ghostBoundsForTest(filmstrip);
            assertNotNull(ghost, "拖动时应当有缩略图跟着鼠标走");
            assertTrue(ghost.width > 100 && ghost.height > 50, "跟着走的那张图得有实际大小");
            // 它应当画在鼠标旁边——这是"跟着鼠标"的证据。
            // 玻璃板长在窗口原点，所以它画的位置就是窗口里的位置
            javax.swing.JRootPane root = window.frame.getRootPane();
            Point mouseInRoot = toWindow(target, new Point(target.getWidth() / 2, 200), root);
            assertTrue(Math.abs(ghost.x - mouseInRoot.x) < 60
                            && Math.abs(ghost.y - mouseInRoot.y) < 60,
                    "那张图应当贴在鼠标旁边：" + ghost + " vs " + mouseInRoot);
            // 落点提示：白线
            int hint = target.dropHintIndex();
            assertTrue(hint >= 0, "拖到目标面板上时应当标出落点");
            assertTrue(hasWhiteLine(target), "落点应当画成一条白线");

            SwingUtilities.invokeAndWait(() -> release(filmstrip, draggedOnFilmstrip.get()));

            assertEquals(before + 1, target.entryCount(), "松手后照片应当插进目标序列");
            assertEquals(dragged, target.session().entryAt(hint).source(), "插的就是白线标出的那个位置");
            assertTrue(target.dropHintIndex() < 0, "松手后白线要擦掉");
            assertNull(DragOut.ghostBoundsForTest(filmstrip), "松手后跟着鼠标的缩略图要收起来");
        } finally {
            window.dispose();
        }
    }

    @Test
    void 有没保存的改动时选不保存_窗口要真的关掉() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "没有显示器就测不了真窗口");
        TestWindow window = new TestWindow();
        try {
            window.show();
            window.choice.set(JOptionPane.NO_OPTION);

            window.panel.addToTarget(window.panel.filmstripForTest().photoAtForTest(0));
            assertTrue(window.panel.isDirty(), "先造出没保存的改动");

            // 用户点窗口的 ×
            SwingUtilities.invokeLater(() -> window.frame.dispatchEvent(
                    new WindowEvent(window.frame, WindowEvent.WINDOW_CLOSING)));

            waitUntil(() -> !window.frame.isDisplayable(), "窗口关闭");
            assertEquals(1, window.confirms.get(), "有改动时应当问一次");
            assertEquals(1, window.exits.get(), "退出动作应当执行一次");
            assertFalse(Files.exists(targetJpg(window, "010.jpg")), "选不保存，磁盘上不该多出东西");
        } finally {
            window.dispose();
        }
    }

    private static Path targetJpg(TestWindow window, String name) {
        return window.panel.targetPanelForTest().session().targetFolder().resolve(name);
    }

    /** 打开一个真窗口，并等素材与目标都就位。 */
    private final class TestWindow {

        final MainFrame frame;
        final MainPanel panel;
        final AtomicReference<Integer> choice = new AtomicReference<>(JOptionPane.NO_OPTION);
        final AtomicInteger confirms = new AtomicInteger();
        final AtomicInteger exits = new AtomicInteger();

        TestWindow() throws Exception {
            Path source = Files.createDirectories(tempDir.resolve("素材"));
            Path shots = Files.createDirectories(source.resolve("001_拍摄"));
            for (int i = 0; i < 3; i++) {
                TestImages.writeJpeg(shots.resolve("p" + i + ".jpg"), 300, 200);
            }
            Path target = Files.createDirectories(tempDir.resolve("成片"));
            TestImages.writeJpeg(target.resolve("旧A.jpg"), 300, 200);
            TestImages.writeJpeg(target.resolve("旧B.jpg"), 300, 200);

            AtomicReference<MainFrame> created = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                Theme.install();
                // 配置写到临时目录：别碰用户真实的 %APPDATA%
                created.set(new MainFrame(new WorkspaceStore(tempDir.resolve("workspace.properties"))));
            });
            frame = created.get();
            panel = (MainPanel) frame.getContentPane();
            // 不弹真对话框：测试里点不了按钮
            panel.setConfirmSaveOnClose(owner -> {
                confirms.incrementAndGet();
                return choice.get();
            });
            panel.setOnWorkspaceChanged(workspace -> { });
            panel.setOnExit(() -> {
                exits.incrementAndGet();
                panel.shutdown();
                frame.dispose();
            });
            SwingUtilities.invokeAndWait(() ->
                    panel.applyWorkspace(new Workspace(source, Set.of(), target)));
            waitUntil(() -> panel.targetPanelForTest().session() != null
                    && panel.filmstripGroupCount() == 1, "素材与目标就位");
        }

        void show() throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                frame.setSize(1200, 800);
                frame.setVisible(true);
                frame.validate(); // 把尺寸真正分下去，玻璃板才有大小可画
            });
        }

        void dispose() throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                frame.setVisible(false);
                frame.dispose();
            });
        }
    }

    // ─────────────────────────── 找格子、发鼠标事件 ───────────────────────────

    /** 素材条里第 index 张照片所在格子的中心（相对素材条自己）。 */
    private static Point cellCenter(FilmstripPanel filmstrip, int index) {
        Rectangle group = filmstrip.groupBoundsForTest().get(0);
        int cellWidth = filmstrip.cellWidthForTest();
        return new Point(group.x + 8 + index * cellWidth + cellWidth / 2, group.y + 60);
    }

    /**
     * 某个组件内部的某一点，换算成"相对窗口内容面板"的位置。
     *
     * <p>发给窗体的事件走的正是这套坐标（菜单栏在内容面板之上，两套坐标差着一截，
     * 混用的话事件就会落在别的控件上）。
     */
    private static Point inWindow(Component component, Point local) {
        return toWindow(component, local,
                SwingUtilities.getRootPane(component).getContentPane());
    }

    private static Point convertLocal(Component from, Point pointInFrom, Component to) {
        javax.swing.JRootPane root = SwingUtilities.getRootPane(from);
        return fromWindow(root, toWindow(from, pointInFrom, root), to);
    }

    // 和 DragOut 里那对换算方法同一套算法：只在窗口内部算，不绕屏幕坐标，滚动也要算进去
    private static Point toWindow(Component from, Point pointInFrom, Component ancestor) {
        Point inAncestor = new Point(pointInFrom);
        for (Component c = from; c != null && c != ancestor; c = c.getParent()) {
            inAncestor.translate(c.getX(), c.getY());
            if (c instanceof javax.swing.JViewport viewport) {
                inAncestor.translate(-viewport.getViewPosition().x, -viewport.getViewPosition().y);
            }
        }
        return inAncestor;
    }

    private static Point fromWindow(Component ancestor, Point inAncestor, Component to) {
        Point inTo = new Point(inAncestor);
        for (Component c = to; c != null && c != ancestor; c = c.getParent()) {
            inTo.translate(-c.getX(), -c.getY());
            if (c instanceof javax.swing.JViewport viewport) {
                inTo.translate(viewport.getViewPosition().x, viewport.getViewPosition().y);
            }
        }
        return inTo;
    }

    /**
     * 鼠标事件发给某个组件，坐标是它自己坐标系里的。
     *
     * <p>真实 AWT 的一次拖动就是这样的：按下之后，后续事件一直送给按住的组件，
     * 直到松手为止。所以这里不去找"这一点上是谁"——那样反而找不到拖动的收尾。
     */
    private static void press(Component source, Point at) {
        send(source, at, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1_DOWN_MASK, 1);
    }

    private static void drag(Component source, Point at) {
        send(source, at, MouseEvent.MOUSE_DRAGGED, MouseEvent.BUTTON1_DOWN_MASK, 0);
    }

    private static void release(Component source, Point at) {
        send(source, at, MouseEvent.MOUSE_RELEASED, 0, 1);
    }

    private static void send(Component source, Point at, int id, int modifiers, int count) {
        // 按住和松开都要说清楚是哪个键；拖动事件没有"键"这一说，只带修饰符
        int button = (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED)
                ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON;
        source.dispatchEvent(new MouseEvent(source, id, System.currentTimeMillis(), modifiers,
                at.x, at.y, count, false, button));
    }

    // ─────────────────────────── 看画面 ───────────────────────────

    /** 玻璃板上有没有真画出东西（跟着鼠标的那张缩略图）。 */
    private static boolean hasVisiblePixels(MainFrame frame) {
        Component glass = frame.getRootPane().getGlassPane();
        if (!glass.isVisible() || glass.getWidth() <= 0 || glass.getHeight() <= 0) {
            return false;
        }
        BufferedImage image = new BufferedImage(glass.getWidth(), glass.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        glass.paint(g);
        g.dispose();
        // 逐个像素找：那条线只有 3 像素宽，隔一个看一个正好会漏掉它
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha >= 0xC0) {
                    return true; // 有一块接近不透明的像素：那是画上去的图
                }
            }
        }
        return false;
    }

    /** 目标面板的画面上有没有那条纯白的落点线。 */
    private static boolean hasWhiteLine(TargetPanel target) {
        int width = Math.max(1, target.getWidth());
        int height = Math.max(1, target.getHeight());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        target.paint(g);
        g.dispose();
        int wanted = Theme.DIVIDER_HINT.getRGB() & 0xFFFFFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                if (rgb == wanted) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }
}

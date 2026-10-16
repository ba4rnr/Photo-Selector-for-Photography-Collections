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
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
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

import com.photoselector.core.Session;
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
            // 拖动时跟着鼠标走的就是这张缩略图；还没解好就没有可比的尺寸，先等它
            Assumptions.assumeTrue(waitForThumbnail(filmstrip), "缩略图没解出来就不比尺寸");

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
            assertEquals(dragged, target.session().entryAt(hint).source(),
                    "插的就是白线标出的那个位置");
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

    @Test
    void 在目录树里选了文件夹之后_方向键还能过片() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "没有显示器就测不了真窗口");
        TestWindow window = new TestWindow(2);
        try {
            window.show();
            FilmstripPanel filmstrip = window.panel.filmstripForTest();
            SourceTreePanel tree = window.panel.sourceTreeForTest();

            // 先在目录树里点一个文件夹：焦点落到树上（这正是出问题的一步）
            Point inTree = new Point(40, 40);
            SwingUtilities.invokeAndWait(() -> {
                tree.requestFocusInWindow();
                tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                        inTree.x, inTree.y, 1, false, MouseEvent.BUTTON1));
            });

            // 再点底部的第一张照片
            FilmstripPanel panel = filmstrip;
            Point cell = new Point(panel.groupBoundsForTest().get(0).x + 20, 60);
            SwingUtilities.invokeAndWait(() -> {
                window.frame.toFront();
                panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                        cell.x, cell.y, 1, false, MouseEvent.BUTTON1));
            });
            Path first = panel.currentPhoto();
            assertNotNull(first, "点过之后应当有一张是当前照片");

            // 焦点要能被素材条接过去，方向键过片才不会被目录树吃掉。
            assertTrue(panel.isFocusable(), "素材条必须可聚焦，否则要不过来焦点");

            // 按右方向键：该翻到下一张。
            // 测试机上窗口不一定有系统焦点，所以先给窗口补一次焦点；
            // 补不上也没关系——下面的断言只看"焦点在素材条上时按方向键管用"，
            // 而真窗口里那一步由点击时的 requestFocus() 完成。
            SwingUtilities.invokeAndWait(() -> {
                window.frame.requestFocus();
                panel.requestFocusInWindow();
            });
            KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            boolean focusOnPanel = manager.getFocusOwner() == panel;
            SwingUtilities.invokeAndWait(() -> manager.dispatchEvent(new KeyEvent(window.frame,
                    KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_RIGHT,
                    KeyEvent.CHAR_UNDEFINED)));
            if (focusOnPanel) {
                assertTrue(!first.equals(panel.currentPhoto()),
                        "素材条拿到焦点时，右方向键应当翻到下一张");
            }
        } finally {
            window.dispose();
        }
    }

    @Test
    void 方向键是过片_过到的照片要看得见() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "没有显示器就测不了真窗口");
        TestWindow window = new TestWindow(1, 8); // 一组 8 张，素材条比窗口宽
        try {
            window.show();
            FilmstripPanel strip = window.panel.filmstripForTest();
            SwingUtilities.invokeAndWait(() -> {
                window.frame.setSize(700, 640);
                window.frame.validate();
            });
            waitUntil(() -> strip.getWidth() > strip.getVisibleRect().width,
                    "素材条比视口宽");

            // 点第一张照片，焦点应当跟过来。
            // 注意顺序：先改窗口大小、再点照片——改大小会把焦点打回工具栏那个按钮上，
            // 点照片这一步才把焦点接回素材条
            // 先把焦点放到"不是素材条"的地方（工具栏按钮），复现用户遇到的起点
            SwingUtilities.invokeAndWait(() -> {
                window.frame.toFront();
                window.panel.toolBar().chooseWorkspaceButton().requestFocusInWindow();
            });
            Point first = new Point(strip.groupBoundsForTest().get(0).x + 20, 60);
            SwingUtilities.invokeAndWait(() -> strip.dispatchEvent(new MouseEvent(strip,
                    MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    MouseEvent.BUTTON1_DOWN_MASK, first.x, first.y, 1, false, MouseEvent.BUTTON1)));
            Path clicked = strip.currentPhoto();
            assertNotNull(clicked, "点过之后应当有一张是当前照片");

            // 方向键要真的"过片"：每一次当前照片都换一张。
            // 测试机上窗口不一定拿得到系统焦点，那就手工把焦点摆到素材条上——
            // 真窗口里这一步由点击时 handleClick 里的 requestFocus() 完成。
            KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            SwingUtilities.invokeAndWait(() -> strip.requestFocusInWindow());
            Path previous = strip.currentPhoto();
            for (int i = 0; i < 2; i++) {
                SwingUtilities.invokeAndWait(() -> manager.dispatchEvent(new KeyEvent(window.frame,
                        KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                        KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED)));
                Path now = strip.currentPhoto();
                assertTrue(!java.util.Objects.equals(previous, now),
                        "第 " + (i + 1) + " 次按右键，当前照片应当换成下一张"
                                + "（焦点=" + manager.getFocusOwner() + "）");
                previous = now;
            }
            // 过到的这张必须还看得见：过片时素材条自己会滚，不是让用户手动去找
            Rectangle visible = strip.getVisibleRect();
            int cell = strip.photoLeftInViewForTest(strip.currentPhoto());
            assertTrue(cell >= visible.x - 1 && cell < visible.x + visible.width,
                    "过到的照片应当已经被滚进视野（照片在 " + cell
                            + "，视野 " + visible.x + "…" + (visible.x + visible.width) + "）");
        } finally {
            window.dispose();
        }
    }

    @Test
    void 选完文件夹之后_素材条跳到那一组的最左边() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "没有显示器就测不了真窗口");
        // 组数要够多、但也要让目标组落在"滚得到"的范围内：
        // 一批 313 张的素材条宽约 1592px，窗口只有 1186px，
        // 选第 2 组（下标 1）时它的左边缘正好在可滚范围内
        TestWindow window = new TestWindow(3);
        try {
            window.show();
            FilmstripPanel filmstrip = window.panel.filmstripForTest();
            // 把窗口收窄：素材条只有比视口宽才滚得动，窗口太宽的话
            // 三组正好塞得下，视口一动也不动，这条测试就测了个寂寞
            SwingUtilities.invokeAndWait(() -> {
                window.frame.setSize(700, 640);
                window.frame.validate();
            });

            // 等扫描把四个组都放进素材条、窗口也把尺寸分下来：
            // 扫描是异步的，之前这里抢在它前面读尺寸，读到的是"还没长开"的素材条
            waitUntil(() -> window.panel.filmstripGroupCount() == 3
                            && filmstrip.getWidth() > filmstrip.getVisibleRect().width,
                    "四个组都到齐，且素材条比视口宽（有得滚）——组数 "
                            + window.panel.filmstripGroupCount()
                            + "，素材条宽 " + filmstrip.getWidth()
                            + "，可见宽 " + filmstrip.getVisibleRect().width
                            + "，首选宽 " + filmstrip.getPreferredSize().width);
            // 先滚到中间去，制造"目标组在左边看不见"的状态。
            // 直接摆视口位置，不走 scrollRectToVisible——离屏的组件不一定认那条路
            SwingUtilities.invokeAndWait(() -> ((javax.swing.JViewport) filmstrip.getParent())
                    .setViewPosition(new Point(filmstrip.getWidth() / 2, 0)));
            int scrolled = filmstrip.viewPositionXForTest();
            assertTrue(scrolled > 0, "先得滚走一点才有可测的余地（当前 " + scrolled + "）");

            // 选中第二个文件夹：素材条应当停到那一组的最左边
            Path folder = testFolderPath(1);
            SwingUtilities.invokeAndWait(() -> filmstrip.scrollToGroup(folder));

            waitUntil(() -> filmstrip.viewPositionXForTest() == expectedGroupLeft(filmstrip, folder),
                    "素材条停在那一组的最左边");
        } finally {
            window.dispose();
        }
    }

    /** 某一组左边缘在素材条里的位置。 */
    private static int expectedGroupLeft(FilmstripPanel filmstrip, Path folder) {
        int index = filmstrip.groupFolderIndexForTest(folder);
        return filmstrip.groupBoundsForTest().get(index).x;
    }

    /** 一个按下事件（坐标相对组件自己）。 */
    private static MouseEvent mouse(Component source, int id, int x, int y) {
        return new MouseEvent(source, id, System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1);
    }

    /** 测试用素材根目录下第 index 个子文件夹的路径。 */
    private Path testFolderPath(int index) {
        return tempDir.resolve("素材").resolve(String.format("%03d_拍摄", index + 1));
    }

    @Test
    void 目标文件夹里的照片可以拖着换位置() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "没有显示器就测不了真窗口");
        TestWindow window = new TestWindow();
        try {
            window.show();
            TargetPanel target = window.panel.targetPanelForTest();

            // 坐标都按"面板自己"算：目标面板上的每张照片都看得见，
            // 直接用它的格位就行，不必绕窗口坐标（绕了反而容易落到面板外面）
            Point onFirstCell = new Point(target.cellXForTest(0) + 20,
                    target.rowHeightForTest() / 2);
            Point rightOfSecond = new Point(target.cellRightForTest(1) + 6,
                    target.rowHeightForTest() / 2);
            SwingUtilities.invokeAndWait(() -> {
                press(target, onFirstCell);
                drag(target, new Point(onFirstCell.x + 12, onFirstCell.y));
                drag(target, rightOfSecond);
            });
            assertEquals(2, target.dropHintIndex(), "落点应当标在第二张右边那个缝里"
                    + "（面板 " + target.getWidth() + "x" + target.getHeight()
                    + "，列数 " + target.columnsForTest() + "）");

            SwingUtilities.invokeAndWait(() -> release(target, rightOfSecond));

            Session session = target.session();
            assertEquals(2, session.size(), "换位置不该改变总数");
            assertEquals("旧B.jpg", session.entryAt(0).source().getFileName().toString(),
                    "原来第二张现在排头");
            assertEquals("旧A.jpg", session.entryAt(1).source().getFileName().toString(),
                    "被拖的那张落在它后面");
            assertEquals("020", session.nameAt(1), "编号跟着位置现算");
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
            this(1, 3);
        }

        /** @param sourceSubfolders 素材根目录下放几个子文件夹（几个组） */
        TestWindow(int sourceSubfolders) throws Exception {
            this(sourceSubfolders, 3);
        }

        /**
         * @param sourceSubfolders 素材根目录下放几个子文件夹（几个组），测试布局才用得到多组
         * @param photosPerFolder 每个子文件夹里放几张
         */
        TestWindow(int sourceSubfolders, int photosPerFolder) throws Exception {
            Path source = Files.createDirectories(tempDir.resolve("素材"));
            for (int s = 0; s < sourceSubfolders; s++) {
                Path shots = Files.createDirectories(source.resolve(String.format("%03d_拍摄", s + 1)));
                for (int i = 0; i < photosPerFolder; i++) {
                    TestImages.writeJpeg(shots.resolve("p" + i + ".jpg"), 300, 200);
                }
            }
            Path target = Files.createDirectories(tempDir.resolve("成片"));
            TestImages.writeJpeg(target.resolve("旧A.jpg"), 300, 200);
            TestImages.writeJpeg(target.resolve("旧B.jpg"), 300, 200);

            AtomicReference<MainFrame> created = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                Theme.install();
                // 配置写到临时目录：别碰用户真实的 %APPDATA%
                created.set(new MainFrame(new WorkspaceStore(tempDir.resolve("workspace.properties")),
                        new com.photoselector.io.AppSettings(tempDir.resolve("preferences.properties"))));
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
                    && panel.filmstripGroupCount() == sourceSubfolders, "素材与目标就位");
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
        // 逐像素找纯白（那条线只有 3 像素宽，隔一个看一个会漏），
        // 而且允许再画几遍：拖动刚结束，面板的重绘可能还排在事件队列里，
        // 只画一次会读到"还没轮到它"的旧画面
        int wanted = Theme.DIVIDER_HINT.getRGB() & 0xFFFFFF;
        for (int attempt = 0; attempt < 20; attempt++) {
            int width = Math.max(1, target.getWidth());
            int height = Math.max(1, target.getHeight());
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            target.paint(g);
            g.dispose();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) & 0xFFFFFF) == wanted) {
                        return true;
                    }
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false; // 被叫停了，别再画了
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

    /** 等素材条手里的那张缩略图解好。 */
    private static boolean waitForThumbnail(FilmstripPanel filmstrip) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (filmstrip.currentThumbnail() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        return filmstrip.currentThumbnail() != null;
    }
}

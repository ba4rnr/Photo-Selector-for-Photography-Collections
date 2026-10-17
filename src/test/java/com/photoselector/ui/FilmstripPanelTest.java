package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.io.ThumbnailService;
import com.photoselector.io.PhotoFiles;
import com.photoselector.core.PhotoGroup;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/**
 * 素材条的端到端测试：给一个装着真照片的文件夹，看它能不能把照片画出来。
 *
 * <p>整条链路都跑了一遍——扫描文件夹、后台解码、回调回到界面线程、自绘。
 * 跑完会把渲染结果存成 {@code target/ui-filmstrip.png} 供肉眼核对。
 */
class FilmstripPanelTest {

    @TempDir
    Path tempDir;

    /** 把一堆照片当成一个组交给素材条。 */
    private static List<PhotoGroup> oneGroup(String name, List<Path> photos) {
        return List.of(new PhotoGroup(name, photos.get(0).getParent(), photos));
    }

    @Test
    void 打开文件夹后缩略图会陆续到位并画出来() throws Exception {
        Theme.install();

        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            photos.add(TestImages.writeJpeg(tempDir.resolve("photo-" + i + ".jpg"), 900, 600));
        }

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);

        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(1000, 190);
            filmstrip.setGroups(oneGroup("照片", photos), 120);
            layoutTree(filmstrip);
        });

        // 缩略图在后台解，回调回到界面线程，所以这里从外面等
        long deadline = System.currentTimeMillis() + 30_000;
        while (filmstrip.loadedCount() < photos.size() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertEquals(photos.size(), filmstrip.loadedCount(), "所有缩略图都应当陆续到位");

        BufferedImage rendered = render(filmstrip);
        Path output = Path.of("target", "ui-filmstrip.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(rendered, "png", output.toFile());

        assertTrue(countColors(rendered) > 3, "素材条应当画出照片，而不是一片空白");
        awaitIdle(loader);
        loader.shutdown();
    }

    @Test
    void 没有照片时显示提示而不是空白() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);

        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(600, 160);
            layoutTree(filmstrip);
        });

        BufferedImage rendered = render(filmstrip);
        assertTrue(countColors(rendered) > 1, "空素材条上应当有一行提示文字");
        awaitIdle(loader);
        loader.shutdown();
    }

    @Test
    void 真实照片的素材条() throws Exception {
        String configured = System.getProperty("photoselector.samples");
        assumeTrue(configured != null && Files.isDirectory(Path.of(configured)),
                "没有用 -Dphotoselector.samples=<目录> 指定样例照片，跳过");

        Theme.install();

        // 走的是和程序里一模一样的那条路：扫描文件夹 → 后台解码 → 自绘
        List<Path> photos = PhotoFiles.imagesIn(Path.of(configured)).stream().limit(12).toList();
        assumeTrue(!photos.isEmpty(), "这个目录里没有可读的照片");

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        int height = 120;
        int width = (int) Math.round(height * 1.5f) + 8;

        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(width * photos.size(), 190);
            filmstrip.setGroups(oneGroup("照片", photos), height);
            layoutTree(filmstrip);
        });

        long deadline = System.currentTimeMillis() + 60_000;
        while (filmstrip.loadedCount() < photos.size() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(photos.size(), filmstrip.loadedCount(), "真实照片也应当全部解出来");

        Path output = Path.of("target", "ui-filmstrip-samples.png");
        ImageIO.write(render(filmstrip), "png", output.toFile());
        System.out.println("素材条截图已写出：" + output.toAbsolutePath());
        awaitIdle(loader);
        loader.shutdown();
    }

    @Test
    void 视口边缘被切一半的照片照样正常绘制() throws Exception {
        Theme.install();

        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            photos.add(TestImages.writeJpeg(tempDir.resolve("half-" + i + ".jpg"), 900, 600));
        }

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);

        // 故意把宽度设成"三格半"：右边那半张正好被切在视口边缘上
        int cellWidth = Math.round(120 * 1.5f) + 8; // 与 FilmstripPanel 内部算法一致
        int width = cellWidth * 3 + cellWidth / 2;
        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(width, 190);
            filmstrip.setGroups(oneGroup("照片", photos), 120);
            layoutTree(filmstrip);
        });

        waitUntilLoaded(filmstrip, photos.size());

        BufferedImage rendered = render(filmstrip);
        Path output = Path.of("target", "ui-filmstrip-partial.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(rendered, "png", output.toFile());

        // 被切的那半张：视口最右边这一条里应当有照片的颜色，而不是一片背景
        BufferedImage cutEdge = rendered.getSubimage(width - 40, 12, 38, 116);
        assertTrue(countColors(cutEdge) > 3,
                "半露的那张也应当完整画出来、只是被裁掉一截，而不是画一半或干脆不画");
        awaitIdle(loader);
        loader.shutdown();
    }

    @Test
    void 视口变宽后新露出来的格子也会去取图() throws Exception {
        Theme.install();

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        // 用生产代码里那套滚动面板，连同它挂的监听一起测
        JScrollPane scroll = filmstrip.wrapInScrollPane();
        // 张数按"一屏 + 各方向各预取两屏（KEEP_SCREENS=2）"来定，再多给几张：
        // 少了的话"窄视口也不该全解出来"这句话就不成立（格子宽度是会变的）
        int cellWidth = Math.round(120 / 1.0f) + 8;
        int prefetched = 5 * 300 / cellWidth + 4;
        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < prefetched + 2; i++) {
            photos.add(TestImages.writeJpeg(tempDir.resolve("wide-" + i + ".jpg"), 900, 600));
        }

        SwingUtilities.invokeAndWait(() -> {
            scroll.setSize(300, 190);
            filmstrip.setGroups(oneGroup("照片", photos), 120);
            layoutTree(scroll);
        });
        int before = waitUntilStable(filmstrip, 2);
        assertTrue(before < photos.size(),
                "窄视口下不应当把 " + photos.size() + " 张全解出来");

        // 一次拉宽到远超"一屏 + 预取余量"，新露出来的格子必须被重新请求。
        // 离屏渲染时组件不会自己派发 resize 事件，所以这里手动补一个——
        // 真实窗口里这个事件是系统发的。
        SwingUtilities.invokeAndWait(() -> {
            scroll.setSize(2400, 190);
            layoutTree(scroll);
            scroll.getViewport().dispatchEvent(
                    new ComponentEvent(scroll.getViewport(), ComponentEvent.COMPONENT_RESIZED));
        });

        int after = waitUntilStable(filmstrip, before + 1);
        assertTrue(after > before,
                "视口变宽之后，新露出来的格子也该去取图（原来 " + before + " 张，现在 " + after + " 张）");
        awaitIdle(loader);
        loader.shutdown();
    }

    /** 等到缩略图张数不再增长（至少到 {@code atLeast} 张）为止，返回最终张数。 */
    private static int waitUntilStable(FilmstripPanel filmstrip, int atLeast) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        int lastSeen = -1;
        while (System.currentTimeMillis() < deadline) {
            int now = filmstrip.loadedCount();
            if (now >= atLeast && now == lastSeen) {
                return now;
            }
            lastSeen = now;
            Thread.sleep(50);
        }
        return filmstrip.loadedCount();
    }

    /**
     * 等后台解码全部收工。
     *
     * <p>不等的话，测试刚结束、JUnit 就去删临时目录，而某个后台线程可能还捏着
     * 里面的照片文件在读——Windows 上这会让删目录整个失败。
     */
    private static void awaitIdle(ThumbnailLoader loader) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (loader.inFlightCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void 分隔条也不能抢走左右方向键() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        JScrollPane scroll = filmstrip.wrapInScrollPane();

        // 素材条长在分隔条里面，而 JSplitPane 默认把 ← / → 绑在
        // "焦点组件之祖先"这一层（用来挪分隔条）——不摘掉，方向键就会被它吃掉
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT, new javax.swing.JPanel(), scroll);
        ScrollKeys.stripArrowKeys(split);
        SwingUtilities.invokeAndWait(() -> {
            split.setSize(800, 400);
            layoutTree(split);
        });

        assertFalse(filmstrip.arrowKeySwallowedByAncestor(
                        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_LEFT, 0)),
                "左方向键不该被祖先链（含分隔条）截走");
        assertFalse(filmstrip.arrowKeySwallowedByAncestor(
                        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT, 0)),
                "右方向键不该被祖先链（含分隔条）截走");
        loader.shutdown();
    }

    @Test
    void 素材条上左右方向键不会被滚动面板吃掉() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        JScrollPane scroll = filmstrip.wrapInScrollPane();
        SwingUtilities.invokeAndWait(() -> {
            scroll.setSize(400, 190);
            layoutTree(scroll);
        });

        // JScrollPane 默认把 ← / → 绑在"焦点组件之祖先"这一层（unitScrollLeft/Right），
        // 而这一层检查在窗口级快捷键之前——不摘掉它，按方向键只会让条滑一格，
        // 轮不到"换上一张/下一张"。这条测试就是钉住那件事：这两个键必须能落到全局。
        assertFalse(filmstrip.arrowKeySwallowedByAncestor(
                        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_LEFT, 0)),
                "左方向键不该被祖先链上的组件截走");
        assertFalse(filmstrip.arrowKeySwallowedByAncestor(
                        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT, 0)),
                "右方向键不该被祖先链上的组件截走");
        // 翻页键还留在滚动面板上：键盘滚动并没有被一刀切掉
        assertTrue(filmstrip.arrowKeySwallowedByAncestor(
                        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_PAGE_DOWN, 0)),
                "PageDown 仍然由滚动面板负责翻页");
        loader.shutdown();
    }

    @Test
    void 每组都有一圈灰色细边框() throws Exception {
        Theme.install();

        Path folderA = Files.createDirectories(tempDir.resolve("001_第一组"));
        Path folderB = Files.createDirectories(tempDir.resolve("002_第二组"));
        List<Path> photosA = new ArrayList<>();
        List<Path> photosB = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            photosA.add(TestImages.writeJpeg(folderA.resolve("a" + i + ".jpg"), 900, 600));
            photosB.add(TestImages.writeJpeg(folderB.resolve("b" + i + ".jpg"), 900, 600));
        }

        ThumbnailLoader loader = new ThumbnailLoader(
                new ThumbnailService(Path.of("target", "test-cache-group-border")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(1200, 200);
            filmstrip.setGroups(List.of(
                    new PhotoGroup("001_第一组", folderA, photosA),
                    new PhotoGroup("002_第二组", folderB, photosB)), 120);
            layoutTree(filmstrip);
        });

        List<Rectangle> bounds = filmstrip.groupBoundsForTest();
        assertEquals(2, bounds.size(), "两组应当各占一块");

        BufferedImage rendered = render(filmstrip);
        for (Rectangle box : bounds) {
            // 左边框那一条竖线，从上到下都应当是边框色（避开上下两条圆角边）
            for (int y = 40; y < 120; y++) {
                assertEquals(Theme.GROUP_BORDER.getRGB() & 0xFFFFFF,
                        rendered.getRGB(box.x, y) & 0xFFFFFF,
                        "第 " + box.x + " 列应当是这一组的竖直边框，y=" + y);
            }
        }
        awaitIdle(loader);
        loader.shutdown();
    }

    @Test
    void 收起之后显示第一张照片作为预览() throws Exception {
        Theme.install();

        Path folderA = Files.createDirectories(tempDir.resolve("001_第一组"));
        Path folderB = Files.createDirectories(tempDir.resolve("002_第二组"));
        List<Path> photosA = new ArrayList<>();
        List<Path> photosB = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            photosA.add(TestImages.writeJpeg(folderA.resolve("a" + i + ".jpg"), 900, 600));
            photosB.add(TestImages.writeJpeg(folderB.resolve("b" + i + ".jpg"), 900, 600));
        }

        ThumbnailLoader loader = new ThumbnailLoader(
                new ThumbnailService(Path.of("target", "test-cache-collapsed-preview")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(1200, 200);
            filmstrip.setGroups(List.of(
                    new PhotoGroup("001_第一组", folderA, photosA),
                    new PhotoGroup("002_第二组", folderB, photosB)), 120);
            layoutTree(filmstrip);
        });

        // 点第一组的组头把它收起来
        SwingUtilities.invokeAndWait(() -> filmstrip.dispatchEvent(new MouseEvent(filmstrip,
                MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                40, 8, 1, false, MouseEvent.BUTTON1)));
        assertTrue(filmstrip.isCollapsed(folderA), "点组头应当把这一组收起来");

        // 四张都在最开始请求过，等它们全部到位，第一张肯定在其中
        assertEquals(4, waitUntilStable(filmstrip, 4), "缩略图应当全部到位");

        BufferedImage rendered = render(filmstrip);
        Rectangle first = filmstrip.groupBoundsForTest().get(0);
        // 取"文件夹身体"中间那一块：避开顶上的小耳朵和边框，看照片有没有画上去
        BufferedImage preview = rendered.getSubimage(first.x + 10, 34, 110, 76);
        assertTrue(countColors(preview) > 3, "收起后的缩略图里应当能看到第一张照片，而不是空文件夹");
        assertTrue(hasColorNear(preview, 0xFF8B16, 60), "预览里应当有照片的颜色（测试图片是橙色底）");

        // 凸起（耳朵）和下面的大矩形之间不该有一条灰线：
        // 那一行（身体顶边所在的 y=40）在耳朵底下应当只剩文件夹的填充色
        for (int x = first.x + 8; x <= first.x + 40; x++) {
            assertEquals(Theme.CANVAS.getRGB() & 0xFFFFFF, rendered.getRGB(x, 40) & 0xFFFFFF,
                    "耳朵底下不该留下身体顶边的灰线，x=" + x);
        }

        Path output = Path.of("target", "ui-filmstrip-collapsed.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(rendered, "png", output.toFile());
        awaitIdle(loader);
        loader.shutdown();
    }

    @Test
    void 按子文件夹分组_点组头可以收起() throws Exception {
        Theme.install();

        Path folderA = Files.createDirectories(tempDir.resolve("001_第一组"));
        Path folderB = Files.createDirectories(tempDir.resolve("002_第二组"));
        List<Path> photosA = new ArrayList<>();
        List<Path> photosB = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            photosA.add(TestImages.writeJpeg(folderA.resolve("a" + i + ".jpg"), 900, 600));
            photosB.add(TestImages.writeJpeg(folderB.resolve("b" + i + ".jpg"), 900, 600));
        }

        // 这条测试不等缩略图加载完就结束，后台可能还在写缓存文件。
        // 缓存放到 target 下，免得拖累临时目录的清理。
        ThumbnailLoader loader = new ThumbnailLoader(
                new ThumbnailService(Path.of("target", "test-cache-collapse")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(1200, 200);
            filmstrip.setGroups(List.of(
                    new PhotoGroup("001_第一组", folderA, photosA),
                    new PhotoGroup("002_第二组", folderB, photosB)), 120);
            layoutTree(filmstrip);
        });

        assertEquals(2, filmstrip.groupCount());
        assertEquals(8, filmstrip.photoCount());
        int expandedWidth = filmstrip.getPreferredSize().width;

        // 点第一组的组头（组头在顶部那一条里）
        SwingUtilities.invokeAndWait(() -> filmstrip.dispatchEvent(new MouseEvent(filmstrip,
                MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                40, 8, 1, false, MouseEvent.BUTTON1)));
        assertTrue(filmstrip.isCollapsed(folderA), "点组头应当把这一组收起来");
        assertTrue(filmstrip.getPreferredSize().width < expandedWidth, "收起之后整条应当变窄");

        // 再点一次展开
        SwingUtilities.invokeAndWait(() -> filmstrip.dispatchEvent(new MouseEvent(filmstrip,
                MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                40, 8, 1, false, MouseEvent.BUTTON1)));
        assertFalse(filmstrip.isCollapsed(folderA));
        assertEquals(expandedWidth, filmstrip.getPreferredSize().width);
        awaitIdle(loader);
        loader.shutdown();
    }

    @Test
    void 方向键过片会跳过已收起的组() throws Exception {
        Theme.install();

        List<PhotoGroup> groups = new ArrayList<>();
        for (int g = 0; g < 3; g++) {
            Path folder = Files.createDirectories(tempDir.resolve("00" + (g + 1) + "_组"));
            List<Path> photos = List.of(
                    TestImages.writeJpeg(folder.resolve("p.jpg"), 900, 600));
            groups.add(new PhotoGroup(folder.getFileName().toString(), folder, photos));
        }

        ThumbnailLoader loader = new ThumbnailLoader(
                new ThumbnailService(Path.of("target", "test-cache-collapsed-navigation")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        SwingUtilities.invokeAndWait(() -> {
            filmstrip.setSize(1200, 200);
            filmstrip.setGroups(groups, 120);
            layoutTree(filmstrip);
        });

        // 收起中间那组，再从第一组往右走：应当直接到第三组。
        Rectangle middle = filmstrip.groupBoundsForTest().get(1);
        SwingUtilities.invokeAndWait(() -> filmstrip.dispatchEvent(new MouseEvent(filmstrip,
                MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                middle.x + 30, 8, 1, false, MouseEvent.BUTTON1)));
        assertTrue(filmstrip.isCollapsed(groups.get(1).folder()));

        Path first = groups.get(0).photos().get(0);
        Path third = groups.get(2).photos().get(0);
        FilmstripPanel.Neighbors neighbors = filmstrip.neighborsOf(first);
        assertEquals(third, neighbors.next(), "预读取也要跳过已收起组");
        assertEquals(null, neighbors.previous(), "第一张前面没有邻居");

        filmstrip.setCurrentPhoto(first);
        filmstrip.moveCurrent(1);

        assertEquals(groups.get(2).photos().get(0), filmstrip.currentPhoto(),
                "收起组的照片不该被方向键选中");
        awaitIdle(loader);
        loader.shutdown();
    }

    @Test
    void 恢复照片时素材条把目标照片放在视口正中() throws Exception {
        Theme.install();
        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            photos.add(TestImages.writeJpeg(tempDir.resolve("center-" + i + ".jpg"), 900, 600));
        }
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        JScrollPane scroll = filmstrip.wrapInScrollPane();
        SwingUtilities.invokeAndWait(() -> {
            scroll.setSize(500, 190);
            filmstrip.setGroups(oneGroup("照片", photos), 120);
            layoutTree(scroll);
        });

        Path target = photos.get(10);
        SwingUtilities.invokeAndWait(() -> filmstrip.selectPhoto(target));
        long deadline = System.currentTimeMillis() + 5_000;
        int position = filmstrip.viewPositionXForTest();
        while (position == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            position = filmstrip.viewPositionXForTest();
        }

        int extent = scroll.getViewport().getExtentSize().width;
        int expected = Math.max(0, Math.min(
                filmstrip.getPreferredSize().width - extent,
                filmstrip.photoCenterInViewForTest(target) - extent / 2));
        assertEquals(expected, position, 2, "恢复的照片应当落在可见区域中央");
        awaitIdle(loader);
        loader.shutdown();
    }

    private static void waitUntilLoaded(FilmstripPanel filmstrip, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (filmstrip.loadedCount() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertEquals(expected, filmstrip.loadedCount(), "缩略图应当全部到位");
    }

    private static BufferedImage render(FilmstripPanel filmstrip) {
        BufferedImage image = new BufferedImage(filmstrip.getWidth(), filmstrip.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        filmstrip.paint(g);
        g.dispose();
        return image;
    }

    private static int countColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                colors.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return colors.size();
    }

    /** 图里有没有"接近某个颜色"的像素。边界模糊的断言全靠它，别拿色相去猜。 */
    private static boolean hasColorNear(BufferedImage image, int target, int tolerance) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if (Math.abs(((rgb >> 16) & 0xFF) - ((target >> 16) & 0xFF)) <= tolerance
                        && Math.abs(((rgb >> 8) & 0xFF) - ((target >> 8) & 0xFF)) <= tolerance
                        && Math.abs((rgb & 0xFF) - (target & 0xFF)) <= tolerance) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 离屏布局：让没有真正显示的组件也把位置算出来。 */
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }
}

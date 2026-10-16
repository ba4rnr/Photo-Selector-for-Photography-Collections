package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.ComponentEvent;
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
            filmstrip.setPhotos(photos, 120);
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
            filmstrip.setPhotos(photos, height);
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
            filmstrip.setPhotos(photos, 120);
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
        loader.shutdown();
    }

    @Test
    void 视口变宽后新露出来的格子也会去取图() throws Exception {
        Theme.install();

        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            photos.add(TestImages.writeJpeg(tempDir.resolve("wide-" + i + ".jpg"), 900, 600));
        }

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        FilmstripPanel filmstrip = new FilmstripPanel(loader);
        // 用生产代码里那套滚动面板，连同它挂的监听一起测
        JScrollPane scroll = filmstrip.wrapInScrollPane();

        SwingUtilities.invokeAndWait(() -> {
            scroll.setSize(400, 190);
            filmstrip.setPhotos(photos, 120);
            layoutTree(scroll);
        });
        int before = waitUntilStable(filmstrip, 2);
        assertTrue(before < photos.size(), "窄视口下不应当把 12 张全解出来");

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

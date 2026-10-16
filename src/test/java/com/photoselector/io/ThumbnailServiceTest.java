package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.testing.TestImages;

class ThumbnailServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void 第一次生成会写出磁盘缓存() throws Exception {
        Path photo = TestImages.writeJpeg(tempDir.resolve("photo.jpg"), 1000, 800);
        ThumbnailService service = new ThumbnailService(tempDir.resolve("cache"));

        BufferedImage thumbnail = service.thumbnail(photo, 120);

        assertEquals(120, thumbnail.getHeight());
        assertEquals(150, thumbnail.getWidth());
        assertTrue(Files.isRegularFile(service.cacheFileFor(photo, 120)), "应当写出磁盘缓存");
    }

    @Test
    void 缓存文件损坏时会被忽略并重新生成() throws Exception {
        Path photo = TestImages.writeJpeg(tempDir.resolve("photo.jpg"), 600, 400);
        Path cacheDir = tempDir.resolve("cache");

        Path cacheFile = new ThumbnailService(cacheDir).cacheFileFor(photo, 100);
        new ThumbnailService(cacheDir).thumbnail(photo, 100);
        assertTrue(Files.isRegularFile(cacheFile));

        Files.write(cacheFile, new byte[] {1, 2, 3}); // 假装上次写到一半被打断

        // 换一个实例，保证内存缓存是空的，强制它去读磁盘
        BufferedImage recovered = new ThumbnailService(cacheDir).thumbnail(photo, 100);

        assertEquals(100, recovered.getHeight(), "坏缓存应当被忽略，重新解出正确的图");
        assertTrue(Files.size(cacheFile) > 3, "重新生成的缓存应当被写回去");
    }

    @Test
    void 照片改动之后缓存键跟着变() throws Exception {
        Path photo = TestImages.writeJpeg(tempDir.resolve("photo.jpg"), 400, 300);
        ThumbnailService service = new ThumbnailService(tempDir.resolve("cache"));

        Path before = service.cacheFileFor(photo, 100);
        Files.setLastModifiedTime(photo, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        Path after = service.cacheFileFor(photo, 100);

        assertNotEquals(before, after, "文件变了，缓存就该重新算");
    }

    @Test
    void 文件不在了就给一张占位图() {
        ThumbnailService service = new ThumbnailService(tempDir.resolve("cache"));

        BufferedImage image = service.thumbnail(tempDir.resolve("早就被删了.jpg"), 100);

        assertEquals(100, image.getHeight(), "取不到图不该抛异常，而是一张占位图");
        assertEquals(150, image.getWidth());
    }

    @Test
    void 文件损坏时也给占位图() throws Exception {
        Path broken = tempDir.resolve("broken.jpg");
        Files.write(broken, new byte[] {1, 2, 3, 4, 5});
        ThumbnailService service = new ThumbnailService(tempDir.resolve("cache"));

        BufferedImage image = service.thumbnail(broken, 100);

        assertEquals(100, image.getHeight(), "坏文件不该让整批取图一起崩掉");
    }

    @Test
    void 内存缓存按字节数淘汰最久没用的() throws Exception {
        Path cacheDir = tempDir.resolve("cache");
        // 每张 300×200 的缩略图约 24 万字节，把预算设成只放得下一张
        ThumbnailService service = new ThumbnailService(cacheDir, 300_000, 64L * 1024 * 1024, 200);

        for (int i = 0; i < 3; i++) {
            Path photo = TestImages.writeJpeg(tempDir.resolve("p" + i + ".jpg"), 900, 600);
            service.thumbnail(photo, 200);
        }

        assertEquals(1, service.cachedCount(), "预算只够一张，多余的应当被淘汰");
        assertTrue(service.cachedBytes() <= 300_000, "占用的字节数不该超出预算");
    }

    @Test
    void 磁盘缓存超过上限时会删掉最旧的() throws Exception {
        Path cacheDir = tempDir.resolve("cache");
        Path first = TestImages.writeJpeg(tempDir.resolve("first.jpg"), 800, 600);

        // 先量出一张缓存文件有多大，再把上限设成"刚好放得下一张再多一点"
        ThumbnailService probe = new ThumbnailService(cacheDir);
        probe.thumbnail(first, 100);
        long oneFileBytes = Files.size(probe.cacheFileFor(first, 100));

        ThumbnailService service = new ThumbnailService(cacheDir, 64L * 1024 * 1024, oneFileBytes + 200, 1);

        service.thumbnail(first, 100);
        service.thumbnail(TestImages.writeJpeg(tempDir.resolve("second.jpg"), 800, 600), 100);
        service.thumbnail(TestImages.writeJpeg(tempDir.resolve("third.jpg"), 800, 600), 100);

        long remaining;
        try (Stream<Path> entries = Files.list(cacheDir)) {
            remaining = entries.filter(Files::isRegularFile).count();
        }
        assertEquals(1, remaining, "超上限时应当只留下最新的那一张");
    }

    @Test
    void 真实照片的联系表() throws Exception {
        String configured = System.getProperty("photoselector.samples");
        assumeTrue(configured != null && Files.isDirectory(Path.of(configured)),
                "没有用 -Dphotoselector.samples=<目录> 指定样例照片，跳过");

        List<Path> photos;
        try (Stream<Path> entries = Files.list(Path.of(configured))) {
            photos = entries.filter(TestImages::looksLikeJpeg).sorted().limit(8).toList();
        }
        assumeTrue(!photos.isEmpty(), "这个目录里没有 JPG");

        ThumbnailService service = new ThumbnailService(tempDir.resolve("cache"));
        int height = 200;
        List<BufferedImage> thumbnails = new ArrayList<>();
        for (Path photo : photos) {
            thumbnails.add(service.thumbnail(photo, height));
        }

        Path sheet = Path.of("target", "samples-contact-sheet.png");
        Files.createDirectories(sheet.getParent());
        ImageIO.write(contactSheet(thumbnails), "png", sheet.toFile());
        System.out.println("联系表已写出：" + sheet.toAbsolutePath());
    }

    /** 把若干张缩略图拼成一张联系表，用来肉眼核对比例、方向和画质。 */
    private static BufferedImage contactSheet(List<BufferedImage> thumbnails) {
        int columns = 4;
        int gap = 14;
        int cellWidth = thumbnails.stream().mapToInt(BufferedImage::getWidth).max().orElse(100);
        int cellHeight = thumbnails.stream().mapToInt(BufferedImage::getHeight).max().orElse(100) + 22;
        int rows = (thumbnails.size() + columns - 1) / columns;

        BufferedImage sheet = new BufferedImage(
                columns * cellWidth + (columns + 1) * gap,
                rows * cellHeight + (rows + 1) * gap,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2D2D2D));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));

        for (int i = 0; i < thumbnails.size(); i++) {
            BufferedImage thumbnail = thumbnails.get(i);
            int x = gap + (i % columns) * (cellWidth + gap);
            int y = gap + (i / columns) * (cellHeight + gap);
            g.drawImage(thumbnail, x, y, null);
            g.setColor(new Color(0xC3C3C3));
            g.drawString(thumbnail.getWidth() + " × " + thumbnail.getHeight(),
                    x, y + thumbnail.getHeight() + 16);
        }
        g.dispose();
        return sheet;
    }
}

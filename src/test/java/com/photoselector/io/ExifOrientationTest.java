package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.testing.TestImages;

/**
 * EXIF 方向：读得出来、摆得正。
 *
 * <p>相机竖拍时像素是横着存的，只在 EXIF 里写一句"显示时转 90 度"。
 * 不认这句话，竖拍的照片在素材条、目标面板、大图里就全是躺着的。
 */
class ExifOrientationTest {

    @TempDir
    Path tempDir;

    @Test
    void 能读出方向标记() throws Exception {
        Path upright = TestImages.writeJpeg(tempDir.resolve("正.jpg"), 120, 80);
        assertEquals(1, ExifOrientation.read(upright), "没有 EXIF 就当正放");

        Path rotated = TestImages.writeJpegWithOrientation(
                tempDir.resolve("转90.jpg"), 120, 80, 6);
        assertEquals(6, ExifOrientation.read(rotated));
    }

    @Test
    void 方向六_宽高互换_横躺的照片立起来() throws Exception {
        // 120×80 横躺，方向 6 = 顺时针转 90°，应当变成 80×120 的竖图
        Path file = TestImages.writeJpegWithOrientation(tempDir.resolve("竖.jpg"), 120, 80, 6);
        ImageSource source = ImageSource.open(file);

        assertEquals(80, source.width(), "转 90° 之后宽高互换");
        assertEquals(120, source.height());

        BufferedImage full = source.loadFull();
        assertEquals(80, full.getWidth());
        assertEquals(120, full.getHeight());
    }

    @Test
    void 方向六_原图的左上角转到右上角() {
        // 手工造一张 4×2 的图，四角各一个颜色，验证转的方向对不对
        BufferedImage stored = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        stored.setRGB(0, 0, 0xFF0000); // 左上：红
        stored.setRGB(3, 0, 0x00FF00); // 右上：绿
        stored.setRGB(0, 1, 0x0000FF); // 左下：蓝
        stored.setRGB(3, 1, 0xFFFF00); // 右下：黄

        BufferedImage turned = ExifOrientation.apply(stored, 6);

        assertEquals(2, turned.getWidth(), "4×2 转 90° 之后是 2×4");
        assertEquals(4, turned.getHeight());
        // 顺时针转 90°：原来的左上角跑到右上角，原来的右上角跑到右下角
        assertEquals(0xFF0000, turned.getRGB(1, 0) & 0xFFFFFF, "红应当在右上");
        assertEquals(0x00FF00, turned.getRGB(1, 3) & 0xFFFFFF, "绿应当在右下");
        assertEquals(0x0000FF, turned.getRGB(0, 0) & 0xFFFFFF, "蓝应当在左上");
    }

    @Test
    void 八种方向的映射逐个核对() {
        // 储图左上角放一个红点，其余全黑。八种方向各转一次，红点该落在哪儿，
        // 按 EXIF 的定义一个一个人工推导出来（别用位运算糊过去，错了肉眼很难发现）。
        // 储图是 2×3，转过的那四个方向宽高互换后是 3×2。储图的左上角（红点）会搬到：
        //   1 正放 → (0,0)；2 水平镜像 → (1,0)；3 转 180° → (1,2)；4 垂直镜像 → (0,2)
        //   5 转 90°+镜像 → (0,0)；6 转 90° → (2,0)；7 转 270°+镜像 → (2,1)；8 转 270° → (0,1)
        // （这张表是照着实现逐个核出来的，不是背的——第一版我凭印象写的结果错了三个）
        int[][] expected = {
                {0, 0}, // 1
                {1, 0}, // 2 镜像到右边
                {1, 2}, // 3 转 180°
                {0, 2}, // 4 镜像到下边
                {0, 0}, // 5
                {2, 0}, // 6
                {2, 1}, // 7
                {0, 1}, // 8
        };
        for (int orientation = 1; orientation <= 8; orientation++) {
            BufferedImage stored = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
            stored.setRGB(0, 0, 0xFF0000);

            BufferedImage turned = ExifOrientation.apply(stored, orientation);

            if (orientation >= 5) {
                assertTrue(turned.getWidth() == 3 && turned.getHeight() == 2,
                        "方向 " + orientation + " 应当宽高互换");
            } else {
                assertTrue(turned.getWidth() == 2 && turned.getHeight() == 3,
                        "方向 " + orientation + " 不该换宽高");
            }
            int[] want = expected[orientation - 1];
            assertEquals(0xFF0000, turned.getRGB(want[0], want[1]) & 0xFFFFFF,
                    "方向 " + orientation + " 的红点应当落在 (" + want[0] + "," + want[1] + ")");
        }
    }

    @Test
    void 缩略图也按方向摆正() throws Exception {
        // 用竖拍的 300×200（像素横着）造一张：摆正后应当是 200 宽、300 高
        Path file = TestImages.writeJpegWithOrientation(
                tempDir.resolve("竖.jpg"), 300, 200, 6);

        BufferedImage thumbnail = new ThumbnailService(tempDir.resolve("cache"))
                .thumbnail(file, 100);

        assertTrue(thumbnail.getHeight() <= 100, "缩略图高度不超过目标");
        assertTrue(thumbnail.getWidth() < thumbnail.getHeight(),
                "摆正之后是竖图，宽应当小于高（实际 "
                        + thumbnail.getWidth() + "×" + thumbnail.getHeight() + "）");
    }

    @Test
    void 缓存里躺着一张老版本画的横图_不该再被当成新鲜的用() throws Exception {
        // 复现用户遇到的情况：程序升级前缓存过一张"方向没摆正"的缩略图，
        // 照片文件本身一直没变——按老规矩（键里只有路径/大小/时间）它会被一直用下去
        Path photo = TestImages.writeJpegWithOrientation(
                tempDir.resolve("竖.jpg"), 300, 200, 6);
        ThumbnailService service = new ThumbnailService(tempDir.resolve("cache"));

        // 假装老版本写下的缓存：放在**老算法**算出来的位置上
        // （第一版这条测试把它写到了当前版本号的路径上，等于伪造了一份"当前缓存"，
        //   测出来的是"缓存照常命中"——自欺欺人）。
        Path legacyFile = service.cacheFileFor(photo, 100).getParent()
                .resolve(legacyKey(photo, 100) + ".png");
        Files.createDirectories(legacyFile.getParent());
        // 尺寸用一个独有的（333×111）：别撞上"占位图"的 150×100，
        // 否则"旧缓存还在用"和"读盘失败回了占位图"分不出来
        BufferedImage stale = new BufferedImage(333, 111, BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(stale, "png", legacyFile.toFile());

        BufferedImage got = service.thumbnail(photo, 100);

        assertEquals(333 == got.getWidth() && 111 == got.getHeight(), false,
                "旧的缓存图不该再被端出来（实际 " + got.getWidth() + "×" + got.getHeight() + "）");
        assertTrue(got.getWidth() < got.getHeight(),
                "老缓存该被作废、按 EXIF 重算：应当是竖图，实际 "
                        + got.getWidth() + "×" + got.getHeight());
        assertTrue(Files.exists(service.cacheFileFor(photo, 100)),
                "顺手把新版本的结果写进了缓存");
    }

    /** 老的缓存键算法（没有版本号那一项）：用来伪造"上一版本留下的缓存"。 */
    private static String legacyKey(Path photo, int maxHeight) throws Exception {
        String raw = photo.toAbsolutePath()
                + "|" + Files.size(photo)
                + "|" + Files.getLastModifiedTime(photo).toMillis()
                + "|" + maxHeight;
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return hex.toString();
    }

    @Test
    void 缩略图和大图的宽高比一致() throws Exception {
        // 两边都得摆正，不然会出现"大图是竖的、缩略图是横的"这种对不上
        Path photo = TestImages.writeJpegWithOrientation(
                tempDir.resolve("竖.jpg"), 300, 200, 8);
        ImageSource source = ImageSource.open(photo);

        boolean fullPortrait = source.width() < source.height();
        BufferedImage thumbnail = new ThumbnailService(tempDir.resolve("cache"))
                .thumbnail(photo, 100);
        boolean thumbPortrait = thumbnail.getWidth() < thumbnail.getHeight();

        assertEquals(fullPortrait, thumbPortrait, "缩略图的方向要和大图一致");
    }

    @Test
    void 没有方向标记的照片原样不动() throws Exception {
        Path file = TestImages.writeJpeg(tempDir.resolve("普通.jpg"), 120, 80);
        ImageSource source = ImageSource.open(file);

        assertEquals(120, source.width());
        assertEquals(80, source.height());
        assertEquals(80, source.loadThumbnail(80).getHeight());
    }
}

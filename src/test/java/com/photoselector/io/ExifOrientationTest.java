package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
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
    void 没有方向标记的照片原样不动() throws Exception {
        Path file = TestImages.writeJpeg(tempDir.resolve("普通.jpg"), 120, 80);
        ImageSource source = ImageSource.open(file);

        assertEquals(120, source.width());
        assertEquals(80, source.height());
        assertEquals(80, source.loadThumbnail(80).getHeight());
    }
}

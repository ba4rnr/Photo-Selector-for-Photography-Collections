package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.testing.TestImages;

class JpegImageSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void 不解码整张图也能问出尺寸() throws Exception {
        Path file = TestImages.writeJpeg(tempDir.resolve("wide.jpg"), 800, 600);

        ImageSource source = ImageSource.open(file);

        assertEquals(800, source.width());
        assertEquals(600, source.height());
        assertEquals("wide.jpg", source.fileName());
    }

    @Test
    void 全尺寸解码拿到的是原始像素() throws Exception {
        Path file = TestImages.writeJpeg(tempDir.resolve("full.jpg"), 640, 480);

        BufferedImage image = ImageSource.open(file).loadFull();

        assertEquals(640, image.getWidth());
        assertEquals(480, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, image.getType(),
                "大图也统一成一种像素格式，界面绘制性能才稳定");
    }

    @Test
    void 缩略图按高度缩放并保持长宽比() throws Exception {
        Path file = TestImages.writeJpeg(tempDir.resolve("ratio.jpg"), 1200, 600); // 2:1

        BufferedImage thumbnail = ImageSource.open(file).loadThumbnail(200);

        assertEquals(200, thumbnail.getHeight());
        assertEquals(400, thumbnail.getWidth());
    }

    @Test
    void 本来就小的图不会被放大() throws Exception {
        Path file = TestImages.writeJpeg(tempDir.resolve("small.jpg"), 120, 80);

        BufferedImage thumbnail = ImageSource.open(file).loadThumbnail(200);

        assertEquals(120, thumbnail.getWidth(), "放大只会让画面变糊，所以保持原样");
        assertEquals(80, thumbnail.getHeight());
    }

    @Test
    void 不认识的格式会明确报错() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> ImageSource.open(Path.of("photo.dng")));
        assertTrue(error.getMessage().contains(".dng"), "报错信息里应当带上不认识的文件名");
    }
}

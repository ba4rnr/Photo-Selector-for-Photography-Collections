package com.photoselector.io;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;

/** 测试用的假照片：造一张颜色和形状都固定、尺寸可控的 JPG。 */
final class TestImages {

    private TestImages() {
    }

    static Path writeJpeg(Path file, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0xFF8B16));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(0x191919));
        g.fillOval(width / 8, height / 8, width * 3 / 4, height * 3 / 4);
        g.dispose();
        ImageIO.write(image, "jpg", file.toFile());
        return file;
    }

    static boolean looksLikeJpeg(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }
}

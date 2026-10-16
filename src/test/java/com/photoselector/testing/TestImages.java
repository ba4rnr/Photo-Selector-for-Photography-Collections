package com.photoselector.testing;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * 测试用的假照片：造一张颜色和形状都固定、尺寸可控的 JPG。
 *
 * <p>放在公共的 testing 包里，是为了让 io 和 ui 两个包的测试都能用。
 */
public final class TestImages {

    private TestImages() {
    }

    public static Path writeJpeg(Path file, int width, int height) throws IOException {
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

    public static boolean looksLikeJpeg(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }
}

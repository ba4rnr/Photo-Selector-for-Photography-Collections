package com.photoselector.io;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 用 JDK 自带的 ImageIO 读 JPG。
 *
 * <p>两个关键做法，都是为了快：
 * <ul>
 *   <li><b>问尺寸不解码</b>。JPEG 的文件头里就写着宽高，用 ImageReader 问一下即可，
 *       只读几十字节，而不是把整张 24MP 的图解出来再量。</li>
 *   <li><b>做缩略图先粗后细</b>。让解码器直接"隔行隔列"取样，
 *       先把图解成大约目标尺寸的两倍，再插值缩到目标大小。
 *       比全尺寸解码再缩小快好几倍，画质却看不出差别。</li>
 * </ul>
 */
public final class JpegImageSource implements ImageSource {

    private final Path file;

    /** 宽高只读一次文件头就记住，之后不再碰磁盘。 */
    private int width = -1;
    private int height = -1;

    /** EXIF 方向只读一次；1 = 正放。 */
    private int orientation;

    JpegImageSource(Path file) {
        this.file = Objects.requireNonNull(file, "file 不能为空");
    }

    @Override
    public String fileName() {
        return file.getFileName().toString();
    }

    @Override
    public int width() {
        readSizeIfNeeded();
        return ExifOrientation.swapsSides(orientation) ? height : width;
    }

    @Override
    public int height() {
        readSizeIfNeeded();
        return ExifOrientation.swapsSides(orientation) ? width : height;
    }

    /** 文件里存的像素宽度（没按 EXIF 转过的那一套）。 */
    private int storedWidth() {
        readSizeIfNeeded();
        return width;
    }

    /** 文件里存的像素高度。 */
    private int storedHeight() {
        readSizeIfNeeded();
        return height;
    }

    @Override
    public BufferedImage loadFull() {
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                throw new IOException("这不是能识别的图像格式");
            }
            // 相机竖拍的照片像素是横着存的，靠 EXIF 那句"显示时转 90 度"。
            // 在这儿摆正，界面拿到的就永远是"看着是对的"那一版。
            return toRgb(ExifOrientation.apply(image, orientation()));
        } catch (IOException e) {
            throw new UncheckedIOException("解码失败：" + file, e);
        }
    }

    @Override
    public BufferedImage loadThumbnail(int maxHeight) {
        if (maxHeight < 1) {
            throw new IllegalArgumentException("目标高度必须为正：" + maxHeight);
        }
        readSizeIfNeeded();

        // 采样倍数：让粗解出来的图仍然比目标大一些，缩放才有余量
        int step = Math.max(1, Math.min(storedWidth(), storedHeight()) / (maxHeight * 2));

        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = firstReader(stream);
            try {
                ImageReadParam param = reader.getDefaultReadParam();
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage sampled = reader.read(0, param);
                if (sampled == null) {
                    throw new IOException("解码器没有返回图像");
                }
                // 先摆正再缩放：转过的图宽高变了，"按高度缩"要按摆正之后的来算
                return scaleToHeight(ExifOrientation.apply(sampled, orientation()), maxHeight);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("生成缩略图失败：" + file, e);
        }
    }

    private void readSizeIfNeeded() {
        if (width > 0) {
            return;
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = firstReader(stream);
            try {
                width = reader.getWidth(0);
                height = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读不出图像尺寸：" + file, e);
        }
        orientation = ExifOrientation.read(file);
    }

    /** 方向值；没读出来按正放算。 */
    private int orientation() {
        readSizeIfNeeded();
        return orientation == 0 ? 1 : orientation;
    }

    /** 让 ImageIO 挑一个能读这个文件的解码器，并把输入接上去。 */
    private static ImageReader firstReader(ImageInputStream stream) throws IOException {
        if (stream == null) {
            throw new IOException("无法建立图像输入流");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) {
            throw new IOException("没有能读这个格式的解码器");
        }
        ImageReader reader = readers.next();
        reader.setInput(stream, true, true);
        return reader;
    }

    /** 等比缩到指定高度；本来就比目标小就不放大。 */
    private static BufferedImage scaleToHeight(BufferedImage source, int maxHeight) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();

        if (sourceHeight <= maxHeight) {
            return toRgb(source); // 已经够小，放大只会变糊
        }

        int targetWidth = Math.max(1, Math.round(sourceWidth * (float) maxHeight / sourceHeight));
        BufferedImage target = new BufferedImage(targetWidth, maxHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, targetWidth, maxHeight, null);
        g.dispose();
        return target;
    }

    /**
     * 统一转成 {@code TYPE_INT_RGB}。
     *
     * <p>相机出来的 JPEG 可能是灰度、CMYK 或带透明通道的各种类型，
     * 统一成一种，后面的缓存和界面绘制才不会有意外。
     */
    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }
}

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

    /**
     * 给一张 JPG 塞进"EXIF 方向"标记（只写这一个 tag）。
     *
     * <p>ImageIO 写不出 EXIF，所以直接在字节层面拼一个 APP1 段插到文件头后面——
     * 相机就是这么写的，读的一方（我们自己的 {@code ExifOrientation}）也按这个读。
     */
    public static Path writeJpegWithOrientation(Path file, int width, int height,
                                                int orientation) throws IOException {
        writeJpeg(file, width, height);
        byte[] jpeg = java.nio.file.Files.readAllBytes(file);
        byte[] exifSegment = exifSegment(orientation, width, height);
        byte[] out = new byte[jpeg.length + exifSegment.length];
        // ImageIO 生成的 JPEG 在 SOI 后面紧跟 APP0/JFIF。EXIF 的 APP1 要放在它后面，
        // 否则 JDK 自己读元数据时会报“JFIF APP0 must be first marker after SOI”。
        int insertAt = afterApp0(jpeg);
        System.arraycopy(jpeg, 0, out, 0, insertAt);
        System.arraycopy(exifSegment, 0, out, insertAt, exifSegment.length);
        System.arraycopy(jpeg, insertAt, out, insertAt + exifSegment.length,
                jpeg.length - insertAt);
        java.nio.file.Files.write(file, out);
        return file;
    }

    /**
     * 往现有 JPG 里插一个完整的 APPn 段；给测试 ICC / IPTC / XMP 保留用。
     *
     * @param marker 段标记，例如 0xE2 表示 APP2
     * @param payload 段内容（不含 marker 和长度字段）
     */
    public static Path insertAppSegment(Path file, int marker, byte[] payload)
            throws IOException {
        if (marker < 0xE0 || marker > 0xEF) {
            throw new IllegalArgumentException("不是 APPn 段：" + marker);
        }
        if (payload.length > 65533) {
            throw new IllegalArgumentException("单个 JPEG 段不能超过 65533 字节");
        }
        byte[] jpeg = java.nio.file.Files.readAllBytes(file);
        byte[] segment = new byte[payload.length + 4];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) marker;
        int length = payload.length + 2;
        segment[2] = (byte) (length >> 8);
        segment[3] = (byte) length;
        System.arraycopy(payload, 0, segment, 4, payload.length);

        int insertAt = afterApp0(jpeg);
        byte[] out = new byte[jpeg.length + segment.length];
        System.arraycopy(jpeg, 0, out, 0, insertAt);
        System.arraycopy(segment, 0, out, insertAt, segment.length);
        System.arraycopy(jpeg, insertAt, out, insertAt + segment.length,
                jpeg.length - insertAt);
        java.nio.file.Files.write(file, out);
        return file;
    }

    /** APP0 段之后的插入位置；没有 APP0 就在 SOI 之后。 */
    private static int afterApp0(byte[] jpeg) {
        if (jpeg.length >= 6 && (jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            int length = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
            int end = 4 + length; // 2 字节标记 + 2 字节长度 + (length-2) 字节内容
            if (end <= jpeg.length) {
                return end;
            }
        }
        return 2;
    }

    /**
     * 造一个结构完整的小端 EXIF 段：宽高、方向、Make、Exif SubIFD 和 IFD1 缩略图。
     *
     * <p>这些派生字段正是编辑重写时最容易忘记同步的部分，测试素材必须真的带上它们。
     */
    private static byte[] exifSegment(int orientation, int width, int height) throws IOException {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes("Exif".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        body.write(0);
        body.write(0);
        // TIFF：小端（II）、版本 42、IFD0 从第 8 字节开始
        body.writeBytes(new byte[] {'I', 'I', 42, 0});
        body.writeBytes(new byte[] {8, 0, 0, 0});
        // IFD0 从 TIFF 偏移 8 开始，5 条记录共 66 字节；随后是 Exif SubIFD 与 IFD1。
        body.writeBytes(new byte[] {5, 0});
        // ImageWidth / ImageLength：LONG，各 1 项
        tiffEntry(body, 0x0100, 4, 1, width);
        tiffEntry(body, 0x0101, 4, 1, height);
        // Make：ASCII，数量 3，内联 "Ab\0"
        body.writeBytes(new byte[] {0x0F, 0x01, 2, 0, 3, 0, 0, 0, 'A', 'b', 0, 0});
        // tag 0x0112（方向）、类型 3（short）、数量 1、值
        tiffEntry(body, 0x0112, 3, 1, orientation);
        // ExifIFDPointer -> TIFF 偏移 74
        tiffEntry(body, 0x8769, 4, 1, 74);
        tiffLong(body, 104); // IFD1

        // Exif SubIFD：PixelXDimension / PixelYDimension
        body.writeBytes(new byte[] {2, 0});
        tiffEntry(body, 0xA002, 4, 1, width);
        tiffEntry(body, 0xA003, 4, 1, height);
        tiffLong(body, 0);

        byte[] thumbnail = tinyJpeg(24, 16);
        // IFD1：旧的内嵌缩略图（24×16），紧跟在 IFD1 之后
        body.writeBytes(new byte[] {4, 0});
        tiffEntry(body, 0x0100, 3, 1, 24);   // ImageWidth
        tiffEntry(body, 0x0101, 3, 1, 16);   // ImageLength
        tiffEntry(body, 0x0201, 4, 1, 158);  // JPEGInterchangeFormat
        tiffEntry(body, 0x0202, 4, 1, thumbnail.length);
        tiffLong(body, 0);
        body.writeBytes(thumbnail);

        byte[] payload = body.toByteArray();
        byte[] segment = new byte[payload.length + 4];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        int length = payload.length + 2;
        segment[2] = (byte) (length >> 8);
        segment[3] = (byte) length;
        System.arraycopy(payload, 0, segment, 4, payload.length);
        return segment;
    }

    /** 12 字节的 TIFF IFD 项；数值按 4 字节内联字段写。 */
    private static void tiffEntry(java.io.ByteArrayOutputStream out, int tag, int type,
                                  int count, int value) {
        tiffShort(out, tag);
        tiffShort(out, type);
        tiffLong(out, count);
        tiffLong(out, value);
    }

    private static void tiffShort(java.io.ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void tiffLong(java.io.ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static byte[] tinyJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x808080));
        g.fillRect(0, 0, width, height);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}

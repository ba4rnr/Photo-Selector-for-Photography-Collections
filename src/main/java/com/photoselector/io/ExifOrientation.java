package com.photoselector.io;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * EXIF 的"方向"标记：读出它，并把解出来的图摆正。
 *
 * <p>为什么必须处理：相机竖着拍的时候，像素其实还是横着存的，只是在 EXIF 里写了一句
 * "显示时转 90 度"。不理会这句话，竖拍的照片在素材条、目标面板和大图里全是躺着的。
 * JDK 的 ImageIO 不管这个标记，所以自己读。
 *
 * <p>只认这一个标记，不做通用的 EXIF 库。做法是顺着 JPEG 的段链找到 APP1，
 * 再按 TIFF 的规矩翻到 IFD 里第一条第几个 tag——都是按偏移量读，不解析别的东西。
 */
public final class ExifOrientation {

    /** TIFF 里方向标记的编号。 */
    private static final int TAG_ORIENTATION = 0x0112;

    /** 段的起始字节，以及 APP1 段的编号。 */
    private static final int MARKER_PREFIX = 0xFF;
    private static final int APP1 = 0xE1;
    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;

    private ExifOrientation() {
    }

    /**
     * 读出这张照片的方向；读不到、文件没有 EXIF、值不合法，都返回 1（正放）。
     *
     * <p>"读不到就当正放"是有意的：一张照片的元数据坏了不该让它显示不出来。
     */
    public static int read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] exif = findExifSegment(in);
            return exif == null ? 1 : orientationIn(exif);
        } catch (IOException e) {
            return 1; // 读不了当成正放，让上层的报错机制去管
        }
    }

    /** 方向值是不是"要转 90 度"那一类（转了之后宽高互换）。 */
    public static boolean swapsSides(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    /**
     * 按方向把图摆正。方向 1（正放）原样返回，不做无谓的拷贝。
     *
     * <p>八种方向其实就是"先镜像、再转 90 度"的组合。这里按像素直接搬：
     * 对每个存储位置的像素算出它在显示图上的位置，一次搬完。
     */
    public static BufferedImage apply(BufferedImage stored, int orientation) {
        if (orientation <= 1 || orientation > 8 || stored == null) {
            return stored;
        }
        int w = stored.getWidth();
        int h = stored.getHeight();
        boolean swap = swapsSides(orientation);
        BufferedImage out = new BufferedImage(
                swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // 每个方向就是"存储位置 → 显示位置"的一个固定映射，逐个写清楚，
                // 别用花哨的位运算——这段错了肉眼很难发现，写直白点好核对
                int dx;
                int dy;
                switch (orientation) {
                    // 1 = 正放（不动）；2..4 是三种不换宽高的镜像 / 旋转；
                    // 5..8 会把宽高换过来：6 / 8 是纯转 90°（顺时针 / 逆时针），
                    // 5 / 7 是另外两种镜像（沿对角线），相机里很少见，但规范里有
                    case 2 -> { dx = w - 1 - x; dy = y; }          // 水平镜像
                    case 3 -> { dx = w - 1 - x; dy = h - 1 - y; }  // 转 180°
                    case 4 -> { dx = x; dy = h - 1 - y; }          // 垂直镜像
                    case 5 -> { dx = y; dy = x; }                  // 沿主对角线镜像（转置）
                    case 6 -> { dx = h - 1 - y; dy = x; }          // 转 90°（顺时针）
                    case 7 -> { dx = h - 1 - y; dy = w - 1 - x; }  // 沿副对角线镜像
                    case 8 -> { dx = y; dy = w - 1 - x; }          // 转 90°（逆时针）
                    default -> { dx = x; dy = y; }
                }
                out.setRGB(dx, dy, stored.getRGB(x, y));
            }
        }
        return out;
    }

    // ─────────────────────────── 从 JPEG 里把 EXIF 段挖出来 ───────────────────────────

    /** 顺着段链找 APP1，返回"Exif\0\0"之后的那一段；没有就返回 null。 */
    private static byte[] findExifSegment(InputStream in) throws IOException {
        if (in.read() != MARKER_PREFIX || in.read() != SOI) {
            return null; // 不是 JPEG
        }
        while (true) {
            int first = in.read();
            if (first < 0) {
                return null;
            }
            if (first != MARKER_PREFIX) {
                continue; // 中间可能夹着填充字节
            }
            int marker = in.read();
            if (marker < 0) {
                return null;
            }
            if (marker == EOI) {
                return null;
            }
            if (marker == APP1) {
                int length = (in.read() << 8) | in.read();
                if (length < 2) {
                    return null;
                }
                byte[] payload = in.readNBytes(length - 2);
                if (payload.length < 6
                        || payload[0] != 'E' || payload[1] != 'x' || payload[2] != 'i'
                        || payload[3] != 'f' || payload[4] != 0 || payload[5] != 0) {
                    continue; // 不是 EXIF 的 APP1（比如 XMP），接着往下找
                }
                return java.util.Arrays.copyOfRange(payload, 6, payload.length);
            }
            if (marker >= 0xC0 && marker <= 0xFE) {
                // 别的段：读出长度，整段跳过去（长度含它自己那两字节）
                int length = (in.read() << 8) | in.read();
                if (length < 2 || in.skip(length - 2) != length - 2) {
                    return null;
                }
            }
        }
    }

    /** 在 TIFF 块里翻 IFD0，找方向标记。 */
    private static int orientationIn(byte[] exif) {
        try {
            boolean little;
            if (exif[0] == 'I' && exif[1] == 'I') {
                little = true;
            } else if (exif[0] == 'M' && exif[1] == 'M') {
                little = false;
            } else {
                return 1;
            }
            if (u16(exif, 2, little) != 42) {
                return 1;
            }
            int ifdOffset = u32(exif, 4, little);
            if (ifdOffset < 8 || ifdOffset + 2 > exif.length) {
                return 1;
            }
            int count = u16(exif, ifdOffset, little);
            for (int i = 0; i < count; i++) {
                int entry = ifdOffset + 2 + i * 12;
                if (entry + 12 > exif.length) {
                    return 1;
                }
                if (u16(exif, entry, little) != TAG_ORIENTATION) {
                    continue;
                }
                int value = u16(exif, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        } catch (EOFException | ArrayIndexOutOfBoundsException e) {
            return 1; // 元数据是坏的，当作正放
        }
        return 1;
    }

    private static int u16(byte[] data, int at, boolean little) throws EOFException {
        if (at + 2 > data.length) {
            throw new EOFException("EXIF 段落不完整");
        }
        return little
                ? ((data[at + 1] & 0xFF) << 8) | (data[at] & 0xFF)
                : ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static int u32(byte[] data, int at, boolean little) throws EOFException {
        if (at + 4 > data.length) {
            throw new EOFException("EXIF 段落不完整");
        }
        return little
                ? (data[at + 3] & 0xFF) << 24 | (data[at + 2] & 0xFF) << 16
                        | (data[at + 1] & 0xFF) << 8 | (data[at] & 0xFF)
                : (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16
                        | (data[at + 2] & 0xFF) << 8 | (data[at + 3] & 0xFF);
    }
}

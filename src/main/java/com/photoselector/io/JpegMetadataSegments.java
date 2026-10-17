package com.photoselector.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JPEG 元数据段的搬运：把原文件里的 EXIF / XMP / ICC / IPTC 等 APPn 段
 * 原样带到重新编码的新文件里。
 *
 * <p>为什么不通过 {@code IIOMetadata} 重建：JPEG 的 APP 段在 JDK 里常常只是一块
 * “unknown”原始字节。重新组装树容易丢字段，也容易被编码器重新排序。这里直接保留
 * 段字节，只对 EXIF 的方向字段做必要修改，其他内容一个字节都不碰。
 *
 * <p>只处理 APPn（0xE1…0xEF）和 COM（0xFE）；DQT / DHT / SOF / SOS 属于图像编码，
 * 必须由新的编码器自己生成。APP0/JFIF 也不搬，避免和新文件头重复。
 */
final class JpegMetadataSegments {

    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int APP0 = 0xE0;
    private static final int APP1 = 0xE1;
    private static final int COM = 0xFE;

    /** XMP 的 APP1 前缀；用于和 EXIF 的 APP1 区分。 */
    private static final byte[] XMP_PREFIX =
            "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);

    /** XMP 里可能同时记录方向；属性写法和元素写法都要归一成 1。 */
    private static final Pattern XMP_ATTRIBUTE_ORIENTATION = Pattern.compile(
            "(tiff:Orientation\\s*=\\s*[\"'])[1-8]([\"'])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern XMP_ELEMENT_ORIENTATION = Pattern.compile(
            "(<tiff:Orientation\\s*>\\s*)[1-8](\\s*</tiff:Orientation>)",
            Pattern.CASE_INSENSITIVE);

    private JpegMetadataSegments() {
    }

    /** 读出源文件里需要保留的完整 JPEG 段（含 FF、marker 和长度）。 */
    static List<byte[]> from(Path jpeg) throws IOException {
        byte[] data = Files.readAllBytes(jpeg);
        if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != SOI) {
            return List.of();
        }

        List<byte[]> segments = new ArrayList<>();
        int pos = 2;
        while (pos + 4 <= data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                pos++;
                continue;
            }
            int marker = data[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            if (marker == SOI || marker == EOI) {
                pos += 2;
                if (marker == EOI) {
                    break;
                }
                continue;
            }
            if (marker == SOS || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                break; // 扫描数据从这里开始，前面该收的元数据段已经收完
            }

            int length = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            int total = length + 2;
            if (length < 2 || total < 4 || pos + total > data.length) {
                break; // 段损坏；能保下来的已经保下来了
            }
            int segmentMarker = marker;
            if ((segmentMarker >= APP1 && segmentMarker <= 0xEF) || segmentMarker == COM) {
                byte[] segment = Arrays.copyOfRange(data, pos, pos + total);
                if (segmentMarker == APP1) {
                    if (isExif(segment)) {
                        normalizeExifOrientation(segment);
                    } else if (isXmp(segment)) {
                        normalizeXmpOrientation(segment);
                    }
                }
                segments.add(segment);
            }
            pos += total;
        }
        return segments;
    }

    /**
     * 把元数据段插入编码器刚写出的 JPEG。
     *
     * <p>APP0 必须排在 SOI 后面、其他 APP 段前面；因此插入点是 APP0 段的末尾。
     * 找不到 APP0 时退回 SOI 之后。
     */
    static byte[] inject(byte[] jpeg, List<byte[]> segments) {
        if (segments.isEmpty()) {
            return jpeg;
        }
        int insertAt = afterApp0(jpeg);
        int added = segments.stream().mapToInt(segment -> segment.length).sum();
        byte[] out = new byte[jpeg.length + added];
        System.arraycopy(jpeg, 0, out, 0, insertAt);
        int at = insertAt;
        for (byte[] segment : segments) {
            System.arraycopy(segment, 0, out, at, segment.length);
            at += segment.length;
        }
        System.arraycopy(jpeg, insertAt, out, at, jpeg.length - insertAt);
        return out;
    }

    private static int afterApp0(byte[] jpeg) {
        if (jpeg.length >= 6 && (jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == APP0) {
            int length = ((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF);
            int end = 4 + length;
            if (length >= 2 && end <= jpeg.length) {
                return end;
            }
        }
        return 2;
    }

    private static boolean isExif(byte[] segment) {
        return segment.length >= 10
                && (segment[4] & 0xFF) == 'E' && (segment[5] & 0xFF) == 'x'
                && (segment[6] & 0xFF) == 'i' && (segment[7] & 0xFF) == 'f'
                && segment[8] == 0 && segment[9] == 0;
    }

    /** EXIF 段：marker(2) + length(2) + "Exif\0\0"(6) 之后才是 TIFF。 */
    private static void normalizeExifOrientation(byte[] segment) {
        byte[] tiff = Arrays.copyOfRange(segment, 10, segment.length);
        if (ExifOrientation.setOrientation(tiff, 1)) {
            System.arraycopy(tiff, 0, segment, 10, tiff.length);
        }
    }

    private static boolean isXmp(byte[] segment) {
        if (segment.length < 4 + XMP_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < XMP_PREFIX.length; i++) {
            if (segment[4 + i] != XMP_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * XMP 有时还会写一份 `tiff:Orientation`。保留 XMP 的同时把它也改成 1，
     * 否则部分软件会优先读 XMP，再按旧方向转一次。
     */
    private static void normalizeXmpOrientation(byte[] segment) {
        String xml = new String(segment, 4, segment.length - 4, StandardCharsets.UTF_8);
        String normalized = replaceOrientation(XMP_ATTRIBUTE_ORIENTATION, xml);
        normalized = replaceOrientation(XMP_ELEMENT_ORIENTATION, normalized);
        if (!normalized.equals(xml)
                && normalized.getBytes(StandardCharsets.UTF_8).length == segment.length - 4) {
            byte[] payload = normalized.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(payload, 0, segment, 4, payload.length);
        }
    }

    private static String replaceOrientation(Pattern pattern, String xml) {
        Matcher matcher = pattern.matcher(xml);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + "1"
                    + matcher.group(2)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

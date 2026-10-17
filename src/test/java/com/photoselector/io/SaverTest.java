package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;

import com.photoselector.core.EditState;
import com.photoselector.core.NumberingRule;
import com.photoselector.core.Session;
import com.photoselector.testing.TestImages;

class SaverTest {

    @TempDir
    Path tempDir;

    private static Session sessionFor(Path target) {
        return new Session(target, NumberingRule.DEFAULT);
    }

    private static Set<String> fileNamesIn(Path folder) throws Exception {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    @Test
    void 复制新增_按新顺序改名_并删掉垃圾桶里的文件() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Path oldA = TestImages.writeJpeg(target.resolve("旧A.jpg"), 300, 200);
        Path oldB = TestImages.writeJpeg(target.resolve("旧B.jpg"), 320, 200);
        byte[] oldBBytes = Files.readAllBytes(oldB);

        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path fresh = TestImages.writeJpeg(source.resolve("新照片.jpg"), 340, 200);
        byte[] freshBytes = Files.readAllBytes(fresh);

        Session session = sessionFor(target);
        session.seedExisting(List.of(oldA, oldB));
        session.trash(0);      // 把"旧A"丢进垃圾桶
        session.add(fresh);    // 再把新照片挑进来

        Saver.Result result = new Saver().save(Saver.Plan.from(session));

        assertEquals(1, result.copied(), "复制了一张");
        assertEquals(1, result.renamed(), "旧B 改了名");
        assertEquals(1, result.deleted(), "旧A 被删掉");
        assertFalse(result.hasProblems(), "不该有问题：" + result.problems());

        // 剩下的两个槽位：第 0 项是旧B（现在叫 010），第 1 项是新照片（叫 020）
        assertEquals(Set.of("010.jpg", "020.jpg"), fileNamesIn(target));
        assertArrayEquals(oldBBytes, Files.readAllBytes(target.resolve("010.jpg")));
        assertArrayEquals(freshBytes, Files.readAllBytes(target.resolve("020.jpg")));

        // 最终顺序也要报出来，调用方据此重建中间商
        assertEquals(List.of(target.resolve("010.jpg"), target.resolve("020.jpg")), result.finalOrder());
    }

    @Test
    void 两个文件互换名字时不会互相覆盖() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Path first = TestImages.writeJpeg(target.resolve("020.jpg"), 300, 200);
        Path second = TestImages.writeJpeg(target.resolve("010.jpg"), 340, 200);
        byte[] firstBytes = Files.readAllBytes(first);
        byte[] secondBytes = Files.readAllBytes(second);

        // 序列顺序是 [020.jpg, 010.jpg]，于是最终名字正好互换
        Session session = sessionFor(target);
        session.seedExisting(List.of(first, second));

        Saver.Result result = new Saver().save(Saver.Plan.from(session));

        assertEquals(2, result.renamed());
        assertFalse(result.hasProblems(), "不该有问题：" + result.problems());
        assertEquals(Set.of("010.jpg", "020.jpg"), fileNamesIn(target));
        assertArrayEquals(firstBytes, Files.readAllBytes(target.resolve("010.jpg")),
                "原来叫 020 的那张应当改名叫 010，内容不变");
        assertArrayEquals(secondBytes, Files.readAllBytes(target.resolve("020.jpg")));
    }

    @Test
    void 目标文件夹里已有同名文件时不覆盖() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Path occupied = TestImages.writeJpeg(target.resolve("010.jpg"), 300, 200);
        byte[] occupiedBytes = Files.readAllBytes(occupied);

        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path fresh = TestImages.writeJpeg(source.resolve("新照片.jpg"), 340, 200);

        // 中间商里只有这一张新照片，它想叫 010，但那个名字已经被占了
        Session session = sessionFor(target);
        session.add(fresh);

        Saver.Result result = new Saver().save(Saver.Plan.from(session));

        assertEquals(0, result.copied());
        assertTrue(result.hasProblems(), "占用名字这件事应当被报出来");
        assertEquals(List.of(fresh), result.failedSources(),
                "没复制成功的条目要报回去，好让调用方放回中间商");
        assertArrayEquals(occupiedBytes, Files.readAllBytes(occupied), "原来那个文件一个字都没动");
    }

    @Test
    void 垃圾桶里没复制过的条目不会去删任何东西() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path fresh = TestImages.writeJpeg(source.resolve("新照片.jpg"), 300, 200);

        Session session = sessionFor(target);
        session.add(fresh);
        session.trash(0); // 只是把"指针"丢进垃圾桶，磁盘上从来没有过这个东西

        Saver.Result result = new Saver().save(Saver.Plan.from(session));

        assertEquals(0, result.deleted());
        assertTrue(Files.exists(fresh), "素材文件绝对不能被碰");
        assertEquals(Set.of(), fileNamesIn(target));
    }

    @Test
    void 已经叫对了名字的文件不会被反复搬运() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Path already = TestImages.writeJpeg(target.resolve("010.jpg"), 300, 200);

        Session session = sessionFor(target);
        session.seedExisting(new ArrayList<>(List.of(already)));

        Saver.Result result = new Saver().save(Saver.Plan.from(session));

        assertEquals(0, result.renamed(), "名字本来就对，不该动它");
        assertEquals(Set.of("010.jpg"), fileNamesIn(target));
    }

    @Test
    void 新挑的照片带旋转_保存时直接写成摆正后的JPG() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path fresh = TestImages.writeJpeg(source.resolve("竖构图.jpg"), 300, 200);

        Session session = sessionFor(target);
        session.add(fresh);
        session.setEdits(fresh, EditState.NONE.rotate(1));

        Saver.Result result = new Saver().save(Saver.Plan.from(session));

        assertEquals(1, result.copied());
        assertEquals(1, result.rewritten(), "带编辑的新照片应当按重写计数");
        assertFalse(result.hasProblems(), "不该有问题：" + result.problems());

        BufferedImage saved = ImageIO.read(target.resolve("010.jpg").toFile());
        assertEquals(200, saved.getWidth(), "顺时针转 90° 后宽高互换");
        assertEquals(300, saved.getHeight());
    }

    @Test
    void 原有照片带旋转_保存时用临时名安全重写() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Path old = TestImages.writeJpeg(target.resolve("旧A.jpg"), 300, 200);

        Session session = sessionFor(target);
        session.seedExisting(List.of(old));
        session.setEdits(old, EditState.NONE.rotate(1));

        Saver.Result result = new Saver().save(Saver.Plan.from(session));

        assertEquals(1, result.rewritten());
        assertEquals(0, result.renamed(), "重写编辑单独计数，不再混进普通改名");
        assertFalse(result.hasProblems(), "不该有问题：" + result.problems());
        assertEquals(Set.of("010.jpg"), fileNamesIn(target),
                "成功后不该留下 __renaming_ 临时文件");

        BufferedImage saved = ImageIO.read(target.resolve("010.jpg").toFile());
        assertEquals(200, saved.getWidth());
        assertEquals(300, saved.getHeight());
    }

    @Test
    void 重写编辑时保留EXIF_XMP_ICC和IPTC() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Path source = TestImages.writeJpegWithOrientation(
                tempDir.resolve("带元数据.jpg"), 300, 200, 6);
        assertEquals(6, ExifOrientation.read(source), "前置条件：源图带方向 6");

        byte[] xmp = concat(
                "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII),
                "<x:xmpmeta tiff:Orientation=\"6\">photo-selector-metadata</x:xmpmeta>"
                        .getBytes(StandardCharsets.UTF_8));
        byte[] icc = concat(
                concat("ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII),
                        new byte[] {1, 1}),
                ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
        byte[] iptc = concat(
                "Photoshop 3.0\0".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0x1C, 0x02, 0x05, 0x00, 0x03, 'I', 'P', 'T', 'C'});
        TestImages.insertAppSegment(source, 0xE1, xmp);
        TestImages.insertAppSegment(source, 0xE2, icc);
        TestImages.insertAppSegment(source, 0xED, iptc);

        Session session = sessionFor(target);
        session.add(source);
        session.setEdits(source, EditState.NONE.rotate(1));
        Saver.Result result = new Saver().save(Saver.Plan.from(session));

        assertFalse(result.hasProblems(), "不该有问题：" + result.problems());
        Path saved = target.resolve("010.jpg");
        assertEquals(1, ExifOrientation.read(saved),
                "像素已经摆正，EXIF 方向必须改成 1，避免看图软件再转一次");

        List<byte[]> segments = JpegMetadataSegments.from(saved, 300, 200);
        assertTrue(containsText(segments, 0xE1, "Ab"),
                "EXIF 里方向以外的字段（Make）也应当保留");
        assertTrue(containsText(segments, 0xE1, "photo-selector-metadata"),
                "XMP 内容应当保留");
        assertTrue(containsText(segments, 0xE1, "tiff:Orientation=\"1\""),
                "XMP 里记录的旧方向也要归一成 1");
        assertFalse(containsText(segments, 0xE1, "tiff:Orientation=\"6\""));
        assertTrue(containsSegment(segments, 0xE2, icc), "ICC 应当原样保留");
        assertTrue(containsSegment(segments, 0xED, iptc), "IPTC 应当原样保留");
    }

    @Test
    void 重写编辑时同步EXIF宽高并断开旧的内嵌缩略图() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Path source = TestImages.writeJpegWithOrientation(
                tempDir.resolve("带尺寸.jpg"), 300, 200, 1);

        Session session = sessionFor(target);
        session.add(source);
        session.setEdits(source, EditState.NONE.rotate(1));
        Saver.Result result = new Saver().save(Saver.Plan.from(session));

        assertFalse(result.hasProblems(), "不该有问题：" + result.problems());
        Path saved = target.resolve("010.jpg");
        List<byte[]> segments = JpegMetadataSegments.from(saved, 200, 300);
        byte[] tiff = exifTiff(segments);
        assertArrayEquals(new int[] {200, 300}, ExifOrientation.dimensions(tiff),
                "IFD0 / Exif SubIFD 的宽高都应当跟上旋转后的像素");
        assertFalse(ExifOrientation.hasThumbnailIfd(tiff),
                "旧缩略图 IFD 不能再挂在 EXIF 链上");
    }

    private static boolean containsSegment(List<byte[]> segments, int marker, byte[] payload) {
        for (byte[] segment : segments) {
            if ((segment[1] & 0xFF) == marker
                    && Arrays.equals(Arrays.copyOfRange(segment, 4, segment.length), payload)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] exifTiff(List<byte[]> segments) {
        for (byte[] segment : segments) {
            if ((segment[1] & 0xFF) == 0xE1
                    && segment.length > 10
                    && segment[4] == 'E' && segment[5] == 'x' && segment[6] == 'i'
                    && segment[7] == 'f' && segment[8] == 0 && segment[9] == 0) {
                return Arrays.copyOfRange(segment, 10, segment.length);
            }
        }
        throw new AssertionError("找不到 EXIF 段");
    }

    private static boolean containsText(List<byte[]> segments, int marker, String text) {
        byte[] wanted = text.getBytes(StandardCharsets.US_ASCII);
        for (byte[] segment : segments) {
            if ((segment[1] & 0xFF) != marker) {
                continue;
            }
            for (int i = 4; i + wanted.length <= segment.length; i++) {
                if (Arrays.equals(Arrays.copyOfRange(segment, i, i + wanted.length), wanted)) {
                    return true;
                }
            }
        }
        return false;
    }


    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}

package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}

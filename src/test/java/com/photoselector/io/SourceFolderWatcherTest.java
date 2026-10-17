package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceFolderWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void 根目录和直接子文件夹里的文件变化都会通知() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Path child = Files.createDirectories(root.resolve("001_拍摄"));
        AtomicInteger changes = new AtomicInteger();
        AtomicReference<Path> reported = new AtomicReference<>();

        try (SourceFolderWatcher watcher = new SourceFolderWatcher(path -> {
            reported.set(path);
            changes.incrementAndGet();
        })) {
            watcher.watch(root);
            assertEquals(root.toAbsolutePath().normalize(), watcher.watchedRoot());

            Files.writeString(child.resolve("外部修改.tmp"), "x");
            waitUntil(() -> changes.get() >= 1, "子文件夹里的文件变化");
            assertEquals(root.toAbsolutePath().normalize(), reported.get());

            Files.writeString(root.resolve("根目录照片.tmp"), "x");
            waitUntil(() -> changes.get() >= 2, "根目录里的文件变化");
        }
    }

    @Test
    void 新建的直接子文件夹会被补上监听() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Path later = Files.createDirectories(root.resolve("001_稍后加入"));
        AtomicInteger changes = new AtomicInteger();

        try (SourceFolderWatcher watcher = new SourceFolderWatcher(path -> changes.incrementAndGet())) {
            watcher.watch(root);

            Files.writeString(later.resolve("第一张.tmp"), "x");
            waitUntil(() -> changes.get() >= 1, "新增子文件夹内的第一轮变化");

            Path another = Files.createDirectories(root.resolve("002_新文件夹"));
            waitUntil(() -> changes.get() >= 2, "根目录发现新子文件夹");

            Files.writeString(another.resolve("之后写入.tmp"), "x");
            waitUntil(() -> changes.get() >= 3, "新子文件夹已经补上监听");
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }
}

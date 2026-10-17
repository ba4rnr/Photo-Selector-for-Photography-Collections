package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetFolderWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void 文件变化会先去抖再通知监听者() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("成片"));
        CountDownLatch changed = new CountDownLatch(1);
        AtomicReference<Path> reported = new AtomicReference<>();
        try (TargetFolderWatcher watcher = new TargetFolderWatcher(path -> {
            reported.set(path);
            changed.countDown();
        })) {
            watcher.watch(folder);
            assertEquals(folder.toAbsolutePath().normalize(), watcher.watchedFolder());

            // 一串快速写入应当被合并；监听者只需要知道“它变了”。
            Files.writeString(folder.resolve("a.tmp"), "a");
            Files.writeString(folder.resolve("b.tmp"), "b");

            assertTrue(changed.await(10, TimeUnit.SECONDS), "监听到外部变化");
            assertEquals(folder.toAbsolutePath().normalize(), reported.get());
        }
    }

    @Test
    void 目录消失后会等待并重新注册监听() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("会重建的目录"));
        AtomicInteger changes = new AtomicInteger();
        AtomicReference<Path> reported = new AtomicReference<>();
        try (TargetFolderWatcher watcher = new TargetFolderWatcher(path -> {
            reported.set(path);
            changes.incrementAndGet();
        })) {
            watcher.watch(folder);

            Files.delete(folder);
            waitUntil(() -> changes.get() >= 1, "目录失效通知");

            Files.createDirectories(folder);
            waitUntil(() -> changes.get() >= 2, "目录重建并重新注册");

            Files.writeString(folder.resolve("new.tmp"), "x");
            waitUntil(() -> changes.get() >= 3, "重新注册后还能收到事件");
            assertEquals(folder.toAbsolutePath().normalize(), reported.get());
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

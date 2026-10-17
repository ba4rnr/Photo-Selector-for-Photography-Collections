package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
}

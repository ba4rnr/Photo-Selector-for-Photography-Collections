package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.io.ThumbnailService;
import com.photoselector.testing.TestImages;

class ThumbnailLoaderTest {

    @TempDir
    Path tempDir;

    private ThumbnailLoader newLoader() {
        return new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
    }

    @Test
    void 回调在界面线程上执行并拿到缩略图() throws Exception {
        Path photo = TestImages.writeJpeg(tempDir.resolve("a.jpg"), 900, 600);
        ThumbnailLoader loader = newLoader();

        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean onEdt = new AtomicBoolean();
        AtomicReference<BufferedImage> received = new AtomicReference<>();

        loader.load(photo, 100, image -> {
            onEdt.set(SwingUtilities.isEventDispatchThread());
            received.set(image);
            done.countDown();
        });

        assertTrue(done.await(30, TimeUnit.SECONDS), "缩略图应当在超时前送到");
        assertTrue(onEdt.get(), "回调必须回到界面线程，否则下一步改界面就会出问题");
        assertEquals(100, received.get().getHeight());
        loader.shutdown();
    }

    @Test
    void 换了一轮之后旧请求的结果会被丢掉() throws Exception {
        Path photo = TestImages.writeJpeg(tempDir.resolve("a.jpg"), 900, 600);
        ThumbnailLoader loader = newLoader();

        AtomicInteger received = new AtomicInteger();
        CountDownLatch late = new CountDownLatch(1);

        // 把界面线程占住：回调只能排队等着，等我们把 restart 做完才有机会轮到它
        SwingUtilities.invokeAndWait(() -> {
            loader.load(photo, 100, image -> {
                received.incrementAndGet();
                late.countDown();
            });
            loader.restart();
        });

        assertFalse(late.await(1, TimeUnit.SECONDS), "换了一轮之后，旧请求的结果不该再送出来");
        assertEquals(0, received.get());
        loader.shutdown();
    }

    @Test
    void 取完图之后名额会被释放() throws Exception {
        Path photo = TestImages.writeJpeg(tempDir.resolve("a.jpg"), 900, 600);
        ThumbnailLoader loader = newLoader();

        CountDownLatch done = new CountDownLatch(1);
        loader.load(photo, 100, image -> done.countDown());
        assertTrue(done.await(30, TimeUnit.SECONDS));

        long deadline = System.currentTimeMillis() + 3_000;
        while (loader.inFlightCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, loader.inFlightCount(), "取完之后不该还占着名额");
        loader.shutdown();
    }

    @Test
    void 关掉之后迟到的请求会被忽略_而不是抛异常() throws Exception {
        Path photo = TestImages.writeJpeg(tempDir.resolve("a.jpg"), 900, 600);
        ThumbnailLoader loader = newLoader();
        loader.shutdown();

        AtomicInteger received = new AtomicInteger();
        // 关窗口那一刻界面还可能收到一次 resize，跟着就来请求缩略图。
        // 这时候线程池已经收工，直接忽略即可——不能把 RejectedExecutionException 甩出来
        loader.load(photo, 100, image -> received.incrementAndGet());
        loader.loadFull(photo, image -> received.incrementAndGet());

        Thread.sleep(200); // 给"万一真排上了队"一点时间露馅
        assertEquals(0, received.get(), "关掉之后不该再送结果出来");
    }
}

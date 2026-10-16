package com.photoselector.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import com.photoselector.io.ThumbnailService;

/**
 * 后台取图：界面与解码之间的那座桥。
 *
 * <p>Swing 有两条铁律——耗时的事不能放在界面线程（EDT）上，界面改动又只能在 EDT 上做。
 * 这个类就是同时满足两条的地方：解码丢给线程池，结果用 {@code invokeLater} 送回 EDT。
 *
 * <p>另外两件容易做错、这里顺手处理掉的事：
 * <ul>
 *   <li><b>去重</b>。滚动时同一个格子会被反复请求，同一张图已经在路上就不必再排一次队。</li>
 *   <li><b>作废</b>。换了文件夹之后，上一轮排队的请求结果必须全部丢掉，
 *       否则新文件夹的画面会被旧图一张张刷回来。</li>
 * </ul>
 */
final class ThumbnailLoader {

    private final ThumbnailService service;
    private final ExecutorService pool;

    /** 正在排队或正在解的图（键是路径 + 目标高度）。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** 当前是第几轮。每次换文件夹就加一，旧一轮的回调自然全部失效。 */
    private final AtomicLong session = new AtomicLong();

    ThumbnailLoader(ThumbnailService service) {
        this.service = service;
        // 留一个核给界面和系统，别把整台机器占满
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        this.pool = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "thumbnail-loader");
            thread.setDaemon(true); // 守护线程：程序退出时不必等它收工
            return thread;
        });
    }

    /** 开始新一轮（比如换了文件夹）。之前排队的请求，结果一律作废。 */
    void restart() {
        session.incrementAndGet();
        inFlight.clear();
    }

    /**
     * 请求一张缩略图。
     *
     * <p>回调保证在界面线程上执行，可以直接在里面改界面。
     * 取不到图不会抛异常（服务那边已经换成占位图了），失败的回调会被安静地丢掉。
     */
    void load(Path photo, int maxHeight, Consumer<BufferedImage> onReady) {
        long mySession = session.get();
        String key = photo.toAbsolutePath() + "|" + maxHeight;
        if (!inFlight.add(key)) {
            return; // 同一张已经在路上了
        }

        pool.submit(() -> {
            try {
                BufferedImage image = service.thumbnail(photo, maxHeight);
                SwingUtilities.invokeLater(() -> {
                    if (mySession == session.get()) { // 期间没换过文件夹，这张还有用
                        onReady.accept(image);
                    }
                });
            } catch (RuntimeException e) {
                // 单张出意外不该影响别的图，丢掉即可
            } finally {
                inFlight.remove(key);
            }
        });
    }

    /** 正在路上的图有多少张。排查卡顿时有用。 */
    int inFlightCount() {
        return inFlight.size();
    }

    /** 关窗口时调用，让线程池收工。 */
    void shutdown() {
        pool.shutdownNow();
    }
}

package com.photoselector.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import com.photoselector.io.ImageSource;
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

    /** 一次等待中的请求，以及所有等这张图的回调。 */
    private record Pending(long session, Consumer<BufferedImage> callback) {
    }

    /** 正在排队或正在解的一张图；同一张图的多个调用者共享一次解码。 */
    private static final class InFlight {
        final List<Pending> listeners = new CopyOnWriteArrayList<>();
        volatile boolean completed;
    }

    /** 正在排队或正在解的图（键是路径 + 目标高度）。 */
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();

    /** 当前是第几轮。每次换文件夹就加一，旧一轮的回调自然全部失效。 */
    private final AtomicLong session = new AtomicLong();

    /** 是不是已经关掉了。关掉之后不再收新的请求。 */
    private final AtomicBoolean shutdown = new AtomicBoolean();

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
        if (shutdown.get()) {
            // 线程池已经收工了。迟到的请求（比如关窗口那一刻界面又收到一次 resize）
            // 直接忽略：不忽略的话 submit 会抛 RejectedExecutionException
            return;
        }
        long mySession = session.get();
        String key = photo.toAbsolutePath() + "|" + maxHeight;
        InFlight task = join(key, new Pending(mySession, onReady));
        if (task == null) {
            return; // 已经并进前一个请求，回调会在那一次完成时统一调用
        }

        submit(key, task, () -> {
            try {
                BufferedImage image = service.thumbnail(photo, maxHeight);
                deliver(task, image);
            } catch (RuntimeException e) {
                // 单张出意外不该影响别的图，丢掉即可
            } finally {
                complete(key, task);
            }
        });
    }

    /**
     * 请求一张照片的<b>原尺寸</b>图像，供大图视图使用。
     *
     * <p>比缩略图慢得多（24MP 的 JPEG 要几百毫秒），所以同样丢给线程池。
     * 读不出来时回调收到 {@code null}——大图视图据此显示"读不出来"，而不是一直转圈。
     */
    void loadFull(Path photo, Consumer<BufferedImage> onReady) {
        if (shutdown.get()) {
            return;
        }
        long mySession = session.get();
        String key = photo.toAbsolutePath() + "|full";
        InFlight task = join(key, new Pending(mySession, onReady));
        if (task == null) {
            return;
        }

        submit(key, task, () -> {
            try {
                BufferedImage image = ImageSource.open(photo).loadFull();
                deliver(task, image);
            } catch (RuntimeException e) {
                deliver(task, null);
            } finally {
                complete(key, task);
            }
        });
    }

    /**
     * 加入一次取图请求。
     *
     * @return 新建的任务；如果已经并进前一个请求则返回 null
     */
    private InFlight join(String key, Pending pending) {
        java.util.concurrent.atomic.AtomicBoolean owner = new java.util.concurrent.atomic.AtomicBoolean();
        InFlight task = inFlight.compute(key, (ignored, existing) -> {
            if (existing == null || existing.completed) {
                InFlight fresh = new InFlight();
                fresh.listeners.add(pending);
                owner.set(true);
                return fresh;
            }
            existing.listeners.add(pending);
            return existing;
        });
        return owner.get() ? task : null;
    }

    private void complete(String key, InFlight task) {
        task.completed = true;
        // 按值删除：restart 清空后可能有同键的新任务，别把新任务误删。
        inFlight.remove(key, task);
    }

    /**
     * 把任务交给线程池，并把"关闭恰好发生在检查之后"这一个极窄的竞态也收干净。
     *
     * <p>只靠 {@link #shutdown} 标记还不够：线程可能在看到"还没关"之后、
     * 真正 {@code submit} 之前被另一条线程关掉线程池。此时线程池会拒绝任务，
     * 这只是一种迟到的正常情况，不该让异常从界面事件里冒出来。
     */
    private void submit(String key, InFlight pending, Runnable task) {
        try {
            pool.submit(task);
        } catch (RejectedExecutionException e) {
            complete(key, pending);
        }
    }

    /**
     * 把同一张图的结果送回界面线程，并通知所有还在等它的调用者。
     *
     * <p>每个等待者记着自己发起时的轮次；换了文件夹的旧轮次仍然会静默作废。
     */
    private void deliver(InFlight task, BufferedImage image) {
        SwingUtilities.invokeLater(() -> {
            long current = session.get();
            for (Pending pending : task.listeners) {
                if (pending.session() == current) {
                    pending.callback().accept(image);
                }
            }
        });
    }

    /** 正在路上的图有多少张。排查卡顿时有用。 */
    int inFlightCount() {
        return inFlight.size();
    }

    /**
     * 关窗口时调用，让线程池收工。
     *
     * <p>会等正在解的那几张结束——不然它们还攥着文件句柄，
     * 外面的程序（比如测试要删临时目录）就会删失败。
     */
    void shutdown() {
        shutdown.set(true);
        pool.shutdownNow();
        try {
            pool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

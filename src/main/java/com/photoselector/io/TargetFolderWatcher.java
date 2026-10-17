package com.photoselector.io;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 目标文件夹的外部改动监听。
 *
 * <p>一次改名或保存会连续产生很多文件事件，所以这里先等短暂的静默期，
 * 再把整串事件合并成一次“文件夹变了”的通知。真正读目录和更新界面由上层做。
 *
 * <p>监听线程是守护线程；换目录、关程序时会把旧的 {@link WatchService} 一起关掉。
 */
public final class TargetFolderWatcher implements AutoCloseable {

    /** 一串文件事件停止多久之后算稳定。 */
    static final long DEBOUNCE_MILLIS = 300;

    /** 文件夹内容变了。回调在监听线程上执行，调用方要自己切回界面线程。 */
    @FunctionalInterface
    public interface Listener {
        void changed(Path folder);
    }

    private final Listener listener;

    private WatchService service;
    private Thread thread;
    private Path watchedFolder;
    private volatile long generation;

    public TargetFolderWatcher(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * 改成监听另一个文件夹；传 null 表示停止监听。
     *
     * @throws IOException 目录不存在、没有权限，或者系统无法创建监听器
     */
    public synchronized void watch(Path folder) throws IOException {
        stopCurrent();
        if (folder == null) {
            return;
        }

        Path normalized = folder.toAbsolutePath().normalize();
        WatchService next = FileSystems.getDefault().newWatchService();
        try {
            normalized.register(next,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException | RuntimeException e) {
            next.close();
            throw e;
        }

        service = next;
        watchedFolder = normalized;
        long myGeneration = ++generation;
        thread = new Thread(() -> run(next, normalized, myGeneration),
                "target-folder-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** 当前正在监听的目录；没监听就是 null。给测试用。 */
    synchronized Path watchedFolder() {
        return watchedFolder;
    }

    @Override
    public synchronized void close() {
        stopCurrent();
    }

    private void run(WatchService watcher, Path folder, long myGeneration) {
        try {
            while (myGeneration == generation) {
                WatchKey key = watcher.take();
                boolean changed = drain(key);
                if (!changed) {
                    continue;
                }

                // 静默期里还可能继续来事件；等完再排空一次，尽量只通知一轮。
                TimeUnit.MILLISECONDS.sleep(DEBOUNCE_MILLIS);
                WatchKey extra;
                while ((extra = watcher.poll()) != null) {
                    changed |= drain(extra);
                }
                if (changed && myGeneration == generation) {
                    try {
                        listener.changed(folder);
                    } catch (RuntimeException ignored) {
                        // 上层回调出问题不该让监听线程永久退出。
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException ignored) {
            // 正常关闭或换目录
        }
    }

    private static boolean drain(WatchKey key) {
        boolean changed = !key.pollEvents().isEmpty();
        key.reset();
        return changed;
    }

    private void stopCurrent() {
        generation++;
        if (service != null) {
            try {
                service.close();
            } catch (IOException ignored) {
                // 已经关掉就算了
            }
            service = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        watchedFolder = null;
    }
}

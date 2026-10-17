package com.photoselector.io;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 素材根目录的实时监听。
 *
 * <p>{@link WatchService} 本身不递归，所以这里注册两层：根目录本身，
 * 以及根目录下的直接子文件夹。两层正好覆盖素材条显示的全部照片；
 * 新子文件夹出现时会在下一次事件里补注册，子文件夹删除时旧 key 会被清掉。
 *
 * <p>文件复制或保存常会连续产生很多事件，所以同样先等短暂静默期，
 * 再向上层通知一次。回调在监听线程上执行，调用方需要自己切回界面线程。
 */
public final class SourceFolderWatcher implements AutoCloseable {

    static final long DEBOUNCE_MILLIS = 300;
    private static final long RECOVERY_MILLIS = 500;

    @FunctionalInterface
    public interface Listener {
        void changed(Path sourceRoot);
    }

    private record Registration(WatchService service, Map<WatchKey, Path> keys) {
    }

    private final Listener listener;

    private WatchService service;
    private Thread thread;
    private Path root;
    private volatile long generation;

    public SourceFolderWatcher(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * 开始监听一个素材根目录；传 null 表示停止。
     *
     * @throws IOException 根目录不存在、没有读取权限，或者系统无法创建监听器
     */
    public synchronized void watch(Path folder) throws IOException {
        stopCurrent();
        if (folder == null) {
            return;
        }

        Path normalized = folder.toAbsolutePath().normalize();
        Registration registration = open(normalized);
        service = registration.service();
        root = normalized;
        long myGeneration = ++generation;
        thread = new Thread(() -> run(registration, normalized, myGeneration),
                "source-folder-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized Path watchedRoot() {
        return root;
    }

    @Override
    public synchronized void close() {
        stopCurrent();
    }

    private void run(Registration initial, Path watchedRoot, long myGeneration) {
        Registration registration = initial;
        try {
            while (myGeneration == generation) {
                WatchKey key = registration.service().take();
                boolean changed = drain(key, registration.keys());
                if (!changed) {
                    continue;
                }

                TimeUnit.MILLISECONDS.sleep(DEBOUNCE_MILLIS);
                WatchKey extra;
                while ((extra = registration.service().poll()) != null) {
                    changed |= drain(extra, registration.keys());
                }
                if (myGeneration != generation) {
                    return;
                }

                boolean rootPresent = refreshRegistrations(
                        registration.service(), watchedRoot, registration.keys());
                if (changed) {
                    notifyChanged(watchedRoot, myGeneration);
                }
                if (rootPresent) {
                    continue;
                }

                // 根目录被删除 / 改名：关闭当前服务，等它回来再重新注册整套目录。
                closeQuietly(registration.service());
                Registration reopened = recover(watchedRoot, myGeneration);
                if (reopened == null) {
                    return;
                }
                registration = reopened;
                notifyChanged(watchedRoot, myGeneration);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException ignored) {
            // 正常关闭或换工作区
        }
    }

    private static boolean drain(WatchKey key, Map<WatchKey, Path> keys) {
        boolean changed = !key.pollEvents().isEmpty();
        boolean valid = key.reset();
        if (!valid) {
            keys.remove(key);
            changed = true; // 目录被删除 / 改名本身也算一次变化
        }
        return changed;
    }

    /**
     * 重新对齐“根目录 + 直接子文件夹”的注册表。
     *
     * @return 根目录是否仍然存在
     */
    private static boolean refreshRegistrations(
            WatchService service, Path root, Map<WatchKey, Path> keys) {
        if (!Files.isDirectory(root)) {
            return false;
        }

        Set<Path> wanted = new HashSet<>();
        wanted.add(root);
        boolean listed = false;
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory).forEach(wanted::add);
            listed = true;
        } catch (IOException | RuntimeException ignored) {
            // 子目录正在变化时枚举可能失败；下一轮事件还会再对齐。
        }
        if (!listed) {
            return true; // 枚举失败时保留现有注册，别因为一次读取失败丢掉全部子目录
        }

        Iterator<Map.Entry<WatchKey, Path>> existing = keys.entrySet().iterator();
        while (existing.hasNext()) {
            Map.Entry<WatchKey, Path> entry = existing.next();
            if (!wanted.contains(entry.getValue())) {
                entry.getKey().cancel();
                existing.remove();
            }
        }

        Set<Path> registered = new HashSet<>(keys.values());
        for (Path folder : wanted) {
            if (registered.contains(folder)) {
                continue;
            }
            try {
                register(service, folder, keys);
            } catch (IOException | RuntimeException ignored) {
                // 单个子目录读不了不应影响其他组继续监听
            }
        }
        return true;
    }

    private static Registration open(Path root) throws IOException {
        WatchService service = FileSystems.getDefault().newWatchService();
        Map<WatchKey, Path> keys = new HashMap<>();
        try {
            if (!Files.isDirectory(root)) {
                throw new IOException("素材根目录不存在：" + root);
            }
            register(service, root, keys);
            refreshRegistrations(service, root, keys);
            return new Registration(service, keys);
        } catch (IOException | RuntimeException e) {
            closeQuietly(service);
            throw e;
        }
    }

    private static void register(WatchService service, Path folder, Map<WatchKey, Path> keys)
            throws IOException {
        WatchKey key = folder.register(service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        keys.put(key, folder);
    }

    private Registration recover(Path watchedRoot, long myGeneration) {
        while (myGeneration == generation) {
            try {
                TimeUnit.MILLISECONDS.sleep(RECOVERY_MILLIS);
                if (Files.isDirectory(watchedRoot)) {
                    return open(watchedRoot);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (IOException | RuntimeException ignored) {
                // 目录刚回来时注册可能还会失败，下一轮再试
            }
        }
        return null;
    }

    private void notifyChanged(Path sourceRoot, long myGeneration) {
        if (myGeneration != generation) {
            return;
        }
        try {
            listener.changed(sourceRoot);
        } catch (RuntimeException ignored) {
            // 上层回调出错不该让监听线程永久退出
        }
    }

    private void stopCurrent() {
        generation++;
        if (service != null) {
            closeQuietly(service);
            service = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        root = null;
    }

    private static void closeQuietly(WatchService watcher) {
        try {
            watcher.close();
        } catch (IOException ignored) {
            // 已经关闭或失效
        }
    }
}

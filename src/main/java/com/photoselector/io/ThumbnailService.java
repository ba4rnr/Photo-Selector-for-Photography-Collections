package com.photoselector.io;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

/**
 * 缩略图服务：把"解码一次"的成本摊到很多次使用上。
 *
 * <p>两层缓存各管一段：
 * <ul>
 *   <li><b>内存</b>：最近用过的若干张，按<b>字节数</b>限量。
 *       滚动看片时来回翻的都是附近那几张，命中率最高。界面正在使用的图通过
 *       {@link #acquire} 加租约，租约未关闭时不会被 LRU 淘汰。</li>
 *   <li><b>磁盘</b>：程序关掉也还在，总量有上限、超了就删最旧的。</li>
 * </ul>
 *
 * <p>缓存键 = 文件路径 + 文件大小 + 最后修改时间 + 目标高度。
 * 带上大小和时间，照片被换过或改过时缓存会自动失效——绝不会拿旧图糊弄人。
 *
 * <p><b>线程模型</b>：解码可以并发进行（耗时的部分在锁外），
 * 只有缓存读写的瞬间需要加锁。代价是同一张图可能被两个线程各解一遍，
 * 结果一样、后写的覆盖先写的，可以接受。
 *
 * <p><b>返回值是共享对象</b>：{@link #thumbnail} 交出去的图像就是缓存里那一份，
 * 请不要就地修改它（比如在上面画角标），否则会把缓存污染给后面的调用者。
 * 需要加工时先自己拷一份。
 *
 * <p>界面面板不应长期保存自己的 {@code Map<Path, BufferedImage>} 副本。使用
 * {@link #acquire} 拿租约，滚出视野或隐藏面板时关闭租约；同一张图被多个面板使用，
 * 也始终指向同一个 {@link BufferedImage}，只有最后一个租约释放后才允许淘汰。
 */
public final class ThumbnailService {

    /** 内存里默认最多占多少字节。按字节算而不是按张数，因为不同用途的缩略图大小差很多。 */
    private static final long DEFAULT_MEMORY_BYTES = 128L * 1024 * 1024;

    /** 磁盘缓存默认的上限。 */
    private static final long DEFAULT_DISK_BYTES = 512L * 1024 * 1024;

    /** 每写这么多次缓存，顺手清理一遍磁盘。 */
    private static final int DEFAULT_PRUNE_INTERVAL = 200;

    private final Path cacheDir;
    private final long memoryLimitBytes;
    private final long diskLimitBytes;
    private final int pruneInterval;

    /**
     * 内存缓存。{@code LinkedHashMap} 的第三个参数开启"按访问顺序排列"，
     * 再配合 {@code removeEldestEntry}，就是最简的 LRU：
     * 超出预算时，最久没被用到的那个先被丢掉。
     */
    private final Map<String, Entry> memory = new LinkedHashMap<>(64, 0.75f, true);

    private long memoryBytes;
    private int writesSincePrune;

    /** 一份共享像素数据，以及当前有多少个面板租约正在使用它。 */
    private static final class Entry {
        final BufferedImage image;
        final long bytes;
        int pins;

        Entry(BufferedImage image) {
            this.image = image;
            this.bytes = bytesOf(image);
        }
    }

    /**
     * 一张缩略图的租约。
     *
     * <p>各面板不再自己长期保存一份图，而是拿着租约。只要租约还在，
     * 服务就不能把这张图从内存里淘汰；面板滚出视野或换会话时关闭租约，
     * 最后一个租约关闭后它才重新变成可淘汰的普通缓存项。
     */
    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean closed;

        private Lease(Entry entry) {
            this.entry = entry;
        }

        public BufferedImage image() {
            return entry.image;
        }

        /** 复制一个租约引用，给同一张图的另一个面板使用。 */
        public synchronized Lease retain() {
            if (closed) {
                throw new IllegalStateException("租约已经关闭，不能继续 retain");
            }
            pin(entry);
            return new Lease(entry);
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            release(entry);
        }
    }

    public ThumbnailService(Path cacheDir) {
        this(cacheDir, DEFAULT_MEMORY_BYTES, DEFAULT_DISK_BYTES, DEFAULT_PRUNE_INTERVAL);
    }

    /** 供测试使用的完整版本：把各种上限都调小，好在几步之内验证行为。 */
    ThumbnailService(Path cacheDir, long memoryLimitBytes, long diskLimitBytes, int pruneInterval) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir 不能为空");
        this.memoryLimitBytes = memoryLimitBytes;
        this.diskLimitBytes = diskLimitBytes;
        this.pruneInterval = pruneInterval;
    }

    /**
     * 取一张照片的缩略图；缓存里没有就现场生成，并写回磁盘缓存。
     *
     * <p>这个方法可能很慢（第一次要真解码），<b>不要</b>在界面线程上调用。
     *
     * <p>取不到图不会抛异常：文件不见了、文件损坏，都返回一张画着叉的占位图。
     * 这是刻意的——素材条一次要取几百张，一张坏图不该让整组都显示不出来。
     * 只有参数用错（比如高度为负）才会抛。
     */
    public BufferedImage thumbnail(Path photo, int maxHeight) {
        try (Lease lease = acquire(photo, maxHeight)) {
            return lease.image();
        }
    }

    /**
     * 获取一张带租约的缩略图。界面面板应当用这个方法，而不是 {@link #thumbnail}。
     *
     * <p>租约把这张图暂时钉在内存缓存里；关闭租约后才允许 LRU 淘汰。
     * 同一张图发给多个面板时，它们拿到的是同一个 {@link BufferedImage} 对象。
     */
    public Lease acquire(Path photo, int maxHeight) {
        if (maxHeight < 1) {
            throw new IllegalArgumentException("目标高度必须为正：" + maxHeight);
        }
        if (!Files.isRegularFile(photo)) {
            // 文件不在了，连缓存键都算不出来；占位图独立交给调用者，不进入共享缓存。
            return new Lease(new Entry(placeholder(maxHeight)));
        }

        String key = cacheKey(photo, maxHeight);

        Entry cached = pinIfCached(key);
        if (cached != null) {
            return new Lease(cached);
        }

        Path cacheFile = cacheDir.resolve(key + ".png");
        BufferedImage image = readFromDisk(cacheFile);
        if (image == null) {
            // 解码这一步刻意放在锁外：它最耗时，锁在里面等于把并发能力废掉
            image = decode(photo, maxHeight);
            writeToDisk(cacheFile, image);
        }

        return new Lease(storeAndPin(key, image));
    }

    /** 内存缓存里现在有多少张。 */
    public synchronized int cachedCount() {
        return memory.size();
    }

    /** 内存缓存现在占多少字节。 */
    public synchronized long cachedBytes() {
        return memoryBytes;
    }

    /** 某张照片的缓存放到了哪个文件。测试和排查问题时用得上。 */
    public Path cacheFileFor(Path photo, int maxHeight) {
        return cacheDir.resolve(cacheKey(photo, maxHeight) + ".png");
    }

    /**
     * 清理磁盘缓存：总量超过上限时，从最旧的开始删，直到降到上限以内。
     *
     * <p>平时由服务自己隔一段时间自动跑一次；也可以挂到菜单上做成"清理缓存"。
     */
    public void pruneDiskCache() {
        try (Stream<Path> entries = Files.list(cacheDir)) {
            List<Path> files = new ArrayList<>(entries.filter(Files::isRegularFile).toList());

            long total = 0;
            for (Path file : files) {
                total += sizeOf(file);
            }
            if (total <= diskLimitBytes) {
                return;
            }

            // 从最久没用过的开始删——按最后修改时间排就够近似了
            files.sort(Comparator.comparingLong(ThumbnailService::lastModified));
            for (Path file : files) {
                if (total <= diskLimitBytes) {
                    break;
                }
                long size = sizeOf(file);
                if (deleteQuietly(file)) {
                    total -= size;
                }
            }
        } catch (IOException e) {
            // 缓存是不是超了，不影响功能，清理失败就算了
        }
    }

    /** 默认的缓存位置：Windows 下是 %LOCALAPPDATA%\PhotoSelector\cache\thumbs。 */
    public static Path defaultCacheDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = (localAppData == null || localAppData.isBlank())
                ? Path.of(System.getProperty("user.home"), ".photoselector")
                : Path.of(localAppData, "PhotoSelector");
        return root.resolve("cache").resolve("thumbs");
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    /** 解码一张缩略图；出任何问题都给占位图，不往外抛。 */
    private static BufferedImage decode(Path photo, int maxHeight) {
        try {
            return ImageSource.open(photo).loadThumbnail(maxHeight);
        } catch (RuntimeException e) {
            return placeholder(maxHeight);
        }
    }

    /** 取不到图时用的占位图：深底、灰框、一个叉。 */
    private static BufferedImage placeholder(int maxHeight) {
        int height = Math.max(1, maxHeight);
        int width = Math.round(height * 1.5f); // 按 3:2 画，跟多数相机一致

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x24, 0x24, 0x24));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(0x6E, 0x6E, 0x6E));
        int inset = Math.max(2, height / 12);
        g.drawRect(inset, inset, width - inset * 2 - 1, height - inset * 2 - 1);
        g.drawLine(inset, inset, width - inset - 1, height - inset - 1);
        g.drawLine(width - inset - 1, inset, inset, height - inset - 1);
        g.dispose();
        return image;
    }

    private synchronized Entry pinIfCached(String key) {
        Entry entry = memory.get(key);
        if (entry != null) {
            entry.pins++;
        }
        return entry;
    }

    private synchronized Entry storeAndPin(String key, BufferedImage image) {
        Entry existing = memory.get(key);
        if (existing != null) {
            existing.pins++;
            return existing; // 另一只线程刚好先解完；共享那一份，丢掉这次重复的结果
        }
        Entry entry = new Entry(image);
        memory.put(key, entry);
        memoryBytes += entry.bytes;
        entry.pins = 1;
        evictOldestUntilWithinBudget();
        return entry;
    }

    private synchronized void pin(Entry entry) {
        entry.pins++;
    }

    private synchronized void release(Entry entry) {
        if (entry.pins > 0) {
            entry.pins--;
        }
        evictOldestUntilWithinBudget();
    }

    /**
     * 超出预算就从头丢，直到降回预算以内。
     *
     * <p>用显式循环而不是 {@code removeEldestEntry}：那个回调在插入完成前就触发了，
     * 判定时还没把新图算进去，结果会常年超预算一张。
     */
    private void evictOldestUntilWithinBudget() {
        while (memoryBytes > memoryLimitBytes) {
            String victim = null;
            for (Map.Entry<String, Entry> candidate : memory.entrySet()) {
                if (candidate.getValue().pins == 0) {
                    victim = candidate.getKey();
                    break;
                }
            }
            if (victim == null) {
                break; // 剩下的全被面板租用；关掉租约后会再次尝试淘汰
            }
            Entry removed = memory.remove(victim);
            if (removed != null) {
                memoryBytes -= removed.bytes;
            }
        }
    }

    /** 一张图大概占多少内存：每像素 4 字节（TYPE_INT_RGB）。 */
    private static long bytesOf(BufferedImage image) {
        return (long) image.getWidth() * image.getHeight() * 4;
    }

    /**
     * 缩略图"怎么画"的代次。渲染逻辑一改（比如开始按 EXIF 方向摆正）就 +1。
     *
     * <p>为什么必须有它：缓存键是"路径 + 大小 + 修改时间 + 目标高度"，
     * 照片文件本身没变的话，程序升级后键还是老样子——旧版本缓存下来的图
     * （比如方向没摆正的那批）会被当成新鲜的继续用，怎么重启都不更新。
     * 代次号一变，所有旧缓存自动作废，重算一遍。
     */
    private static final int RENDER_VERSION = 2;

    /**
     * 把"路径 + 大小 + 修改时间 + 目标高度"揉成一个定长的文件名。
     *
     * <p>为什么不直接用路径当文件名？因为路径里有冒号斜杠、还可能很长，
     * 拿来当文件名会失败。先算哈希，既安全又定长。
     */
    private String cacheKey(Path photo, int maxHeight) {
        try {
            String raw = photo.toAbsolutePath()
                    + "|" + Files.size(photo)
                    + "|" + Files.getLastModifiedTime(photo).toMillis()
                    + "|v" + RENDER_VERSION
                    + "|" + maxHeight;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) { // 取前 16 字节，够唯一了
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("读不到文件信息：" + photo, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 一定自带 SHA-256", e);
        }
    }

    private static BufferedImage readFromDisk(Path cacheFile) {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        try {
            return ImageIO.read(cacheFile.toFile());
        } catch (IOException e) {
            // 读不出来就当没有——比如上次写到一半被打断，留了个半截文件
            return null;
        }
    }

    private void writeToDisk(Path cacheFile, BufferedImage image) {
        Path temp = null;
        try {
            Files.createDirectories(cacheDir);
            // 先写临时文件再改名：中途出意外也不会留下半截的缓存文件
            temp = Files.createTempFile(cacheDir, "thumb-", ".tmp");
            ImageIO.write(image, "png", temp.toFile());
            Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (IOException e) {
            // 缓存写不进去不影响功能，顶多下次再算一遍
        } finally {
            // 写失败时别把临时文件落在缓存目录里没人管
            if (temp != null) {
                deleteQuietly(temp);
            }
        }

        if (++writesSincePrune >= pruneInterval) {
            writesSincePrune = 0;
            pruneDiskCache();
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE; // 读不到时间的当成最新的，最后再删
        }
    }

    private static boolean deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            return false;
        }
    }
}

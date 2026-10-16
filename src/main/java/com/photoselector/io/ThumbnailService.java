package com.photoselector.io;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.imageio.ImageIO;

/**
 * 缩略图服务：把"解码一次"的成本摊到很多次使用上。
 *
 * <p>两层缓存各管一段：
 * <ul>
 *   <li><b>内存</b>：最近用过的若干张。滚动看片时来回翻的都是附近那几张，
 *       命中率最高，拿到就是零成本。</li>
 *   <li><b>磁盘</b>：程序关掉也还在。第二次打开同一个文件夹，
 *       几百张缩略图直接读小文件，不用再解一遍原始大图。</li>
 * </ul>
 *
 * <p>缓存键 = 文件路径 + 文件大小 + 最后修改时间 + 目标高度。
 * 带上大小和时间，照片被换过或改过时缓存会自动失效——绝不会拿旧图糊弄人。
 */
public final class ThumbnailService {

    /** 内存里默认最多留多少张。 */
    private static final int DEFAULT_MEMORY_LIMIT = 300;

    private final Path cacheDir;
    private final int memoryLimit;

    /**
     * 内存缓存。{@code LinkedHashMap} 的第三个参数开启"按访问顺序排列"，
     * 再配合 {@code removeEldestEntry}，就是最简的 LRU：
     * 容量满了，最久没被用到的那个先被丢。
     */
    private final Map<String, BufferedImage> memory;

    public ThumbnailService(Path cacheDir) {
        this(cacheDir, DEFAULT_MEMORY_LIMIT);
    }

    public ThumbnailService(Path cacheDir, int memoryLimit) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir 不能为空");
        this.memoryLimit = memoryLimit;
        this.memory = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                return size() > ThumbnailService.this.memoryLimit;
            }
        };
    }

    /**
     * 取一张照片的缩略图；两层缓存都没有就现场生成，并写回磁盘缓存。
     *
     * <p>这个方法可能很慢（第一次要真解码），<b>不要</b>在界面线程上调用。
     *
     * @param photo     照片文件
     * @param maxHeight 缩略图的高度上限
     */
    public synchronized BufferedImage thumbnail(Path photo, int maxHeight) {
        String key = cacheKey(photo, maxHeight);

        BufferedImage cached = memory.get(key);
        if (cached != null) {
            return cached;
        }

        Path cacheFile = cacheDir.resolve(key + ".png");
        BufferedImage image = readFromDisk(cacheFile);
        if (image == null) {
            image = ImageSource.open(photo).loadThumbnail(maxHeight);
            writeToDisk(cacheFile, image);
        }

        memory.put(key, image);
        return image;
    }

    /** 某张照片的缓存放到了哪个文件。测试和排查问题时用得上。 */
    public Path cacheFileFor(Path photo, int maxHeight) {
        return cacheDir.resolve(cacheKey(photo, maxHeight) + ".png");
    }

    /** 默认的缓存位置：Windows 下是 %LOCALAPPDATA%\PhotoSelector\cache\thumbs。 */
    public static Path defaultCacheDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = (localAppData == null || localAppData.isBlank())
                ? Path.of(System.getProperty("user.home"), ".photoselector")
                : Path.of(localAppData, "PhotoSelector");
        return root.resolve("cache").resolve("thumbs");
    }

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
        try {
            Files.createDirectories(cacheDir);
            // 先写临时文件再改名：中途出意外也不会留下半截的缓存文件
            Path temp = Files.createTempFile(cacheDir, "thumb-", ".tmp");
            ImageIO.write(image, "png", temp.toFile());
            Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 缓存写不进去不影响功能，顶多下次再算一遍
        }
    }
}

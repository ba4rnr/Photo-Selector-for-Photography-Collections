package com.photoselector.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import com.photoselector.core.Workspace;

/**
 * 工作区的存放处：一个纯文本的 properties 文件。
 *
 * <p>为什么用 {@code java.util.Properties} 而不是自己写 JSON：
 * JDK 自带、不引依赖、文件能直接用记事本打开看，出问题好排查。
 * 内容就这么几个字段，不值得为它引入一套序列化。
 *
 * <p>默认位置：{@code %APPDATA%\PhotoSelector\workspace.properties}。
 */
public final class WorkspaceStore {

    /** 素材根文件夹。 */
    private static final String KEY_SOURCE_ROOT = "sourceRoot";

    /** 目标文件夹。 */
    private static final String KEY_TARGET = "targetFolder";

    /** 上次关闭时正在浏览的照片。 */
    private static final String KEY_LAST_VIEWED = "lastViewedPhoto";

    /** 被剔除的文件夹，一项一个键：excluded.0、excluded.1…… */
    private static final String KEY_EXCLUDED_PREFIX = "excluded.";

    private final Path configFile;

    public WorkspaceStore() {
        this(defaultConfigFile());
    }

    /** 指定配置文件的位置；想挑别处存（测试、便携版）就用它。 */
    public WorkspaceStore(Path configFile) {
        this.configFile = configFile;
    }

    /** 默认的配置文件位置。 */
    public static Path defaultConfigFile() {
        String appData = System.getenv("APPDATA");
        Path root = (appData == null || appData.isBlank())
                ? Path.of(System.getProperty("user.home"), ".photoselector")
                : Path.of(appData, "PhotoSelector");
        return root.resolve("workspace.properties");
    }

    /**
     * 读回上次的工作区。
     *
     * <p>没有配置、或者文件坏了，都返回空——启动流程会据此打开"选择工作区"界面。
     *
     * <p>对账规则（见《软件设计》4.2）：某条剔除项指向的文件夹已经不存在了，
     * 就忽略这一条，其余照常。素材根目录不存在<b>不</b>在这里处理——
     * 那需要告诉用户一句，是界面的事。
     */
    public Optional<Workspace> load() {
        if (!Files.isRegularFile(configFile)) {
            return Optional.empty();
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            properties.load(in);
        } catch (IOException | RuntimeException e) {
            // Properties.load 遇到畸形 Unicode 转义会抛 IllegalArgumentException；
            // 和读文件失败一样，坏配置只该当成"没配过"。
            return Optional.empty();
        }

        Path sourceRoot = parsePath(properties.getProperty(KEY_SOURCE_ROOT));
        if (sourceRoot == null) {
            return Optional.empty();
        }

        Set<Path> excluded = new LinkedHashSet<>();
        for (String key : new TreeSet<>(properties.stringPropertyNames())) {
            if (!key.startsWith(KEY_EXCLUDED_PREFIX)) {
                continue;
            }
            Path folder = parsePath(properties.getProperty(key));
            if (folder != null && Files.isDirectory(folder)) { // 不在了就忽略这一条
                excluded.add(folder);
            }
        }

        Path targetFolder = parsePath(properties.getProperty(KEY_TARGET));
        Path lastViewedPhoto = parsePath(properties.getProperty(KEY_LAST_VIEWED));
        return Optional.of(new Workspace(sourceRoot, excluded, targetFolder,
                lastViewedPhoto));
    }

    /** 把工作区写回文件。 */
    public void save(Workspace workspace) {
        Properties properties = new Properties();
        properties.setProperty(KEY_SOURCE_ROOT, workspace.sourceRoot().toString());
        if (workspace.targetFolder() != null) {
            properties.setProperty(KEY_TARGET, workspace.targetFolder().toString());
        }
        if (workspace.lastViewedPhoto() != null) {
            properties.setProperty(KEY_LAST_VIEWED, workspace.lastViewedPhoto().toString());
        }
        int index = 0;
        for (Path folder : workspace.excludedFolders()) {
            properties.setProperty(KEY_EXCLUDED_PREFIX + index++, folder.toString());
        }

        try {
            PropertyFiles.write(configFile, properties, "摄影集照片筛选器的工作区配置");
        } catch (IOException e) {
            // 记不住不影响这次使用，只是下次打开要重新选一遍
            throw new UncheckedIOException("写不进配置文件：" + configFile, e);
        }
    }

    /**
     * 把配置里的字符串转成路径；空串或系统不接受的路径都当作没写。
     *
     * <p>{@code Path.of} 遇到 NUL、非法盘符等会抛 {@link InvalidPathException}。
     * 这类损坏只应该让对应的一项失效，不能让整个程序启动失败。
     */
    private static Path parsePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Path.of(raw);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}

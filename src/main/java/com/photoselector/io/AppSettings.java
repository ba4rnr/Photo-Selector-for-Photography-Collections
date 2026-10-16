package com.photoselector.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 属于"这台机器上的习惯"的设置（和某个具体工作区无关的那些）。
 *
 * <p>眼下只有一项：打开一张照片时的默认放大倍率。
 * 用 properties 文件存，理由和 {@link WorkspaceStore} 一样：够简单、能拿记事本打开、
 * 坏了大不了回默认值——为一行设置引入一套序列化框架不值得。
 *
 * <p>默认位置：{@code %APPDATA%\PhotoSelector\preferences.properties}。
 */
public final class AppSettings {

    /** 默认放大倍率（百分比）。 */
    private static final String KEY_DEFAULT_ZOOM = "viewer.defaultZoomPercent";

    /** 出厂默认：18%。 */
    private static final int FALLBACK_ZOOM_PERCENT = 18;

    private final Path configFile;

    public AppSettings() {
        this(defaultConfigFile());
    }

    /** 指定配置文件的位置；想挑别处存（测试、便携版）就用它。 */
    public AppSettings(Path configFile) {
        this.configFile = configFile;
    }

    /** 默认的配置文件位置。 */
    public static Path defaultConfigFile() {
        String appData = System.getenv("APPDATA");
        Path root = (appData == null || appData.isBlank())
                ? Path.of(System.getProperty("user.home"), ".photoselector")
                : Path.of(appData, "PhotoSelector");
        return root.resolve("preferences.properties");
    }

    /**
     * 读回默认放大倍率（百分比）。
     *
     * <p>文件不在、内容坏了、值离谱（不是正数），一律回到出厂默认——
     * 一份设置文件不值得让程序起不来。
     */
    public int defaultZoomPercent() {
        Properties properties = new Properties();
        if (Files.isRegularFile(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                properties.load(in);
            } catch (IOException e) {
                return FALLBACK_ZOOM_PERCENT;
            }
        }
        String raw = properties.getProperty(KEY_DEFAULT_ZOOM);
        if (raw == null || raw.isBlank()) {
            return FALLBACK_ZOOM_PERCENT;
        }
        try {
            int percent = Integer.parseInt(raw.trim());
            return percent > 0 ? percent : FALLBACK_ZOOM_PERCENT;
        } catch (NumberFormatException e) {
            return FALLBACK_ZOOM_PERCENT;
        }
    }

    /** 记下默认放大倍率（百分比）。 */
    public void setDefaultZoomPercent(int percent) {
        Properties properties = new Properties();
        if (Files.isRegularFile(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                properties.load(in); // 先把别的设置留着，只改这一项
            } catch (IOException ignored) {
                // 读不了就从空的开始：总比整份设置都写不进去强
            }
        }
        properties.setProperty(KEY_DEFAULT_ZOOM, Integer.toString(percent));
        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream out = Files.newOutputStream(configFile)) {
                properties.store(out, "摄影集照片筛选器的偏好设置");
            }
        } catch (IOException e) {
            // 记不住不影响这次使用，只是下次打开要重设一遍
        }
    }
}

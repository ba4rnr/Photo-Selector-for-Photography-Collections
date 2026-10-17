package com.photoselector.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * 属于"这台机器上的习惯"的设置（和某个具体工作区无关的那些）。
 *
 * <p>眼下有两类：打开照片时的默认放大倍率，以及主窗口上次的位置和大小。
 * 用 properties 文件存，理由和 {@link WorkspaceStore} 一样：够简单、能拿记事本打开、
 * 坏了大不了回默认值——为一行设置引入一套序列化框架不值得。
 *
 * <p>默认位置：{@code %APPDATA%\PhotoSelector\preferences.properties}。
 */
public final class AppSettings {

    /** 默认放大倍率（百分比）。 */
    private static final String KEY_DEFAULT_ZOOM = "viewer.defaultZoomPercent";

    private static final String KEY_WINDOW_X = "window.x";
    private static final String KEY_WINDOW_Y = "window.y";
    private static final String KEY_WINDOW_WIDTH = "window.width";
    private static final String KEY_WINDOW_HEIGHT = "window.height";
    private static final String KEY_WINDOW_MAXIMIZED = "window.maximized";

    /** 出厂默认：18%。 */
    private static final int FALLBACK_ZOOM_PERCENT = 18;

    /** 防止坏配置里的离谱数字让窗口尺寸计算溢出；正常多屏坐标远小于这个值。 */
    private static final long MAX_WINDOW_COORDINATE = 1_000_000L;
    private static final int MAX_WINDOW_DIMENSION = 20_000;

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
        Properties properties = readProperties();
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
        Properties properties = readProperties();
        properties.setProperty(KEY_DEFAULT_ZOOM, Integer.toString(percent));
        writeProperties(properties);
    }

    /**
     * 主窗口上次关闭时的位置、大小和最大化状态。
     *
     * <p>四项位置尺寸必须齐全且数字有效，否则整项视为没存过：
     * 只恢复半套坐标反而可能把窗口丢到屏幕外。
     */
    public Optional<WindowState> windowState() {
        Properties properties = readProperties();
        try {
            int x = Integer.parseInt(required(properties, KEY_WINDOW_X));
            int y = Integer.parseInt(required(properties, KEY_WINDOW_Y));
            int width = Integer.parseInt(required(properties, KEY_WINDOW_WIDTH));
            int height = Integer.parseInt(required(properties, KEY_WINDOW_HEIGHT));
            if (width <= 0 || height <= 0
                    || width > MAX_WINDOW_DIMENSION || height > MAX_WINDOW_DIMENSION
                    || Math.abs((long) x) > MAX_WINDOW_COORDINATE
                    || Math.abs((long) y) > MAX_WINDOW_COORDINATE) {
                return Optional.empty();
            }
            boolean maximized = Boolean.parseBoolean(
                    properties.getProperty(KEY_WINDOW_MAXIMIZED, "false"));
            return Optional.of(new WindowState(x, y, width, height, maximized));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 记下主窗口的位置、大小和最大化状态；其他设置保持不变。 */
    public void setWindowState(WindowState state) {
        Properties properties = readProperties();
        properties.setProperty(KEY_WINDOW_X, Integer.toString(state.x()));
        properties.setProperty(KEY_WINDOW_Y, Integer.toString(state.y()));
        properties.setProperty(KEY_WINDOW_WIDTH, Integer.toString(state.width()));
        properties.setProperty(KEY_WINDOW_HEIGHT, Integer.toString(state.height()));
        properties.setProperty(KEY_WINDOW_MAXIMIZED, Boolean.toString(state.maximized()));
        writeProperties(properties);
    }

    /** 主窗口边界，以及关闭时是否处于最大化。 */
    public record WindowState(int x, int y, int width, int height, boolean maximized) {
    }

    private Properties readProperties() {
        Properties properties = new Properties();
        if (Files.isRegularFile(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                properties.load(in);
            } catch (IOException ignored) {
                // 读不了就从空的开始：总比整份设置都写不进去强
            }
        }
        return properties;
    }

    private void writeProperties(Properties properties) {
        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream out = Files.newOutputStream(configFile)) {
                properties.store(out, "摄影集照片筛选器的偏好设置");
            }
        } catch (IOException e) {
            // 记不住不影响这次使用，只是下次打开要重设一遍
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少窗口设置：" + key);
        }
        return value.trim();
    }
}

package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 偏好设置的读写：改了要记得住，文件坏了也不能让程序起不来。
 */
class AppSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    void 没存过的时候给出厂默认() {
        AppSettings settings = new AppSettings(tempDir.resolve("preferences.properties"));
        assertEquals(18, settings.defaultZoomPercent(), "出厂默认 18%");
        assertTrue(settings.defaultFitToWindow(), "新版本出厂默认适应窗口");
    }

    @Test
    void 改过之后记得住_下次打开还是它() {
        Path file = tempDir.resolve("preferences.properties");
        new AppSettings(file).setDefaultZoomPercent(33);

        assertEquals(33, new AppSettings(file).defaultZoomPercent(),
                "同一台机器上，重开程序应当还是上次选的倍率");
        assertFalse(new AppSettings(file).defaultFitToWindow(),
                "选了固定百分比就不再是默认适应窗口");
    }

    @Test
    void 默认适应窗口的选择也会记住() {
        Path file = tempDir.resolve("preferences.properties");
        AppSettings settings = new AppSettings(file);
        settings.setDefaultZoomPercent(33);
        settings.setDefaultFitToWindow(true);

        AppSettings reloaded = new AppSettings(file);
        assertTrue(reloaded.defaultFitToWindow());
        assertEquals(33, reloaded.defaultZoomPercent(), "切回 Fit 不应丢掉备用固定档位");
    }

    @Test
    void 文件坏了就当没存过_回默认值而不是崩掉() throws Exception {
        Path file = tempDir.resolve("preferences.properties");
        Files.writeString(file, "viewer.defaultZoomPercent=这不是数字\n");

        assertEquals(18, new AppSettings(file).defaultZoomPercent());

        Files.writeString(file, "viewer.defaultZoomPercent=-5\n");
        assertEquals(18, new AppSettings(file).defaultZoomPercent(), "负数的倍率没有意义");
    }

    @Test
    void 窗口位置大小与放大倍率互不覆盖() {
        Path file = tempDir.resolve("preferences.properties");
        AppSettings settings = new AppSettings(file);
        AppSettings.WindowState state =
                new AppSettings.WindowState(-1280, 80, 1100, 720, true);

        settings.setDefaultZoomPercent(33);
        settings.setWindowState(state);

        AppSettings reloaded = new AppSettings(file);
        assertEquals(state, reloaded.windowState().orElseThrow(),
                "重新打开程序时应当读回上次的窗口边界");
        assertEquals(33, reloaded.defaultZoomPercent(),
                "保存窗口位置不能顺手把放大倍率清掉");
    }

    @Test
    void 窗口设置不完整或尺寸无效时回退为居中打开() throws Exception {
        Path file = tempDir.resolve("preferences.properties");
        Files.writeString(file, """
                window.x=10
                window.y=20
                window.width=0
                window.height=700
                window.maximized=true
                """);

        assertTrue(new AppSettings(file).windowState().isEmpty(),
                "尺寸为 0 的窗口配置不能拿来恢复");
    }
}

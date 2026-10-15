package com.photoselector;

import javax.swing.SwingUtilities;

import com.photoselector.ui.MainFrame;
import com.photoselector.ui.theme.Theme;

/**
 * 程序入口。
 *
 * <p>负责装好外观、开出主窗口。真正的照片筛选逻辑在 {@code core} 与 {@code io} 包里。
 */
public final class PhotoSelectorApp {

    private static final String APP_NAME = "摄影集照片筛选器";

    private PhotoSelectorApp() {
        // 工具类，禁止实例化
    }

    /**
     * 返回程序名称，供启动信息与测试使用。
     *
     * @return 程序名称
     */
    public static String appName() {
        return APP_NAME;
    }

    public static void main(String[] args) {
        // Swing 的铁律：界面必须在事件分发线程（EDT）上创建和修改。
        // invokeLater 把这段任务排进 EDT 的队列，而不是在 main 线程里直接开窗口。
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            new MainFrame().setVisible(true);
        });
    }
}

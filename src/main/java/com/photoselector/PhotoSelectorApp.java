package com.photoselector;

import java.nio.file.Files;
import java.util.Optional;

import javax.swing.SwingUtilities;

import com.photoselector.core.Workspace;
import com.photoselector.io.AppSettings;
import com.photoselector.io.WorkspaceStore;
import com.photoselector.ui.MainFrame;
import com.photoselector.ui.theme.Theme;

/**
 * 程序入口。
 *
 * <p>负责装好外观、开出主窗口。真正的照片筛选逻辑在 {@code core} 与 {@code io} 包里。
 */
public final class PhotoSelectorApp {

    private static final String APP_NAME = "摄影集照片筛选器";
    private static final String APP_VERSION = "1.1";

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

    /** 当前发行版本，显示在主窗口标题和打包信息中。 */
    public static String appVersion() {
        return APP_VERSION;
    }

    public static void main(String[] args) {
        // Swing 的铁律：界面必须在事件分发线程（EDT）上创建和修改。
        // invokeLater 把这段任务排进 EDT 的队列，而不是在 main 线程里直接开窗口。
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            WorkspaceStore store = new WorkspaceStore();
            MainFrame frame = new MainFrame(store, new AppSettings());
            frame.setVisible(true);
            restoreOrChooseWorkspace(frame, store);
        });
    }

    /**
     * 启动时的工作区逻辑。
     *
     * <p>上次选过就直接用；没选过、或者素材根文件夹已经不在了，就打开「选择工作区」。
     * 根目录不在了要先说一句再弹——不然用户只会看到一个空界面，不知道发生了什么。
     */
    private static void restoreOrChooseWorkspace(MainFrame frame, WorkspaceStore store) {
        Optional<Workspace> saved = store.load();
        if (saved.isEmpty()) {
            frame.chooseWorkspace(); // 头一次打开
            return;
        }

        Workspace workspace = saved.get();
        if (Files.isDirectory(workspace.sourceRoot())) {
            frame.showWorkspace(workspace);
            return;
        }

        frame.warnMissingSourceRoot(workspace.sourceRoot());
        frame.chooseWorkspace();
    }
}

package com.photoselector.ui;

import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicMenuBarUI;

import com.photoselector.PhotoSelectorApp;
import com.photoselector.core.Workspace;
import com.photoselector.io.AppSettings;
import com.photoselector.io.Saver;
import com.photoselector.io.ThumbnailService;
import com.photoselector.io.WorkspaceStore;
import com.photoselector.ui.theme.Theme;

/**
 * 主窗口：菜单栏 + {@link MainPanel}。
 */
@SuppressWarnings("serial") // Swing 组件都实现了 Serializable，但我们从不序列化界面，无需 serialVersionUID
public final class MainFrame extends JFrame {

    /** 判断一块旧窗口位置是否还落在屏幕上：至少留出这么多标题栏。 */
    private static final int MIN_VISIBLE_WIDTH = 120;
    private static final int MIN_VISIBLE_HEIGHT = 60;

    private final MainPanel mainPanel;
    private final WorkspaceStore workspaceStore;
    private final AppSettings settings;
    private boolean restoreMaximizedOnOpen;
    private Rectangle normalWindowBounds;
    private boolean normalBoundsCapturePending;

    /** 菜单里那两项：灰亮状态跟着撤销 / 重做的历史走。 */
    public MainFrame(WorkspaceStore workspaceStore, AppSettings settings) {
        this(workspaceStore, settings, ThumbnailService.defaultCacheDir());
    }

    /** 供测试指定缩略图缓存目录，避免碰用户真实缓存。 */
    MainFrame(WorkspaceStore workspaceStore, AppSettings settings, Path thumbnailCacheDir) {
        super(PhotoSelectorApp.appName() + " " + PhotoSelectorApp.appVersion());
        this.workspaceStore = workspaceStore;
        this.settings = settings;
        this.mainPanel = new MainPanel(thumbnailCacheDir);
        mainPanel.applyStartupZoom(settings.defaultFitToWindow(), settings.defaultZoomPercent());

        // 不能再用 EXIT_ON_CLOSE：那样点 × 会绕过"有没有没保存的改动"这一问。
        // 关窗口现在只有一条路——MainPanel.requestClose()，菜单里的"退出"也走它。
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                if (restoreMaximizedOnOpen) {
                    restoreMaximizedOnOpen = false;
                    setExtendedState(MAXIMIZED_BOTH);
                }
            }

            @Override
            public void windowClosing(WindowEvent e) {
                mainPanel.requestClose();
            }
        });
        mainPanel.setOnExit(this::exit);
        setContentPane(mainPanel);
        setJMenuBar(createMenuBar(mainPanel, this::chooseWorkspace, mainPanel::requestClose,
                this::changeDefaultZoom, this::refreshHistoryStates));
        mainPanel.setOnFolderOpened(this::folderOpened);
        mainPanel.setOnTargetFolderError(this::targetFolderFailed);
        mainPanel.setOnSaveResiduals(this::showSaveResiduals);
        // 配置文件写不进去只影响下次恢复，不该让“选工作区”这个界面动作抛到 EDT 上。
        mainPanel.setOnWorkspaceChanged(workspace -> {
            try {
                workspaceStore.save(workspace);
            } catch (RuntimeException ignored) {
                // 下次启动重新选一遍工作区即可，当前这次使用不受影响。
            }
        });
        mainPanel.setOnSaveFinished(this::saveFinished);

        // 工具栏左组那两个按钮：和菜单里对应的项走同一条路
        mainPanel.toolBar().chooseWorkspaceButton().addActionListener(e -> chooseWorkspace());
        mainPanel.toolBar().saveButton().addActionListener(e -> mainPanel.save());
        mainPanel.toolBar().fitButton().addActionListener(e -> mainPanel.fitViewerToWindow());
        // 撤销 / 重做的接线在 MainPanel 自己那儿（见 MainPanel 构造器），
        // 这里只负责开局把菜单项的灰亮对一次
        // 菜单里的两项和工具栏按钮共用同一份状态：谁换了历史，两边一起更新
        mainPanel.setOnHistoryChanged(this::refreshHistoryStates);
        refreshHistoryStates(); // 开局同步一次：没有历史时那两颗按钮该是灰的

        setPreferredSize(new Dimension(1440, 900));
        pack();
        normalWindowBounds = getBounds();
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                scheduleNormalBoundsCapture();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                scheduleNormalBoundsCapture();
            }
        });
        restoreWindowState();
    }

    /**
     * 恢复上次的窗口边界；若当时最大化，等窗口真正打开后再切回最大化。
     *
     * <p>显示器拔掉或分辨率变小后，旧坐标可能已经完全落在屏幕外。
     * 这种情况不硬套旧位置，而是在当前可用区域里重新居中，免得窗口“打开却看不见”。
     */
    private void restoreWindowState() {
        var saved = settings.windowState();
        if (saved.isEmpty()) {
            fitNewWindowOnScreen();
            return;
        }

        AppSettings.WindowState state = saved.get();
        Rectangle target = new Rectangle(state.x(), state.y(), state.width(), state.height());
        if (screenShowing(target) == null) {
            Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            target.width = Math.min(target.width, usable.width);
            target.height = Math.min(target.height, usable.height);
            target.x = usable.x + (usable.width - target.width) / 2;
            target.y = usable.y + (usable.height - target.height) / 2;
        }
        setBounds(target);
        normalWindowBounds = target;
        restoreMaximizedOnOpen = state.maximized();
    }

    /** 第一次打开时若出厂尺寸比屏幕还大，先缩到可用区域，再居中。 */
    private void fitNewWindowOnScreen() {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        setSize(Math.min(getWidth(), usable.width), Math.min(getHeight(), usable.height));
        setLocationRelativeTo(null);
    }

    /** 找到与窗口矩形有足够可见交集的那块屏幕；全在屏幕外就返回 null。 */
    private static Rectangle screenShowing(Rectangle window) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getScreenDevices()) {
            Rectangle screen = device.getDefaultConfiguration().getBounds();
            Rectangle visible = screen.intersection(window);
            if (visible.width >= MIN_VISIBLE_WIDTH && visible.height >= MIN_VISIBLE_HEIGHT) {
                return screen;
            }
        }
        return null;
    }

    /** 撤销 / 重做的灰亮：工具栏按钮和菜单项一起跟着历史走。 */
    private void refreshHistoryStates() {
        boolean canUndo = mainPanel.canUndo();
        boolean canRedo = mainPanel.canRedo();
        mainPanel.toolBar().undoButton().setEnabled(canUndo);
        mainPanel.toolBar().redoButton().setEnabled(canRedo);
        // 菜单里那两项不单独记引用：直接按名字在菜单树上找。
        // 只存两份引用的话，将来菜单结构一挪就悄悄失效；按名字找想挪都挪不坏。
        for (java.awt.Component menuComponent : getJMenuBar().getComponents()) {
            if (menuComponent instanceof JMenu menu) {
                for (java.awt.Component item : menu.getMenuComponents()) {
                    if (item instanceof JMenuItem menuItem) {
                        if ("撤销".equals(menuItem.getText())) {
                            menuItem.setEnabled(canUndo);
                        } else if ("重做".equals(menuItem.getText())) {
                            menuItem.setEnabled(canRedo);
                        }
                    }
                }
            }
        }
    }

    /**
     * 菜单栏：文件 / 编辑 / 图像 / 查看。
     *
     * <p>没实现的功能一律先 {@code setEnabled(false)} 灰着——
     * 点了没反应的菜单比灰掉的菜单更让人困惑。
     *
     * <p>写成 static 是方便测试把菜单栏单独拿去渲染，不必真的开一个窗口。
     */
    static JMenuBar createMenuBar(MainPanel mainPanel, Runnable openFolderAction, Runnable quitAction) {
        return createMenuBar(mainPanel, openFolderAction, quitAction, percent -> { }, () -> { });
    }

    static JMenuBar createMenuBar(MainPanel mainPanel, Runnable openFolderAction, Runnable quitAction,
                                  java.util.function.IntConsumer changeDefaultZoom,
                                  Runnable historyChanged) {
        JMenuBar bar = new JMenuBar();
        // Metal 的菜单栏默认会画一道渐变，换成基础实现后它就老老实实只填背景色
        bar.setUI(new BasicMenuBarUI());
        bar.setOpaque(true);
        bar.setBackground(Theme.PANEL);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));

        JMenu file = menu("文件");
        JMenuItem chooseWorkspace = new JMenuItem("选择工作区…");
        chooseWorkspace.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        chooseWorkspace.addActionListener(e -> openFolderAction.run());
        file.add(chooseWorkspace);
        JMenuItem save = new JMenuItem("保存");
        save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        save.addActionListener(e -> mainPanel.save());
        file.add(save);
        file.addSeparator();
        JMenuItem quit = new JMenuItem("退出");
        quit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        // 不做 shutdown()——收线程池是"真要走了"才做的事，交给 quitAction 那条路统一收尾
        quit.addActionListener(e -> quitAction.run());
        file.add(quit);

        JMenu edit = menu("编辑");
        // 不给它设加速键：这两个键已经通过 MainPanel 的输入映射全局生效，
        // 菜单项再占同样的键就多了个出处，将来想换键得改两处。
        JMenuItem addToTarget = new JMenuItem("加入目标文件夹（追加到末尾）");
        addToTarget.addActionListener(e -> mainPanel.addCurrentPhotoToTarget());
        edit.add(addToTarget);
        edit.addSeparator();
        // 这两个和工具栏按钮、快捷键共用同一个动作（MainPanel.undo / redo）：
        // 从哪儿触发都是同一条路。加速键和 MainPanel 的表里是同一个键，
        // 菜单开着时由菜单接管、平时由输入映射接管，落到的是同一段代码。
        JMenuItem undoItem = new JMenuItem("撤销");
        undoItem.setAccelerator(MainPanel.undoKeyStroke());
        undoItem.addActionListener(e -> {
            mainPanel.undo();
            historyChanged.run();
        });
        edit.add(undoItem);
        JMenuItem redoItem = new JMenuItem("重做");
        redoItem.setAccelerator(MainPanel.redoKeyStroke());
        redoItem.addActionListener(e -> {
            mainPanel.redo();
            historyChanged.run();
        });
        edit.add(redoItem);

        JMenu image = menu("图像");
        // 旋转 / 翻转都先给左右（上下）两项；点完之后裁切中留在裁切，
        // 其余情况回到“选择”——菜单是一次性动作，不是工具模式。
        JMenu rotate = menu("旋转");
        rotate.add(imageAction(mainPanel, "向左转 90°", () -> mainPanel.rotateCurrentPhoto(false)));
        rotate.add(imageAction(mainPanel, "向右转 90°", () -> mainPanel.rotateCurrentPhoto(true)));
        image.add(rotate);
        JMenu flip = menu("翻转");
        flip.add(imageAction(mainPanel, "水平翻转", mainPanel::flipCurrentPhotoHorizontally));
        flip.add(imageAction(mainPanel, "垂直翻转", mainPanel::flipCurrentPhotoVertically));
        image.add(flip);
        JMenuItem crop = new JMenuItem("裁切");
        crop.addActionListener(e -> mainPanel.beginCrop());
        image.add(crop);
        JMenuItem reset = new JMenuItem("重置");
        reset.addActionListener(e -> mainPanel.resetCurrentPhotoEdits());
        image.add(reset);

        JMenu view = menu("查看");
        JMenuItem browse = new JMenuItem(ViewMode.BROWSE.label());
        browse.addActionListener(e -> mainPanel.setMode(ViewMode.BROWSE));
        JMenuItem arrange = new JMenuItem(ViewMode.ARRANGE.label());
        arrange.addActionListener(e -> mainPanel.setMode(ViewMode.ARRANGE));
        view.add(browse);
        view.add(arrange);
        view.addSeparator();
        view.add(zoomMenu(mainPanel));
        view.addSeparator();
        view.add(defaultZoomMenu(mainPanel, changeDefaultZoom));
        view.addSeparator();
        view.add(thumbnailSizeMenu(mainPanel));

        bar.add(file);
        bar.add(edit);
        bar.add(image);
        bar.add(view);
        return bar;
    }

    /** 打开「选择工作区」对话框；用户确认后应用并记下来。 */
    public void chooseWorkspace() {
        WorkspaceDialog.show(this, mainPanel.workspace()).ifPresent(this::showWorkspace);
    }

    /** 应用一个工作区：界面切过去，配置写回文件。 */
    public void showWorkspace(Workspace workspace) {
        // 走 switchWorkspace：有没保存的改动就复用关窗口那套「保存 / 不保存 / 取消」，
        // 等保存真跑完再切（扫描在后台进行，结果通过回调通知）
        mainPanel.switchWorkspace(workspace);
    }

    /** 用户改了默认缩放方式：-1 表示适应窗口，其余是固定百分比。 */
    private void changeDefaultZoom(int percent) {
        if (percent < 0) {
            settings.setDefaultFitToWindow(true);
        } else {
            settings.setDefaultZoomPercent(percent);
        }
    }

    /** 上次的素材文件夹不见了时的提示。 */
    public void warnMissingSourceRoot(Path missing) {
        MessageDialog.show(this, "工作区",
                "上次打开的素材文件夹找不到了：\n" + missing + "\n\n请重新选一个。");
    }

    /** 保存结束了：把做了什么、以及没做成什么告诉用户。 */
    private void saveFinished(Saver.Result result) {
        if (mainPanel.isClosingAfterSave() && !result.hasProblems()) {
            // 成功保存后马上退出：不用弹一个只会闪一下的完成提示。
            // 有失败时必须弹，而且 MainPanel 会取消本次退出，让用户先处理。
            return;
        }
        String text = result.summary();
        if (result.hasProblems()) {
            text += "\n\n有 " + result.problems().size() + " 处没能完成：\n· "
                    + String.join("\n· ", result.problems());
        }
        MessageDialog.show(this, "保存", text);
    }

    /** 真正退出：收掉后台线程池，再关窗口。 */
    private void exit() {
        rememberWindowState();
        Workspace workspace = mainPanel.workspace();
        if (workspace != null) {
            try {
                workspaceStore.save(workspace);
            } catch (RuntimeException ignored) {
                // 偏好文件写不进去不该阻止用户退出
            }
        }
        dispose();
    }

    /** 记下当前窗口边界；最大化状态单独存，打开时先落位再重新最大化。 */
    private void rememberWindowState() {
        int state = getExtendedState();
        boolean maximized = (state & MAXIMIZED_BOTH) != 0;
        Rectangle bounds = state == NORMAL || normalWindowBounds == null
                ? getBounds()
                : normalWindowBounds;
        if (bounds.width <= 0 || bounds.height <= 0) {
            return;
        }
        settings.setWindowState(new AppSettings.WindowState(
                bounds.x, bounds.y, bounds.width, bounds.height, maximized));
    }

    /**
     * 延迟一拍再记普通窗口边界。
     *
     * <p>最大化时组件尺寸也会变化；如果当场记，会把整块屏幕误当成“还原后的尺寸”。
     * 等本轮事件走完再看扩展状态，就能把最大化 / 最小化的中间态排除掉。
     */
    private void scheduleNormalBoundsCapture() {
        if (normalBoundsCapturePending) {
            return;
        }
        normalBoundsCapturePending = true;
        SwingUtilities.invokeLater(() -> {
            normalBoundsCapturePending = false;
            if (getExtendedState() != NORMAL) {
                return;
            }
            Rectangle bounds = getBounds();
            if (bounds.width > 0 && bounds.height > 0) {
                normalWindowBounds = bounds;
            }
        });
    }

    /**
     * 窗口一旦销毁就收掉监听线程、扫描线程和取图线程。
     *
     * <p>正常退出会走到这里；测试或别的调用直接 {@code dispose()} 时也必须走同一条收尾路，
     * 否则监听器会在临时目录被删掉后继续工作，甚至弹出一个没有主窗口依附的错误框。
     */
    @Override
    public void dispose() {
        mainPanel.shutdown();
        super.dispose();
    }

    /** 素材文件夹扫描完之后的收尾：更新标题，或者报错。 */
    private void folderOpened(Path folder, int count) {
        if (count < 0) {
            MessageDialog.show(this, "打开失败", "读不了这个文件夹：\n" + folder);
            return;
        }
        Path name = folder.getFileName();
        setTitle(PhotoSelectorApp.appName() + " " + PhotoSelectorApp.appVersion() + " — "
                + (name == null ? folder : name)
                + "（" + count + " 张）");
    }

    /** 目标文件夹读不了时明确报错，并保持中间商为空。 */
    private void targetFolderFailed(Path folder) {
        MessageDialog.show(this, "目标文件夹",
                "读不了或无法监听目标文件夹：\n" + folder
                        + "\n\n请检查目录是否存在、是否有读取权限。");
    }

    private void showSaveResiduals(java.util.List<Path> files) {
        String names = files.stream().limit(8)
                .map(path -> "· " + path.getFileName())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (files.size() > 8) {
            names += "\n· …还有 " + (files.size() - 8) + " 个";
        }
        MessageDialog.show(this, "保存残留",
                "发现上次保存中断留下的临时文件，已暂时排除在照片列表之外：\n\n"
                        + names
                        + "\n\n它们没有自动删除，请确认内容后手工处理。");
    }

    private static JMenu menu(String text) {
        JMenu menu = new JMenu(text);
        menu.setForeground(Theme.TEXT);
        // 上下各留一点：菜单栏太扁的话，字贴着边框显得局促。
        // 菜单的高度看的是边框的衬边（PreferredSize 里用的是 getInsets），不是 margin
        menu.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        return menu;
    }

    /** 「图像」菜单里的一项：点一下做件事，再回到裁切或选择工具。 */
    private static JMenuItem imageAction(MainPanel mainPanel, String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> {
            action.run();
            mainPanel.finishImageAction();
        });
        return item;
    }

    /**
     * 「查看 → 缩略图大小」：目标文件夹那一栏的缩略图尺寸。
     *
     * <p>放在菜单里而不是靠拖分隔条，是因为这两件事分工不同：分隔条决定这块地方有多宽，
     * 而尺寸是你对"一次能看清多少张"的偏好。宽度变化时变的是间距，照片本身不缩放。
     */
    private static JMenu thumbnailSizeMenu(MainPanel mainPanel) {
        JMenu sizeMenu = menu("缩略图大小");
        ButtonGroup group = new ButtonGroup();
        sizeMenu.add(sizeItem(group, mainPanel, "小", 110, false));
        sizeMenu.add(sizeItem(group, mainPanel, "中", 150, true));
        sizeMenu.add(sizeItem(group, mainPanel, "大", 210, false));
        return sizeMenu;
    }

    /**
     * 「查看 → 缩放」：大图是适应窗口还是 1:1。
     *
     * <p>菜单里的勾选跟着大图的真实状态走——滚轮缩放之后会自己跳到"自定义"，
     * 这样菜单不会跟眼前的画面对不上。
     */
    private static JMenu zoomMenu(MainPanel mainPanel) {
        JMenu menu = menu("缩放");
        JRadioButtonMenuItem fit = new JRadioButtonMenuItem("适应窗口", true);
        JRadioButtonMenuItem actual = new JRadioButtonMenuItem("100%", false);
        for (JRadioButtonMenuItem item : new JRadioButtonMenuItem[] {fit, actual}) {
            item.setForeground(Theme.TEXT);
            item.setBackground(Theme.PANEL);
            menu.add(item);
        }
        ButtonGroup group = new ButtonGroup();
        group.add(fit);
        group.add(actual);

        fit.addActionListener(e -> mainPanel.fitViewerToWindow());
        actual.addActionListener(e -> mainPanel.viewerActualSize());
        mainPanel.setOnViewerZoomChanged(() -> {
            if (mainPanel.viewerIsFitToWindow()) {
                fit.setSelected(true);
            } else if (mainPanel.viewerZoomPercent() == 100) {
                actual.setSelected(true);
            } else {
                // 滚轮缩放到别的比例时两个都不是，那就都不勾——
                // 硬勾一个会让菜单和眼前的画面对不上
                group.clearSelection();
            }
        });
        return menu;
    }

    /**
     * 「查看 → 默认缩放」：以后每张照片打开时怎么摆。
     *
     * <p>默认是适应窗口；也可以改成固定百分比。两种选择都会记住。
     */
    private static JMenu defaultZoomMenu(MainPanel mainPanel,
                                         java.util.function.IntConsumer onChange) {
        JMenu menu = menu("默认缩放");
        ButtonGroup group = new ButtonGroup();
        JRadioButtonMenuItem fit = new JRadioButtonMenuItem(
                "适应窗口", mainPanel.defaultFitToWindow());
        fit.setForeground(Theme.TEXT);
        fit.setBackground(Theme.PANEL);
        fit.addActionListener(e -> {
            mainPanel.setDefaultFitToWindow();
            onChange.accept(-1);
        });
        group.add(fit);
        menu.add(fit);
        menu.addSeparator();
        // 档位都取自大图自己的档位表，保证菜单里选得出来的值滚轮也滚得到
        for (int percent : ViewerPanel.defaultZoomChoices()) {
            JRadioButtonMenuItem item =
                    new JRadioButtonMenuItem(percent + "%",
                            !mainPanel.defaultFitToWindow()
                                    && percent == mainPanel.defaultZoomPercent());
            item.setForeground(Theme.TEXT);
            item.setBackground(Theme.PANEL);
            item.addActionListener(e -> {
                mainPanel.setDefaultZoomPercent(percent);
                onChange.accept(percent);
            });
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    private static JRadioButtonMenuItem sizeItem(ButtonGroup group, MainPanel mainPanel,
                                                 String text, int width, boolean selected) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(text, selected);
        item.setForeground(Theme.TEXT);
        item.setBackground(Theme.PANEL);
        item.addActionListener(e -> mainPanel.setTargetThumbnailWidth(width));
        group.add(item);
        return item;
    }

}

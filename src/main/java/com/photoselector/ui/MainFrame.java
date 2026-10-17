package com.photoselector.ui;

import java.awt.Dimension;
import java.awt.Insets;
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
import javax.swing.JOptionPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.plaf.basic.BasicMenuBarUI;

import com.photoselector.core.Workspace;
import com.photoselector.io.AppSettings;
import com.photoselector.io.Saver;
import com.photoselector.io.WorkspaceStore;
import com.photoselector.ui.theme.Theme;

/**
 * 主窗口：菜单栏 + {@link MainPanel}。
 */
@SuppressWarnings("serial") // Swing 组件都实现了 Serializable，但我们从不序列化界面，无需 serialVersionUID
public final class MainFrame extends JFrame {

    private final MainPanel mainPanel = new MainPanel();
    private final WorkspaceStore workspaceStore;
    private final AppSettings settings;

    /** 菜单里那两项：灰亮状态跟着撤销 / 重做的历史走。 */
    public MainFrame(WorkspaceStore workspaceStore, AppSettings settings) {
        super("摄影集照片筛选器");
        this.workspaceStore = workspaceStore;
        this.settings = settings;
        mainPanel.setDefaultZoomPercent(settings.defaultZoomPercent());

        // 不能再用 EXIT_ON_CLOSE：那样点 × 会绕过"有没有没保存的改动"这一问。
        // 关窗口现在只有一条路——MainPanel.requestClose()，菜单里的"退出"也走它。
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
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
        mainPanel.setOnWorkspaceChanged(workspaceStore::save);
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
        setLocationRelativeTo(null); // 居中打开
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
        // 旋转 / 翻转都先给左右（上下）两项；点完之后把工具拨回"选择"，
        // 免得光标一直停在旋转图标上——菜单是一次性动作，不是模式
        JMenu rotate = menu("旋转");
        rotate.add(imageAction(mainPanel, "向左转 90°", () -> mainPanel.rotateCurrentPhoto(false)));
        rotate.add(imageAction(mainPanel, "向右转 90°", () -> mainPanel.rotateCurrentPhoto(true)));
        image.add(rotate);
        JMenu flip = menu("翻转");
        flip.add(imageAction(mainPanel, "水平翻转", mainPanel::flipCurrentPhotoHorizontally));
        flip.add(imageAction(mainPanel, "垂直翻转", mainPanel::flipCurrentPhotoVertically));
        image.add(flip);
        image.add(pending("裁切"));
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

    /** 用户改了默认放大倍率：记下来，下次打开还是它。 */
    private void changeDefaultZoom(int percent) {
        settings.setDefaultZoomPercent(percent);
    }

    /** 上次的素材文件夹不见了时的提示。 */
    public void warnMissingSourceRoot(Path missing) {
        JOptionPane.showMessageDialog(this,
                "上次打开的素材文件夹找不到了：\n" + missing + "\n\n请重新选一个。",
                "工作区", JOptionPane.WARNING_MESSAGE);
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
        JOptionPane.showMessageDialog(this, text, "保存",
                result.hasProblems() ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    /** 真正退出：收掉后台线程池，再关窗口。 */
    private void exit() {
        mainPanel.shutdown();
        dispose();
    }

    /** 素材文件夹扫描完之后的收尾：更新标题，或者报错。 */
    private void folderOpened(Path folder, int count) {
        if (count < 0) {
            JOptionPane.showMessageDialog(this, "读不了这个文件夹：\n" + folder,
                    "打开失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        setTitle("摄影集照片筛选器 — " + folder.getFileName() + "（" + count + " 张）");
    }

    /** 目标文件夹读不了时明确报错，并保持中间商为空。 */
    private void targetFolderFailed(Path folder) {
        JOptionPane.showMessageDialog(this,
                "读不了目标文件夹：\n" + folder
                        + "\n\n请检查目录是否存在、是否有读取权限。",
                "目标文件夹", JOptionPane.ERROR_MESSAGE);
    }

    private static JMenu menu(String text) {
        JMenu menu = new JMenu(text);
        menu.setForeground(Theme.TEXT);
        // 上下各留一点：菜单栏太扁的话，字贴着边框显得局促。
        // 菜单的高度看的是边框的衬边（PreferredSize 里用的是 getInsets），不是 margin
        menu.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        return menu;
    }

    /** 「图像」菜单里的一项：点一下做件事，并把光标工具拨回"选择"。 */
    private static JMenuItem imageAction(MainPanel mainPanel, String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> {
            action.run();
            mainPanel.toolBar().selectTool(CursorTool.SELECT);
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
     * 「查看 → 默认放大倍率」：打开一张照片时用多大的比例。
     *
     * <p>和上面那个「缩放」是两回事：那个管"眼前这一张现在多大"，这个管"以后每张打开时多大"。
     * 挑一个档位，当前这张立刻按新倍率显示，之后翻到的每一张也都用它。
     */
    private static JMenu defaultZoomMenu(MainPanel mainPanel,
                                         java.util.function.IntConsumer onChange) {
        JMenu menu = menu("默认放大倍率");
        ButtonGroup group = new ButtonGroup();
        // 档位都取自大图自己的档位表，保证菜单里选得出来的值滚轮也滚得到
        for (int percent : ViewerPanel.defaultZoomChoices()) {
            JRadioButtonMenuItem item =
                    new JRadioButtonMenuItem(percent + "%", percent == mainPanel.defaultZoomPercent());
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

    /** 尚未实现的菜单项：先禁用占位。 */
    private static JMenuItem pending(String text) {
        return pending(text, null);
    }

    private static JMenuItem pending(String text, KeyStroke accelerator) {
        JMenuItem item = new JMenuItem(text);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.setEnabled(false);
        return item;
    }
}

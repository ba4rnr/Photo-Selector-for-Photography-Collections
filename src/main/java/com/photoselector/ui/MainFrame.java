package com.photoselector.ui;

import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.plaf.basic.BasicMenuBarUI;

import com.photoselector.ui.theme.Theme;
import com.photoselector.core.Workspace;
import com.photoselector.io.WorkspaceStore;
import com.photoselector.io.Saver;

/**
 * 主窗口：菜单栏 + {@link MainPanel}。
 */
@SuppressWarnings("serial") // Swing 组件都实现了 Serializable，但我们从不序列化界面，无需 serialVersionUID
public final class MainFrame extends JFrame {

    private final MainPanel mainPanel = new MainPanel();
    private final WorkspaceStore workspaceStore;

    public MainFrame(WorkspaceStore workspaceStore) {
        super("摄影集照片筛选器");
        this.workspaceStore = workspaceStore;

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setContentPane(mainPanel);
        setJMenuBar(createMenuBar(mainPanel, this::chooseWorkspace, this::dispose));
        mainPanel.setOnFolderOpened(this::folderOpened);
        mainPanel.setOnWorkspaceChanged(workspaceStore::save);
        mainPanel.setOnSaveFinished(this::saveFinished);

        // 工具栏左组那两个按钮：和菜单里对应的项走同一条路
        mainPanel.toolBar().chooseWorkspaceButton().addActionListener(e -> chooseWorkspace());
        mainPanel.toolBar().saveButton().addActionListener(e -> mainPanel.save());
        mainPanel.toolBar().fitButton().addActionListener(e -> mainPanel.fitViewerToWindow());

        setPreferredSize(new Dimension(1440, 900));
        pack();
        setLocationRelativeTo(null); // 居中打开
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
        quit.addActionListener(e -> {
            mainPanel.shutdown();
            quitAction.run();
        });
        file.add(quit);

        JMenu edit = menu("编辑");
        edit.add(pending("撤销", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)));
        edit.add(pending("重做", KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)));

        JMenu image = menu("图像");
        image.add(pending("旋转"));
        image.add(pending("翻转"));
        image.add(pending("裁切"));
        image.add(pending("重置"));

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
        mainPanel.applyWorkspace(workspace); // 扫描在后台进行，结果通过回调通知
    }

    /** 上次的素材文件夹不见了时的提示。 */
    public void warnMissingSourceRoot(Path missing) {
        JOptionPane.showMessageDialog(this,
                "上次打开的素材文件夹找不到了：\n" + missing + "\n\n请重新选一个。",
                "工作区", JOptionPane.WARNING_MESSAGE);
    }

    /** 保存结束了：把做了什么、以及没做成什么告诉用户。 */
    private void saveFinished(Saver.Result result) {
        String text = result.summary();
        if (result.hasProblems()) {
            text += "\n\n有 " + result.problems().size() + " 处没能完成：\n· "
                    + String.join("\n· ", result.problems());
        }
        JOptionPane.showMessageDialog(this, text, "保存",
                result.hasProblems() ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
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

    private static JMenu menu(String text) {
        JMenu menu = new JMenu(text);
        menu.setForeground(Theme.TEXT);
        return menu;
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

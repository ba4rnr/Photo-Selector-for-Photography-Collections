package com.photoselector.ui;

import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.plaf.basic.BasicMenuBarUI;

import com.photoselector.ui.theme.Theme;

/**
 * 主窗口：菜单栏 + {@link MainPanel}。
 */
@SuppressWarnings("serial") // Swing 组件都实现了 Serializable，但我们从不序列化界面，无需 serialVersionUID
public final class MainFrame extends JFrame {

    private final MainPanel mainPanel = new MainPanel();

    public MainFrame() {
        super("摄影集照片筛选器");

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setContentPane(mainPanel);
        setJMenuBar(createMenuBar(mainPanel, this::chooseFolder, this::dispose));
        mainPanel.setOnFolderOpened(this::folderOpened);

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
        JMenuItem openFolder = new JMenuItem("打开素材文件夹…");
        openFolder.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openFolder.addActionListener(e -> openFolderAction.run());
        file.add(openFolder);
        file.add(pending("保存", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK)));
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

        bar.add(file);
        bar.add(edit);
        bar.add(image);
        bar.add(view);
        return bar;
    }

    /**
     * 让用户挑一个素材文件夹，然后把里面的照片放进底部素材条。
     *
     * <p>这是"选择工作区"的临时入口：完整的那个还要选剔除哪些子文件夹、
     * 选目标文件夹，并记住上次的选择，等那一块做好会把它替换掉。
     */
    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择素材文件夹");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path folder = chooser.getSelectedFile().toPath();
        mainPanel.setRootFolder(folder); // 扫描在后台进行，结果通过回调通知
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

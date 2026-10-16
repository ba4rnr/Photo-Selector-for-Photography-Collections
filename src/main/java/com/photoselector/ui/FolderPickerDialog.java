package com.photoselector.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileSystemView;

import com.photoselector.ui.theme.Icons;
import com.photoselector.ui.theme.Theme;

/**
 * 「挑一个文件夹」对话框：替代 JDK 自带的 {@code JFileChooser}。
 *
 * <p>为什么不用 {@code JFileChooser}：它是系统/金属外观，浅色、带立体边框，
 * 放进这套深色界面里像贴了一块补丁；而且每一处"浏览…"都长得不一样，风格没法统一。
 * 这里照 Capture One 的思路自己画一个：顶部一行导航（后退 / 前进 / 上一级 / 刷新）
 * 加一条可以点的路径面包屑，左边是常用位置和磁盘，中间只列子文件夹，
 * 底部把"将会选择谁"写得明明白白。
 *
 * <p>语义与 Windows 的「选择文件夹」窗口一致：
 * <b>当前所在的文件夹就是默认答案</b>；单击一个子文件夹会把它改成答案，双击则进入它。
 *
 * <p>列目录放在后台线程：网络位置或上千个条目的文件夹，在界面线程里等会整个卡住。
 */
@SuppressWarnings("serial")
final class FolderPickerDialog extends JDialog {

    /** 列表行高。比目录树略宽一点，好让文件名和图标都舒展。 */
    private static final int ROW_HEIGHT = 27;

    /** 左侧栏宽度。 */
    private static final int SIDEBAR_WIDTH = 190;

    /** 左侧栏的一项；分区标题没有路径，只是把两类位置隔开。 */
    private record Place(Path path, String label, Icon icon, boolean header) {

        static Place header(String label) {
            return new Place(null, label, null, true);
        }

        static Place of(Path path, String label, Icon icon) {
            return new Place(path, label, icon, false);
        }
    }

    private final DefaultListModel<Path> folderModel = new DefaultListModel<>();
    private final JList<Path> folderList = new JList<>(folderModel);
    private final DefaultListModel<Place> placeModel = new DefaultListModel<>();
    private final JList<Place> placeList = new JList<>(placeModel);

    private final JPanel breadcrumbs = new JPanel(new FlowLayout(FlowLayout.LEFT, 1, 0));
    private final JTextField pathField = new JTextField();

    private final FlatButton backButton = navButton(Icons.back(), "后退（Alt+←）");
    private final FlatButton forwardButton = navButton(Icons.forward(), "前进（Alt+→）");
    private final FlatButton upButton = navButton(Icons.up(), "向上一级（Alt+↑）");
    private final FlatButton refreshButton = navButton(Icons.refresh(), "刷新（F5）");

    private final CardLayout contentCards = new CardLayout();
    private final JPanel contentArea = new JPanel(contentCards);
    private final JLabel hintLabel = new JLabel("", SwingConstants.CENTER);

    /** 列目录用的后台线程；单线程即可，反正同一时刻只在一个文件夹里。 */
    private final ExecutorService listingPool = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "folder-picker");
        thread.setDaemon(true);
        return thread;
    });

    /** 浏览历史：后退 / 前进两摞。 */
    private final Deque<Path> backStack = new ArrayDeque<>();
    private final Deque<Path> forwardStack = new ArrayDeque<>();

    /** 现在在哪儿。 */
    private Path currentFolder;

    /** 确定时会交出去的文件夹：谁被单击、或者进了哪儿，就是谁。 */
    private Path chosen;

    /** 已经列完并用在画面上的文件夹（比 currentFolder 晚一步到位）。 */
    private Path listedFolder;

    /** 点了"选择此文件夹"才有值。 */
    private Path result;

    /** 每换一次地方就加一；回来的结果对不上号就丢掉，免得旧文件夹的内容刷回来。 */
    private int listingGeneration;

    /** 鼠标正悬在哪一行；只用于画悬停底色。 */
    private int hoveredRow = -1;

    /** 打开对话框；用户点了"选择此文件夹"就返回那个文件夹。 */
    static Optional<Path> show(Window owner, String title, Path initial) {
        FolderPickerDialog dialog = new FolderPickerDialog(owner, title, initial);
        dialog.setVisible(true);
        return Optional.ofNullable(dialog.result);
    }

    FolderPickerDialog(Window owner, String title, Path initial) {
        super(owner, title, ModalityType.APPLICATION_MODAL);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(buildNavBar(), BorderLayout.NORTH);
        content.add(buildBody(), BorderLayout.CENTER);
        content.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(content);

        installShortcuts();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                folderList.requestFocusInWindow(); // 打开就能用方向键选
            }

            @Override
            public void windowClosed(WindowEvent e) {
                listingPool.shutdownNow();
            }
        });

        setSize(880, 560);
        setMinimumSize(new Dimension(620, 420));
        setLocationRelativeTo(owner);

        Path start = initial != null && Files.isDirectory(initial) ? initial : defaultStart();
        navigateTo(start, false);
    }

    // ─────────────────────────── 界面搭建 ───────────────────────────

    /** 顶部：导航按钮 + 面包屑。面包屑放滚动面板里，路径长了可以自动滚到"当前这一段"。 */
    private JComponent buildNavBar() {
        backButton.addActionListener(e -> goBack());
        forwardButton.addActionListener(e -> goForward());
        upButton.addActionListener(e -> goUp());
        refreshButton.addActionListener(e -> refresh());

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        nav.setBackground(Theme.PANEL);
        nav.add(backButton);
        nav.add(forwardButton);
        nav.add(upButton);
        nav.add(refreshButton);

        breadcrumbs.setBackground(Theme.PANEL);
        JScrollPane crumbScroll = new JScrollPane(breadcrumbs,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        crumbScroll.setBorder(null);
        crumbScroll.setOpaque(false);
        crumbScroll.getViewport().setOpaque(false);
        // 视口一改大小就要重新把"当前这一段"滚出来：窗口刚开时还没量出宽度，
        // 单靠重建面包屑那一次滚动是滚不动的
        crumbScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                scrollBreadcrumbToTail();
            }
        });

        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(Theme.PANEL);
        // 底下一条淡线，把导航和内容分开；线到按钮之间留一点空气
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(0, 0, 8, 0)));
        bar.add(nav, BorderLayout.WEST);
        bar.add(crumbScroll, BorderLayout.CENTER);
        return bar;
    }

    /** 中部：左快速访问，右文件夹列表。 */
    private JComponent buildBody() {
        JPanel body = new JPanel(new BorderLayout(12, 0));
        body.setBackground(Theme.PANEL);
        body.add(buildSidebar(), BorderLayout.WEST);
        body.add(buildContentArea(), BorderLayout.CENTER);
        return body;
    }

    private JComponent buildSidebar() {
        placeList.setBackground(Theme.PANEL);
        placeList.setCellRenderer(new PlaceRenderer());
        placeList.setFixedCellHeight(ROW_HEIGHT);
        placeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        placeList.setBorder(null);
        placeList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return; // 右键留给以后
                }
                int index = indexAt(placeList, e.getPoint());
                if (index < 0) {
                    return;
                }
                Place place = placeModel.get(index);
                if (place.header()) {
                    placeList.clearSelection(); // 分区标题不是可选项
                    return;
                }
                navigateTo(place.path());
            }
        });
        // 键盘：选到某一项后回车即进入（和资源管理器一致）
        placeList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "picker-place-enter");
        placeList.getActionMap().put("picker-place-enter", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Place place = placeList.getSelectedValue();
                if (place != null && !place.header()) {
                    navigateTo(place.path());
                }
            }
        });
        fillPlaces();

        JScrollPane scroll = new JScrollPane(placeList,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.PANEL);
        Theme.themeScrollBar(scroll.getVerticalScrollBar());
        scroll.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 100));
        return scroll;
    }

    /** 内容区用两张卡换着显示：列表，或者一句"正在读取 / 没有东西看"的提示。 */
    private JComponent buildContentArea() {
        folderList.setBackground(Theme.CANVAS);
        folderList.setCellRenderer(new FolderRenderer());
        folderList.setFixedCellHeight(ROW_HEIGHT);
        folderList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        folderList.setBorder(null);
        folderList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                // 单击 = 把答案改成选中的那一项；点空白处则回到"当前文件夹"
                Path selected = folderList.getSelectedValue();
                setChosen(selected != null ? selected : currentFolder);
            }
        });
        folderList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    int index = indexAt(folderList, e.getPoint());
                    if (index >= 0) {
                        navigateTo(folderModel.get(index));
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                updateHover(-1);
            }
        });
        folderList.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateHover(indexAt(folderList, e.getPoint()));
            }
        });

        JScrollPane scroll = new JScrollPane(folderList,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.CANVAS);
        Theme.themeScrollBar(scroll.getVerticalScrollBar());

        hintLabel.setFont(Theme.FONT);
        hintLabel.setForeground(Theme.TEXT_MUTED);
        hintLabel.setOpaque(true);
        hintLabel.setBackground(Theme.CANVAS);

        contentArea.add(scroll, "list");
        contentArea.add(hintLabel, "hint");
        return contentArea;
    }

    /** 底部：把"将会选择谁"写出来，再放两个动作按钮。 */
    private JComponent buildFooter() {
        JLabel caption = new JLabel("文件夹");
        caption.setFont(Theme.FONT_SMALL);
        caption.setForeground(Theme.TEXT_MUTED);
        caption.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

        pathField.setEditable(false); // 只看不写：要改路径就回上面的输入框改
        pathField.setFont(Theme.FONT);
        pathField.setForeground(Theme.TEXT);
        pathField.setBackground(Theme.CANVAS);
        pathField.setCaretColor(Theme.TEXT);
        pathField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));

        JPanel fieldRow = new JPanel(new BorderLayout());
        fieldRow.setBackground(Theme.PANEL);
        fieldRow.add(caption, BorderLayout.WEST);
        fieldRow.add(pathField, BorderLayout.CENTER);

        FlatButton cancel = new FlatButton("取消", true);
        cancel.addActionListener(e -> dispose());
        FlatButton confirm = new FlatButton("选择此文件夹", true);
        confirm.addActionListener(e -> confirm());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBackground(Theme.PANEL);
        buttons.add(cancel);
        buttons.add(confirm);

        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.setBackground(Theme.PANEL);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(10, 0, 0, 0)));
        footer.add(fieldRow, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);

        getRootPane().setDefaultButton(confirm); // 回车 = 选择此文件夹
        return footer;
    }

    private void installShortcuts() {
        JComponent root = getRootPane();
        shortcut(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), this::dispose);
        shortcut(root, KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), this::goUp);
        shortcut(root, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), this::goBack);
        shortcut(root, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK), this::goForward);
        shortcut(root, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), this::refresh);
        shortcut(root, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), this::goUp);

        // 列表上按右方向键 = 进入选中的文件夹。回车留给默认按钮"选择此文件夹"，
        // 与一般对话框的惯例一致，也避免"本想进去、结果把对话框关掉了"。
        folderList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "picker-enter");
        folderList.getActionMap().put("picker-enter", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                enterSelected();
            }
        });
    }

    private static void shortcut(JComponent component, KeyStroke key, Runnable action) {
        String name = "picker-" + key;
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    // ─────────────────────────── 导航 ───────────────────────────

    /** 去某个文件夹。回退 / 前进 / 刷新走的都是别的入口，它们不记历史。 */
    void navigateTo(Path folder) {
        navigateTo(folder, true);
    }

    private void navigateTo(Path folder, boolean remember) {
        if (folder == null) {
            return;
        }
        Path target = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            return; // 位置已经不在（盘符被拔掉之类），安静地忽略
        }
        if (remember && currentFolder != null && !currentFolder.equals(target)) {
            backStack.push(currentFolder);
            forwardStack.clear();
        }
        currentFolder = target;
        folderList.clearSelection();
        placeList.clearSelection();
        setChosen(target);
        rebuildBreadcrumbs();
        refreshNavEnabled();
        listSubfolders(target);
    }

    /** 向上一级。已经在盘符根目录时按钮是灰的，这个方法也就到不了这儿。 */
    void goUp() {
        Path parent = currentFolder == null ? null : currentFolder.getParent();
        if (parent != null) {
            navigateTo(parent);
        }
    }

    /** 回退到上一个地方。 */
    void goBack() {
        if (backStack.isEmpty()) {
            return;
        }
        Path target = backStack.pop();
        forwardStack.push(currentFolder);
        navigateTo(target, false);
    }

    /** 前进（回退的反向）。 */
    void goForward() {
        if (forwardStack.isEmpty()) {
            return;
        }
        Path target = forwardStack.pop();
        backStack.push(currentFolder);
        navigateTo(target, false);
    }

    /** 重新读一遍当前文件夹。 */
    void refresh() {
        if (currentFolder != null) {
            listSubfolders(currentFolder);
        }
    }

    /** 确认：把待选的文件夹交出去。 */
    void confirm() {
        result = chosen;
        dispose();
    }

    private void enterSelected() {
        Path selected = folderList.getSelectedValue();
        if (selected != null) {
            navigateTo(selected);
        }
    }

    /**
     * 后台读当前文件夹的子文件夹，读完回界面线程更新。
     *
     * <p>拉一个"代数"计数：结果回来时如果用户已经换到别处，这批直接丢掉。
     */
    private void listSubfolders(Path folder) {
        int generation = ++listingGeneration;
        listedFolder = null;
        updateHover(-1); // 换了地方，上一处残留的悬停行不该还亮着
        showHint("正在读取…");

        listingPool.submit(() -> {
            List<Path> folders;
            boolean readable;
            try (Stream<Path> entries = Files.list(folder)) {
                folders = entries.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
                readable = true;
            } catch (IOException e) {
                folders = List.of();
                readable = false;
            }
            List<Path> listed = folders;
            boolean ok = readable;

            SwingUtilities.invokeLater(() -> {
                if (generation != listingGeneration || !folder.equals(currentFolder)) {
                    return;
                }
                listedFolder = folder;
                folderModel.clear();
                for (Path child : listed) {
                    folderModel.addElement(child);
                }
                if (!ok) {
                    showHint("读不了这个文件夹");
                } else if (folderModel.isEmpty()) {
                    showHint("这个文件夹里没有子文件夹");
                } else {
                    showList();
                }
            });
        });
    }

    // ─────────────────────────── 杂项 ───────────────────────────

    private void setChosen(Path folder) {
        chosen = folder;
        pathField.setText(folder == null ? "" : folder.toString());
    }

    private void showHint(String text) {
        hintLabel.setText(text);
        contentCards.show(contentArea, "hint");
    }

    private void showList() {
        hintLabel.setText(""); // 清掉上一句提示，免得测试和排查时读到旧文案
        contentCards.show(contentArea, "list");
    }

    private void rebuildBreadcrumbs() {
        breadcrumbs.removeAll();
        List<Path> parts = new ArrayList<>();
        for (Path part = currentFolder; part != null; part = part.getParent()) {
            parts.add(part);
        }
        Collections.reverse(parts);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                JLabel chevron = new JLabel("›");
                chevron.setFont(Theme.FONT);
                chevron.setForeground(Theme.TEXT_MUTED);
                breadcrumbs.add(chevron);
            }
            Path part = parts.get(i);
            boolean last = i == parts.size() - 1;
            FlatButton crumb = new FlatButton(displayName(part), false);
            crumb.setForeground(last ? Theme.TEXT : Theme.TEXT_MUTED);
            crumb.setToolTipText(part.toString());
            crumb.addActionListener(e -> navigateTo(part));
            breadcrumbs.add(crumb);
        }
        breadcrumbs.revalidate();
        breadcrumbs.repaint();
        scrollBreadcrumbToTail();
    }

    /** 路径长了以后，看得见的一段应该是"现在在哪"，而不是盘符。 */
    private void scrollBreadcrumbToTail() {
        SwingUtilities.invokeLater(() -> {
            int count = breadcrumbs.getComponentCount();
            if (count > 0) {
                breadcrumbs.scrollRectToVisible(breadcrumbs.getComponent(count - 1).getBounds());
            }
        });
    }

    private void refreshNavEnabled() {
        setUsable(backButton, !backStack.isEmpty());
        setUsable(forwardButton, !forwardStack.isEmpty());
        setUsable(upButton, currentFolder != null && currentFolder.getParent() != null);
    }

    private static void setUsable(JButton button, boolean usable) {
        button.setEnabled(usable);
        // 图标跟着按钮的前景色走；禁用时手动压暗，免得还亮着
        button.setForeground(usable ? Theme.TEXT : Theme.TEXT_DISABLED);
    }

    private void updateHover(int row) {
        if (hoveredRow != row) {
            hoveredRow = row;
            folderList.repaint();
        }
    }

    private void fillPlaces() {
        placeModel.clear();
        List<Place> quick = quickPlaces();
        if (!quick.isEmpty()) {
            placeModel.addElement(Place.header("快速访问"));
            for (Place place : quick) {
                placeModel.addElement(place);
            }
        }
        placeModel.addElement(Place.header("此电脑"));
        for (File root : File.listRoots()) {
            if (root.exists()) { // 没有介质的软驱 / 光驱不必列出来
                placeModel.addElement(Place.of(root.toPath(), displayName(root.toPath()), Icons.drive()));
            }
        }
    }

    private static List<Place> quickPlaces() {
        FileSystemView view = FileSystemView.getFileSystemView();
        Path home = Path.of(System.getProperty("user.home", "."));
        List<Place> places = new ArrayList<>();
        addPlace(places, home, "主文件夹", Icons.home());
        addPlace(places, view.getDefaultDirectory().toPath(), "桌面", Icons.desktop());
        addPlace(places, home.resolve("Documents"), "文档", Icons.file());
        addPlace(places, home.resolve("Downloads"), "下载", Icons.save());
        addPlace(places, home.resolve("Pictures"), "图片", Icons.pictures());
        return places;
    }

    private static void addPlace(List<Place> places, Path path, String label, Icon icon) {
        if (Files.isDirectory(path)) {
            places.add(Place.of(path.toAbsolutePath().normalize(), label, icon));
        }
    }

    /** 起点：优先主文件夹；主文件夹不在了就退到工作目录，再不行挑第一个盘。 */
    private static Path defaultStart() {
        Path home = Path.of(System.getProperty("user.home", "."));
        if (Files.isDirectory(home)) {
            return home;
        }
        Path working = Path.of(System.getProperty("user.dir", "."));
        if (Files.isDirectory(working)) {
            return working;
        }
        File[] roots = File.listRoots();
        return roots.length > 0 ? roots[0].toPath() : home;
    }

    /** 显示名：普通文件夹用文件夹名；盘符根目录（D:\）交给系统给出"本地磁盘 (D:)"这样的名字。 */
    private static String displayName(Path path) {
        Path name = path.getFileName();
        if (name != null) {
            return name.toString();
        }
        String shown = FileSystemView.getFileSystemView().getSystemDisplayName(path.toFile());
        return shown == null || shown.isBlank() ? path.toString() : shown;
    }

    /** 落在某一行上才算；点在行间空白处返回 -1。 */
    private static int indexAt(JList<?> list, Point point) {
        int index = list.locationToIndex(point);
        if (index < 0) {
            return -1;
        }
        return list.getCellBounds(index, index).contains(point) ? index : -1;
    }

    private static FlatButton navButton(Icon icon, String tooltip) {
        FlatButton button = new FlatButton(null, true);
        button.setIcon(icon);
        button.setToolTipText(tooltip);
        button.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        return button;
    }

    // ─────────────────────────── 测试入口 ───────────────────────────

    /** 只留给测试用：模拟单击、模拟双击之后的导航。 */
    JList<Path> folderListForTest() {
        return folderList;
    }

    /** 只留给测试用：这个文件夹的内容是否已经列出来并画上去了。 */
    boolean listingDoneForTest(Path folder) {
        return folder.equals(listedFolder);
    }

    /** 只留给测试用：提示卡上写着什么；列表卡显示时是空串。 */
    String hintForTest() {
        return hintLabel.getText();
    }

    /** 现在在哪个文件夹。 */
    Path currentFolder() {
        return currentFolder;
    }

    /** 确定将会交出去的文件夹。 */
    Path chosen() {
        return chosen;
    }

    /** 点了"选择此文件夹"之后的结果；还没点时为 null。 */
    Path result() {
        return result;
    }

    // ─────────────────────────── 渲染 ───────────────────────────

    /** 左侧栏的一行：分区标题只是小字，位置是图标加文字。 */
    private final class PlaceRenderer extends JLabel implements ListCellRenderer<Place> {

        @Override
        public Component getListCellRendererComponent(JList<? extends Place> list, Place place,
                                                      int index, boolean selected, boolean focused) {
            setOpaque(true);
            if (place.header()) {
                setText(place.label());
                setFont(Theme.FONT_SMALL);
                setForeground(Theme.TEXT_MUTED);
                setIcon(null);
                setBackground(Theme.PANEL);
                setBorder(BorderFactory.createEmptyBorder(8, 8, 2, 6));
            } else {
                setText(place.label());
                setFont(Theme.FONT);
                setForeground(Theme.TEXT);
                setIcon(place.icon());
                setIconTextGap(8);
                setBackground(selected ? Theme.HIGHLIGHT : Theme.PANEL);
                setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 6));
            }
            return this;
        }
    }

    /** 文件夹列表的一行：图标加名字，悬停和选中各有一条底色。 */
    private final class FolderRenderer extends JLabel implements ListCellRenderer<Path> {

        @Override
        public Component getListCellRendererComponent(JList<? extends Path> list, Path folder,
                                                      int index, boolean selected, boolean focused) {
            setOpaque(true);
            setText(displayName(folder));
            setToolTipText(folder.toString());
            setIcon(Icons.folder());
            setIconTextGap(8);
            setFont(Theme.FONT);
            setForeground(Theme.TEXT);
            setBackground(selected ? Theme.HIGHLIGHT
                    : index == hoveredRow ? Theme.HOVER : Theme.CANVAS);
            setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            return this;
        }
    }
}

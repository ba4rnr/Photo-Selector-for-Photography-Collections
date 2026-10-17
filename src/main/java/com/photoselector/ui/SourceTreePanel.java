package com.photoselector.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import com.photoselector.ui.theme.Icons;
import com.photoselector.ui.theme.Theme;

/**
 * 左栏的素材目录树。
 *
 * <p>它管三件事：
 * <ul>
 *   <li><b>懒加载</b>。展开某个节点时才去读它的子项。
 *       一个拍摄文件夹里有几百个文件，一上来全扫一遍纯属浪费。</li>
 *   <li><b>勾选</b>。文件夹前面的方框控制它是否纳入范围，默认全部纳入；
 *       取消勾选就是设计里说的"剔除"。</li>
 *   <li><b>点选</b>。单击一个文件夹，外部（主面板）就把它的照片加载到底部素材条。</li>
 * </ul>
 *
 * <p>勾选框占的是树节点的"图标位"，所以它天然就是点击热区：
 * 落在那一小块里是切换勾选，落在别处是选中节点。这样不必自己去算文字宽度。
 */
@SuppressWarnings("serial")
final class SourceTreePanel extends JPanel {

    /** 勾选框那一小块占的宽度，落在这里的点击算切换。 */
    static final int CHECKBOX_WIDTH = 20;

    /**
     * 树节点的内容。
     *
     * @param path   对应的文件或文件夹；占位节点为 {@code null}
     * @param folder 是不是文件夹
     */
    record Node(Path path, boolean folder) {
        static Node placeholder() {
            return new Node(null, false);
        }

        boolean isPlaceholder() {
            return path == null;
        }
    }

    /** 目录树刷新前的快照；只递归用户当前展开着的目录。 */
    private record TreeSnapshot(Path path, boolean folder, boolean loaded,
                                List<TreeSnapshot> children) {
    }

    private final DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode(Node.placeholder()));
    private final JTree tree = new JTree(model);

    /** 被剔除的文件夹。空集合表示全部纳入。 */
    private final Set<Path> excluded = new HashSet<>();
    private final ExecutorService listingPool = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "tree-lister");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<DefaultMutableTreeNode> loadingNodes = new HashSet<>();
    private long generation;

    private Consumer<Path> onFolderChosen = folder -> { };

    /** 勾选状态变了（有文件夹被纳入或剔除）时通知外部。 */
    private Runnable onInclusionChanged = () -> { };

    SourceTreePanel() {
        super(new BorderLayout());
        setBackground(Theme.PANEL);
        setBorder(BorderFactory.createLineBorder(Theme.BORDER));

        tree.setBackground(Theme.PANEL);
        tree.setForeground(Theme.TEXT);
        tree.setRowHeight(22);
        tree.setShowsRootHandles(true);
        tree.setRootVisible(true);
        tree.setBorder(null);
        // 不画连接节点的那些线。左边的图形只保留两种：
        // 文件夹是三角加勾选框，文件是一个文件图标——不再有别的装饰。
        tree.putClientProperty("JTree.lineStyle", "None");
        tree.setCellRenderer(new Renderer());

        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                loadChildren((DefaultMutableTreeNode) event.getPath().getLastPathComponent());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                // 收起不需要做任何事：子节点已经读好了，留着下次展开直接用
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    return; // 右键留给以后的上下文菜单用
                }
                handleClick(e.getPoint());
            }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.PANEL);
        Theme.themeScrollBar(scroll.getVerticalScrollBar());
        // 面板被拖窄、树的文字放不下时，底下会冒出一条水平滚动条；
        // 它也得是深色基础外观，否则就是一条系统样式的亮色带，在深色界面里很跳
        Theme.themeScrollBar(scroll.getHorizontalScrollBar());
        add(scroll, BorderLayout.CENTER);
    }

    /** 换一个根文件夹。之前勾掉的和展开的状态都作废。 */
    void setRoot(Path folder) {
        generation++;
        loadingNodes.clear();
        excluded.clear();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Node(folder, true));
        root.add(new DefaultMutableTreeNode(Node.placeholder()));
        model.setRoot(root);
        model.reload();

        // 注意：JTree 的根节点默认就是展开状态，expandPath 在这里是个空操作，
        // 所以第一层要主动读出来；再往下的层次才交给展开事件去懒加载。
        loadChildren(root);
        tree.expandPath(new TreePath(root.getPath())); // 万一某些外观下根是收起的，这里补一下
    }

    /** 点中一个文件夹时通知外部。 */
    void setOnFolderChosen(Consumer<Path> listener) {
        this.onFolderChosen = listener == null ? folder -> { } : listener;
    }

    /** 勾选状态变化时通知外部。 */
    void setOnInclusionChanged(Runnable listener) {
        this.onInclusionChanged = listener == null ? () -> { } : listener;
    }

    /** 这个文件夹是否纳入范围（勾上的就是纳入）。 */
    boolean isIncluded(Path folder) {
        return !excluded.contains(folder);
    }

    /** 当前被剔除的文件夹。 */
    Set<Path> excludedFolders() {
        return Set.copyOf(excluded);
    }

    /**
     * 直接指定哪些文件夹被剔除（从工作区配置恢复时用）。
     *
     * <p>和 {@link #toggleIncluded} 的区别：这个方法不通知外部——
     * 它就是外部设置进来的，再回手通知一遍会转成死循环。
     */
    void setExcluded(Set<Path> folders) {
        excluded.clear();
        excluded.addAll(folders);
        tree.repaint();
    }

    /**
     * 外部文件变化后重读目录树，尽量保留勾选、展开和选中状态。
     *
     * <p>只递归当前展开的目录；收起目录重新变成占位节点，下次展开时再读，
     * 这样既不会丢掉用户展开的位置，也不会为整棵树做不必要的文件系统扫描。
     */
    void refreshFromDisk() {
        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) model.getRoot();
        Node rootPayload = nodeOf(rootNode);
        if (rootPayload == null || !rootPayload.folder()) {
            return;
        }

        Path rootPath = rootPayload.path();
        Set<Path> expandedPaths = new HashSet<>();
        collectExpandedPaths(rootNode, expandedPaths);
        expandedPaths.add(rootPath);
        Path selected = selectedPath();
        Set<Path> keepExcluded = Set.copyOf(excluded);
        long myGeneration = ++generation;
        loadingNodes.clear();

        try {
            listingPool.submit(() -> {
                TreeSnapshot snapshot = snapshot(rootPath, true, expandedPaths);
                SwingUtilities.invokeLater(() -> {
                    if (myGeneration != generation || listingPool.isShutdown()) {
                        return;
                    }
                    excluded.clear();
                    excluded.addAll(keepExcluded);
                    DefaultMutableTreeNode rebuilt = buildNode(snapshot);
                    model.setRoot(rebuilt);
                    model.reload();

                    List<Path> orderedExpanded = new ArrayList<>(expandedPaths);
                    orderedExpanded.sort(Comparator.comparingInt(Path::getNameCount));
                    for (Path path : orderedExpanded) {
                        DefaultMutableTreeNode node = findNode(rebuilt, path);
                        if (node != null && !tree.isExpanded(new TreePath(node.getPath()))) {
                            tree.expandPath(new TreePath(node.getPath()));
                        }
                    }
                    if (selected != null) {
                        DefaultMutableTreeNode node = findNode(rebuilt, selected);
                        if (node != null) {
                            tree.setSelectionPath(new TreePath(node.getPath()));
                        }
                    }
                    tree.repaint();
                });
            });
        } catch (RejectedExecutionException ignored) {
            // 对话框/主窗口收尾期间，树线程池已经关闭
        }
    }

    /** 只留给测试用。 */
    JTree tree() {
        return tree;
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    /**
     * 一次点击落在树上时的处理。
     *
     * <p>点在勾选框上 → 切换是否纳入；点在文件夹的其他位置 → 通知外部加载它。
     */
    private void handleClick(Point point) {
        TreePath path = tree.getPathForLocation(point.x, point.y);
        if (path == null) {
            return;
        }
        Node node = nodeOf(path.getLastPathComponent());
        if (node == null || node.isPlaceholder() || !node.folder()) {
            return;
        }

        Rectangle row = tree.getPathBounds(path);
        if (row != null && hitsCheckbox(row, point.x)) {
            toggleIncluded(node.path());
        } else {
            chooseFolder(node.path());
        }
    }

    /** 选定了某个文件夹（点在名字上，而不是勾选框上）。 */
    void chooseFolder(Path folder) {
        onFolderChosen.accept(folder);
    }

    /** 点击位置是否落在勾选框那一小块里。 */
    static boolean hitsCheckbox(Rectangle rowBounds, int clickX) {
        return clickX < rowBounds.x + CHECKBOX_WIDTH;
    }

    /** 切换某个文件夹"是否纳入范围"。点击勾选框时由 {@link #handleClick} 调用。 */
    void toggleIncluded(Path folder) {
        if (!excluded.remove(folder)) {
            excluded.add(folder);
        }
        tree.repaint();
        onInclusionChanged.run();
    }

    /** 第一次展开时，把占位节点换成真正的子项。 */
    private void loadChildren(DefaultMutableTreeNode node) {
        Node payload = nodeOf(node);
        if (payload == null || !payload.folder() || !hasOnlyPlaceholder(node)) {
            return;
        }
        if (!loadingNodes.add(node)) {
            return; // 同一个节点已经有人在读
        }
        long myGeneration = generation;
        try {
            listingPool.submit(() -> {
                List<Path> children = listChildren(payload.path());
                SwingUtilities.invokeLater(() -> {
                    loadingNodes.remove(node);
                    if (myGeneration != generation || !hasOnlyPlaceholder(node)
                            || listingPool.isShutdown()) {
                        return;
                    }
                    node.removeAllChildren();
                    for (Path child : children) {
                        boolean isFolder = Files.isDirectory(child);
                        DefaultMutableTreeNode childNode =
                                new DefaultMutableTreeNode(new Node(child, isFolder));
                        if (isFolder) {
                            // 给文件夹塞个占位子节点，它才长得像"可以展开"，
                            // 真正的子项等用户展开时再去读
                            childNode.add(new DefaultMutableTreeNode(Node.placeholder()));
                        }
                        node.add(childNode);
                    }
                    model.nodeStructureChanged(node);
                });
            });
        } catch (RejectedExecutionException ignored) {
            loadingNodes.remove(node);
            // 收尾期间不再更新已销毁的树
        }
    }

    private void collectExpandedPaths(DefaultMutableTreeNode node, Set<Path> expandedPaths) {
        Node payload = nodeOf(node);
        if (payload == null || !payload.folder()) {
            return;
        }
        TreePath path = new TreePath(node.getPath());
        if (!tree.isExpanded(path)) {
            return;
        }
        expandedPaths.add(payload.path());
        for (int i = 0; i < node.getChildCount(); i++) {
            collectExpandedPaths((DefaultMutableTreeNode) node.getChildAt(i), expandedPaths);
        }
    }

    private Path selectedPath() {
        TreePath selection = tree.getSelectionPath();
        if (selection == null) {
            return null;
        }
        Node payload = nodeOf(selection.getLastPathComponent());
        return payload == null || payload.isPlaceholder() ? null : payload.path();
    }

    private static TreeSnapshot snapshot(Path path, boolean folder, Set<Path> expandedPaths) {
        boolean loaded = folder && expandedPaths.contains(path);
        List<TreeSnapshot> children = new ArrayList<>();
        if (loaded) {
            for (Path child : listChildren(path)) {
                boolean childFolder = Files.isDirectory(child);
                children.add(snapshot(child, childFolder, expandedPaths));
            }
        }
        return new TreeSnapshot(path, folder, loaded || !folder, List.copyOf(children));
    }

    private static DefaultMutableTreeNode buildNode(TreeSnapshot snapshot) {
        DefaultMutableTreeNode node =
                new DefaultMutableTreeNode(new Node(snapshot.path(), snapshot.folder()));
        for (TreeSnapshot child : snapshot.children()) {
            node.add(buildNode(child));
        }
        if (snapshot.folder() && !snapshot.loaded()) {
            node.add(new DefaultMutableTreeNode(Node.placeholder()));
        }
        return node;
    }

    private static DefaultMutableTreeNode findNode(DefaultMutableTreeNode node, Path path) {
        Node payload = nodeOf(node);
        if (payload != null && !payload.isPlaceholder()
                && payload.path().toAbsolutePath().normalize()
                        .equals(path.toAbsolutePath().normalize())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode found =
                    findNode((DefaultMutableTreeNode) node.getChildAt(i), path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    void shutdown() {
        generation++;
        listingPool.shutdownNow();
    }

    private static boolean hasOnlyPlaceholder(DefaultMutableTreeNode node) {
        if (node.getChildCount() != 1) {
            return false;
        }
        Node child = nodeOf(node.getChildAt(0));
        return child != null && child.isPlaceholder();
    }

    /** 子文件夹排在前面，然后是照片文件，各自按名字排序。 */
    private static List<Path> listChildren(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            List<Path> all = new ArrayList<>(entries.toList());
            // 先分一次组，再各自排序。
            // 如果直接拿 Files.isDirectory 当排序键，排序过程会调用它 O(n log n) 次，
            // 每个文件都变成一次文件系统查询——几百个文件就是几千次。
            List<Path> folders = new ArrayList<>();
            List<Path> files = new ArrayList<>();
            for (Path path : all) {
                (Files.isDirectory(path) ? folders : files).add(path);
            }
            Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString());
            folders.sort(byName);
            files.sort(byName);
            folders.addAll(files);
            return folders;
        } catch (IOException | RuntimeException e) {
            return List.of(); // 读不了就当空的，界面上表现为"这个文件夹没有东西"
        }
    }

    /** 把一个树节点还原成它的内容。测试也用它来检查树的形状。 */
    static Node nodeOf(Object value) {
        if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Node payload) {
            return payload;
        }
        return null;
    }

    /** 树节点长什么样：左侧是勾选框（或文件图标），右侧是名字。 */
    @SuppressWarnings("serial")
    private final class Renderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree ignored, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(ignored, value, selected, expanded, leaf, row, false);

            Node node = nodeOf(value);
            setFont(Theme.FONT);
            setOpaque(true);
            setBackground(selected ? Theme.HIGHLIGHT : Theme.PANEL);
            setForeground(selected ? Theme.TEXT : Theme.TEXT_MUTED);

            if (node == null || node.isPlaceholder()) {
                setText(" ");
                setIcon(null);
            } else if (node.folder()) {
                Path name = node.path().getFileName();
                setText(name == null ? node.path().toString() : name.toString());
                setIcon(Icons.checkBox(isIncluded(node.path())));
            } else {
                setText(node.path().getFileName().toString());
                setIcon(Icons.file());
            }
            return this;
        }
    }
}

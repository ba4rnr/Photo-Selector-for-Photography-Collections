package com.photoselector.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import com.photoselector.io.PhotoFiles;
import com.photoselector.io.Saver;
import com.photoselector.io.ThumbnailService;
import com.photoselector.core.PhotoGroup;
import com.photoselector.core.NumberingRule;
import com.photoselector.core.Session;
import com.photoselector.core.Workspace;
import com.photoselector.ui.theme.Theme;

/**
 * 主界面内容：工具栏 + 三栏布局 + 底部照片条。
 *
 * <p>两种视图状态（见《软件设计》第 1 节）共用左栏，
 * 中间、右侧、底部三块各自用 {@link CardLayout} 切换。
 * 用卡片切换而不是重建组件，好处是来回切模式时不会丢状态，
 * 分隔条的拖动位置也能保持住。
 */
@SuppressWarnings("serial") // 界面组件不参与序列化
public final class MainPanel extends JPanel {

    /** 素材条里缩略图的高度。 */
    private static final int FILMSTRIP_THUMBNAIL_HEIGHT = 120;

    /** 展开排序模式中间那块用的缩略图宽度：比右栏大一截，好做精细筛选。 */
    private static final int ARRANGE_THUMBNAIL_WIDTH = 240;

    /** 底栏（照片条）的默认高度：按组头、缩略图、文件名加滚动条算出来。 */
    private static final int BOTTOM_HEIGHT = FilmstripPanel.preferredHeight(FILMSTRIP_THUMBNAIL_HEIGHT);

    private final ToolBarPanel toolBar = new ToolBarPanel();

    private final ThumbnailLoader thumbnailLoader;
    private final FilmstripPanel filmstrip;
    private final TargetPanel targetPanel;
    private final TargetPanel arrangePanel;
    private final ViewerPanel viewer;
    private final SourceTreePanel sourceTree = new SourceTreePanel();

    /**
     * 扫文件夹用的线程池。
     *
     * <p>列目录也是文件系统操作，几千个文件就能卡住界面几百毫秒，
     * 所以和取图一样放到后台去。
     */
    private final ExecutorService scanPool = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "folder-scanner");
        thread.setDaemon(true);
        return thread;
    });

    /** 保存用的线程池。和目录扫描分开，免得保存几十张的时候点目录树要排队。 */
    private final ExecutorService savePool = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "saver");
        thread.setDaemon(true);
        return thread;
    });

    /** 某个文件夹被打开时通知外部（参数是文件夹和张数，张数为 -1 表示读失败）。 */
    private BiConsumer<Path, Integer> onFolderOpened = (folder, count) -> { };

    /** 扫描出来的全部组；剔除哪些由目录树的勾选状态决定。 */
    private List<PhotoGroup> scannedGroups = List.of();

    /** 当前工作区。还没选过时为 null。 */
    private Workspace workspace;

    /** 工作区变化时的回调（用来写回配置文件）。 */
    private Consumer<Workspace> onWorkspaceChanged = updated -> { };

    /** 保存结束后的回调（外部负责把结果告诉用户）。 */
    private Consumer<Saver.Result> onSaveFinished = result -> { };

    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerArea = new JPanel(centerCards);

    private final CardLayout rightCards = new CardLayout();
    private final JPanel rightArea = new JPanel(rightCards);

    private final CardLayout bottomCards = new CardLayout();
    private final JPanel bottomArea = new JPanel(bottomCards);

    private final JSplitPane centerRight;
    private final JSplitPane mainSplit;
    private final JSplitPane outerSplit;

    private ViewMode mode = ViewMode.BROWSE;
    private boolean bottomPlaced;

    public MainPanel() {
        this(ThumbnailService.defaultCacheDir());
    }

    /** 供测试指定缓存目录，免得测试往用户真实的缓存目录里写东西。 */
    MainPanel(Path thumbnailCacheDir) {
        super(new BorderLayout());
        setBackground(Theme.PANEL);

        ThumbnailService thumbnailService = new ThumbnailService(thumbnailCacheDir);
        thumbnailLoader = new ThumbnailLoader(thumbnailService);
        filmstrip = new FilmstripPanel(thumbnailLoader);
        // 目标文件夹面板单独用一只取图器：素材条换文件夹时会重启自己那一轮取图，
        // 共用一只的话会把这边正在路上的请求一起作废。缩略图缓存还是共用同一份。
        // 两块面板显示的是同一批照片，共用一只取图器：缓存命中率高，也省一个线程池。
        // （只有素材条会调 restart()，所以共用是安全的。）
        ThumbnailLoader targetLoader = new ThumbnailLoader(thumbnailService);
        targetPanel = new TargetPanel(targetLoader);
        arrangePanel = new TargetPanel(targetLoader);
        arrangePanel.setThumbnailWidth(ARRANGE_THUMBNAIL_WIDTH);
        targetPanel.setOnToggleExpanded(this::toggleMode);
        arrangePanel.setOnToggleExpanded(this::toggleMode);
        viewer = new ViewerPanel(thumbnailLoader);

        buildAreas();

        // 三道分隔条：左栏 | 中右，中栏 | 右栏，主体 | 底栏，全都可以拖着调整
        centerRight = Splitters.horizontal(centerArea, rightArea, 1.0);
        mainSplit = Splitters.horizontal(leftColumn(), centerRight, 0.0);
        outerSplit = Splitters.vertical(mainSplit, bottomArea, 1.0);

        toolBar.modeButton().addActionListener(e -> toggleMode());
        // 目录树里点一个文件夹，素材条滚到那一组；勾选变化则重新筛选组
        sourceTree.setOnFolderChosen(filmstrip::scrollToGroup);
        sourceTree.setOnInclusionChanged(this::inclusionChanged);
        // 素材条里点中一张，中间的大图就换成它
        filmstrip.setOnCurrentPhotoChanged(viewer::showPhoto);
        bindKeys();

        add(toolBar, BorderLayout.NORTH);
        add(outerSplit, BorderLayout.CENTER);

        setMode(ViewMode.BROWSE);
    }

    /**
     * 第一次布局时把底栏上方那条分隔条摆到位。
     *
     * <p>JSplitPane 的默认规则是"按上半部分的首选高度"决定横分隔条的位置，
     * 照那样底栏会抢走大半高度。我们要的是"底栏固定高度、其余留给上方"，
     * 所以显式摆一次；摆完就交给用户拖，不再干预。
     */
    @Override
    public void doLayout() {
        super.doLayout();
        if (!bottomPlaced && outerSplit.getHeight() > 0) {
            bottomPlaced = true;
            outerSplit.setDividerLocation(Math.max(0, outerSplit.getHeight() - BOTTOM_HEIGHT));
        }
    }

    /** 切换视图模式：三块区域同时换卡片，工具栏按钮文字同步。 */
    public void setMode(ViewMode next) {
        if (mode == next) {
            return;
        }
        mode = next;
        centerCards.show(centerArea, next.name());
        rightCards.show(rightArea, next.name());
        bottomCards.show(bottomArea, next.name());
        toolBar.setMode(next);
        // 按钮上的字跟着状态变；刚露出来的那一块也要去取自己的缩略图
        boolean expanded = next == ViewMode.ARRANGE;
        targetPanel.setExpanded(expanded);
        arrangePanel.setExpanded(expanded);
        arrangePanel.refresh();
    }

    public ViewMode mode() {
        return mode;
    }

    private void buildAreas() {
        centerArea.add(viewer, ViewMode.BROWSE.name());
        centerArea.add(arrangePanel.wrapInScrollPane(), ViewMode.ARRANGE.name());

        rightArea.add(targetPanel.wrapInScrollPane(), ViewMode.BROWSE.name());
        rightArea.add(placeholder("垃圾桶", 360, 400, Theme.PANEL), ViewMode.ARRANGE.name());

        JScrollPane filmstripScroll = filmstrip.wrapInScrollPane();
        filmstripScroll.setPreferredSize(new Dimension(100, BOTTOM_HEIGHT));
        bottomArea.add(filmstripScroll, ViewMode.BROWSE.name());
        bottomArea.add(placeholder("目标文件夹的照片", 100, 170, Theme.PANEL), ViewMode.ARRANGE.name());
    }

    /**
     * 打开一个素材文件夹，把里面的照片放进底部素材条。
     *
     * @return 这个文件夹里有多少张能读的照片
     */
    /**
     * 选一个根文件夹：左栏目录树立刻换成它，整个档案夹在<b>后台</b>扫成若干组交给素材条。
     *
     * <p>列目录是文件系统操作，一个档案夹可能有几十个子目录、上千张照片，
     * 在界面线程上扫会卡住；所以这里立刻返回，结果通过 {@link #setOnFolderOpened} 通知。
     */
    private void reloadGroups(Path folder) {
        scanPool.submit(() -> {
            List<PhotoGroup> groups;
            try {
                groups = PhotoFiles.groupsIn(folder);
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> onFolderOpened.accept(folder, -1));
                return;
            }

            SwingUtilities.invokeLater(() -> {
                scannedGroups = groups;
                applyGroups();
                onFolderOpened.accept(folder, filmstrip.photoCount());
            });
        });
    }

    /** 勾选变化：重新筛组，并把新的剔除项记进工作区。 */
    private void inclusionChanged() {
        applyGroups();
        if (workspace != null) {
            workspace = workspace.withExcluded(sourceTree.excludedFolders());
            onWorkspaceChanged.accept(workspace);
        }
    }

    /** 按目录树的勾选状态筛一遍：被剔除的组不进素材条。 */
    private void applyGroups() {
        List<PhotoGroup> included = new ArrayList<>();
        for (PhotoGroup group : scannedGroups) {
            if (sourceTree.isIncluded(group.folder())) {
                included.add(group);
            }
        }
        filmstrip.setGroups(included, FILMSTRIP_THUMBNAIL_HEIGHT);
    }

    /** 设置"某个文件夹被打开"的回调。 */
    public void setOnFolderOpened(BiConsumer<Path, Integer> listener) {
        this.onFolderOpened = listener == null ? (folder, count) -> { } : listener;
    }

    /** 设置右栏目标文件夹里缩略图的大小（「查看 → 缩略图大小」用）。 */
    public void setTargetThumbnailWidth(int width) {
        targetPanel.setThumbnailWidth(width);
    }

    /** 大图：适应窗口（「查看」菜单与工具栏上的"适应窗口"按钮用）。 */
    public void fitViewerToWindow() {
        viewer.fitToWindow();
    }

    /** 大图：1:1（「查看」菜单用）。 */
    public void viewerActualSize() {
        viewer.actualSize();
    }

    /** 大图的当前比例（百分比），菜单里显示用。 */
    public int viewerZoomPercent() {
        return viewer.zoomPercent();
    }

    /** 大图现在是不是"适应窗口"（菜单里的勾选状态跟着它走）。 */
    public boolean viewerIsFitToWindow() {
        return viewer.isFitToWindow();
    }

    /** 大图比例变化时通知外部（菜单里的勾选状态跟着更新）。 */
    public void setOnViewerZoomChanged(Runnable listener) {
        viewer.setOnZoomChanged(listener);
    }

    /** 工作区变化时通知外部（外部负责写回配置文件）。 */
    public void setOnWorkspaceChanged(Consumer<Workspace> listener) {
        this.onWorkspaceChanged = listener == null ? updated -> { } : listener;
    }

    /** 当前工作区；还没选过时为 null。 */
    public Workspace workspace() {
        return workspace;
    }

    /**
     * 应用一个工作区：左栏按它的剔除项勾好，素材条按它的根目录重新扫一遍。
     *
     * <p>顺序要紧——{@code setRoot} 会把剔除记录清空（换了根目录，旧记录就没意义了），
     * 所以必须先换根、再把剔除项设回去。
     */
    public void applyWorkspace(Workspace newWorkspace) {
        this.workspace = newWorkspace;
        sourceTree.setRoot(newWorkspace.sourceRoot());
        sourceTree.setExcluded(newWorkspace.excludedFolders());
        openTargetFolder(newWorkspace.targetFolder());
        reloadGroups(newWorkspace.sourceRoot());
        onWorkspaceChanged.accept(newWorkspace);
    }

    /**
     * 打开目标文件夹：把它现有的照片读出来当"底色"铺进中间商。
     *
     * <p>读目录同样是文件系统操作，所以丢给后台线程池；读完再回到界面线程建中间商。
     */
    private void openTargetFolder(Path target) {
        if (target == null) {
            targetPanel.setSession(null);
            arrangePanel.setSession(null);
            return;
        }
        scanPool.submit(() -> {
            List<Path> existing = List.of();
            try {
                existing = PhotoFiles.imagesIn(target);
            } catch (IOException ignored) {
                // 读不了就当空的：中间商照常建起来，挑进去的照片不受影响
            }
            List<Path> files = existing;
            SwingUtilities.invokeLater(() -> {
                Session session = new Session(target, NumberingRule.DEFAULT);
                session.seedExisting(files);
                targetPanel.setSession(session);
                arrangePanel.setSession(session);
            });
        });
    }

    /** 空格：把素材条里点中的那张照片加入目标序列。 */
    private void addCurrentPhotoToTarget() {
        Path photo = filmstrip.currentPhoto();
        Session session = targetPanel.session();
        if (photo == null || session == null) {
            return;
        }
        if (session.add(photo)) {
            targetPanel.refresh();
            arrangePanel.refresh();
        }
    }

    /** Ctrl+Z：撤销上一步（挑入、挪位、丢垃圾桶都算）。 */
    private void undo() {
        Session session = targetPanel.session();
        if (session != null && session.undo()) {
            targetPanel.refresh();
            arrangePanel.refresh();
        }
    }

    /**
     * 保存：把中间商里的改动一次性落到磁盘。
     *
     * <p>文件操作可能很慢（复制几十张就是几十次磁盘写入），所以丢给后台线程池，
     * 做完回到界面线程：先按磁盘上的真实结果重建中间商（顺序不变、撤销记录清空），
     * 再把结果交给外部去告诉用户。
     */
    public void save() {
        Session session = targetPanel.session();
        if (session == null) {
            onSaveFinished.accept(new Saver.Result(0, 0, 0, List.of(),
                    List.of(), List.of("还没有选目标文件夹，没有可以保存的改动。")));
            return;
        }
        if (session.isEmpty()) {
            onSaveFinished.accept(new Saver.Result(0, 0, 0, List.of(),
                    List.of(), List.of("目标文件夹里什么都没有，没有可以保存的改动。")));
            return;
        }

        // 在界面线程上先拍一份快照：保存跑在后台，而中间商是活的，
        // 不拍的话你在保存期间再挑一张，后台就会读到一半的数据
        Saver.Plan plan = Saver.Plan.from(session);
        savePool.submit(() -> {
            Saver.Result result = new Saver().save(plan);
            SwingUtilities.invokeLater(() -> {
                // 保存之后磁盘就是真相：按真实结果重建中间商，顺序保留、历史清空
                session.seedExisting(result.finalOrder());
                // 没复制成功的那几条放回末尾，别让用户再去素材条里挑一遍
                result.failedSources().forEach(session::add);
                targetPanel.refresh();
                arrangePanel.refresh();
                onSaveFinished.accept(result);
            });
        });
    }

    /** 保存结束后的回调（外部负责把结果告诉用户）。 */
    public void setOnSaveFinished(Consumer<Saver.Result> listener) {
        this.onSaveFinished = listener == null ? result -> { } : listener;
    }

    /**
     * 键盘快捷键。
     *
     * <p>挂在 {@code WHEN_IN_FOCUSED_WINDOW} 上，所以不管焦点在素材条还是目录树上都管用。
     * 工具栏上那几个按钮的接线等界面那一侧定下来再接。
     */
    private void bindKeys() {
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "addToTarget", this::addCurrentPhotoToTarget);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo", this::undo);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "previousPhoto", () -> filmstrip.moveCurrent(-1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "nextPhoto", () -> filmstrip.moveCurrent(1));
    }

    private void bind(KeyStroke key, String actionName, Runnable action) {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, actionName);
        getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }


    /** 素材条上已经拿到缩略图的张数。给测试和排查用。 */
    int loadedThumbnails() {
        return filmstrip.loadedCount();
    }

    /** 素材条上现在有几组。给测试和排查用。 */
    int filmstripGroupCount() {
        return filmstrip.groupCount();
    }

    /** 素材条，给测试用。 */
    FilmstripPanel filmstripForTest() {
        return filmstrip;
    }

    /** 工具栏，给主窗口挂"重新选择工作区"和"保存"两个按钮用。 */
    ToolBarPanel toolBar() {
        return toolBar;
    }

    /** 目标文件夹面板，给测试用。 */
    TargetPanel targetPanelForTest() {
        return targetPanel;
    }

    /** 展开排序模式中间那块面板，给测试用。 */
    TargetPanel arrangePanelForTest() {
        return arrangePanel;
    }

    /** 目录树，给测试用。 */
    SourceTreePanel sourceTreeForTest() {
        return sourceTree;
    }

    /** 关窗口时收掉后台取图的线程池。 */
    public void shutdown() {
        thumbnailLoader.shutdown();
        scanPool.shutdownNow();
        savePool.shutdownNow();
    }

    private JComponent leftColumn() {
        sourceTree.setPreferredSize(new Dimension(220, 400));
        return sourceTree;
    }

    /**
     * 造一个占位面板。
     *
     * <p>这里的宽高不只是"占位"——JSplitPane 在没被显式指定分隔条位置时，
     * 会按左侧组件的首选宽度来摆分隔条，所以这些尺寸决定了初始的三栏比例。
     */
    private static PlaceholderPanel placeholder(String title, int width, int height, Color background) {
        PlaceholderPanel panel = new PlaceholderPanel(title, background);
        panel.setPreferredSize(new Dimension(width, height));
        return panel;
    }

    private void toggleMode() {
        setMode(mode == ViewMode.BROWSE ? ViewMode.ARRANGE : ViewMode.BROWSE);
    }
}

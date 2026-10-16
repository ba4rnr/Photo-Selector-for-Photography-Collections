package com.photoselector.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

import com.photoselector.io.PhotoFiles;
import com.photoselector.io.ThumbnailService;
import com.photoselector.core.PhotoGroup;
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

    /** 底栏（照片条）的默认高度：按组头、缩略图、文件名加滚动条算出来。 */
    private static final int BOTTOM_HEIGHT = FilmstripPanel.preferredHeight(FILMSTRIP_THUMBNAIL_HEIGHT);

    private final ToolBarPanel toolBar = new ToolBarPanel();

    private final ThumbnailLoader thumbnailLoader;
    private final FilmstripPanel filmstrip;
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

    /** 某个文件夹被打开时通知外部（参数是文件夹和张数，张数为 -1 表示读失败）。 */
    private BiConsumer<Path, Integer> onFolderOpened = (folder, count) -> { };

    /** 扫描出来的全部组；剔除哪些由目录树的勾选状态决定。 */
    private List<PhotoGroup> scannedGroups = List.of();

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

        thumbnailLoader = new ThumbnailLoader(new ThumbnailService(thumbnailCacheDir));
        filmstrip = new FilmstripPanel(thumbnailLoader);

        buildAreas();

        // 三道分隔条：左栏 | 中右，中栏 | 右栏，主体 | 底栏，全都可以拖着调整
        centerRight = Splitters.horizontal(centerArea, rightArea, 1.0);
        mainSplit = Splitters.horizontal(leftColumn(), centerRight, 0.0);
        outerSplit = Splitters.vertical(mainSplit, bottomArea, 1.0);

        toolBar.modeButton().addActionListener(e -> toggleMode());
        // 目录树里点一个文件夹，素材条滚到那一组；勾选变化则重新筛选组
        sourceTree.setOnFolderChosen(filmstrip::scrollToGroup);
        sourceTree.setOnInclusionChanged(this::applyGroups);

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
    }

    public ViewMode mode() {
        return mode;
    }

    private void buildAreas() {
        centerArea.add(placeholder("素材大图", 700, 400, Theme.CANVAS), ViewMode.BROWSE.name());
        centerArea.add(placeholder("目标文件夹的缩略图（可拖动排序）", 700, 400, Theme.CANVAS), ViewMode.ARRANGE.name());

        rightArea.add(placeholder("目标文件夹", 360, 400, Theme.PANEL), ViewMode.BROWSE.name());
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
    public void setRootFolder(Path folder) {
        sourceTree.setRoot(folder);
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


    /** 素材条上已经拿到缩略图的张数。给测试和排查用。 */
    int loadedThumbnails() {
        return filmstrip.loadedCount();
    }

    /** 素材条上现在有几组。给测试和排查用。 */
    int filmstripGroupCount() {
        return filmstrip.groupCount();
    }

    /** 目录树，给测试用。 */
    SourceTreePanel sourceTreeForTest() {
        return sourceTree;
    }

    /** 关窗口时收掉后台取图的线程池。 */
    public void shutdown() {
        thumbnailLoader.shutdown();
        scanPool.shutdownNow();
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

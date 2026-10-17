package com.photoselector.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import com.photoselector.io.PhotoFiles;
import com.photoselector.io.Saver;
import com.photoselector.io.SourceFolderWatcher;
import com.photoselector.io.TargetFolderWatcher;
import com.photoselector.io.ThumbnailService;
import com.photoselector.core.PhotoGroup;
import com.photoselector.core.CropRect;
import com.photoselector.core.EditState;
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

    /**
     * 键盘快捷键表：**这个程序里所有的键都在这一处**。
     *
     * <p>集中成一张表是为了改着方便：想换键就改这里的 {@code KeyStroke}，
     * 不用去翻散落在各处的 {@code bind()} 调用。
     *
     * <p>"追加到末尾"给了两个键：空格是挑片时最顺手的那颗，回车是很多工具里
     * "确认加上去"的习惯。它们走的是同一个动作。
     */
    /** 撤销键。菜单里要显示同一个提示，所以单独留个名字给它。 */
    private static final KeyStroke UNDO_KEY =
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);

    /** 重做键。 */
    private static final KeyStroke REDO_KEY =
            KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK);

    private static final List<Shortcut> SHORTCUTS = List.of(
            new Shortcut("addToTarget", KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)),
            new Shortcut("addToTargetAlt", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)),
            new Shortcut("undo", UNDO_KEY),
            new Shortcut("redo", REDO_KEY),
            new Shortcut("previousPhoto", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)),
            new Shortcut("nextPhoto", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)));

    /** 表里的一行：动作名（同时也是按键映射的名字）和触发它的键。 */
    private record Shortcut(String actionName, KeyStroke key) {
    }

    /** 素材条里缩略图的高度。 */
    private static final int FILMSTRIP_THUMBNAIL_HEIGHT = 120;

    /** 展开排序模式中间那块用的缩略图宽度：比右栏大一截，好做精细筛选。 */
    private static final int ARRANGE_THUMBNAIL_WIDTH = 240;

    /** 底栏（照片条）的默认高度：按组头、缩略图、文件名加滚动条算出来。 */
    private static final int BOTTOM_HEIGHT = FilmstripPanel.preferredHeight(FILMSTRIP_THUMBNAIL_HEIGHT);

    private final ToolBarPanel toolBar = new ToolBarPanel();

    private final ThumbnailLoader thumbnailLoader;
    private final ThumbnailLoader targetLoader;
    private final FilmstripPanel filmstrip;
    private final TargetPanel targetPanel;
    private final TargetPanel arrangePanel;
    private final TrashPanel trashPanel;
    private final TargetFilmstripPanel targetFilmstrip;
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

    /** 目标文件夹的外部改动监听；换工作区或退出时跟着换 / 关。 */
    private final TargetFolderWatcher targetWatcher;

    /** 素材根目录 + 直接子文件夹的外部改动监听。 */
    private final SourceFolderWatcher sourceWatcher;

    /** 外部刷新正在跑；期间又来事件就记一笔，跑完再补一次。 */
    private boolean refreshingTargetExternally;
    private boolean pendingTargetRefresh;

    /** 素材扫描 / 外部刷新的轮次，迟到的旧目录结果据此丢掉。 */
    private long sourceScanRound;

    /** 某个文件夹被打开时通知外部（参数是文件夹和张数，张数为 -1 表示读失败）。 */
    private BiConsumer<Path, Integer> onFolderOpened = (folder, count) -> { };

    /** 目标文件夹读失败时通知外部；不能静默把它当成空文件夹。 */
    private Consumer<Path> onTargetFolderError = folder -> { };

    /** 上次保存中断留下的临时文件；只提醒，不擅自删除。 */
    private Consumer<List<Path>> onSaveResiduals = files -> { };

    /** 扫描出来的全部组；剔除哪些由目录树的勾选状态决定。 */
    private List<PhotoGroup> scannedGroups = List.of();

    /**
     * 目标文件夹读取的第几轮。
     *
     * <p>读目录是后台活儿，回来的时候世界可能已经变了——用户可能已经换了工作区。
     * 每换一次就加一，结果回来先对暗号，不是当前这一轮的直接丢掉。
     * 只在界面线程上读写，所以用不着原子类型。
     */
    private long targetLoadRound;

    /** 当前工作区。还没选过时为 null。 */
    private Workspace workspace;

    /** 工作区变化时的回调（用来写回配置文件）。 */
    private Consumer<Workspace> onWorkspaceChanged = updated -> { };

    /** 保存结束后的回调（外部负责把结果告诉用户）。 */
    private Consumer<Saver.Result> onSaveFinished = result -> { };

    /** 关闭流程走完后真正退出的动作（收线程池、关窗口）。 */
    private Runnable onExit = () -> { };

    /** 「有没保存的改动，要不要保存？」的提问方式；默认弹对话框。 */
    private Function<java.awt.Component, Integer> confirmSaveOnClose = MainPanel::askToSave;

    /** 关闭流程正在走（问过了、正在等保存）。防止连点 × 时再问一遍。 */
    private boolean closing;

    /**
     * 正在保存。
     *
     * <p>保存跑在后台，界面本来是能继续点的；但保存一结束，中间商会按磁盘的真实结果
     * <b>整个重建</b>（{@code seedExisting}），保存期间挑进来的会被这一下盖掉——
     * 你以为加进去了，其实没有。所以保存期间把会改中间商的操作挡在外面，
     * 等保存回来再放开。挑片手速再快，也快不过一次保存（最多几秒）。
     */
    private boolean saving;

    /** 窗口已经进入关闭收尾；迟到的监听 / 扫描回调不能再碰界面或线程池。 */
    private volatile boolean shutDown;

    /**
     * 保存跑完之后要接着做的事（退出程序、切到新工作区）；没有就是 null。
     *
     * <p>为什么要有它：保存是后台活儿，"选保存 → 保存完再执行"这件事必须挂在
     * 保存完成的回调上。关窗口和换工作区共用这一条路。
     */
    private Runnable afterSave;

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

    /**
     * 底栏自身希望保持的高度（不含上方 4 像素分隔条）。
     *
     * <p>窗口尺寸变化时不能只把分隔条停在旧绝对坐标，否则放大窗口 / 最大化后，
     * 多出来的高度会整块落进底栏。用户拖过底栏分隔条后，这个值会跟着更新，
     * 后续再改窗口大小仍保留用户选的高度。
     */
    private int desiredBottomHeight = BOTTOM_HEIGHT;
    private int lastOuterSplitHeight;
    private boolean placingBottomDivider;

    /** 启动 / 切工作区时要恢复的照片，以及两条异步加载链是否已经完成。 */
    private Path pendingRestorePhoto;
    private boolean sourceLoadFinished;
    private boolean targetLoadFinished;
    private boolean restoringPhoto;

    public MainPanel() {
        this(ThumbnailService.defaultCacheDir());
    }

    /** 供测试指定缓存目录，免得测试往用户真实的缓存目录里写东西。 */
    MainPanel(Path thumbnailCacheDir) {
        super(new BorderLayout());
        setBackground(Theme.PANEL);
        targetWatcher = new TargetFolderWatcher(folder ->
                SwingUtilities.invokeLater(() -> externalTargetChanged(folder)));
        sourceWatcher = new SourceFolderWatcher(root ->
                SwingUtilities.invokeLater(() -> externalSourceChanged(root)));

        ThumbnailService thumbnailService = new ThumbnailService(thumbnailCacheDir);
        thumbnailLoader = new ThumbnailLoader(thumbnailService);
        filmstrip = new FilmstripPanel(thumbnailLoader);
        // 目标文件夹面板单独用一只取图器：素材条换文件夹时会重启自己那一轮取图，
        // 共用一只的话会把这边正在路上的请求一起作废。缩略图缓存还是共用同一份。
        // 两块面板显示的是同一批照片，共用一只取图器：缓存命中率高，也省一个线程池。
        // （只有素材条会调 restart()，所以共用是安全的。）
        targetLoader = new ThumbnailLoader(thumbnailService);
        targetPanel = new TargetPanel(targetLoader);
        arrangePanel = new TargetPanel(targetLoader);
        trashPanel = new TrashPanel(targetLoader);
        targetFilmstrip = new TargetFilmstripPanel(targetLoader);
        arrangePanel.setThumbnailWidth(ARRANGE_THUMBNAIL_WIDTH);
        targetPanel.setOnToggleExpanded(this::toggleMode);
        arrangePanel.setOnToggleExpanded(this::toggleMode);
        viewer = new ViewerPanel(thumbnailLoader);
        // 点目标文件夹里的某张，大图就换成它，并套用这张已有的虚拟编辑。
        targetPanel.setOnPhotoChosen(photo -> {
            showPhotoInViewer(photo);
            // 两块目标视图看的是同一个序列，选中状态也要同步；
            // 否则切到整理模式时底部目标带会把刚才的选择清掉。
            arrangePanel.selectPhoto(photo);
            targetFilmstrip.selectPhoto(photo);
        });
        arrangePanel.setOnPhotoChosen(this::arrangePhotoChosen);
        targetFilmstrip.setOnPhotoChosen(this::chooseFromTargetStrip);
        targetFilmstrip.setOnReorder(this::reorder);

        // 从素材条、或者从正在看的大图上，都可以直接把照片拖到目标文件夹面板里。
        // 拖动时跟着鼠标走的那张图：素材条用已经解好的缩略图，大图临时缩一张
        DragOut.install(filmstrip, filmstrip::currentPhoto, filmstrip::thumbnailOf,
                () -> true, this::addToTargetAt, null, null, null);
        // 拖动是"拖照片"还是"搬画面"，由工具栏上的选择 / 平移工具决定
        DragOut.install(viewer, viewer::photo, viewer::ghostThumbnail,
                viewer::isDragOutEnabled, this::addToTargetAt, null, null, null);

        // 目标文件夹里的照片也能拖：往旁边一放就是重排顺序（白线照旧标落点）。
        // 这两块是"同一个序列的两个视图"（选图模式的窄栏、展开排序模式的大网格），
        // 拖哪一块都是改同一个中间商的顺序。
        // 另外：选图模式下拖到中间的大图区 = 丢垃圾桶（只记指针，保存时才真删）。
        DragOut.install(targetPanel, targetPanel::selectedPhoto, targetPanel::thumbnailOf,
                () -> true, this::reorder, null, this::dropOnViewer, viewer);
        DragOut.install(arrangePanel, arrangePanel::selectedPhoto, arrangePanel::thumbnailOf,
                () -> true, this::reorder, null, this::dropOnViewer, viewer);

        // 垃圾桶里的照片可以拖回目标栏：
        // · 落在选图模式那块窄栏上，或者展开排序模式的大网格上，都算数
        // · 拖到大图区、素材条这些地方什么都不做——照片留在垃圾桶里
        DragOut.installTrashBack(trashPanel, trashPanel::selectedPhoto, trashPanel::thumbnailOf,
                this::untrashFromTrash);
        // 两块目标面板内容一变，撤销 / 重做按钮的灰亮跟着走
        targetPanel.setOnChanged(this::historyChanged);
        arrangePanel.setOnChanged(this::historyChanged);
        // 开局同步一次：没有历史时那两颗按钮该是灰的
        historyChanged();
        // 旋转 / 翻转是"点一下马上生效"的工具：选中它的同时也对当前这张做一次。
        toolBar.setOnToolChanged(tool -> {
            viewer.setCursorTool(tool);
            boolean action = false;
            if (tool == CursorTool.ROTATE) {
                rotateCurrentPhoto(true);
                action = true;
            } else if (tool == CursorTool.FLIP) {
                flipCurrentPhotoHorizontally();
                action = true;
            }
            if (action) {
                // 动作型工具做完就回到"选择"：否则主视图停在既不能拖出、
                // 也不能平移的状态，看起来像突然失灵。
                toolBar.selectTool(CursorTool.SELECT);
            }
        });
        // 撤销 / 重做是这块面板自己的逻辑，接线也放在这儿：谁拿到面板，
        // 这两颗按钮都是活的（只接到主窗口去的话，单独用面板的地方就没人管了）
        toolBar.undoButton().addActionListener(e -> undo());
        toolBar.redoButton().addActionListener(e -> redo());
        toolBar.resetButton().addActionListener(e -> resetCurrentPhotoEdits());
        toolBar.gridButton().addActionListener(e -> {
            boolean next = !viewer.isGridVisible();
            viewer.setGridVisible(next);
            toolBar.gridButton().setActive(next);
        });
        viewer.setOnCropCommitted(this::applyCropToCurrentPhoto);

        buildAreas();

        // 三道分隔条：左栏 | 中右，中栏 | 右栏，主体 | 底栏，全都可以拖着调整
        centerRight = Splitters.horizontal(centerArea, rightArea, 1.0);
        mainSplit = Splitters.horizontal(leftColumn(), centerRight, 0.0);
        outerSplit = Splitters.vertical(mainSplit, bottomArea, 1.0);
        outerSplit.addPropertyChangeListener("dividerLocation", event -> {
            // 只有分隔条在"同一窗口高度下"移动，才是用户自己拖的。
            // 窗口 resize 引起的自动移动会在随后 placeBottomDivider() 里被纠正。
            if (!placingBottomDivider && outerSplit.getHeight() == lastOuterSplitHeight
                    && outerSplit.getHeight() > 0) {
                desiredBottomHeight = clampBottomHeight(
                        outerSplit.getHeight() - ((Integer) event.getNewValue()
                                + outerSplit.getDividerSize()));
            }
        });
        outerSplit.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                placeBottomDivider();
            }
        });
        // 分隔条默认把 ← / → 绑在"焦点组件之祖先"这一层（作用是挪分隔条），
        // 而这一层先于窗口级快捷键被检查——素材条长在里面，方向键会被它截走。
        // 这个程序里 ← / → 是"换照片"，所以把这几个绑定摘掉。
        ScrollKeys.stripArrowKeys(centerRight);
        ScrollKeys.stripArrowKeys(mainSplit);
        ScrollKeys.stripArrowKeys(outerSplit);

        toolBar.modeButton().addActionListener(e -> toggleMode());
        // 目录树里点一个文件夹，素材条滚到那一组；勾选变化则重新筛选组
        sourceTree.setOnFolderChosen(filmstrip::scrollToGroup);
        sourceTree.setOnInclusionChanged(this::inclusionChanged);
        // 素材条里点中一张，中间的大图就换成它；顺手把这张的虚拟编辑灌进去
        filmstrip.setOnCurrentPhotoChanged(photo -> {
            showPhotoInViewer(photo);
        });
        bindKeys();

        add(toolBar, BorderLayout.NORTH);
        add(outerSplit, BorderLayout.CENTER);

        setMode(ViewMode.BROWSE);
    }

    /** 布局后重新把底栏高度对到用户上次选的值，窗口变高时不会让底栏跟着长胖。 */
    @Override
    public void doLayout() {
        super.doLayout();
        placeBottomDivider();
    }

    private void placeBottomDivider() {
        int height = outerSplit.getHeight();
        if (height <= 0) {
            return;
        }
        int shownHeight = clampBottomHeight(desiredBottomHeight);
        int location = Math.max(0, height - shownHeight - outerSplit.getDividerSize());
        if (outerSplit.getDividerLocation() != location) {
            placingBottomDivider = true;
            try {
                outerSplit.setDividerLocation(location);
            } finally {
                placingBottomDivider = false;
            }
        }
        lastOuterSplitHeight = outerSplit.getHeight();
    }

    private int clampBottomHeight(int height) {
        int maximum = Math.max(0, outerSplit.getHeight() - outerSplit.getDividerSize());
        return Math.max(0, Math.min(height, maximum));
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
        if (next == ViewMode.BROWSE && viewer.isLoadDeferred()) {
            showPhotoInViewer(arrangePanel.selectedPhoto());
        }
        // 按钮上的字跟着状态变；刚露出来的那一块也要去取自己的缩略图
        boolean expanded = next == ViewMode.ARRANGE;
        targetPanel.setExpanded(expanded);
        arrangePanel.setExpanded(expanded);
        arrangePanel.refresh();
        targetFilmstrip.refresh();
        targetFilmstrip.selectPhoto(arrangePanel.selectedPhoto());
    }

    public ViewMode mode() {
        return mode;
    }

    private void buildAreas() {
        centerArea.add(viewer, ViewMode.BROWSE.name());
        centerArea.add(arrangePanel.wrapInScrollPane(), ViewMode.ARRANGE.name());

        rightArea.add(targetPanel.wrapInScrollPane(), ViewMode.BROWSE.name());
        JScrollPane trashScroll = trashPanel.wrapInScrollPane();
        trashScroll.setPreferredSize(new Dimension(360, 400));
        rightArea.add(trashScroll, ViewMode.ARRANGE.name());

        JScrollPane filmstripScroll = filmstrip.wrapInScrollPane();
        filmstripScroll.setPreferredSize(new Dimension(100, BOTTOM_HEIGHT));
        bottomArea.add(filmstripScroll, ViewMode.BROWSE.name());
        JScrollPane targetStripScroll = targetFilmstrip.wrapInScrollPane();
        targetStripScroll.setPreferredSize(new Dimension(100, BOTTOM_HEIGHT));
        bottomArea.add(targetStripScroll, ViewMode.ARRANGE.name());
    }

    /**
     * 选一个根文件夹：左栏目录树立刻换成它，整个档案夹在<b>后台</b>扫成若干组交给素材条。
     *
     * <p>列目录是文件系统操作，一个档案夹可能有几十个子目录、上千张照片，
     * 在界面线程上扫会卡住；所以这里立刻返回，结果通过 {@link #setOnFolderOpened} 通知。
     */
    private void reloadGroups(Path folder) {
        long myRound = ++sourceScanRound;
        try {
            scanPool.submit(() -> {
                List<PhotoGroup> groups;
                try {
                    groups = PhotoFiles.groupsIn(folder);
                } catch (IOException | RuntimeException e) {
                    SwingUtilities.invokeLater(() -> {
                        if (!shutDown && myRound == sourceScanRound
                                && workspace != null && folder.equals(workspace.sourceRoot())) {
                            markSourceLoadFinished();
                            onFolderOpened.accept(folder, -1);
                        }
                    });
                    return;
                }

                SwingUtilities.invokeLater(() -> {
                    if (shutDown || myRound != sourceScanRound
                            || workspace == null || !folder.equals(workspace.sourceRoot())) {
                        return; // 扫的是旧的根目录，或窗口已经关闭：这批结果作废
                    }
                    scannedGroups = groups;
                    applyGroups();
                    markSourceLoadFinished();
                    onFolderOpened.accept(folder, filmstrip.photoCount());
                    startSourceWatcher(folder);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // 关闭收尾期间扫描池已经不再收任务
        }
    }

    /** 勾选变化：重新筛组，并把新的剔除项记进工作区。 */
    private void inclusionChanged() {
        // 只增删一组的筛选，不应把用户当前看的那张和滚动位置一起重置；
        // refreshGroups 会在当前照片仍被保留时尽量留在原处。
        applyGroups(true);
        if (workspace != null) {
            workspace = workspace.withExcluded(sourceTree.excludedFolders());
            onWorkspaceChanged.accept(workspace);
        }
        // 如果当前大图正是刚被勾掉的素材组里的照片，它已经从浏览范围里消失，
        // 不能继续留在中间冒充"还在范围里"。目标文件夹里的照片不受排除规则影响。
        Path showing = viewer.photo();
        Session session = targetPanel.session();
        boolean inTarget = session != null && showing != null && session.indexOf(showing) >= 0;
        if (!inTarget && showing != null && workspace != null
                && isSourcePhoto(workspace.sourceRoot(), showing)
                && !filmstrip.containsPhoto(showing)) {
            showPhotoInViewer(null);
        }
    }

    /** 按目录树的勾选状态筛一遍：被剔除的组不进素材条。 */
    private void applyGroups() {
        applyGroups(false);
    }

    /**
     * @param preserveView 外部刷新时为 true：保留当前照片和折叠状态；
     *                     初次扫描 / 换工作区时为 false，从头布置素材条
     */
    private void applyGroups(boolean preserveView) {
        List<PhotoGroup> included = new ArrayList<>();
        for (PhotoGroup group : scannedGroups) {
            if (sourceTree.isIncluded(group.folder())) {
                included.add(group);
            }
        }
        if (preserveView) {
            filmstrip.refreshGroups(included);
        } else {
            filmstrip.setGroups(included, FILMSTRIP_THUMBNAIL_HEIGHT);
        }
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

    // ─────────────────────────── 图像编辑 ───────────────────────────

    /**
     * 把当前这张照片顺时针转一个直角（{@code clockwise} 为 false 就是逆时针）。
     *
     * <p>只改中间商和画面，磁盘上的 JPG 要等「保存」才重写——和挑片、排序一样，
     * 都属于"虚拟编辑"。撤销 / 重做也走同一条历史。
     */
    public void rotateCurrentPhoto(boolean clockwise) {
        editingPhoto().ifPresent(photo -> {
            Session session = targetPanel.session();
            if (session.setEdits(photo, session.editsOf(photo).rotate(clockwise ? 1 : -1))) {
                viewer.setEdits(session.editsOf(photo));
                refreshEditedThumbnail(photo);
                historyChanged();
            }
        });
    }

    /** 左右镜像当前这张。 */
    public void flipCurrentPhotoHorizontally() {
        editingPhoto().ifPresent(photo -> {
            Session session = targetPanel.session();
            if (session.setEdits(photo, session.editsOf(photo).flipHorizontal())) {
                viewer.setEdits(session.editsOf(photo));
                refreshEditedThumbnail(photo);
                historyChanged();
            }
        });
    }

    /** 上下镜像当前这张。 */
    public void flipCurrentPhotoVertically() {
        editingPhoto().ifPresent(photo -> {
            Session session = targetPanel.session();
            if (session.setEdits(photo, session.editsOf(photo).flipVertically())) {
                viewer.setEdits(session.editsOf(photo));
                refreshEditedThumbnail(photo);
                historyChanged();
            }
        });
    }

    /** 「重置」：把当前这张照片的旋转 / 翻转全部清掉。 */
    public void resetCurrentPhotoEdits() {
        editingPhoto().ifPresent(photo -> {
            Session session = targetPanel.session();
            if (session.clearEdits(photo)) {
                viewer.setEdits(EditState.NONE);
                refreshEditedThumbnail(photo);
                historyChanged();
            }
        });
    }

    /** 从菜单进入裁切：工具切到裁切后，在图片上拖出目标区域。 */
    public void beginCrop() {
        if (canEditCurrentPhoto()) {
            toolBar.selectTool(CursorTool.CROP);
        }
    }

    /** 裁切框选完成：写入中间商、刷新画面，并回到选择工具。 */
    private void applyCropToCurrentPhoto(CropRect crop) {
        editingPhoto().ifPresent(photo -> {
            Session session = targetPanel.session();
            EditState next = session.editsOf(photo).withCrop(crop);
            if (session.setEdits(photo, next)) {
                viewer.setEdits(next);
                refreshEditedThumbnail(photo);
                historyChanged();
            }
        });
        toolBar.selectTool(CursorTool.SELECT);
    }

    /** 有没有选中的照片、有没有目标文件夹——两个都有才谈得上编辑。 */
    public boolean canEditCurrentPhoto() {
        return editingPhoto().isPresent();
    }

    /** 当前正在看的那张；大图显示谁，编辑就落到谁身上。 */
    private java.util.Optional<Path> editingPhoto() {
        Session session = targetPanel.session();
        Path photo = viewer.photo();
        // 保存期间所有会改中间商的动作都必须停下；旋转 / 翻转 / 裁切
        // 也属于中间商变更，不能趁着后台保存把结果改到下一份快照之外。
        if (saving || session == null || photo == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(photo);
    }

    /** 大图换片时把这张照片已有的编辑一起灌进去；所有入口都走这里。 */
    private void showPhotoInViewer(Path photo) {
        rememberViewedPhoto(photo);
        FilmstripPanel.Neighbors neighbors = neighborsForViewer(photo);
        viewer.showPhoto(photo, neighbors.previous(), neighbors.next());
        Session session = targetPanel.session();
        viewer.setEdits(session == null ? EditState.NONE : session.editsOf(photo));
        // 选中的照片换了，"能不能编辑"也跟着变：旋转 / 翻转 / 重置要跟着亮 / 灰
        historyChanged();
    }

    /**
     * 大图该预读取哪两张。
     *
     * <p>素材照片按底部素材条的真实顺序取邻居——收起的分组也按方向键的实际行为跳过；
     * 只在目标文件夹里出现、不在素材条里的照片，则按目标序列取前后张。
     */
    private FilmstripPanel.Neighbors neighborsForViewer(Path photo) {
        if (photo == null) {
            return FilmstripPanel.Neighbors.NONE;
        }
        if (filmstrip.containsPhoto(photo)) {
            return filmstrip.neighborsOf(photo);
        }
        Session session = targetPanel.session();
        int index = session == null ? -1 : session.indexOf(photo);
        if (index < 0) {
            return FilmstripPanel.Neighbors.NONE;
        }
        return new FilmstripPanel.Neighbors(
                index > 0 ? session.entryAt(index - 1).source() : null,
                index + 1 < session.size() ? session.entryAt(index + 1).source() : null);
    }

    /** 中间整理网格点中一张：大图状态和底部目标带一起跟上。 */
    private void arrangePhotoChosen(Path photo) {
        showPhotoDeferredInViewer(photo);
        targetPanel.selectPhoto(photo);
        targetFilmstrip.selectPhoto(photo);
    }

    /** 底栏目标照片带点中一张：选中中间网格里的同一张，并滚到它。 */
    private void chooseFromTargetStrip(Path photo) {
        showPhotoDeferredInViewer(photo);
        targetPanel.selectPhoto(photo);
        arrangePanel.selectPhoto(photo);
        targetFilmstrip.selectPhoto(photo);
    }

    /** 整理模式选图：只更新当前对象，不在隐藏的大图区解码全尺寸原图。 */
    private void showPhotoDeferredInViewer(Path photo) {
        rememberViewedPhoto(photo);
        viewer.selectPhotoDeferred(photo);
        Session session = targetPanel.session();
        viewer.setEdits(session == null ? EditState.NONE : session.editsOf(photo));
        historyChanged();
    }

    /** 只更新内存中的工作区指针；磁盘写入发生在正常退出时，避免每次过片都写文件。 */
    private void rememberViewedPhoto(Path photo) {
        if (!restoringPhoto) {
            pendingRestorePhoto = null;
        }
        if (workspace == null) {
            return;
        }
        Path normalized = photo == null ? null : photo.toAbsolutePath().normalize();
        if (!java.util.Objects.equals(normalized, workspace.lastViewedPhoto())) {
            workspace = workspace.withLastViewedPhoto(normalized);
        }
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

    /** 改成固定倍率模式，并记住作为以后打开照片的默认方式。 */
    public void setDefaultZoomPercent(int percent) {
        viewer.setDefaultScale(percent / 100.0);
    }

    /** 以后打开每张照片都默认适应窗口；当前这张也立刻切过去。 */
    public void setDefaultFitToWindow() {
        viewer.setDefaultFitToWindow();
    }

    /** 默认缩放方式是不是"适应窗口"；菜单勾选用。 */
    public boolean defaultFitToWindow() {
        return viewer.isDefaultFitToWindow();
    }

    /** 启动时应用保存的“适应 / 固定倍率”偏好；只配置状态，不重复写偏好文件。 */
    void applyStartupZoom(boolean fit, int percent) {
        viewer.applyStartupZoom(fit, percent / 100.0);
    }

    /** 固定倍率模式下的默认放大倍率（百分比）。 */
    public int defaultZoomPercent() {
        return (int) Math.round(viewer.defaultScale() * 100);
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
        // 旧工作区的照片不能继续留在中央、也不能让它的预读取读跑完再盖回来。
        // 新工作区的 lastViewedPhoto 会在两条异步扫描完成后重新显示。
        viewer.showPhoto(null);
        stopSourceWatcher();
        // 换工作区前把旧工作区刚记住的"最后浏览照片"写回配置；否则旧工作区
        // 的浏览指针只留在即将被替换掉的内存对象里，下次再选它就丢了。
        if (workspace != null) {
            try {
                onWorkspaceChanged.accept(workspace);
            } catch (RuntimeException ignored) {
                // 配置写不进去不该阻止切换工作区。
            }
        }
        this.workspace = newWorkspace;
        pendingRestorePhoto = newWorkspace.lastViewedPhoto();
        sourceLoadFinished = false;
        targetLoadFinished = false;
        // 新工作区扫描完成之前，旧素材不能继续留在底栏里冒充新工作区的内容。
        scannedGroups = List.of();
        filmstrip.setGroups(List.of(), FILMSTRIP_THUMBNAIL_HEIGHT);
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
        long myRound = ++targetLoadRound; // 换目标文件夹 = 开新的一轮，旧结果作废
        stopTargetWatcher();
        refreshingTargetExternally = false;
        pendingTargetRefresh = false;
        if (target == null) {
            targetPanel.setSession(null);
            arrangePanel.setSession(null);
            trashPanel.setSession(null);
            targetFilmstrip.setSession(null);
            markTargetLoadFinished();
            return;
        }
        targetLoadFinished = false;
        // 读新目标文件夹期间先清空旧中间商；读失败时也不能让上一份旧序列
        // 继续出现在界面上，否则用户会以为自己正在编辑新文件夹。
        targetPanel.setSession(null);
        arrangePanel.setSession(null);
        trashPanel.setSession(null);
        targetFilmstrip.setSession(null);
        try {
            scanPool.submit(() -> {
                List<Path> existing = List.of();
                List<Path> residuals = List.of();
                Exception readError = null;
                try {
                    residuals = Saver.residualFiles(target);
                    existing = PhotoFiles.imagesIn(target);
                } catch (IOException | RuntimeException e) {
                    readError = e;
                }
                List<Path> files = existing;
                List<Path> leftovers = residuals;
                Exception failure = readError;
                SwingUtilities.invokeLater(() -> {
                    if (shutDown || myRound != targetLoadRound) {
                        return; // 这一轮已经过期：用户换过目标文件夹了
                    }
                    if (failure != null) {
                        // 读失败和“真的空目录”是两回事。前者不能建空中间商，
                        // 否则用户会以为目标文件夹里什么都没有，甚至继续往不可用的目录保存。
                        targetPanel.setSession(null);
                        arrangePanel.setSession(null);
                        trashPanel.setSession(null);
                        targetFilmstrip.setSession(null);
                        markTargetLoadFinished();
                        onTargetFolderError.accept(target);
                        return;
                    }
                    Session session = new Session(target, NumberingRule.DEFAULT);
                    session.seedExisting(files);
                    targetPanel.setSession(session);
                    arrangePanel.setSession(session);
                    trashPanel.setSession(session);
                    targetFilmstrip.setSession(session);
                    markTargetLoadFinished();
                    if (!leftovers.isEmpty()) {
                        onSaveResiduals.accept(leftovers);
                    }
                    try {
                        targetWatcher.watch(target);
                    } catch (IOException | RuntimeException e) {
                        // 初次读目录成功、但系统不允许注册监听：界面仍可用，
                        // 只是外部变化不会自动跟进，必须明确告诉用户。
                        onTargetFolderError.accept(target);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // 关闭收尾期间扫描池已经不再收任务
        }
    }

    private void stopTargetWatcher() {
        try {
            targetWatcher.watch(null);
        } catch (IOException impossible) {
            // watch(null) 不注册目录，不会真的抛
        }
    }

    /** 目标文件夹读取失败的提示方式。 */
    public void setOnTargetFolderError(Consumer<Path> listener) {
        this.onTargetFolderError = listener == null ? folder -> { } : listener;
    }

    private void markSourceLoadFinished() {
        sourceLoadFinished = true;
        restorePendingPhotoIfReady();
    }

    private void markTargetLoadFinished() {
        targetLoadFinished = true;
        restorePendingPhotoIfReady();
    }

    /**
     * 两条异步链都完成后，按上次关闭时的路径恢复当前照片。
     *
     * <p>先查目标序列，再查素材条；路径已经不存在或不在当前筛选范围里就保持空白，
     * 不为了“恢复”强行显示一张已经不该出现的照片。
     */
    private void restorePendingPhotoIfReady() {
        if (pendingRestorePhoto == null || !sourceLoadFinished || !targetLoadFinished) {
            return;
        }
        Path photo = pendingRestorePhoto;
        pendingRestorePhoto = null;
        if (!Files.isRegularFile(photo)) {
            return;
        }

        restoringPhoto = true;
        try {
            Session session = targetPanel.session();
            if (session != null && session.indexOf(photo) >= 0) {
                targetPanel.selectPhoto(photo);
                arrangePanel.selectPhoto(photo);
                targetFilmstrip.selectPhotoCentered(photo);
                showPhotoInViewer(photo);
            } else {
                filmstrip.selectPhoto(photo);
            }
        } finally {
            restoringPhoto = false;
        }
    }

    public void setOnSaveResiduals(Consumer<List<Path>> listener) {
        this.onSaveResiduals = listener == null ? files -> { } : listener;
    }

    /** 监听线程说目标文件夹变了；真正读目录仍然回后台扫描池。 */
    private void externalTargetChanged(Path folder) {
        if (shutDown || workspace == null || workspace.targetFolder() == null
                || !folder.toAbsolutePath().normalize()
                        .equals(workspace.targetFolder().toAbsolutePath().normalize())) {
            return; // 旧监听器的迟到通知
        }
        if (saving) {
            pendingTargetRefresh = true;
            return;
        }
        refreshTargetFromDisk(folder);
    }

    /** 启动素材监听；注册失败时界面仍可用，只是不再自动跟进外部变化。 */
    private void startSourceWatcher(Path root) {
        try {
            sourceWatcher.watch(root);
        } catch (IOException | RuntimeException ignored) {
            // 读目录已经成功；监听权限问题不该把已经打开的工作区判成失败
        }
    }

    private void stopSourceWatcher() {
        sourceWatcher.close();
    }

    /** 监听线程说素材根目录或某个直接子文件夹变了。 */
    private void externalSourceChanged(Path root) {
        if (shutDown || workspace == null
                || !root.toAbsolutePath().normalize()
                        .equals(workspace.sourceRoot().toAbsolutePath().normalize())) {
            return;
        }
        refreshSourceFromDisk(root);
    }

    /**
     * 外部素材变化后的重扫：换掉素材条内容，但不推倒目标文件夹的中间商。
     *
     * <p>用户正在看的那张如果还在，就重读一次文件，避免文件被外部原地覆盖后
     * 大图和缩略图仍然显示旧像素；它被删掉则清空大图。
     */
    private void refreshSourceFromDisk(Path root) {
        long round = ++sourceScanRound;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        try {
            scanPool.submit(() -> {
                List<PhotoGroup> groups = null;
                Exception failure = null;
                try {
                    groups = PhotoFiles.groupsIn(normalizedRoot);
                } catch (IOException | RuntimeException e) {
                    failure = e;
                }

                List<PhotoGroup> scanned = groups;
                Exception error = failure;
                SwingUtilities.invokeLater(() -> {
                    if (shutDown || round != sourceScanRound || workspace == null
                            || !normalizedRoot.equals(
                                    workspace.sourceRoot().toAbsolutePath().normalize())) {
                        return; // 换过工作区，或者已经有更新的一轮刷新
                    }
                    if (error != null) {
                        onFolderOpened.accept(normalizedRoot, -1);
                        return;
                    }

                    Path showing = viewer.photo();
                    boolean listingChanged = !sourceListingFingerprint(scannedGroups)
                            .equals(sourceListingFingerprint(scanned));
                    scannedGroups = List.copyOf(scanned);
                    applyGroups(true);
                    if (listingChanged) {
                        sourceTree.refreshFromDisk();
                    }
                    onFolderOpened.accept(normalizedRoot, filmstrip.photoCount());
                    if (showing != null && isSourcePhoto(normalizedRoot, showing)) {
                        if (Files.isRegularFile(showing)) {
                            viewer.clearFullImageCache();
                            showPhotoInViewer(showing);
                        } else {
                            showPhotoInViewer(null);
                        }
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // 关闭收尾期间扫描池已经不再收任务
        }
    }

    private static boolean isSourcePhoto(Path root, Path photo) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPhoto = photo.toAbsolutePath().normalize();
        Path parent = normalizedPhoto.getParent();
        return parent != null
                && (parent.equals(normalizedRoot)
                        || normalizedRoot.equals(parent.getParent()));
    }

    /** 组路径 + 照片路径；覆盖同名文件时签名不变，增删 / 改名时会变。 */
    private static Set<Path> sourceListingFingerprint(List<PhotoGroup> groups) {
        Set<Path> paths = new HashSet<>();
        for (PhotoGroup group : groups) {
            paths.add(group.folder().toAbsolutePath().normalize());
            for (Path photo : group.photos()) {
                paths.add(photo.toAbsolutePath().normalize());
            }
        }
        return paths;
    }

    /**
     * 外部变化后的对账：只重读目标文件夹，不推倒整个工作区。
     *
     * <p>界面线程不碰磁盘；扫描结果回来要再核对一次目标目录和保存状态，
     * 防止用户在这期间已经换了工作区，或者正在执行一次保存。
     */
    private void refreshTargetFromDisk(Path target) {
        if (shutDown) {
            return;
        }
        if (refreshingTargetExternally) {
            pendingTargetRefresh = true;
            return;
        }
        refreshingTargetExternally = true;
        long round = targetLoadRound;
        try {
            scanPool.submit(() -> {
                List<Path> files = List.of();
                Exception failure = null;
                try {
                    files = PhotoFiles.imagesIn(target);
                } catch (IOException | RuntimeException e) {
                    failure = e;
                }

                List<Path> scanned = files;
                Exception error = failure;
                SwingUtilities.invokeLater(() -> {
                    refreshingTargetExternally = false;
                    if (shutDown || round != targetLoadRound) {
                        return; // 已经换过目标文件夹
                    }
                    if (saving) {
                        pendingTargetRefresh = true;
                        return;
                    }
                    if (error != null) {
                        // 目标文件夹可能已经被删除/改名。继续留着旧中间商会让用户
                        // 以为还能保存到那个目录；清空并明确报错更安全。
                        Path showing = viewer.photo();
                        if (showing != null && target.toAbsolutePath().normalize()
                                .equals(showing.toAbsolutePath().normalize().getParent())) {
                            showPhotoInViewer(null);
                        }
                        targetPanel.setSession(null);
                        arrangePanel.setSession(null);
                        trashPanel.setSession(null);
                        targetFilmstrip.setSession(null);
                        onTargetFolderError.accept(target);
                    } else {
                        applyExternalTargetFiles(target, scanned);
                    }
                    if (pendingTargetRefresh) {
                        pendingTargetRefresh = false;
                        refreshTargetFromDisk(target);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            refreshingTargetExternally = false; // 正在关窗口，扫描池已经不收任务
        }
    }

    /** 把扫描到的磁盘现状合进当前中间商，并刷新所有相关视图。 */
    private void applyExternalTargetFiles(Path target, List<Path> files) {
        // 目标文件可能被外部程序原地替换；缓存里即使路径没变，像素也未必还是旧的。
        viewer.clearFullImageCache();
        Session session = targetPanel.session();
        Path targetRoot = target.toAbsolutePath().normalize();
        if (session == null) {
            // 刚才目标文件夹消失时我们已经清空了中间商；它重新出现后，监听的
            // 恢复通知会走到这里，重新读一遍把它接回来，而不是让界面一直空着。
            if (Files.isDirectory(targetRoot)) {
                openTargetFolder(targetRoot);
            }
            return;
        }
        if (!targetRoot.equals(session.targetFolder().toAbsolutePath().normalize())) {
            return;
        }
        Path targetSelection = targetPanel.selectedPhoto();
        Path arrangeSelection = arrangePanel.selectedPhoto();

        if (session.isDirty()) {
            // 有没保存的挑片 / 排序 / 编辑：保留它们，只把外部增删并进来。
            session.reconcileExisting(files);
        } else {
            // 本来就干净：直接按磁盘顺序重建最忠实。
            session.seedExisting(files);
        }
        restoreSelection(targetPanel, targetSelection, session);
        restoreSelection(arrangePanel, arrangeSelection, session);

        targetPanel.reloadThumbnails();
        arrangePanel.reloadThumbnails();
        trashPanel.reloadThumbnails();
        targetFilmstrip.reloadThumbnails();
        targetPanel.refresh();
        arrangePanel.refresh();
        trashPanel.refresh();
        targetFilmstrip.refresh();
        historyChanged();

        Path showing = viewer.photo();
        if (showing != null
                && targetRoot.equals(showing.toAbsolutePath().normalize().getParent())) {
            // 同名文件可能被外部替换，必须重读；删掉了就清空大图。
            showPhotoInViewer(Files.isRegularFile(showing) ? showing : null);
        }
    }

    /** 外部对账后尽量让点选仍指向原来那张；它被删了就清空。 */
    private static void restoreSelection(TargetPanel panel, Path photo, Session session) {
        int index = photo == null ? -1 : session.indexOf(photo);
        if (index >= 0) {
            panel.moveSelectionTo(index);
        } else {
            panel.clearSelection();
        }
    }

    /** 保存前后的路径可能变（改名/复制），点选要沿着来源→最终文件映射走。 */
    private static Path savedSelection(Path selection, Saver.Result result) {
        if (selection == null) {
            return null;
        }
        return result.savedLocations().getOrDefault(
                selection.toAbsolutePath().normalize(), selection);
    }

    /** 空格：把素材条里点中的那张照片加入目标序列。 */
    public void addCurrentPhotoToTarget() {
        addToTarget(filmstrip.currentPhoto());
    }

    /**
     * 把一张素材照片加入目标序列。
     *
     * <p>空格键和"拖到目标面板上"走的是同一条路——同一个动作不该有两份实现。
     */
    public void addToTarget(Path photo) {
        addToTargetAt(photo, -1);
    }

    /**
     * 把一张素材照片插进目标序列的指定位置。
     *
     * <p>空格键（追加到末尾）和拖到两张之间（插在指定位置）走的是同一条路。
     */
    public void addToTargetAt(Path photo, int index) {
        Session session = targetPanel.session();
        if (saving || photo == null || session == null) {
            return;
        }
        boolean added = index < 0 ? session.add(photo) : session.insert(index, photo);
        if (added) {
            targetPanel.refresh();
            arrangePanel.refresh();
            targetFilmstrip.refresh();
        }
    }

    /** Ctrl+Z：撤销上一步（挑入、挪位、丢垃圾桶都算）。 */
    public void undo() {
        Session session = targetPanel.session();
        if (!saving && session != null && session.undo()) {
            targetPanel.refresh();
            arrangePanel.refresh();
            targetFilmstrip.refresh();
            // 撤销的可能是"丢进垃圾桶"或者"从垃圾桶捡回来"，
            // 两种都会改变垃圾桶的内容——不刷新的话那块面板会停在旧画面上
            trashPanel.refresh();
            syncViewerEdits();
        }
    }

    /** Ctrl+Y：把刚撤销掉的那一步再做一遍。 */
    public void redo() {
        Session session = targetPanel.session();
        if (!saving && session != null && session.redo()) {
            targetPanel.refresh();
            arrangePanel.refresh();
            targetFilmstrip.refresh();
            trashPanel.refresh();
            syncViewerEdits();
        }
    }

    /** 撤销 / 重做可能改的是"旋转 / 翻转"，把大图上的编辑跟着中间商对一下。 */
    private void syncViewerEdits() {
        Path photo = viewer.photo();
        Session session = targetPanel.session();
        if (photo == null || session == null) {
            return;
        }
        if (session.indexOf(photo) < 0) {
            viewer.setEdits(EditState.NONE);
            refreshEditedThumbnail(photo);
            return;
        }
        viewer.setEdits(session.editsOf(photo));
        refreshEditedThumbnail(photo);
    }

    /** 某张照片的虚拟编辑变了：让三条目标缩略图视图只重取这一张。 */
    private void refreshEditedThumbnail(Path photo) {
        targetPanel.reloadThumbnail(photo);
        arrangePanel.reloadThumbnail(photo);
        targetFilmstrip.reloadThumbnail(photo);
    }

    /**
     * 撤销 / 重做按钮的灰亮状态：没得撤销就灰着。
     *
     * <p>按钮的灰亮在这儿同步（挪到主窗口去的話，光用 {@code MainPanel} 的地方
     * 就没人管这两颗按钮了）；菜单项那两份由外部的回调去同步。
     *
     * <p>调用点集中在"序列变过"的地方（{@code TargetPanel.refresh} 和这一对动作），
     * 不挂在每次重绘上——那样每帧都要问一遍历史栈，白费功夫。
     */
    private void historyChanged() {
        toolBar.undoButton().setEnabled(canUndo());
        toolBar.redoButton().setEnabled(canRedo());
        // 没选中照片（或者还没有目标文件夹）时，旋转 / 翻转 / 重置无从谈起，灰着
        boolean canEdit = canEditCurrentPhoto();
        toolBar.toolButton(CursorTool.ROTATE).setEnabled(canEdit);
        toolBar.toolButton(CursorTool.FLIP).setEnabled(canEdit);
        toolBar.toolButton(CursorTool.CROP).setEnabled(canEdit);
        toolBar.resetButton().setEnabled(canEdit);
        onHistoryChanged.run();
    }

    /** 撤销 / 重做的灰亮状态变了（主窗口拿它同步菜单项）。 */
    public void setOnHistoryChanged(Runnable listener) {
        this.onHistoryChanged = listener == null ? () -> { } : listener;
    }

    private Runnable onHistoryChanged = () -> { };

    /** 现在有没有可以撤销的步骤。 */
    public boolean canUndo() {
        Session session = targetPanel.session();
        return session != null && session.canUndo();
    }

    /** 现在有没有可以重做的步骤。 */
    public boolean canRedo() {
        Session session = targetPanel.session();
        return session != null && session.canRedo();
    }

    /** 撤销键；菜单里显示提示用，和快捷键表里是同一个键。 */
    static KeyStroke undoKeyStroke() {
        return UNDO_KEY;
    }

    /** 重做键；同上。 */
    static KeyStroke redoKeyStroke() {
        return REDO_KEY;
    }

    /**
     * 在目标序列里把一张照片挪到别的位置——面板内拖动排序走的就是它。
     *
     * <p>落点 {@code slot} 是"插在第几项之前"的视觉槽位：比如把第 0 张拖到
     * 第 3 个缝里，意思就是"挪完之后它排在这三张的后面"。
     * 挪走自己之后，后面的槽位会整体前移一格，所以目标位置要相应减一。
     *
     * <p>这个动作只改中间商和画面，磁盘要等「保存」才动——和别处一样。
     */
    private void reorder(Path photo, int slot) {
        Session session = targetPanel.session();
        if (session == null || saving) {
            return;
        }
        int from = session.indexOf(photo);
        if (from < 0) {
            return; // 不在序列里（比如面板刚换过中间商），没什么可挪的
        }
        int to = slot - (slot > from ? 1 : 0);
        if (to < 0 || to >= session.size() || to == from) {
            return; // 落回原地或越界：不产生无意义的历史
        }
        session.move(from, to);
        // 照片挪了位置，"选中"要跟着照片走，而不是把旧的格子下标平移到新位置。
        // 从底部目标带拖动时，两块目标网格原本可能根本没选中任何照片；那时光改下标
        // 会凭空点亮一张无关照片。三个入口统一按路径重新选中真正被拖动的那张。
        targetPanel.selectPhoto(photo);
        arrangePanel.selectPhoto(photo);
        targetFilmstrip.selectPhoto(photo);
        targetPanel.refresh();
        arrangePanel.refresh();
        targetFilmstrip.refresh();
    }

    /**
     * 把目标序列里的一张照片丢进垃圾桶——选图模式下"从目标栏拖到中间大图区"走这里。
     *
     * <p>只是把它从序列里摘出来、在垃圾桶里记一个指针；磁盘上什么都没动。
     * 保存时那些真实文件才会被删掉；关程序时选"不保存"就等于全没发生。
     */
    private void dropOnViewer(Path photo) {
        Session session = targetPanel.session();
        if (session == null || saving) {
            return;
        }
        int index = session.indexOf(photo);
        if (index < 0) {
            return; // 不在目标序列里，没什么可扔的
        }
        boolean showingIt = photo.equals(viewer.photo());
        session.trash(index);
        targetPanel.refresh();
        arrangePanel.refresh();
        targetFilmstrip.refresh();
        trashPanel.refresh();
        if (showingIt) {
            Path next = session.isEmpty() ? null
                    : session.entryAt(Math.min(index, session.size() - 1)).source();
            showPhotoInViewer(next);
        }
    }

    /**
     * 从垃圾桶把照片拖回了目标栏：插到落点指示的位置。
     *
     * <p>插回之后编号按新位置现算——垃圾桶里那条记的旧号只是用来对照的。
     */
    private void untrashFromTrash(Path photo, TargetPanel panel, int slot) {
        Session session = targetPanel.session();
        if (session == null || saving) {
            return;
        }
        if (session.untrash(photo, slot)) {
            targetPanel.refresh();
            arrangePanel.refresh();
            targetFilmstrip.refresh();
            trashPanel.refresh();
        }
    }

    /**
     * 保存：把中间商里的改动一次性落到磁盘。
     *
     * <p>文件操作可能很慢（复制几十张就是几十次磁盘写入），所以丢给后台线程池，
     * 做完回到界面线程：先按磁盘上的真实结果重建中间商（顺序不变、撤销记录清空），
     * 再把结果交给外部去告诉用户。
     *
     * <p>拍快照这一步本身（也就是 {@link Saver.Plan#from}）必须在这里、在界面线程上做完，
     * 它可能因为读不了目录而抛异常。真抛了就当这次保存没启动：返回 false，
     * 调用方（例如"关窗口时问要不要保存"那个流程）才知道自己该等着，而不是空等一场。
     *
     * @return 是否真的启动了保存；false 表示这次连快照都没拍成
     */
    public boolean save() {
        if (saving) {
            return false; // 已有一次保存在路上，别把同一份快照排队执行两遍
        }
        Session session = targetPanel.session();
        if (session == null) {
            onSaveFinished.accept(new Saver.Result(0, 0, 0, 0, List.of(), Map.of(),
                    List.of(), List.of("还没有选目标文件夹，没有可以保存的改动。")));
            return false;
        }
        if (session.isEmpty() && session.trash().isEmpty()) {
            onSaveFinished.accept(new Saver.Result(0, 0, 0, 0, List.of(), Map.of(),
                    List.of(), List.of("目标文件夹里什么都没有，没有可以保存的改动。")));
            return false;
        }

        // 在界面线程上先拍一份快照：保存跑在后台，而中间商是活的，
        // 不拍的话你在保存期间再挑一张，后台就会读到一半的数据
        Saver.Plan plan;
        try {
            plan = Saver.Plan.from(session);
        } catch (RuntimeException e) {
            onSaveFinished.accept(new Saver.Result(0, 0, 0, 0, List.of(), Map.of(), List.of(),
                    List.of("保存没能开始：" + e.getMessage())));
            return false;
        }
        saving = true; // 从这一刻起到下面的回调回来之前，不许再改中间商
        historyChanged(); // 旋转 / 翻转 / 重置等按钮在保存期间先灰掉
        try {
            savePool.submit(() -> {
                Saver.Result result;
                try {
                    result = new Saver().save(plan);
                } catch (RuntimeException e) {
                    // 保存器里出现了没预料到的运行时异常。此时不能假装成功重建中间商，
                    // 否则用户刚挑的照片会从界面上消失；保留现有状态，只把错误报出来。
                    SwingUtilities.invokeLater(() -> {
                        if (shutDown) {
                            return;
                        }
                        saving = false;
                        historyChanged();
                        afterSave = null;
                        closing = false;
                        onSaveFinished.accept(new Saver.Result(0, 0, 0, 0,
                                List.of(), Map.of(), List.of(),
                                List.of("保存器意外失败：" + e.getMessage())));
                        resumePendingExternalRefresh();
                    });
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    if (shutDown) {
                        return;
                    }
                    Path shownBeforeSave = viewer.photo();
                    Path targetSelectionBeforeSave = targetPanel.selectedPhoto();
                    Path arrangeSelectionBeforeSave = arrangePanel.selectedPhoto();
                    Path stripSelectionBeforeSave = targetFilmstrip.selectedPhoto();
                    saving = false; // 中间商马上要重建，此刻起放行——重建之后磁盘就是新的基准
                    // 保存之后磁盘就是真相：按真实结果重建中间商，顺序保留、历史清空
                    session.seedExisting(result.finalOrder());
                    // 没复制成功的那几条放回末尾，别让用户再去素材条里挑一遍
                    result.failedSources().forEach(session::add);
                    // 保存可能只改了顺序；点选是跟着照片走的，不能继续用旧格子下标。
                    Path targetSelectionAfterSave =
                            savedSelection(targetSelectionBeforeSave, result);
                    Path arrangeSelectionAfterSave =
                            savedSelection(arrangeSelectionBeforeSave, result);
                    Path stripSelectionAfterSave =
                            savedSelection(stripSelectionBeforeSave, result);
                    restoreSelection(targetPanel, targetSelectionAfterSave, session);
                    restoreSelection(arrangePanel, arrangeSelectionAfterSave, session);
                    if (stripSelectionAfterSave != null
                            && session.indexOf(stripSelectionAfterSave) >= 0) {
                        targetFilmstrip.selectPhoto(stripSelectionAfterSave);
                    } else {
                        targetFilmstrip.selectPhoto(null);
                    }
                    targetPanel.reloadThumbnails();
                    arrangePanel.reloadThumbnails();
                    trashPanel.reloadThumbnails();
                    targetFilmstrip.reloadThumbnails();
                    targetPanel.refresh();
                    arrangePanel.refresh();
                    trashPanel.refresh();
                    targetFilmstrip.refresh();
                    // 保存可能改名、改写像素，旧缓存里的“同一路径”已经不代表同一张图。
                    viewer.clearFullImageCache();
                    if (shownBeforeSave != null) {
                        Path finalPhoto = result.savedLocations().get(
                                shownBeforeSave.toAbsolutePath().normalize());
                        if (finalPhoto != null && Files.isRegularFile(finalPhoto)) {
                            targetPanel.selectPhoto(finalPhoto);
                            arrangePanel.selectPhoto(finalPhoto);
                            targetFilmstrip.selectPhoto(finalPhoto);
                            // 重新读保存后的文件，编辑状态已经在 seedExisting 里清空了。
                            showPhotoInViewer(finalPhoto);
                        } else if (!Files.isRegularFile(shownBeforeSave)) {
                            // 当前照片被这次保存删掉（或被外部删掉）时，大图不能
                            // 继续拿着内存里的旧像素冒充它还在。
                            showPhotoInViewer(null);
                        }
                    }
                    handleSaveFinished(result);
                });
            });
        } catch (RejectedExecutionException e) {
            saving = false;
            historyChanged();
            closing = false;
            onSaveFinished.accept(new Saver.Result(0, 0, 0, 0, List.of(), Map.of(),
                    List.of(), List.of("程序正在关闭，保存没有启动。")));
            return false;
        }
        return true;
    }

    /** 保存结束后的回调（外部负责把结果告诉用户）。 */
    public void setOnSaveFinished(Consumer<Saver.Result> listener) {
        this.onSaveFinished = listener == null ? result -> { } : listener;
    }

    /** 保存结束时的内部收尾：如果之前排了"保存完要接着做的事"，就接着做。 */
    private void handleSaveFinished(Saver.Result result) {
        // 先交给外部：它可能会弹个"哪几张没成"的报告。
        onSaveFinished.accept(result);
        if (result.hasProblems()) {
            // 有失败时不能继续退出或切工作区：待复制条目刚才虽然放回了中间商，
            // 一旦窗口关掉，这份挑选结果仍然会丢。先留在原处，让用户看到报告、
            // 处理磁盘问题后再决定重试还是放弃。
            afterSave = null;
            closing = false;
            resumePendingExternalRefresh();
            return;
        }
        Runnable next = afterSave;
        afterSave = null;
        if (next != null) {
            next.run();
        } else {
            resumePendingExternalRefresh();
        }
    }

    /** 保存期间攒下的外部变化，在保存收尾后补做一次对账。 */
    private void resumePendingExternalRefresh() {
        if (!pendingTargetRefresh) {
            return;
        }
        pendingTargetRefresh = false;
        Session session = targetPanel.session();
        if (session != null) {
            refreshTargetFromDisk(session.targetFolder());
        }
    }

    /**
     * 键盘快捷键。
     *
     * <p>挂在 {@code WHEN_IN_FOCUSED_WINDOW} 上，所以不管焦点在素材条还是目录树上都管用。
     * 工具栏上那几个按钮的接线等界面那一侧定下来再接。
     */
    private void bindKeys() {
        for (Shortcut shortcut : SHORTCUTS) {
            bind(shortcut.key(), shortcut.actionName(), actionFor(shortcut.actionName()));
        }
    }

    /** 某个动作名对应的活儿。名字和 {@link #SHORTCUTS} 里写的一一对应。 */
    private Runnable actionFor(String actionName) {
        return switch (actionName) {
            case "addToTarget", "addToTargetAlt" -> this::addCurrentPhotoToTarget;
            case "undo" -> this::undo;
            case "redo" -> this::redo;
            case "previousPhoto" -> () -> filmstrip.moveCurrent(-1);
            case "nextPhoto" -> () -> filmstrip.moveCurrent(1);
            default -> throw new IllegalArgumentException("快捷键表里有没接上动作的名字：" + actionName);
        };
    }

    /**
     * 中间商里有没有还没保存的改动（{@link MainFrame} 关窗口时用来决定要不要问一句）。
     *
     * <p>还没有目标文件夹时算"干净"——没有中间商，也就没有可保存的东西。
     */
    public boolean isDirty() {
        Session session = targetPanel.session();
        return session != null && session.isDirty();
    }

    /** 眼下这次保存是不是"保存完就退出 / 换工作区"的一部分。 */
    public boolean isClosingAfterSave() {
        return afterSave != null;
    }

    /**
     * 请求关闭程序：点 ×、菜单"退出"都走这里。
     *
     * <p>放在 {@code MainPanel} 而不是主窗口里，是因为它要摸的是"有没有改动""保存完成没"
     * 这些界面自己的状态；主窗口只负责把"要走了"这件事接过去。
     */
    public void requestClose() {
        if (closing) {
            return; // 已经在走关闭流程了，别问第二遍
        }
        closing = true;
        // 关窗口这条路的收尾就是"退出"，和换工作区共用同一套
        //「有改动先问 → 选保存就等保存 → 再执行」的逻辑
        confirmChangesOrAbort(this::exitAfterSave);
    }

    /**
     * 换工作区（或换目标文件夹）之前先处理掉没保存的改动。
     *
     * <p>用户的决定：这里复用关窗口那套「保存 / 不保存 / 取消」——
     * 选保存要等保存真的跑完再切（和关窗口一样，不能问完就跑）；
     * 选不保存就直接切，磁盘保持原样；把对话框关掉算取消，什么都不做。
     */
    public void switchWorkspace(Workspace newWorkspace) {
        if (!isDirty()) {
            applyWorkspace(newWorkspace);
            return;
        }
        confirmChangesOrAbort(() -> applyWorkspace(newWorkspace));
    }

    /**
     * 「有没保存的改动」的通用收尾：没改动就直接做；有改动先问一句；
     * 选保存就等保存真跑完再做。
     *
     * <p>关窗口、换工作区都走这条——用户明确要求复用这一套，
     * 免得两处行为悄悄长歪（一处等保存、一处不等）。
     *
     * @param action 该做的事情：退出程序，或者切到新工作区
     */
    private void confirmChangesOrAbort(Runnable action) {
        if (!isDirty()) {
            action.run();
            return;
        }
        int choice = confirmSaveOnClose.apply(this);
        if (choice == JOptionPane.CLOSED_OPTION) {
            closing = false;
            return; // 把对话框直接关掉：当作"取消"，什么都不做
        }
        if (choice == JOptionPane.NO_OPTION) {
            // 不保存：中间商连同垃圾桶一起消失，磁盘上什么都没变（见《软件设计》第 7 节）
            action.run();
            return;
        }

        // 用户已经按过一次保存，后台还在跑：不要再排第二份快照，
        // 把这次的收尾挂到那一次上即可。否则关窗口/换工作区会落空。
        if (saving) {
            Runnable previous = afterSave;
            afterSave = () -> {
                if (previous != null) {
                    previous.run();
                }
                action.run();
            };
            return;
        }

        Runnable previous = afterSave;
        afterSave = () -> {
            // 万一以后真有两条路同时走到这儿，先来后到都执行，别丢事
            if (previous != null) {
                previous.run();
            }
            action.run();
        };
        if (!save()) {
            // 连快照都没拍成（回调刚刚已经说清原因了）：把待办退回原样，
            // 否则状态会卡在"点了保存、等一个永远不来的回调"上
            afterSave = previous;
            if (closing && !shutDown) {
                closing = false;
            }
        }
    }

    /** 关窗口那条路的收尾：真的退出。 */
    private void exitAfterSave() {
        onExit.run();
    }

    /** 关闭流程走完后真正退出的动作（主窗口拿去收线程池、关窗口）。 */
    public void setOnExit(Runnable listener) {
        this.onExit = listener == null ? () -> { } : listener;
    }

    /** 换掉"要不要保存"的提问方式；给测试用的（默认弹对话框）。 */
    void setConfirmSaveOnClose(Function<java.awt.Component, Integer> confirm) {
        this.confirmSaveOnClose = confirm == null ? MainPanel::askToSave : confirm;
    }

    /**
     * 默认的问法：和别处同一套深色消息框，两个按钮。
     *
     * <p>返回值取 {@link JOptionPane} 那几样：YES 保存、NO 不保存、CLOSED 取消
     * （直接关掉窗口或按 Esc 就是取消）。
     */
    private static int askToSave(java.awt.Component owner) {
        // 传进来的可能是面板，也可能直接是个窗口（关窗流程与测试两种叫法）
        java.awt.Window window = owner instanceof java.awt.Window w
                ? w
                : SwingUtilities.getWindowAncestor(owner);
        int picked = MessageDialog.showChoice(window,
                "还没保存",
                "目标文件夹里还有没保存的改动。\n关掉之前要保存吗？（选“不保存”的话，文件保持原样、什么都不会丢）",
                "保存", "不保存");
        return switch (picked) {
            case 0 -> JOptionPane.YES_OPTION;
            case 1 -> JOptionPane.NO_OPTION;
            default -> JOptionPane.CLOSED_OPTION; // 直接关掉窗口 = 取消
        };
    }

    /** 问一次"要不要保存"；给测试直接验按钮行为用。 */
    static int askToSaveForTest(java.awt.Component owner) {
        return askToSave(owner);
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

    /** 底栏当前实际高度；验证窗口缩放不会把它拉高。 */
    int bottomAreaHeightForTest() {
        return bottomArea.getHeight();
    }

    /** 模拟用户把底栏拖到指定高度。 */
    void setBottomAreaHeightForTest(int height) {
        int location = outerSplit.getHeight() - height - outerSplit.getDividerSize();
        outerSplit.setDividerLocation(Math.max(0, location));
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

    /** 垃圾桶面板，给测试用。 */
    TrashPanel trashPanelForTest() {
        return trashPanel;
    }

    /** 展开排序模式中间那块面板，给测试用。 */
    TargetPanel arrangePanelForTest() {
        return arrangePanel;
    }

    /** 整理模式底部的目标照片带，给测试用。 */
    TargetFilmstripPanel targetFilmstripForTest() {
        return targetFilmstrip;
    }

    /** 大图面板，给测试用。 */
    ViewerPanel viewerForTest() {
        return viewer;
    }

    /** 目录树，给测试用。 */
    SourceTreePanel sourceTreeForTest() {
        return sourceTree;
    }

    /** 关窗口时收掉后台取图的线程池。 */
    public void shutdown() {
        if (shutDown) {
            return;
        }
        shutDown = true;
        sourceWatcher.close();
        targetWatcher.close();
        sourceTree.shutdown();
        thumbnailLoader.shutdown();
        targetLoader.shutdown(); // 之前漏了这只，关窗口后它的线程还活着、还攥着文件
        scanPool.shutdownNow();
        savePool.shutdownNow();
    }

    private JComponent leftColumn() {
        sourceTree.setPreferredSize(new Dimension(220, 400));
        return sourceTree;
    }

    private void toggleMode() {
        setMode(mode == ViewMode.BROWSE ? ViewMode.ARRANGE : ViewMode.BROWSE);
    }
}

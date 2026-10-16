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
import java.util.function.Function;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
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

    /**
     * 键盘快捷键表：**这个程序里所有的键都在这一处**。
     *
     * <p>集中成一张表是为了改着方便：想换键就改这里的 {@code KeyStroke}，
     * 不用去翻散落在各处的 {@code bind()} 调用。
     *
     * <p>"追加到末尾"给了两个键：空格是挑片时最顺手的那颗，回车是很多工具里
     * "确认加上去"的习惯。它们走的是同一个动作。
     */
    private static final List<Shortcut> SHORTCUTS = List.of(
            new Shortcut("addToTarget", KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)),
            new Shortcut("addToTargetAlt", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)),
            new Shortcut("undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)),
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

    /** 这一轮关闭流程里，保存是不是跑完了。下一次 saveFinished 就是收尾信号。 */
    private boolean saveFinishedDuringClose;

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
        targetLoader = new ThumbnailLoader(thumbnailService);
        targetPanel = new TargetPanel(targetLoader);
        arrangePanel = new TargetPanel(targetLoader);
        arrangePanel.setThumbnailWidth(ARRANGE_THUMBNAIL_WIDTH);
        targetPanel.setOnToggleExpanded(this::toggleMode);
        arrangePanel.setOnToggleExpanded(this::toggleMode);
        viewer = new ViewerPanel(thumbnailLoader);
        // 点目标文件夹里的某张，大图就换成它
        targetPanel.setOnPhotoChosen(viewer::showPhoto);
        arrangePanel.setOnPhotoChosen(viewer::showPhoto);

        // 从素材条、或者从正在看的大图上，都可以直接把照片拖到目标文件夹面板里。
        // 拖动时跟着鼠标走的那张图：素材条用已经解好的缩略图，大图临时缩一张
        DragOut.install(filmstrip, filmstrip::currentPhoto, filmstrip::currentThumbnail,
                () -> true, this::addToTargetAt);
        // 拖动是"拖照片"还是"搬画面"，由工具栏上的选择 / 平移工具决定
        DragOut.install(viewer, viewer::photo, viewer::ghostThumbnail,
                viewer::isDragOutEnabled, this::addToTargetAt);
        toolBar.setOnToolChanged(viewer::setCursorTool);

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
                SwingUtilities.invokeLater(() -> {
                    if (workspace != null && folder.equals(workspace.sourceRoot())) {
                        onFolderOpened.accept(folder, -1);
                    }
                });
                return;
            }

            SwingUtilities.invokeLater(() -> {
                if (workspace == null || !folder.equals(workspace.sourceRoot())) {
                    return; // 扫的是旧的根目录：用户已经换过工作区，这批结果作废
                }
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
        long myRound = ++targetLoadRound; // 换目标文件夹 = 开新的一轮，旧结果作废
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
                if (myRound != targetLoadRound) {
                    return; // 这一轮已经过期：用户换过目标文件夹了
                }
                Session session = new Session(target, NumberingRule.DEFAULT);
                session.seedExisting(files);
                targetPanel.setSession(session);
                arrangePanel.setSession(session);
            });
        });
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
        }
    }

    /** Ctrl+Z：撤销上一步（挑入、挪位、丢垃圾桶都算）。 */
    private void undo() {
        Session session = targetPanel.session();
        if (!saving && session != null && session.undo()) {
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
     *
     * <p>拍快照这一步本身（也就是 {@link Saver.Plan#from}）必须在这里、在界面线程上做完，
     * 它可能因为读不了目录而抛异常。真抛了就当这次保存没启动：返回 false，
     * 调用方（例如"关窗口时问要不要保存"那个流程）才知道自己该等着，而不是空等一场。
     *
     * @return 是否真的启动了保存；false 表示这次连快照都没拍成
     */
    public boolean save() {
        Session session = targetPanel.session();
        if (session == null) {
            onSaveFinished.accept(new Saver.Result(0, 0, 0, List.of(),
                    List.of(), List.of("还没有选目标文件夹，没有可以保存的改动。")));
            return false;
        }
        if (session.isEmpty()) {
            onSaveFinished.accept(new Saver.Result(0, 0, 0, List.of(),
                    List.of(), List.of("目标文件夹里什么都没有，没有可以保存的改动。")));
            return false;
        }

        // 在界面线程上先拍一份快照：保存跑在后台，而中间商是活的，
        // 不拍的话你在保存期间再挑一张，后台就会读到一半的数据
        Saver.Plan plan;
        try {
            plan = Saver.Plan.from(session);
        } catch (RuntimeException e) {
            onSaveFinished.accept(new Saver.Result(0, 0, 0, List.of(), List.of(),
                    List.of("保存没能开始：" + e.getMessage())));
            return false;
        }
        saving = true; // 从这一刻起到下面的回调回来之前，不许再改中间商
        savePool.submit(() -> {
            Saver.Result result = new Saver().save(plan);
            SwingUtilities.invokeLater(() -> {
                saving = false; // 中间商马上要重建，此刻起放行——重建之后磁盘就是新的基准
                // 保存之后磁盘就是真相：按真实结果重建中间商，顺序保留、历史清空
                session.seedExisting(result.finalOrder());
                // 没复制成功的那几条放回末尾，别让用户再去素材条里挑一遍
                result.failedSources().forEach(session::add);
                targetPanel.refresh();
                arrangePanel.refresh();
                handleSaveFinished(result);
            });
        });
        return true;
    }

    /** 保存结束后的回调（外部负责把结果告诉用户）。 */
    public void setOnSaveFinished(Consumer<Saver.Result> listener) {
        this.onSaveFinished = listener == null ? result -> { } : listener;
    }

    /** 保存结束时的内部收尾：如果这次保存是关闭流程的一部分，就接着把程序关掉。 */
    private void handleSaveFinished(Saver.Result result) {
        // 先交给外部：它可能会弹个"哪几张没成"的报告。关窗口走的那次不弹，
        // 由外部自己看 saveFinishedDuringClose 决定（见 MainFrame.saveFinished）。
        onSaveFinished.accept(result);
        if (saveFinishedDuringClose) {
            saveFinishedDuringClose = false;
            onExit.run();
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

    /**
     * 眼下这次保存是不是"关窗口"流程的一部分。
     *
     * <p>给外部用的：关窗口的那次保存结束后程序马上就走，这时候再弹"保存完了"的对话框，
     * 用户只会看到它闪一下——不如干脆别弹。
     */
    public boolean isClosingAfterSave() {
        return saveFinishedDuringClose;
    }

    /**
     * 请求关闭程序：点 ×、菜单"退出"都走这里。
     *
     * <p>放在 {@code MainPanel} 而不是主窗口里，是因为它要摸的是"有没有改动""保存完成没"
     * 这些界面自己的状态；主窗口只负责把"要走了"这件事接过去。
     *
     * <p>三步：没改动就直接走；有改动先问一句；选了保存就等保存真跑完再走。
     * 保存是后台活儿，所以"退出"必须挂在保存完成的回调上，而不是问完就走——
     * 那样等于在保存还没写完磁盘时把程序撤了。
     */
    public void requestClose() {
        if (closing) {
            return; // 已经在走关闭流程了，别问第二遍
        }
        if (!isDirty()) {
            onExit.run();
            return;
        }

        int choice = confirmSaveOnClose.apply(this);
        if (choice == JOptionPane.CLOSED_OPTION) {
            return; // 把对话框直接关掉：当作"取消"，程序留着
        }
        if (choice == JOptionPane.NO_OPTION) {
            // 不保存：中间商连同垃圾桶一起消失，磁盘上什么都没变（见《软件设计》第 7 节）
            onExit.run();
            return;
        }

        closing = true;
        saveFinishedDuringClose = true;
        if (!save()) {
            // 连快照都没拍成（回调刚刚已经说清原因了）：把状态退回原样，
            // 否则窗口会卡在"点了保存、等一个永远不来的回调"上
            closing = false;
            saveFinishedDuringClose = false;
        }
    }

    /** 关闭流程走完后真正退出的动作（主窗口拿去收线程池、关窗口）。 */
    public void setOnExit(Runnable listener) {
        this.onExit = listener == null ? () -> { } : listener;
    }

    /** 换掉"要不要保存"的提问方式；给测试用的（默认弹对话框）。 */
    void setConfirmSaveOnClose(Function<java.awt.Component, Integer> confirm) {
        this.confirmSaveOnClose = confirm == null ? MainPanel::askToSave : confirm;
    }

    /** 默认的问法。返回值取 {@link JOptionPane} 那几样：YES 保存、NO 不保存、CLOSED 取消。 */
    private static int askToSave(java.awt.Component owner) {
        JButton save = new JButton("保存");
        JButton discard = new JButton("不保存");
        return JOptionPane.showOptionDialog(owner,
                "目标文件夹里还有没保存的改动。\n关掉之前要保存吗？（选“不保存”的话，文件保持原样、什么都不会丢）",
                "还没保存",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, new Object[] {save, discard}, save);
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
        thumbnailLoader.shutdown();
        targetLoader.shutdown(); // 之前漏了这只，关窗口后它的线程还活着、还攥着文件
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

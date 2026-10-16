package com.photoselector.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;

import com.photoselector.io.PhotoFiles;
import com.photoselector.io.ThumbnailService;
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

    /** 底栏（照片条）的默认高度。 */
    private static final int BOTTOM_HEIGHT = 170;

    /** 素材条里缩略图的高度。 */
    private static final int FILMSTRIP_THUMBNAIL_HEIGHT = 120;

    private final ToolBarPanel toolBar = new ToolBarPanel();

    private final ThumbnailService thumbnailService = new ThumbnailService(ThumbnailService.defaultCacheDir());
    private final ThumbnailLoader thumbnailLoader = new ThumbnailLoader(thumbnailService);
    private final FilmstripPanel filmstrip = new FilmstripPanel(thumbnailLoader);

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
        super(new BorderLayout());
        setBackground(Theme.PANEL);

        buildAreas();

        // 三道分隔条：左栏 | 中右，中栏 | 右栏，主体 | 底栏，全都可以拖着调整
        centerRight = Splitters.horizontal(centerArea, rightArea, 1.0);
        mainSplit = Splitters.horizontal(leftColumn(), centerRight, 0.0);
        outerSplit = Splitters.vertical(mainSplit, bottomArea, 1.0);

        toolBar.modeButton().addActionListener(e -> toggleMode());

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

        bottomArea.add(createFilmstripScrollPane(), ViewMode.BROWSE.name());
        bottomArea.add(placeholder("目标文件夹的照片", 100, 170, Theme.PANEL), ViewMode.ARRANGE.name());
    }

    /** 把自绘的素材条装进滚动条里。滚动时通知它重算该取哪些缩略图。 */
    private JScrollPane createFilmstripScrollPane() {
        JScrollPane scroll = new JScrollPane(filmstrip,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.getHorizontalScrollBar().setUnitIncrement(40); // 滚一格别只挪几个像素
        scroll.getViewport().addChangeListener(e -> filmstrip.requestVisibleThumbnails());
        scroll.setPreferredSize(new Dimension(100, BOTTOM_HEIGHT));
        return scroll;
    }

    /**
     * 打开一个素材文件夹，把里面的照片放进底部素材条。
     *
     * @return 这个文件夹里有多少张能读的照片
     */
    public int showFolder(Path folder) throws IOException {
        List<Path> photos = PhotoFiles.imagesIn(folder);
        filmstrip.setPhotos(photos, FILMSTRIP_THUMBNAIL_HEIGHT);
        return photos.size();
    }

    /** 关窗口时收掉后台取图的线程池。 */
    public void shutdown() {
        thumbnailLoader.shutdown();
    }

    private JComponent leftColumn() {
        return placeholder("素材目录树", 190, 400, Theme.PANEL);
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

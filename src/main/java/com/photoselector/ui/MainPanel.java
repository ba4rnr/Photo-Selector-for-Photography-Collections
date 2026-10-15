package com.photoselector.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;

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

    private final ToolBarPanel toolBar = new ToolBarPanel();

    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerArea = new JPanel(centerCards);

    private final CardLayout rightCards = new CardLayout();
    private final JPanel rightArea = new JPanel(rightCards);

    private final CardLayout bottomCards = new CardLayout();
    private final JPanel bottomArea = new JPanel(bottomCards);

    private ViewMode mode = ViewMode.BROWSE;

    public MainPanel() {
        super(new BorderLayout());
        setBackground(Theme.PANEL);

        buildAreas();

        // 中栏与右栏可左右拖动分配宽度；左栏与它们之间同理
        JSplitPane centerRight = split(centerArea, rightArea, 1.0);
        JSplitPane mainSplit = split(leftColumn(), centerRight, 0.0);

        toolBar.modeButton().addActionListener(e -> toggleMode());

        add(toolBar, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);
        add(bottomArea, BorderLayout.SOUTH);

        setMode(ViewMode.BROWSE);
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

        bottomArea.add(placeholder("素材照片 · 按子文件夹分组，每组可收起", 100, 170, Theme.PANEL),
                ViewMode.BROWSE.name());
        bottomArea.add(placeholder("目标文件夹的照片", 100, 170, Theme.PANEL), ViewMode.ARRANGE.name());
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

    private static JSplitPane split(JComponent left, JComponent right, double resizeWeight) {
        JSplitPane pane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        pane.setBorder(null);
        pane.setDividerSize(4);
        pane.setContinuousLayout(true); // 拖动分隔条时实时重排，不松手才动
        pane.setBackground(Theme.PANEL);
        pane.setResizeWeight(resizeWeight); // 窗口变大时，多出来的宽度分给谁
        return pane;
    }

    private void toggleMode() {
        setMode(mode == ViewMode.BROWSE ? ViewMode.ARRANGE : ViewMode.BROWSE);
    }
}

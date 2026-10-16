package com.photoselector.ui;

import java.awt.AWTEvent;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

import com.photoselector.ui.theme.Theme;

/**
 * 分隔条的统一定制：所有区域之间的分隔线都在这里造，样子和手感保持一致。
 *
 * <p>平时是一条几乎看不见的细线；鼠标一靠近就变成一条细白线，提示"这里可以拖"。
 * 之所以要"靠近"而不是"压上去"，是因为分隔条只有 4 像素宽，
 * 等指针真落在它上面才给反馈，手感就太迟了。
 */
final class Splitters {

    /** 分隔条宽度：太宽会显得笨重，太窄不好抓，4 像素是两者的折中。 */
    private static final int SIZE = 4;

    /** 鼠标离分隔条多少像素以内，就开始给提示。 */
    private static final int NEAR = 4;

    private static final ProximityTracker TRACKER = new ProximityTracker();

    private Splitters() {
    }

    /** 竖直分隔条，左右两块。 */
    static JSplitPane horizontal(JComponent left, JComponent right, double resizeWeight) {
        return make(JSplitPane.HORIZONTAL_SPLIT, left, right, resizeWeight);
    }

    /** 水平分隔条，上下两块。 */
    static JSplitPane vertical(JComponent top, JComponent bottom, double resizeWeight) {
        return make(JSplitPane.VERTICAL_SPLIT, top, bottom, resizeWeight);
    }

    private static JSplitPane make(int orientation, JComponent first, JComponent second,
                                   double resizeWeight) {
        JSplitPane pane = new JSplitPane(orientation, first, second);
        // 先换外观（这一步会把尺寸等设置重置成默认值），我们自己的设置随后覆盖上去
        pane.setUI(new ThinDividerUI());
        pane.setBorder(null);
        pane.setDividerSize(SIZE);
        pane.setContinuousLayout(true); // 拖动过程中实时重排，而不是松手才动
        pane.setResizeWeight(resizeWeight); // 窗口变大时，多出来的空间分给谁
        pane.setBackground(Theme.PANEL);
        return pane;
    }

    /**
     * 鼠标感应器：全局监听鼠标移动，谁离分隔条够近就点亮谁。
     *
     * <p>为什么非得用全局监听？鼠标移动事件只会发给指针正下方那一个组件，
     * 而"靠近"往往意味着指针还在旁边的面板上——那些事件分隔条根本收不到。
     * 想不侵入各个面板就实现"靠近即提示"，只能从全局层面统一观察。
     */
    private static final class ProximityTracker implements AWTEventListener {

        private final List<ThinDivider> dividers = new CopyOnWriteArrayList<>();
        private boolean installed;

        void watch(ThinDivider divider) {
            if (!installed) {
                installed = true;
                Toolkit.getDefaultToolkit().addAWTEventListener(this,
                        AWTEvent.MOUSE_MOTION_EVENT_MASK);
            }
            dividers.add(divider);
        }

        void forget(ThinDivider divider) {
            dividers.remove(divider);
        }

        @Override
        public void eventDispatched(AWTEvent event) {
            if (!(event instanceof MouseEvent mouse)) {
                return;
            }
            for (ThinDivider divider : dividers) {
                divider.updateProximity(mouse.getLocationOnScreen());
            }
        }
    }

    /** 只画一条细线的分隔条。 */
    private static final class ThinDivider extends BasicSplitPaneDivider {

        /** 鼠标是否在附近。 */
        private boolean near;

        /** 是否正被按住拖动。 */
        private boolean dragging;

        /** 当前是否该显示那条白线。 */
        private boolean lit;

        ThinDivider(BasicSplitPaneUI ui) {
            super(ui);
            setBorder(null);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    dragging = true;
                    refresh();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragging = false;
                    refresh();
                }
            });
        }

        // 分隔条在屏幕上出现和消失时向感应器登记 / 注销，
        // 免得已经关掉的窗口还留在监听名单里
        @Override
        public void addNotify() {
            super.addNotify();
            TRACKER.watch(this);
        }

        @Override
        public void removeNotify() {
            TRACKER.forget(this);
            super.removeNotify();
        }

        /** 由感应器在每次鼠标移动时调用：算出指针离这条分隔条有多远。 */
        void updateProximity(Point screenPoint) {
            if (!isShowing()) {
                return;
            }
            Point local = new Point(screenPoint);
            SwingUtilities.convertPointFromScreen(local, this);

            near = local.x >= -NEAR && local.y >= -NEAR
                    && local.x <= getWidth() + NEAR && local.y <= getHeight() + NEAR;
            refresh();
        }

        private void refresh() {
            boolean value = near || dragging; // 拖动时指针可能瞬间滑开，白线要保持亮着
            if (lit != value) {
                lit = value;
                repaint();
            }
        }

        @Override
        public void paint(Graphics g) {
            boolean horizontalSplit = getBasicSplitPaneUI().getOrientation() == JSplitPane.HORIZONTAL_SPLIT;

            g.setColor(Theme.PANEL);
            g.fillRect(0, 0, getWidth(), getHeight());

            g.setColor(lit ? Theme.DIVIDER_HINT : Theme.BORDER);
            if (horizontalSplit) {
                g.fillRect(getWidth() / 2, 0, 1, getHeight()); // 竖线
            } else {
                g.fillRect(0, getHeight() / 2, getWidth(), 1); // 横线
            }
        }
    }

    /** 让 JSplitPane 使用上面那条分隔条。 */
    private static final class ThinDividerUI extends BasicSplitPaneUI {
        @Override
        public BasicSplitPaneDivider createDefaultDivider() {
            return new ThinDivider(this);
        }
    }
}

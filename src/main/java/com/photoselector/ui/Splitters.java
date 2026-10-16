package com.photoselector.ui;

import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

import com.photoselector.ui.theme.Theme;

/**
 * 分隔条的统一定制：所有区域之间的分隔线都在这里造，样子和手感保持一致。
 *
 * <p>平时是一条几乎看不见的细线；指针压到线上时，那条线变成白色，提示"这里可以拖"。
 *
 * <p>反馈只在指针**真正落在分隔条上**时出现，而不是"靠近就算"：
 * 那 4 像素宽的带子正是它能拖动的范围，白线亮起就等于"现在可以拖了"，
 * 提示范围与实际可操作范围完全重合，不会让人产生错觉。
 */
final class Splitters {

    /** 分隔条宽度：4 像素，够抓住，又不占地方。 */
    private static final int SIZE = 4;

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

    /** 只画一条细线的分隔条。 */
    private static final class ThinDivider extends BasicSplitPaneDivider {

        /** 指针是否正压在分隔条上。 */
        private boolean hovering;

        /** 是否正被按住拖动。 */
        private boolean dragging;

        /** 当前是否该显示那条白线。 */
        private boolean lit;

        ThinDivider(BasicSplitPaneUI ui) {
            super(ui);
            setBorder(null);
            // 分隔条本身就是那 4 像素宽的一条，普通鼠标事件就够用，
            // 不必再从全局去监听指针位置
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovering = true;
                    refresh();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovering = false;
                    refresh();
                }

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

        private void refresh() {
            // 拖动时指针可能一瞬间滑出这条窄带，白线要保持亮着，免得一闪一闪
            boolean value = hovering || dragging;
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

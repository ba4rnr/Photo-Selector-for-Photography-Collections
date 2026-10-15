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
 * <p>平时是一条几乎看不见的细线，鼠标移上去或按住拖动时变成暖橙色——
 * 这就是 Capture One 的做法：不打扰视线，但一找就找得到。
 */
final class Splitters {

    /** 分隔条宽度：够手指抓，又不至于显得笨重。 */
    private static final int SIZE = 6;

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

        private boolean lit;

        ThinDivider(BasicSplitPaneUI ui) {
            super(ui);
            setBorder(null);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    setLit(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setLit(false);
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    setLit(true); // 按住不放时也保持高亮，哪怕指针短暂滑出去
                }
            });
        }

        private void setLit(boolean value) {
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

            g.setColor(lit ? Theme.ACCENT : Theme.BORDER);
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

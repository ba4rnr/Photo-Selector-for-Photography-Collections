package com.photoselector.ui;

import java.awt.event.KeyEvent;

import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;

/**
 * 滚动面板的按键：把 ← / → 从"焦点组件之祖先"这一层摘掉。
 *
 * <p>为什么非摘不可：{@code JScrollPane} 默认把 ← / → 绑在这一层
 * （`unitScrollLeft` / `unitScrollRight`），而 Swing 查按键的顺序是
 * 组件自己 → 焦点组件的祖先链 → 整个窗口。祖先链先命中，窗口级的
 * "上一张 / 下一张照片"就永远轮不到——表现就是按方向键只有画面在滑，
 * 当前照片和大图都不换。
 *
 * <p>这个程序里 ← / → 的语义是"换照片"。滚动交给 PageUp / PageDown 和鼠标滚轮，
 * 所以这里只摘这两个键，其余原样保留。
 */
final class ScrollKeys {

    private ScrollKeys() {
    }

    /** 摘掉某个滚动面板上的左右方向键绑定，其余按键不动。 */
    static void dropArrowKeys(JScrollPane scroll) {
        stripArrowKeys(scroll);
    }

    /**
     * 摘掉某个容器上的左右方向键绑定（"焦点组件之祖先"那一层）。
     *
     * <p>不只是滚动面板会抢：{@code JSplitPane} 也把 ← / → 绑在同一层，
     * 作用是"挪分隔条"（positiveIncrement）。素材条就长在分隔条里面，
     * 于是它一点焦点，方向键又被分隔条吃掉了。凡是有这种绑定的容器都得摘。
     */
    static void stripArrowKeys(JComponent component) {
        // 光 remove 不够：这张表挂在外观默认表下面，本层删掉之后
        // get() 会顺着父表又找回来。所以把外观那一份抄成独立表（不挂父表），
        // 去掉左右键，再整体换上去。
        InputMap builtIn = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        InputMap stripped = new InputMap();
        for (KeyStroke key : builtIn.allKeys()) {
            stripped.put(key, builtIn.get(key));
        }
        stripped.remove(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0));
        stripped.remove(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
        component.setInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, stripped);
    }
}

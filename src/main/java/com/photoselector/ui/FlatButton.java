package com.photoselector.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.SwingConstants;

import com.photoselector.ui.theme.Theme;

/**
 * 扁平按钮：对话框里用的小按钮，外观语言与工具栏一致——悬停时底色亮一档，
 * 不让默认外观去画渐变和立体边框。
 *
 * <p>描边是可选项：底部的动作按钮带一圈细边，一眼能认出是按钮；
 * 面包屑那种"看起来像文字"的按钮则不描边，免得一条条边框把路径切得七零八落。
 */
@SuppressWarnings("serial")
final class FlatButton extends JButton {

    private final boolean outlined;

    FlatButton(String text, boolean outlined) {
        super(text);
        this.outlined = outlined;
        setFont(Theme.FONT);
        setForeground(Theme.TEXT);
        setContentAreaFilled(false); // 背景由下面的 paintComponent 自己画
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setHorizontalTextPosition(SwingConstants.CENTER);
        setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();

        Color fill = null;
        if (isEnabled()) {
            if (getModel().isPressed()) {
                fill = Theme.HIGHLIGHT;
            } else if (getModel().isRollover()) {
                fill = outlined ? Theme.HIGHLIGHT : Theme.HOVER;
            } else if (outlined) {
                fill = Theme.HOVER;
            }
        }
        if (fill != null) {
            g2.setColor(fill);
            g2.fillRect(0, 0, getWidth(), getHeight());
        }
        if (outlined) {
            // 键盘焦点用暖橙描边提示；平时那圈边只是陪衬，选 BORDER 最不抢眼
            g2.setColor(isFocusOwner() && isEnabled() ? Theme.ACCENT : Theme.BORDER);
            g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        }
        g2.dispose();

        super.paintComponent(g); // 文字与图标仍然交给 JButton 自己画
    }
}

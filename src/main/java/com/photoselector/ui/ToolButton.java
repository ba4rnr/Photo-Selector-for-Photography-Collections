package com.photoselector.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.SwingConstants;

import com.photoselector.ui.theme.Theme;

/**
 * 工具栏上的按钮：扁平样式，悬停时底色轻轻亮一点，激活时图标和文字转暖橙。
 *
 * <p>为什么不用 JButton 的默认外观？因为默认外观会画渐变和立体边框，
 * 与 Capture One 那种扁平深色的观感对不上。这里把"背景怎么画"这点活儿
 * 从外观实现手里接过来自己做，剩下的文字绘制仍然交给父类。
 *
 * <p>激活态特意<b>不画边框</b>：细边框在 125%、150% 这类缩放比例下，
 * 一条 1 像素的线会落在半个像素上，左右两条边渲染出来宽度常常不一样。
 * Capture One 的做法本来就是"图标和文字变橙"，不靠描边，这里照它来。
 */
@SuppressWarnings("serial")
final class ToolButton extends JButton {

    private boolean active;

    ToolButton(String text, Icon icon) {
        super(text);
        setFont(Theme.FONT_SMALL);
        setForeground(Theme.TEXT);
        setBackground(Theme.PANEL);
        setIcon(icon);
        // 图标在上、文字在下，跟 Capture One 的工具栏一致
        setVerticalTextPosition(SwingConstants.BOTTOM);
        setHorizontalTextPosition(SwingConstants.CENTER);
        setIconTextGap(3);
        setContentAreaFilled(false); // 背景不再由外观画，改由下面的 paintComponent 负责
        setFocusPainted(false);
        setBorderPainted(false);
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        setRolloverEnabled(true);
    }

    /** 标记为"当前激活的工具"，文字转暖橙、背景加亮并描边。 */
    void setActive(boolean active) {
        this.active = active;
        setForeground(active ? Theme.ACCENT : Theme.TEXT);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        if (active) {
            g2.setColor(Theme.HIGHLIGHT);
        } else if (getModel().isRollover()) {
            g2.setColor(Theme.HOVER);
        } else {
            g2.setColor(Theme.PANEL);
        }
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();

        super.paintComponent(g); // 文字仍然交给 JButton 自己画
    }
}

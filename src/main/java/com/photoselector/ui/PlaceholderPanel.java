package com.photoselector.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import com.photoselector.ui.theme.Theme;

/**
 * 占位面板：骨架阶段用来标记"这一块将来放什么"。
 *
 * <p>深色底 + 居中灰字。功能补齐时，这些面板会被真正的组件逐个替换掉。
 *
 * <p>底色按位置区分：只有大图区用最深的 {@code CANVAS}，面板一律用 {@code PANEL}——
 * 这正是 Capture One 的做法，界面因此显得有层次而不是糊成一片黑。
 */
@SuppressWarnings("serial")
final class PlaceholderPanel extends JPanel {

    private final String title;

    PlaceholderPanel(String title, Color background) {
        this.title = title;
        setBackground(background);
        setOpaque(true);
        setBorder(BorderFactory.createLineBorder(Theme.BORDER));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g); // 先让父类把背景刷上

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(Theme.TEXT_MUTED);
        g2.setFont(Theme.FONT);

        var metrics = g2.getFontMetrics();
        int x = Math.max(8, (getWidth() - metrics.stringWidth(title)) / 2);
        int y = Math.max(metrics.getAscent(), (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
        g2.drawString(title, x, y);
        g2.dispose();
    }
}

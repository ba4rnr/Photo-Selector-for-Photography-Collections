package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.Icon;

import org.junit.jupiter.api.Test;

import com.photoselector.ui.theme.Icons;
import com.photoselector.ui.theme.Theme;

/**
 * 图标库的"验货图"：把每个工具的画法分别按"按钮尺寸"和"光标尺寸"画出来。
 *
 * <p>按钮和光标共用 {@link Icons#paintShape}，所以这两列的形状必须一模一样；
 * 图保存在 {@code target/ui-icon-cursors.png}，肉眼扫一眼就知道有没有跑偏。
 */
class IconLibraryRenderTest {

    @Test
    void 每个工具的按钮图标和光标是同一个形状() throws Exception {
        Theme.install();
        int cellWidth = 96;
        int cellHeight = 84;
        BufferedImage sheet = new BufferedImage(
                cellWidth * CursorTool.values().length, cellHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Theme.PANEL);
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setColor(Theme.TEXT);
        g.setFont(Theme.FONT_SMALL);

        int column = 0;
        for (CursorTool tool : CursorTool.values()) {
            int x = column * cellWidth;
            // 左半边：按钮上那枚 18×18 的图标
            Icon icon = iconFor(tool);
            assertEquals(18, icon.getIconWidth(), "按钮图标的尺寸");
            icon.paintIcon(null, g, x + 10, 16);
            // 右半边：光标用的 32×32 大图（和 CursorTool.cursor() 同一个画法）
            paintCursorShape(tool, g, x + 44, 8);
            // 底下写工具名
            g.setColor(Theme.TEXT_MUTED);
            g.drawString(tool.label(), x + 10, cellHeight - 8);
            g.setColor(Theme.TEXT);
            column++;
        }
        g.dispose();

        Path output = Path.of("target", "ui-icon-cursors.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(sheet, "png", output.toFile());
    }

    /** 和 {@link CursorTool#cursor()} 里那段画法一致：深底描边 + 浅色线条。 */
    private static void paintCursorShape(CursorTool tool, Graphics2D g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(x, y);
        g2.scale(32 / 18.0, 32 / 18.0);
        g2.setStroke(new java.awt.BasicStroke(2.8f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        g2.setColor(Theme.CANVAS);
        Icons.paintShape(tool, g2);
        g2.setStroke(new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        g2.setColor(Theme.TEXT);
        Icons.paintShape(tool, g2);
        g2.dispose();
    }

    private static Icon iconFor(CursorTool tool) {
        return switch (tool) {
            case SELECT -> Icons.select();
            case PAN -> Icons.pan();
            case CROP -> Icons.crop();
            case ROTATE -> Icons.rotate();
            case FLIP -> Icons.flip();
        };
    }
}

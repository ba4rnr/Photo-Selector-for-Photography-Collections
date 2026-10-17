package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * 图标库的"验货图"：把工具栏上每个工具的按钮图标各画一遍。
 *
 * <p>图保存在 {@code target/ui-icons.png}，肉眼扫一眼就知道图标有没有跑偏。
 */
class IconLibraryRenderTest {

    @Test
    void 每个工具都有按钮图标() throws Exception {
        Theme.install();
        int cellWidth = 72;
        int cellHeight = 64;
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
            icon.paintIcon(null, g, x + (cellWidth - 18) / 2, 12);
            // 底下写工具名
            g.setColor(Theme.TEXT_MUTED);
            g.drawString(tool.label(), x + 10, cellHeight - 10);
            g.setColor(Theme.TEXT);
            column++;
        }
        g.dispose();

        Path output = Path.of("target", "ui-icons.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(sheet, "png", output.toFile());
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

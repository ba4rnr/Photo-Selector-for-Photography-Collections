package com.photoselector.ui.theme;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.JLabel;

import org.junit.jupiter.api.Test;

/**
 * 组件库总览：把画笔集里的每样东西各画一遍，存成一张图供肉眼核对。
 *
 * <p>和"图标验货图"同一个思路：库里的东西如果只在代码里，没人看得出长什么样；
 * 每次测试顺手渲染一张，图就永远是当前代码的真实样子。
 */
class ComponentLibraryRenderTest {

    @Test
    void 组件库总览图() throws Exception {
        Theme.install();
        int width = 760;
        int height = 460;
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Theme.PANEL);
        g.fillRect(0, 0, width, height);

        g.setColor(Theme.TEXT);
        g.setFont(Theme.FONT);
        g.drawString("组件库总览（Painters / TextStyles / Icons）", 20, 26);

        FontMetrics metrics = new JLabel().getFontMetrics(Theme.FONT_SMALL);

        // 1) 胶囊按钮的三种状态
        g.setColor(Theme.TEXT_MUTED);
        g.drawString("胶囊按钮：激活 / 悬停 / 普通", 20, 60);
        Painters.chip(g, new Rectangle(20, 72, 110, 24), metrics, "激活", true, false);
        Painters.chip(g, new Rectangle(140, 72, 110, 24), metrics, "悬停", false, true);
        Painters.chip(g, new Rectangle(260, 72, 110, 24), metrics, "普通", false, false);

        // 2) 缩略图格子：普通 / 选中 / 占位（还没有图）
        g.drawString("缩略图格子：普通 / 选中 / 占位", 20, 130);
        BufferedImage photo = demoImage(160, 120);
        Painters.thumbnailCell(g, new Rectangle(20, 142, 160, 150), photo, false, "普通", true);
        Painters.thumbnailCell(g, new Rectangle(200, 142, 160, 150), photo, true, "选中", false);
        Painters.thumbnailCell(g, new Rectangle(380, 142, 160, 150), null, false, "还没解出来", true);
        // 这一格要真的画一张竖图：第一版偷懒复用了同一张横图，标签写着"竖着"，
        // 图却是横的——看图的人当然会以为方向逻辑坏了。验货图必须名副其实。
        BufferedImage portrait = demoImage(120, 160);
        Painters.thumbnailCell(g, new Rectangle(560, 142, 160, 150), portrait, false,
                "竖着的照片（120×160）", true);

        // 3) 文字截断
        g.drawString("文字：放得下 / 末尾截断 / 路径中间截断", 20, 350);
        g.setColor(Theme.TEXT);
        // 例子要真的超宽，否则看着像"截断没生效"——第一版就用了两段放得下的文字，
        // 白白怀疑了一回函数（量了一下才知道它们只有 70px / 216px）。
        String longName = "L1008244_IMG_2026_十月_大时代_最终版.JPG";
        String longPath = "C:\\Users\\BA4RNR\\Desktop\\workspaces\\摄影集_大时代_全部素材";
        g.drawString("原文：", 20, 372);
        g.drawString(longName, 70, 372);
        g.drawString("末尾截断：", 20, 392);
        g.drawString(TextStyles.end(longName, metrics, 110), 100, 392);
        g.drawString("中间截断：", 230, 392);
        g.drawString(TextStyles.middle(longPath, metrics, 260), 320, 392);

        // 4) 落点白线
        g.setColor(Theme.TEXT_MUTED);
        g.drawString("拖动的落点线（3 像素白线）", 20, 410);
        Painters.dropLine(g, new Rectangle(250, 398, 3, 30));
        Painters.dropLine(g, new Rectangle(300, 398, 3, 30));

        g.dispose();
        Path output = Path.of("target", "ui-component-library.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(sheet, "png", output.toFile());
        assertTrue(Files.size(output) > 0);
    }

    /** 造一张有方向的示意图，方便看"等比居中"的效果。 */
    private static BufferedImage demoImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new java.awt.Color(0xFF8B16));
        g.fillRect(0, 0, width, height);
        g.setColor(new java.awt.Color(0x191919));
        g.fillOval(width / 6, height / 8, width * 2 / 3, height * 3 / 4);
        g.dispose();
        return image;
    }
}

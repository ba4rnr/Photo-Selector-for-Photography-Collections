package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPopupMenu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.ui.theme.Theme;

/**
 * 菜单弹层的配色测试。
 *
 * <p>系统外观会给弹层画白边、给选中项画蓝底、给快捷键文字上蓝色，
 * 在深色界面上很跳。这几条断言把"不许出现"的颜色钉住，
 * 同时把渲染结果存成图，方便肉眼核对。
 */
class MenuThemeTest {

    @TempDir
    Path tempDir;

    @Test
    void 菜单弹层不该有白边和蓝底() throws Exception {
        Theme.install();

        MainPanel mainPanel = new MainPanel(tempDir.resolve("cache"));
        JMenuBar bar = MainFrame.createMenuBar(mainPanel, () -> { }, () -> { });
        JMenu fileMenu = bar.getMenu(0);
        JPopupMenu popup = fileMenu.getPopupMenu();
        popup.setSize(popup.getPreferredSize());
        layoutTree(popup);

        BufferedImage image = new BufferedImage(Math.max(1, popup.getWidth()),
                Math.max(1, popup.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        popup.paint(g);
        g.dispose();

        Path output = Path.of("target", "ui-menu.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());

        // 白色描边：一条边就是上百个像素，几乎不可能来自别处，所以要求一个都没有
        assertEquals(0, countPixelsNear(image, 0xFFFFFF, 12),
                "弹层里不该出现白色描边（系统外观会画）");
        // 系统外观的蓝底 #6382BF：文字抗锯齿会在笔画边缘留下零星的偏蓝过渡色，
        // 数总量会误判。真正的蓝底是一整片，所以按"某一行里连续的蓝色有多长"来判断。
        assertTrue(longestRunNear(image, 0x6382BF, 24) < 16,
                "菜单项不该用系统外观的蓝底——那会是一整片连着的蓝色");
        mainPanel.shutdown();
    }

    /** 同一行里连续接近目标色的最长长度。成片的底色会很长，文字边缘的杂色很短。 */
    private static int longestRunNear(BufferedImage image, int target, int tolerance) {
        int longest = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            int run = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                if (isNear(image.getRGB(x, y), target, tolerance)) {
                    longest = Math.max(longest, ++run);
                } else {
                    run = 0;
                }
            }
        }
        return longest;
    }

    private static boolean isNear(int rgb, int target, int tolerance) {
        return Math.abs(((rgb >> 16) & 0xFF) - ((target >> 16) & 0xFF)) <= tolerance
                && Math.abs(((rgb >> 8) & 0xFF) - ((target >> 8) & 0xFF)) <= tolerance
                && Math.abs((rgb & 0xFF) - (target & 0xFF)) <= tolerance;
    }

    /**
     * 统计"接近某个颜色"的像素。
     *
     * <p>为什么不用"是否偏蓝"这类粗判据：文字抗锯齿会在笔画边缘留下很饱和的彩色过渡色
     * （实测出现过 #316897 这种），拿色相去判断会把正常渲染也算进去。
     * 直接盯着"系统外观到底用了哪个颜色"更准。
     */
    private static int countPixelsNear(BufferedImage image, int target, int tolerance) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (isNear(image.getRGB(x, y), target, tolerance)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }
}

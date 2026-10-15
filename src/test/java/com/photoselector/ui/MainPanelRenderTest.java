package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

import com.photoselector.ui.theme.Theme;

/**
 * 窗口骨架的冒烟测试：组件能搭起来、能画出一张像样的图。
 *
 * <p>Swing 组件不必真的显示在屏幕上也能绘制——画到一张离屏图片上即可，
 * 所以这个测试不会弹窗，跑完会生成两张截图供人工核对：
 * {@code target/ui-browse.png} 与 {@code target/ui-arrange.png}。
 * 这比"能编译"强得多：布局塌了、颜色没生效，都能从这张图上直接看出来。
 */
class MainPanelRenderTest {

    private static final int WIDTH = 1440;
    private static final int HEIGHT = 900;

    @Test
    void 浏览模式能构建并绘制出非空画面() throws Exception {
        BufferedImage image = render(ViewMode.BROWSE, "ui-browse.png");

        Set<Integer> colors = sampleColors(image);
        assertTrue(colors.size() > 3, "画面应当有多种颜色，说明组件确实画了东西");
        assertTrue(colors.contains(Theme.PANEL.getRGB() & 0xFFFFFF), "应当能看到面板底色");
        assertTrue(colors.contains(Theme.CANVAS.getRGB() & 0xFFFFFF), "应当能看到大图区底色");
    }

    @Test
    void 切到整理模式后仍能正常绘制() throws Exception {
        // 这条断言的价值在于：模式切换这条代码路径真的被执行过一次
        BufferedImage image = render(ViewMode.ARRANGE, "ui-arrange.png");
        assertTrue(sampleColors(image).size() > 3, "整理模式也应当画出内容");
    }

    /** 离屏渲染指定模式下的整个界面，写出 PNG 并返回图像。 */
    private static BufferedImage render(ViewMode mode, String fileName) throws Exception {
        Theme.install();

        MainPanel mainPanel = new MainPanel();
        mainPanel.setMode(mode);

        // 菜单栏平时挂在窗口上，这里和主内容拼进同一个容器，好一起画下来
        JPanel screen = new JPanel(new BorderLayout());
        screen.add(MainFrame.createMenuBar(mainPanel, () -> { }), BorderLayout.NORTH);
        screen.add(mainPanel, BorderLayout.CENTER);
        screen.setSize(WIDTH, HEIGHT);
        layoutTree(screen);

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        screen.paint(g);
        g.dispose();

        Path output = Path.of("target", fileName);
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
        assertTrue(Files.size(output) > 0, "截图应当已经写出");
        return image;
    }

    /** 递归调用 doLayout：让没有真正显示的组件也把布局算出来。 */
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    private static Set<Integer> sampleColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                colors.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return colors;
    }
}

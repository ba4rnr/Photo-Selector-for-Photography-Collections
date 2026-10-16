package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

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

    @Test
    void 工具栏图标能画出来() throws Exception {
        // 图标只有 18 像素，放大三倍另存一张，方便肉眼检查线条画得对不对
        int scale = 3;
        ToolBarPanel toolBar = new ToolBarPanel();
        toolBar.setSize(1000, 64);
        layoutTree(toolBar);

        BufferedImage image = new BufferedImage(1000 * scale, 64 * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Theme.PANEL);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.scale(scale, scale);
        toolBar.paint(g);
        g.dispose();

        Path output = Path.of("target", "ui-toolbar.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
        assertTrue(sampleColors(image).size() > 3, "工具栏应当画出图标和文字");
    }

    @Test
    void 指针压到分隔条上时会亮出白线() throws Exception {
        Theme.install();
        MainPanel mainPanel = new MainPanel();
        mainPanel.setSize(1200, 800);
        layoutTree(mainPanel);

        BasicSplitPaneDivider divider = findFirstDivider(mainPanel);
        assertNotNull(divider, "应当在界面里找到分隔条");
        // 竖着的分隔条宽度是 4，横着的分隔条高度是 4——总之较薄的那一边是 4
        assertEquals(4, Math.min(divider.getWidth(), divider.getHeight()), "分隔条厚度应当是 4 像素");

        BufferedImage plain = paintDivider(divider);
        assertFalse(containsColor(plain, Theme.DIVIDER_HINT), "平时不该有白线");

        // 派发一个"鼠标进入"事件，等价于把指针压到这条线上
        divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_ENTERED,
                System.currentTimeMillis(), 0, 2, 2, 0, false));

        BufferedImage hovered = paintDivider(divider);
        assertNotEquals(fingerprint(plain), fingerprint(hovered), "压上去之后画出来的样子应当变了");
        assertTrue(containsColor(hovered, Theme.DIVIDER_HINT), "此时应当出现那条白线");

        writeDividerSheet(plain, hovered);
    }

    /** 在界面里找出第一根分隔条。 */
    private static BasicSplitPaneDivider findFirstDivider(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JSplitPane pane && pane.getUI() instanceof BasicSplitPaneUI ui) {
                return ui.getDivider();
            }
            if (child instanceof Container nested) {
                BasicSplitPaneDivider found = findFirstDivider(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static BufferedImage paintDivider(BasicSplitPaneDivider divider) {
        BufferedImage image = new BufferedImage(Math.max(1, divider.getWidth()),
                Math.max(1, divider.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        divider.paint(g);
        g.dispose();
        return image;
    }

    private static boolean containsColor(BufferedImage image, Color color) {
        int wanted = color.getRGB() & 0xFFFFFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == wanted) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 把整张图的像素揉成一个数，用来判断"画出来的是不是同一张图"。 */
    private static int fingerprint(BufferedImage image) {
        int hash = 1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                hash = hash * 31 + image.getRGB(x, y);
            }
        }
        return hash;
    }

    /** 把"平时"和"压上去"两张分隔条并排放大另存，方便肉眼比对。 */
    private static void writeDividerSheet(BufferedImage plain, BufferedImage hovered) throws Exception {
        // 分隔条可能又细又长，从中间截一小块来放大就够了
        int cropWidth = Math.min(40, plain.getWidth());
        int cropHeight = Math.min(40, plain.getHeight());
        int x0 = (plain.getWidth() - cropWidth) / 2;
        int y0 = (plain.getHeight() - cropHeight) / 2;

        int scale = 8;
        int gap = 12;
        int stripWidth = cropWidth * scale;
        int stripHeight = cropHeight * scale;

        BufferedImage sheet = new BufferedImage(stripWidth * 2 + gap, stripHeight,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Theme.PANEL);
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.drawImage(plain.getSubimage(x0, y0, cropWidth, cropHeight),
                0, 0, stripWidth, stripHeight, null);
        g.drawImage(hovered.getSubimage(x0, y0, cropWidth, cropHeight),
                stripWidth + gap, 0, stripWidth, stripHeight, null);
        g.dispose();

        ImageIO.write(sheet, "png", Path.of("target", "ui-divider.png").toFile());
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

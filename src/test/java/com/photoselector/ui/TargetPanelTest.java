package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.awt.event.ComponentEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.Origin;
import com.photoselector.core.NumberingRule;
import com.photoselector.core.Session;
import com.photoselector.core.Workspace;
import com.photoselector.io.ThumbnailService;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/**
 * 目标文件夹那一侧的端到端测试：打开工作区 → 目标文件夹里已有的照片成为底色 →
 * 从素材条挑一张进来 → 编号按位置现算。
 */
class TargetPanelTest {

    @TempDir
    Path tempDir;

    @Test
    void 目标文件夹里已有的照片变成底色_新挑的排在后面并按位置编号() throws Exception {
        Theme.install();

        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path shots = Files.createDirectories(source.resolve("001_拍摄"));
        Path picked = TestImages.writeJpeg(shots.resolve("p0.jpg"), 300, 200);
        TestImages.writeJpeg(shots.resolve("p1.jpg"), 300, 200);

        Path target = Files.createDirectories(tempDir.resolve("成片"));
        TestImages.writeJpeg(target.resolve("旧照片A.jpg"), 300, 200);
        TestImages.writeJpeg(target.resolve("旧照片B.jpg"), 300, 200);

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { }); // 别往真实配置里写
        SwingUtilities.invokeAndWait(() ->
                panel.applyWorkspace(new Workspace(source, Set.of(), target)));

        // 扫描和建中间商都在后台，等它们到位
        waitUntil(() -> panel.filmstripGroupCount() == 1, "素材条分组");
        waitUntil(() -> panel.targetPanelForTest().session() != null, "目标文件夹读进来");

        TargetPanel targetPanel = panel.targetPanelForTest();
        assertEquals(2, targetPanel.entryCount(), "目标文件夹里原有的两张成为底色");
        assertEquals("010", targetPanel.nameAt(0), "名字按位置现算，而不是文件现在叫什么");
        assertEquals("020", targetPanel.nameAt(1));
        assertEquals(Origin.EXISTING, targetPanel.session().entryAt(0).origin());

        // 在素材条里点中一张，然后按空格（等价于按下绑定好的那个动作）
        panel.filmstripForTest().setCurrentPhoto(picked);
        SwingUtilities.invokeAndWait(() -> panel.getActionMap().get("addToTarget")
                .actionPerformed(new ActionEvent(panel, 0, "addToTarget")));

        assertEquals(3, targetPanel.entryCount());
        assertEquals("030", targetPanel.nameAt(2), "新挑的接在末尾");
        assertEquals(Origin.PENDING, targetPanel.session().entryAt(2).origin(), "还没复制到磁盘");

        // 撤销把它拿掉
        SwingUtilities.invokeAndWait(() -> panel.getActionMap().get("undo")
                .actionPerformed(new ActionEvent(panel, 0, "undo")));
        assertEquals(2, targetPanel.entryCount());

        // 顺手把目标面板单独渲染一张图，方便看效果
        SwingUtilities.invokeAndWait(() -> {
            targetPanel.setSize(360, 480);
            layoutTree(targetPanel);
        });
        waitUntil(() -> targetPanel.loadedCount() >= 2, "目标面板的缩略图");
        render(targetPanel, Path.of("target", "ui-target-panel.png"));

        panel.shutdown();
    }

    @Test
    void 路径太长时中间截断_盘符和文件夹名都留着() {
        Theme.install();
        java.awt.FontMetrics metrics = new javax.swing.JLabel().getFontMetrics(Theme.FONT_SMALL);

        String path = "C:\\Users\\BA4RNR\\Desktop\\摄影集_大时代";
        String shortened = TargetPanel.shortenMiddle(path, metrics, 170);

        assertTrue(metrics.stringWidth(shortened) <= 170, "截断之后要真的放得下");
        assertTrue(shortened.contains("…"), "超过长度就该用省略号");
        assertTrue(shortened.startsWith("C:\\"), "盘符要留着");
        assertTrue(shortened.endsWith("摄影集_大时代"), "尾部（文件夹名）最有用，不许掐掉");
        assertEquals(path, TargetPanel.shortenMiddle(path, metrics, 4000), "放得下就原样显示");
    }

    @Test
    void 没选目标文件夹时挑不进去也不会崩() throws Exception {
        Theme.install();
        Path source = Files.createDirectories(tempDir.resolve("素材"));

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { });
        SwingUtilities.invokeAndWait(() ->
                panel.applyWorkspace(new Workspace(source, Set.of(), null)));

        panel.getActionMap().get("addToTarget")
                .actionPerformed(new ActionEvent(panel, 0, "addToTarget"));

        assertEquals(0, panel.targetPanelForTest().entryCount());
        panel.shutdown();
    }

    @Test
    void 目标文件夹读不了时要明确报错_而不是当成空的() throws Exception {
        Theme.install();
        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Files.createDirectories(source.resolve("001_拍摄"));
        Path notAFolder = Files.writeString(tempDir.resolve("不是目录.txt"), "x");

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { });
        java.util.concurrent.atomic.AtomicReference<Path> failed =
                new java.util.concurrent.atomic.AtomicReference<>();
        panel.setOnTargetFolderError(failed::set);
        SwingUtilities.invokeAndWait(() ->
                panel.applyWorkspace(new Workspace(source, Set.of(), notAFolder)));

        waitUntil(() -> failed.get() != null, "目标文件夹读取失败的通知");
        assertEquals(notAFolder, failed.get());
        assertNull(panel.targetPanelForTest().session(),
                "读失败时不能建一个假的空中间商");
        panel.shutdown();
    }

    @Test
    void 目标文件夹里的照片多了之后可以上下滚动() throws Exception {
        Theme.install();

        Path target = Files.createDirectories(tempDir.resolve("成片"));
        java.util.List<Path> existing = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            existing.add(TestImages.writeJpeg(target.resolve("p" + i + ".jpg"), 300, 200));
        }

        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        TargetPanel targetPanel = new TargetPanel(loader);
        Session session = new Session(target, NumberingRule.DEFAULT);
        session.seedExisting(existing);

        JScrollPane scroll = targetPanel.wrapInScrollPane();
        SwingUtilities.invokeAndWait(() -> {
            scroll.setSize(360, 300);
            targetPanel.setSession(session);
            layoutTree(scroll);
            // 离屏布局不会自己派发 resize 事件，手动补一个让面板按新宽度重算
            targetPanel.dispatchEvent(new ComponentEvent(targetPanel, ComponentEvent.COMPONENT_RESIZED));
        });

        assertTrue(targetPanel.getPreferredSize().height > 300,
                "内容比视口高，应当出现垂直滚动条");

        Path lastPhoto = existing.get(existing.size() - 1);
        // 先只看得见前几行
        waitUntil(() -> targetPanel.loadedCount() >= 2, "首屏缩略图");
        assertFalse(targetPanel.isLoaded(lastPhoto), "最后一张在首屏之外，这会儿还不该取到");

        // 滚到底部，新露出来的格子也要去取图
        SwingUtilities.invokeAndWait(() -> scroll.getViewport().setViewPosition(
                new java.awt.Point(0, Math.max(0, targetPanel.getPreferredSize().height))));
        // 注意：不能用"张数变多"来判断——滚远之后看不见的那些会被从手里丢掉，
        // 所以总数可能是平的。直接看最后一行那一张有没有到位。
        waitUntil(() -> targetPanel.isLoaded(lastPhoto), "滚到底部之后最后一行也取到图");

        loader.shutdown();
    }

    @Test
    void 拉宽面板时缩略图尺寸不变_变的是间距() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        TargetPanel panel = new TargetPanel(loader);

        SwingUtilities.invokeAndWait(() -> panel.setSize(360, 500));
        int widthBefore = panel.thumbnailWidth();
        int columnsBefore = panel.columnsForTest();
        int spacingBefore = panel.extraSpaceForTest();

        SwingUtilities.invokeAndWait(() -> panel.setSize(760, 500));
        assertEquals(widthBefore, panel.thumbnailWidth(), "缩略图尺寸不该跟着面板宽度变");
        assertTrue(panel.columnsForTest() > columnsBefore, "变宽之后应当能多排几列");
        assertTrue(panel.extraSpaceForTest() > spacingBefore, "多出来的宽度应当分给间距");

        // 尺寸本身由"查看 → 缩略图大小"改
        SwingUtilities.invokeAndWait(() -> panel.setThumbnailWidth(210));
        assertEquals(210, panel.thumbnailWidth());
        assertTrue(panel.columnsForTest() < 4, "调大之后一列放不下那么多了");
        loader.shutdown();
    }

    @Test
    void 换了目标文件夹之后_旧的点选作废() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        TargetPanel panel = new TargetPanel(loader);

        Path first = Files.createDirectories(tempDir.resolve("成片A"));
        java.util.List<Path> many = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            many.add(TestImages.writeJpeg(first.resolve("a" + i + ".jpg"), 300, 200));
        }
        Session sessionA = new Session(first, NumberingRule.DEFAULT);
        sessionA.seedExisting(many);
        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(360, 480);
            panel.setSession(sessionA);
            layoutTree(panel);
        });

        // 点中第 1 张（坐标按面板自己的格位算，别手写——格子有留白和间距）
        int clickX = panel.cellXForTest(0) + 20;
        int clickY = 34 + panel.rowHeightForTest() / 2;
        SwingUtilities.invokeAndWait(() -> panel.dispatchEvent(new java.awt.event.MouseEvent(
                panel, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                java.awt.event.MouseEvent.BUTTON1_DOWN_MASK, clickX, clickY, 1, false,
                java.awt.event.MouseEvent.BUTTON1)));
        assertTrue(panel.selectedPhoto() != null, "点过之后应当有选中的照片");

        // 换成一个只有两张的目标文件夹
        Path second = Files.createDirectories(tempDir.resolve("成片B"));
        Session sessionB = new Session(second, NumberingRule.DEFAULT);
        sessionB.seedExisting(java.util.List.of(
                TestImages.writeJpeg(second.resolve("b0.jpg"), 300, 200),
                TestImages.writeJpeg(second.resolve("b1.jpg"), 300, 200)));
        SwingUtilities.invokeAndWait(() -> panel.setSession(sessionB));

        // 旧的点选必须作废：不然它会指到新序列里一张不相干的照片上
        assertEquals(null, panel.selectedPhoto(),
                "换了目标文件夹，旧的点选不该还挂着");

        loader.shutdown();
    }

    @Test
    void 落点落在换行处_要算在上一行的末尾() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        TargetPanel panel = new TargetPanel(loader);
        Path folder = Files.createDirectories(tempDir.resolve("成片"));
        java.util.List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            files.add(TestImages.writeJpeg(folder.resolve("p" + i + ".jpg"), 300, 200));
        }
        Session session = new Session(folder, NumberingRule.DEFAULT);
        session.seedExisting(files);

        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(360, 480);
            panel.setSession(session);
            layoutTree(panel);
        });
        assertEquals(2, panel.columnsForTest(), "这个宽度下应当排两列");

        int rowHeight = panel.rowHeightForTest();
        int lastCellRight = panel.cellRightForTest(1);
        int firstCellLeft = panel.cellXForTest(0) + 2;
        int firstRowMid = 34 + rowHeight / 2; // 34 = 表头高度，第一行从它下面开始
        int secondRowMid = 34 + rowHeight + rowHeight / 2;

        // 第一行最后一个格子的右半边：插在它后面，也就是这一行的末尾 → 下标 2
        assertEquals(2, panel.dropIndexAt(new java.awt.Point(lastCellRight - 2, firstRowMid)));
        // 第二行第一格的左半边：光标按视觉是在第二行开头，但照片应当接在第一行末尾
        //（下标 2 = 排在第一行那两张之后），而不是插到中间某处
        assertEquals(2, panel.dropIndexAt(new java.awt.Point(firstCellLeft, secondRowMid)));
        // 第二行第一格的右半边：插在它后面 → 下标 3
        assertEquals(3, panel.dropIndexAt(new java.awt.Point(firstCellLeft
                + panel.thumbnailWidthForTest() - 2, secondRowMid)));
        // 最后一行下面那片空白：夹到末尾
        assertEquals(4, panel.dropIndexAt(new java.awt.Point(20, 34 + 4 * rowHeight)));

        // 白线要画在上一行的末尾，而不是下一行的开头
        SwingUtilities.invokeAndWait(() -> {
            panel.setDropHint(2); // 排在第二行第一张之前 = 第一行最后一张之后
        });
        java.awt.Rectangle line = panel.dropHintRectForTest();
        assertTrue(line.x >= panel.cellRightForTest(1),
                "白线应当画在第一行最后一个格子的右边，而不是绕到下一行开头");
        assertTrue(line.y < panel.rowHeightForTest(), "白线应当还在第一行那一带");
        // 开头那个落点（下标 0）不受影响，仍然留在第一行最左边
        SwingUtilities.invokeAndWait(() -> panel.setDropHint(0));
        assertTrue(panel.dropHintRectForTest().x < panel.cellXForTest(0) + 10,
                "下标 0 是整排的开头，白线画在最左边");

        loader.shutdown();
    }

    @Test
    void 竖照片要竖着立起来_占满一格的宽度() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        TargetPanel panel = new TargetPanel(loader);
        Path folder = Files.createDirectories(tempDir.resolve("成片"));
        // 竖照片：600 宽 × 900 高
        Path portrait = TestImages.writeJpeg(folder.resolve("竖.jpg"), 600, 900);
        Session session = new Session(folder, NumberingRule.DEFAULT);
        session.seedExisting(java.util.List.of(portrait));

        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(360, 500);
            panel.setSession(session);
            layoutTree(panel);
        });
        waitUntil(() -> panel.isLoaded(portrait), "竖照片的缩略图");

        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(360, 500,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            java.awt.Graphics2D gx = image.createGraphics();
            panel.paint(gx);
            gx.dispose();
        });

        // 缩略图那一片（表头下面）数一数橙色占了多宽。测试图是橙底黑圆的。
        int boxWidth = panel.thumbnailWidthForTest();
        int cellLeft = panel.cellXForTest(0);
        int orangeColumns = 0;
        for (int x = cellLeft; x < Math.min(cellLeft + boxWidth, image.getWidth()); x++) {
            int orange = 0;
            for (int y = 40; y < Math.min(40 + boxWidth, image.getHeight()); y++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) > 180 && ((rgb >> 8) & 0xFF) > 90) {
                    orange++;
                }
            }
            if (orange > 5) {
                orangeColumns++;
            }
        }
        assertTrue(orangeColumns > boxWidth * 0.4,
                "竖照片应当占满一格宽度、立起来显示，而不是缩成中间一条窄条（实测 " 
                        + orangeColumns + " / " + boxWidth + "）");

        loader.shutdown();
    }

    @Test
    void 照片没占满格子时_空出来的地方是面板的深灰_不是黑框() throws Exception {
        Theme.install();
        ThumbnailLoader loader = new ThumbnailLoader(new ThumbnailService(tempDir.resolve("cache")));
        TargetPanel panel = new TargetPanel(loader);
        Path folder = Files.createDirectories(tempDir.resolve("成片"));
        // 这张照片的缩略图会是 180×120，放进 150×150 的方格子后上下各空一条
        Path photo = TestImages.writeJpeg(folder.resolve("横.jpg"), 900, 600);
        Session session = new Session(folder, NumberingRule.DEFAULT);
        session.seedExisting(java.util.List.of(photo));

        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(360, 500);
            panel.setSession(session);
            layoutTree(panel);
        });
        waitUntil(() -> panel.isLoaded(photo), "照片的缩略图");

        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(360, 500,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> {
            java.awt.Graphics2D gx = image.createGraphics();
            panel.paint(gx);
            gx.dispose();
        });

        // 怎么比：拿"照片上方空出来的那一条"和"面板自己的底色"比。
        // 面板底色从名字那一行取（那里不会被照片盖住），这样不写死颜色值。
        int cellLeft = panel.cellXForTest(0);
        int boxWidth = panel.thumbnailWidthForTest();
        int boxHeight = panel.rowHeightForTest() - 26; // 一行去掉名字那一行，剩下的就是格子
        int emptyBand = averageLuma(image, cellLeft + 4, 40 + 3, boxWidth - 8, 12);
        int panelColor = averageLuma(image, cellLeft + 4,
                40 + boxHeight + 6, boxWidth - 8, 8);

        assertTrue(Math.abs(emptyBand - panelColor) < 8,
                "照片没占满的那一条应当是面板的深灰，不该比面板底色深一截"
                        + "（空处亮度 " + emptyBand + "，面板底色 " + panelColor + "）");

        loader.shutdown();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }

    /** 一块平均亮度，用来比"这儿的底色是不是那一带的灰"。 */
    private static int averageLuma(java.awt.image.BufferedImage image,
                                   int x, int y, int width, int height) {
        long sum = 0;
        int count = 0;
        for (int py = y; py < Math.min(y + height, image.getHeight()); py++) {
            for (int px = x; px < Math.min(x + width, image.getWidth()); px++) {
                int rgb = image.getRGB(px, py);
                sum += (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
                count++;
            }
        }
        return count == 0 ? -1 : (int) (sum / count);
    }

    private static void render(javax.swing.JComponent component, Path output) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                component.getWidth(), component.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        component.paint(g);
        g.dispose();
        Files.createDirectories(output.getParent());
        javax.imageio.ImageIO.write(image, "png", output.toFile());
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) {
                layoutTree(nested);
            }
        }
    }
}

package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

class SourceTreePanelTest {

    @TempDir
    Path tempDir;

    /** 造一个像样的素材档案夹：两层结构 + 一个非照片文件。 */
    private Path buildLibrary() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("照片_Leica_M10"));
        Path shootA = Files.createDirectories(root.resolve("095_2026_0929"));
        Path shootB = Files.createDirectories(root.resolve("096_2026_1001"));
        TestImages.writeJpeg(shootA.resolve("L1000001.JPG"), 400, 300);
        TestImages.writeJpeg(shootA.resolve("L1000002.JPG"), 400, 300);
        TestImages.writeJpeg(shootB.resolve("L1000003.JPG"), 400, 300);
        Files.writeString(root.resolve("说明.txt"), "不是照片，但也该列出来");
        return root;
    }

    private static List<String> childNames(DefaultMutableTreeNode node) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            SourceTreePanel.Node child = SourceTreePanel.nodeOf(node.getChildAt(i));
            names.add(child == null || child.isPlaceholder()
                    ? "…"
                    : child.path().getFileName().toString());
        }
        return names;
    }

    private static DefaultMutableTreeNode child(DefaultMutableTreeNode parent, String name) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) parent.getChildAt(i);
            SourceTreePanel.Node payload = SourceTreePanel.nodeOf(node);
            if (payload != null && payload.path() != null
                    && payload.path().getFileName().toString().equals(name)) {
                return node;
            }
        }
        throw new AssertionError("树里找不到：" + name);
    }

    @Test
    void 根目录下的子文件夹和文件都会列出来() throws Exception {
        Theme.install();
        SourceTreePanel panel = new SourceTreePanel();
        Path library = buildLibrary();
        SwingUtilities.invokeAndWait(() -> panel.setRoot(library));

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.tree().getModel().getRoot();
        assertEquals(List.of("095_2026_0929", "096_2026_1001", "说明.txt"), childNames(root));
    }

    @Test
    void 展开之前不去读子文件夹的内容() throws Exception {
        Theme.install();
        SourceTreePanel panel = new SourceTreePanel();
        Path library = buildLibrary();
        SwingUtilities.invokeAndWait(() -> panel.setRoot(library));

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.tree().getModel().getRoot();
        DefaultMutableTreeNode shoot = child(root, "095_2026_0929");

        // 展开之前，里面只放了一个占位节点——子项还没去读
        assertEquals(1, shoot.getChildCount());
        assertTrue(SourceTreePanel.nodeOf(shoot.getChildAt(0)).isPlaceholder(),
                "展开之前不该已经读出内容");

        // 展开它，占位换成真正的文件
        SwingUtilities.invokeAndWait(() -> panel.tree().expandPath(new TreePath(shoot.getPath())));
        assertEquals(List.of("L1000001.JPG", "L1000002.JPG"), childNames(shoot));
    }

    @Test
    void 勾选框决定文件夹是否纳入范围() throws Exception {
        Theme.install();
        SourceTreePanel panel = new SourceTreePanel();
        Path root = buildLibrary();
        SwingUtilities.invokeAndWait(() -> panel.setRoot(root));

        Path shootA = root.resolve("095_2026_0929");
        assertTrue(panel.isIncluded(shootA), "默认全部纳入");

        panel.toggleIncluded(shootA); // 等于取消勾选，也就是"剔除"
        assertFalse(panel.isIncluded(shootA));
        assertEquals(1, panel.excludedFolders().size());

        panel.toggleIncluded(shootA);
        assertTrue(panel.isIncluded(shootA));
        assertTrue(panel.excludedFolders().isEmpty());
    }

    @Test
    void 点击落在勾选框那一小块里才算切换() {
        Rectangle row = new Rectangle(10, 40, 200, 22);
        assertTrue(SourceTreePanel.hitsCheckbox(row, 12), "点在方框上");
        assertTrue(SourceTreePanel.hitsCheckbox(row, 28), "仍在方框那一小块内");
        assertFalse(SourceTreePanel.hitsCheckbox(row, 60), "点到名字上就不算切换了");
    }

    @Test
    void 点中文件夹会通知外部() throws Exception {
        Theme.install();
        SourceTreePanel panel = new SourceTreePanel();
        Path root = buildLibrary();
        List<Path> chosen = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> {
            panel.setRoot(root);
            panel.setOnFolderChosen(chosen::add);
        });

        // 模拟"点在文件夹的名字上"
        panel.chooseFolder(root.resolve("096_2026_1001"));
        assertEquals(List.of(root.resolve("096_2026_1001")), chosen);
    }

    @Test
    void 目录树能画出来() throws Exception {
        Theme.install();
        SourceTreePanel panel = new SourceTreePanel();
        Path root = buildLibrary();
        SwingUtilities.invokeAndWait(() -> {
            panel.setRoot(root);
            panel.setSize(240, 300);
            layoutTree(panel);
        });

        BufferedImage image = new BufferedImage(240, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        panel.paint(g);
        g.dispose();

        Path output = Path.of("target", "ui-tree.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    /** 离屏布局。 */
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }
}

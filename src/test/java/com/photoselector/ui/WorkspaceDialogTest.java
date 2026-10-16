package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.Workspace;
import com.photoselector.ui.theme.Theme;

/**
 * 「选择工作区」对话框的冒烟测试：能搭起来、能画出来、能带着上次的选择打开。
 *
 * <p>不调用 {@code setVisible}——那会真的弹一个窗口，还会因为模态把测试卡住。
 */
class WorkspaceDialogTest {

    @TempDir
    Path tempDir;

    @Test
    void 带着上次的工作区打开时_界面里会填好上次的选择() throws Exception {
        Theme.install();
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Path skipped = Files.createDirectories(root.resolve("001_不要的"));
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Workspace current = new Workspace(root, Set.of(skipped), target);

        WorkspaceDialog dialog = new WorkspaceDialog(null, current);
        dialog.setSize(dialog.getPreferredSize());
        layoutTree(dialog.getContentPane());

        BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        dialog.getContentPane().paint(g);
        g.dispose();
        Path output = Path.of("target", "ui-workspace-dialog.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());

        assertTrue(countColors(image) > 3, "对话框应当画出内容");
        dialog.dispose();
    }

    @Test
    void 没有上次的选择时也能打开() throws Exception {
        Theme.install();
        WorkspaceDialog dialog = new WorkspaceDialog(null, null);
        assertEquals(0, new HashSet<>(dialog.excludedForTest()).size());
        dialog.dispose();
    }

    private static int countColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 3) {
            for (int x = 0; x < image.getWidth(); x += 3) {
                colors.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return colors.size();
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

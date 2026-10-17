package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.ui.theme.Theme;

/**
 * 保存完成那条提示的冒烟测试：深色、没有系统外观的亮色、正文和"确定"按钮都在。
 *
 * <p>不调用 {@code setVisible}——那会真的弹窗，还会把测试卡在模态上。
 * 渲染结果另存成 {@code target/ui-message-dialog.png}，方便肉眼核对。
 */
class MessageDialogTest {

    @TempDir
    Path tempDir;

    @Test
    void 两个按钮的消息框_点哪个就返回哪个() throws Exception {
        Theme.install();

        MessageDialog dialog = new MessageDialog(null, "还没保存",
                "目标文件夹里还有没保存的改动。\n关掉之前要保存吗？（选“不保存”的话，文件保持原样、什么都不会丢）",
                "保存", "不保存");
        dialog.setSize(dialog.getPreferredSize());
        Container content = dialog.getContentPane();
        layoutTree(content);

        BufferedImage image = new BufferedImage(Math.max(1, content.getWidth()),
                Math.max(1, content.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        content.paint(g);
        g.dispose();
        ImageIO.write(image, "png", Path.of("target", "ui-confirm-save.png").toFile());

        AbstractButton save = findButton(dialog.getContentPane(), "保存");
        AbstractButton discard = findButton(dialog.getContentPane(), "不保存");
        assertNotNull(save, "应当有保存按钮");
        assertNotNull(discard, "应当有不保存按钮");
        assertEquals("保存", dialog.getRootPane().getDefaultButton().getText(), "回车默认选保存");

        discard.doClick();
        assertEquals(1, dialog.choice(), "点「不保存」应当记成第二个按钮");
        dialog.dispose();
    }

    @Test
    void 保存完成的消息框是深色的_正文和确定按钮都在() throws Exception {
        Theme.install();

        String message = "新增 0 张，重命名 19 张，重写编辑 0 张，删除 0 张";
        MessageDialog dialog = new MessageDialog(null, "保存", message);
        dialog.setSize(dialog.getPreferredSize());
        Container content = dialog.getContentPane();
        layoutTree(content);

        // 只截内容面板本身：窗口底部那一条是窗口底色，不属于对话框的内容
        BufferedImage image = new BufferedImage(Math.max(1, content.getWidth()),
                Math.max(1, content.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        content.paint(g);
        g.dispose();

        Path output = Path.of("target", "ui-message-dialog.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());

        assertTrue(countColors(image) > 3, "对话框应当画出内容");
        // 系统外观会画浅灰底和亮色立体按钮；深色消息框里最亮的字也只有 #C3C3C3
        assertEquals(0, countPixelsBrighterThan(image, 0xD8), "消息框不该还是系统外观的亮色");
        assertTrue(anyTextContains(dialog.getContentPane(), message), "正文应当在");
        assertTrue(anyTextContains(dialog.getContentPane(), "text-align:center"),
                "正文应当居中，而不是贴着左边");
        assertNotNull(findButton(dialog.getContentPane(), "确定"), "右下角应当有确定按钮");

        // 真的落在中间：量出正文像素的左右边界，中心离对话框中线不能差太多
        JLabel body = findBodyLabel(dialog.getContentPane());
        assertNotNull(body, "应当找得到正文标签");
        Point at = SwingUtilities.convertPoint(body, 0, 0, dialog.getContentPane());
        int minX = Integer.MAX_VALUE;
        int maxX = -1;
        for (int y = Math.max(0, at.y); y < Math.min(image.getHeight(), at.y + body.getHeight()); y++) {
            for (int x = Math.max(0, at.x); x < Math.min(image.getWidth(), at.x + body.getWidth()); x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) > 150 && ((rgb >> 8) & 0xFF) > 150 && (rgb & 0xFF) > 150) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }
        assertTrue(maxX > 0, "正文应当画出可见的像素");
        int textCenter = (minX + maxX) / 2;
        int dialogCenter = image.getWidth() / 2;
        assertTrue(Math.abs(textCenter - dialogCenter) <= 12,
                "正文应当在中线上：文字中心 " + textCenter + "，对话框中心 " + dialogCenter);
        dialog.dispose();
    }

    private static boolean anyTextContains(Container container, String wanted) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null
                    && label.getText().contains(wanted)) {
                return true;
            }
            if (child instanceof Container nested && anyTextContains(nested, wanted)) {
                return true;
            }
        }
        return false;
    }

    private static AbstractButton findButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof AbstractButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container nested) {
                AbstractButton found = findButton(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JLabel findBodyLabel(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null
                    && label.getText().startsWith("<html>")) {
                return label;
            }
            if (child instanceof Container nested) {
                JLabel found = findBodyLabel(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int countColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                colors.add(image.getRGB(x, y) & 0xFFFFFF);
            }
        }
        return colors.size();
    }

    /** 比给定亮度（取 R/G/B 里最大的分量）还亮的像素有多少个。 */
    private static int countPixelsBrighterThan(BufferedImage image, int limit) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int brightest = Math.max(Math.max((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF), rgb & 0xFF);
                if (brightest > limit) {
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

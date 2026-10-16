package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.ui.theme.Theme;

/**
 * 「挑一个文件夹」对话框的测试：只列子文件夹、单击改答案、双击进入、
 * 回退与向上能走得通；跑完把画面存成 {@code target/ui-folder-picker.png}
 * 供肉眼核对——这个对话框的卖点就是"好看"，不看看图没法验收。
 */
class FolderPickerDialogTest {

    @TempDir
    Path tempDir;

    @Test
    void 只列出子文件夹_并且默认答案就是当前文件夹() throws Exception {
        Theme.install();
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Files.createDirectories(root.resolve("001_第一组"));
        Files.createDirectories(root.resolve("002_第二组"));
        Files.writeString(root.resolve("散图.jpg"), "这不是文件夹，不该出现在列表里");

        FolderPickerDialog dialog = new FolderPickerDialog(null, "选择文件夹", root);
        awaitListing(dialog, root);

        assertEquals(List.of("001_第一组", "002_第二组"), folderNames(dialog));
        assertEquals(root.toAbsolutePath().normalize(), dialog.currentFolder(), "初始位置应当是传进来的文件夹");
        assertEquals(dialog.currentFolder(), dialog.chosen(), "还没点任何东西时，答案就是当前文件夹");
        dialog.dispose();
    }

    @Test
    void 单击子文件夹把它设为答案_双击进入它_还能向上和回退() throws Exception {
        Theme.install();
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Path first = Files.createDirectories(root.resolve("001_第一组"));
        Path second = Files.createDirectories(root.resolve("002_第二组"));

        FolderPickerDialog dialog = new FolderPickerDialog(null, "选择文件夹", root);
        awaitListing(dialog, root);

        // 单击走的是列表选择；双击走的也是 listSelectionListener 加 navigateTo
        SwingUtilities.invokeAndWait(() -> dialog.folderListForTest().setSelectedIndex(1));
        assertEquals(second.toAbsolutePath().normalize(), dialog.chosen(), "单击 = 把答案改成这一项");
        assertEquals(root.toAbsolutePath().normalize(), dialog.currentFolder(), "单击不该把人带进去");

        dialog.navigateTo(first);
        awaitListing(dialog, first);
        assertEquals(first.toAbsolutePath().normalize(), dialog.currentFolder(), "双击应当进入文件夹");
        assertEquals(first.toAbsolutePath().normalize(), dialog.chosen(), "进去之后答案回到当前文件夹");

        dialog.goUp();
        awaitListing(dialog, root);
        assertEquals(root.toAbsolutePath().normalize(), dialog.currentFolder(), "向上应当回到父文件夹");

        dialog.goBack();
        awaitListing(dialog, first);
        assertEquals(first.toAbsolutePath().normalize(), dialog.currentFolder(), "回退应当回到刚才进去的文件夹");

        dialog.confirm();
        assertEquals(first.toAbsolutePath().normalize(), dialog.result(), "确认后交出去的就是选中的文件夹");
        dialog.dispose();
    }

    @Test
    void 进到空文件夹时不再显示上一个文件夹的内容() throws Exception {
        Theme.install();
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Files.createDirectories(root.resolve("001_第一组"));
        Path empty = Files.createDirectories(root.resolve("002_空组"));

        FolderPickerDialog dialog = new FolderPickerDialog(null, "选择文件夹", root);
        awaitListing(dialog, root);
        assertEquals(2, dialog.folderListForTest().getModel().getSize(), "进空文件夹之前，列表里有东西");

        dialog.navigateTo(empty);
        awaitListing(dialog, empty);
        assertEquals(0, dialog.folderListForTest().getModel().getSize(), "换文件夹时列表要先清空");
        assertEquals("这个文件夹里没有子文件夹", dialog.hintForTest(), "空文件夹要给出提示，而不是留一片空白");
        dialog.dispose();
    }

    @Test
    void 画出来看看样子() throws Exception {
        Theme.install();

        // 造一个"像真的一样"的家目录，让截图里的快速访问不是空的。
        // 用完把属性放回去，免得影响别的测试。
        Path fakeHome = Files.createDirectories(tempDir.resolve("home"));
        for (String name : List.of("Desktop", "Documents", "Downloads", "Pictures")) {
            Files.createDirectories(fakeHome.resolve(name));
        }
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        try {
            // 演示目录放在 target 下：路径浅一点，截图里的面包屑更接近真实使用的样子
            Path root = Path.of("target", "folder-picker-demo", "照片_Leica_M10");
            Files.createDirectories(root);
            Files.createDirectories(root.resolve("095_2026_0929"));
            Files.createDirectories(root.resolve("096_2026_1001"));
            Files.createDirectories(root.resolve("097_2026_1002_1006"));
            Files.createDirectories(root.resolve("098_2026_1006_1011"));
            Files.createDirectories(root.resolve("122_Rollei"));
            Files.createDirectories(root.resolve("123_Nikon_F2"));

            FolderPickerDialog dialog = new FolderPickerDialog(null, "选择文件夹", root);
            awaitListing(dialog, root);
            SwingUtilities.invokeAndWait(() -> dialog.folderListForTest().setSelectedIndex(3));

            int width = 880;
            int height = 560;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(() -> {
                dialog.setSize(width, height);
                // 从窗口本身开始递归布局：内容面板的尺寸是根面板铺出来的，
                // 只对内容面板调 doLayout 的话它会一直是 0×0，画出来一片空
                layoutTree(dialog);
            });
            // 布局完成之后，"面包屑滚到路径末尾"还要在事件队列里走一趟；
            // 等它走完再画，截图才是真实窗口里的样子
            Thread.sleep(200);
            SwingUtilities.invokeAndWait(() -> {
                Graphics2D g = image.createGraphics();
                dialog.getContentPane().paint(g);
                g.dispose();
            });

            Path output = Path.of("target", "ui-folder-picker.png");
            Files.createDirectories(output.getParent());
            ImageIO.write(image, "png", output.toFile());

            assertTrue(countColors(image) > 3, "对话框应当画出内容");
            dialog.dispose();
        } finally {
            System.setProperty("user.home", realHome);
        }
    }

    private static List<String> folderNames(FolderPickerDialog dialog) {
        ListModel<Path> model = dialog.folderListForTest().getModel();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < model.getSize(); i++) {
            names.add(model.getElementAt(i).getFileName().toString());
        }
        return names;
    }

    /** 列表在后台读，等它送到界面线程为止。 */
    private static void awaitListing(FolderPickerDialog dialog, Path folder) throws Exception {
        Path normalized = folder.toAbsolutePath().normalize();
        long deadline = System.currentTimeMillis() + 20_000;
        while (!dialog.listingDoneForTest(normalized) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(dialog.listingDoneForTest(normalized), "列表应当在时限内读出来：" + normalized);
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

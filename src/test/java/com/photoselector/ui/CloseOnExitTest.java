package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.Workspace;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/**
 * 关窗口时的保存流程：有改动才问、选保存要等保存真的跑完再退、选不保存就原样退出。
 *
 * <p>这几条都在 {@link MainPanel#requestClose()} 里，不需要真的开窗口，所以测试也测得起。
 */
class CloseOnExitTest {

    @TempDir
    Path tempDir;

    @Test
    void 没有改动就直接退出_不问也不写盘() throws Exception {
        Theme.install();
        Panel panel = new Panel(false);
        AtomicInteger exits = new AtomicInteger();
        panel.main.setOnExit(exits::incrementAndGet);
        panel.main.setConfirmSaveOnClose(owner -> {
            throw new AssertionError("没有改动就不该问\"要不要保存\"");
        });

        SwingUtilities.invokeAndWait(panel.main::requestClose);

        assertEquals(1, exits.get(), "没有改动，直接退出");
        assertFalse(Files.exists(panel.target.resolve("010.jpg")), "什么都没写");
        panel.main.shutdown();
    }

    @Test
    void 选保存会先把改动写进目标文件夹_写完才退出() throws Exception {
        Theme.install();
        Panel panel = new Panel(true);
        Path added = panel.photoToAdd;

        AtomicInteger exits = new AtomicInteger();
        panel.main.setOnExit(exits::incrementAndGet);
        panel.main.setConfirmSaveOnClose(owner -> JOptionPane.YES_OPTION);

        SwingUtilities.invokeAndWait(panel.main::requestClose);
        assertEquals(0, exits.get(), "保存是后台活儿，不能问完就走");
        assertTrue(panel.main.isClosingAfterSave(), "这时该处于\"等保存\"的状态");

        waitUntil(() -> exits.get() == 1, "保存跑完并退出");

        Path copied = panel.target.resolve("010.jpg");
        assertTrue(Files.isRegularFile(copied), "挑进来的照片应当已经复制成 010.jpg");
        assertEquals(Files.size(added), Files.size(copied), "复制是原样复制，不重新编码");
        assertFalse(panel.main.isDirty(), "保存完中间商就是干净的");
        assertTrue(Files.isRegularFile(panel.target.resolve("020.jpg")), "原有文件按顺序重新编号");
        panel.main.shutdown();
    }

    @Test
    void 选不保存就原样退出_中间商的改动不落盘() throws Exception {
        Theme.install();
        Panel panel = new Panel(true);

        AtomicInteger exits = new AtomicInteger();
        panel.main.setOnExit(exits::incrementAndGet);
        panel.main.setConfirmSaveOnClose(owner -> JOptionPane.NO_OPTION);

        SwingUtilities.invokeAndWait(panel.main::requestClose);

        assertEquals(1, exits.get(), "选不保存，立刻退出");
        assertTrue(panel.main.isDirty(), "中间商没被清空——是磁盘保持原样，而不是模型被改回去");
        assertFalse(Files.exists(panel.target.resolve("010.jpg")), "磁盘上什么都没多出来");
        assertTrue(Files.isRegularFile(panel.target.resolve("旧A.jpg")), "原有文件还是原来的名字");
        panel.main.shutdown();
    }

    @Test
    void 把对话框直接关掉算是取消_程序留着() throws Exception {
        Theme.install();
        Panel panel = new Panel(true);

        AtomicInteger exits = new AtomicInteger();
        panel.main.setOnExit(exits::incrementAndGet);
        panel.main.setConfirmSaveOnClose(owner -> JOptionPane.CLOSED_OPTION);

        SwingUtilities.invokeAndWait(panel.main::requestClose);

        assertEquals(0, exits.get(), "取消就是取消");
        assertTrue(panel.main.isDirty(), "改动还在，等用户回来接着挑");
        assertFalse(Files.exists(panel.target.resolve("010.jpg")));
        panel.main.shutdown();
    }

    @Test
    void 换工作区时_有没保存的改动也问同一套话() throws Exception {
        Theme.install();
        Panel panel = new Panel(true);
        Path newSource = Files.createDirectories(tempDir.resolve("素材2"));
        Files.createDirectories(newSource.resolve("001_拍摄"));
        Path newTarget = Files.createDirectories(tempDir.resolve("成片2"));

        AtomicInteger asked = new AtomicInteger();
        panel.main.setConfirmSaveOnClose(owner -> {
            asked.incrementAndGet();
            return JOptionPane.CLOSED_OPTION; // 把对话框关掉 = 取消
        });

        SwingUtilities.invokeAndWait(() ->
                panel.main.switchWorkspace(new Workspace(newSource, Set.of(), newTarget)));

        assertEquals(1, asked.get(), "有没保存的改动，换工作区也该先问一句");
        assertEquals(panel.target, panel.main.targetPanelForTest().session().targetFolder(),
                "取消之后还留在原来的目标文件夹，什么都没变");
        assertFalse(Files.isRegularFile(panel.target.resolve("010.jpg")), "没有写盘");
        panel.main.shutdown();
    }

    @Test
    void 换工作区时选不保存_直接切走() throws Exception {
        Theme.install();
        Panel panel = new Panel(true);
        Path newSource = Files.createDirectories(tempDir.resolve("素材2"));
        Files.createDirectories(newSource.resolve("001_拍摄"));
        Path newTarget = Files.createDirectories(tempDir.resolve("成片2"));

        panel.main.setConfirmSaveOnClose(owner -> JOptionPane.NO_OPTION);
        SwingUtilities.invokeAndWait(() ->
                panel.main.switchWorkspace(new Workspace(newSource, Set.of(), newTarget)));

        waitUntil(() -> panel.main.targetPanelForTest().session() != null
                        && panel.main.targetPanelForTest().session().targetFolder().equals(newTarget),
                "切到新工作区的目标文件夹");
        assertFalse(Files.isRegularFile(panel.target.resolve("010.jpg")),
                "选不保存，旧的目标文件夹不该被写");
        panel.main.shutdown();
    }

    @Test
    void 换工作区时选保存_先保存到旧文件夹再切走() throws Exception {
        Theme.install();
        Panel panel = new Panel(true);
        Path newSource = Files.createDirectories(tempDir.resolve("素材2"));
        Files.createDirectories(newSource.resolve("001_拍摄"));
        Path newTarget = Files.createDirectories(tempDir.resolve("成片2"));

        panel.main.setConfirmSaveOnClose(owner -> JOptionPane.YES_OPTION);
        SwingUtilities.invokeAndWait(() ->
                panel.main.switchWorkspace(new Workspace(newSource, Set.of(), newTarget)));

        // 保存是后台活儿：先等它写盘，再等切换完成
        waitUntil(() -> Files.isRegularFile(panel.target.resolve("010.jpg")),
                "保存把照片复制进旧的目标文件夹");
        waitUntil(() -> panel.main.targetPanelForTest().session() != null
                        && panel.main.targetPanelForTest().session().targetFolder().equals(newTarget),
                "保存完之后才切到新工作区");
        panel.main.shutdown();
    }

    /** 一个装好素材和目标文件夹的界面，外加"要挑进去的那张照片"。 */
    private final class Panel {

        final MainPanel main;
        final Path target;
        final Path photoToAdd;
        final Path source = tempDir.resolve("素材");

        /**
         * @param addPhoto 是否先挑一张进去，造出"没保存的改动"
         */
        Panel(boolean addPhoto) throws Exception {
            Path source = Files.createDirectories(tempDir.resolve("素材"));
            Path shots = Files.createDirectories(source.resolve("001_拍摄"));
            photoToAdd = TestImages.writeJpeg(shots.resolve("p0.jpg"), 300, 200);
            target = Files.createDirectories(tempDir.resolve("成片"));
            TestImages.writeJpeg(target.resolve("旧A.jpg"), 300, 200);

            main = new MainPanel(tempDir.resolve("cache"));
            main.setOnWorkspaceChanged(workspace -> { }); // 别往真实配置里写
            SwingUtilities.invokeAndWait(() ->
                    main.applyWorkspace(new Workspace(source, Set.of(), target)));
            waitUntil(() -> main.targetPanelForTest().session() != null
                    && main.filmstripGroupCount() == 1, "扫描完成");
            if (addPhoto) {
                main.addToTarget(photoToAdd);
            }
        }
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }
}

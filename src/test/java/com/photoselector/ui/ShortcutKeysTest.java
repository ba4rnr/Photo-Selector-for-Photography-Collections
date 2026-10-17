package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BooleanSupplier;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.Workspace;
import com.photoselector.testing.TestImages;
import com.photoselector.ui.theme.Theme;

/**
 * 键盘快捷键：追加到末尾的两个键（空格 / 回车）、撤销，以及"改动过才算脏"。
 */
class ShortcutKeysTest {

    @TempDir
    Path tempDir;

    @Test
    void 回车和空格都把当前照片追加到末尾() throws Exception {
        MainPanel panel = panelWithPhotos();

        panel.filmstripForTest().setCurrentPhoto(photoNamed(panel, "p1.jpg"));
        pressKey(panel, KeyEvent.VK_ENTER);
        assertEquals(3, panel.targetPanelForTest().entryCount(), "回车应当把它加进去");
        assertEquals("030", panel.targetPanelForTest().nameAt(2), "追加在末尾");

        panel.filmstripForTest().setCurrentPhoto(photoNamed(panel, "p2.jpg"));
        pressKey(panel, KeyEvent.VK_SPACE);
        assertEquals(4, panel.targetPanelForTest().entryCount(), "空格照样管用");
        assertEquals("040", panel.targetPanelForTest().nameAt(3), "同样追加在末尾");
        panel.shutdown();
    }

    @Test
    void 快捷键只有一处出处_改键不会漏改别处() throws Exception {
        MainPanel panel = panelWithPhotos();
        var inputMap = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

        assertNotNull(inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)), "空格");
        assertNotNull(inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)), "回车");
        assertNotNull(inputMap.get(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)),
                "撤销");
        panel.shutdown();
    }

    @Test
    void 没点中任何照片时回车不该报错_也不该加进去什么() throws Exception {
        MainPanel panel = panelWithPhotos();

        // 还没点过任何一张素材
        panel.addCurrentPhotoToTarget();

        assertEquals(2, panel.targetPanelForTest().entryCount(), "还是原有的两张");
        panel.shutdown();
    }

    @Test
    void 工具栏开局就把选择工具点亮() throws Exception {
        MainPanel panel = panelWithPhotos();

        assertEquals(CursorTool.SELECT, panel.toolBar().activeTool());
        assertEquals(Theme.ACCENT,
                panel.toolBar().toolButton(CursorTool.SELECT).getForeground(),
                "默认工具的文字和图标应当是激活色，而不是一片灰");
        panel.shutdown();
    }

    @Test
    void 挑进一张就算脏_保存完又是干净的() throws Exception {
        MainPanel panel = panelWithPhotos();
        assertFalse(panel.isDirty(), "刚打开时和磁盘一致");

        panel.addToTarget(photoNamed(panel, "p0.jpg"));
        assertTrue(panel.isDirty(), "多挑了一张，磁盘上还没有它");

        SwingUtilities.invokeAndWait(panel::save);
        waitUntil(() -> !panel.isDirty(), "保存完成");
        panel.shutdown();
    }

    /** 按一下某个键，走的是 Swing 真正的那条路（输入映射 → 动作），不是直接调方法。 */
    private static void pressKey(JComponent component, int keyCode) {
        KeyStroke stroke = KeyStroke.getKeyStroke(keyCode, 0);
        Object actionName = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);
        assertNotNull(actionName, "这个键应当在主面板的快捷键表里");
        Action action = component.getActionMap().get(actionName);
        assertNotNull(action, "快捷键表里有名字，就该有对应的动作");
        action.actionPerformed(new ActionEvent(component,
                ActionEvent.ACTION_PERFORMED, actionName.toString()));
    }

    private Path photoNamed(MainPanel panel, String fileName) throws Exception {
        return Files.walk(tempDir.resolve("素材"))
                .filter(path -> path.getFileName().toString().equals(fileName))
                .findFirst().orElseThrow();
    }

    private MainPanel panelWithPhotos() throws Exception {
        Theme.install();
        Path source = Files.createDirectories(tempDir.resolve("素材"));
        Path shots = Files.createDirectories(source.resolve("001_拍摄"));
        for (int i = 0; i < 3; i++) {
            TestImages.writeJpeg(shots.resolve("p" + i + ".jpg"), 300, 200);
        }
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        TestImages.writeJpeg(target.resolve("旧A.jpg"), 300, 200);
        TestImages.writeJpeg(target.resolve("旧B.jpg"), 300, 200);

        MainPanel panel = new MainPanel(tempDir.resolve("cache"));
        panel.setOnWorkspaceChanged(workspace -> { }); // 别往真实配置里写
        SwingUtilities.invokeAndWait(() ->
                panel.applyWorkspace(new Workspace(source, Set.of(), target)));
        waitUntil(() -> panel.targetPanelForTest().session() != null
                && panel.filmstripGroupCount() == 1, "扫描完成");
        return panel;
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }
}

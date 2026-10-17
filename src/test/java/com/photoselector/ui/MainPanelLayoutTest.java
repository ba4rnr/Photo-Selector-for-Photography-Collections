package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.ui.theme.Theme;

/** 窗口变大或恢复最大化时，底栏不能把多出来的高度吞进去。 */
class MainPanelLayoutTest {

    @TempDir
    Path tempDir;

    @Test
    void 窗口放大后底栏保持原高度() throws Exception {
        MainPanel[] panelRef = new MainPanel[1];
        int[] before = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            MainPanel panel = new MainPanel(tempDir.resolve("cache"));
            panelRef[0] = panel;
            panel.setSize(1200, 800);
            layoutTree(panel);
            before[0] = panel.bottomAreaHeightForTest();

            panel.setSize(1800, 1200);
            layoutTree(panel);
        });

        try {
            assertEquals(before[0], panelRef[0].bottomAreaHeightForTest(),
                    "窗口从 800 拉高到 1200，底栏高度不能跟着多出 400");
        } finally {
            SwingUtilities.invokeAndWait(panelRef[0]::shutdown);
        }
    }

    @Test
    void 用户拖过底栏之后_放大窗口仍保留自定义高度() throws Exception {
        MainPanel[] panelRef = new MainPanel[1];
        int[] custom = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            MainPanel panel = new MainPanel(tempDir.resolve("cache"));
            panelRef[0] = panel;
            panel.setSize(1200, 800);
            layoutTree(panel);

            custom[0] = panel.bottomAreaHeightForTest() + 50;
            panel.setBottomAreaHeightForTest(custom[0]);
            panel.setSize(1700, 1100);
            layoutTree(panel);
        });

        try {
            assertEquals(custom[0], panelRef[0].bottomAreaHeightForTest(),
                    "用户拖出来的底栏高度也应当在窗口变大时保持");
        } finally {
            SwingUtilities.invokeAndWait(panelRef[0]::shutdown);
        }
    }

    @Test
    void 窗口临时缩小再放大_底栏恢复用户原来的高度() throws Exception {
        MainPanel[] panelRef = new MainPanel[1];
        int[] custom = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            MainPanel panel = new MainPanel(tempDir.resolve("cache"));
            panelRef[0] = panel;
            panel.setSize(1200, 800);
            layoutTree(panel);

            custom[0] = panel.bottomAreaHeightForTest() + 50;
            panel.setBottomAreaHeightForTest(custom[0]);

            panel.setSize(1200, 220);
            layoutTree(panel);
            panel.setSize(1700, 1100);
            layoutTree(panel);
        });

        try {
            assertEquals(custom[0], panelRef[0].bottomAreaHeightForTest(),
                    "临时缩小时只能视觉上夹住，不能把用户选的高度永久改小");
        } finally {
            SwingUtilities.invokeAndWait(panelRef[0]::shutdown);
        }
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

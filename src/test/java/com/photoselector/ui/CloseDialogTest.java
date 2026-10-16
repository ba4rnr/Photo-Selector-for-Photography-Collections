package com.photoselector.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import com.photoselector.ui.theme.Theme;

/**
 * 关闭时那个「保存 / 不保存」对话框本身：点了按钮要真的给出对应的答案。
 *
 * <p>这一条单独拎出来测，是因为"点不保存没反应"这种毛病只可能出在这里——
 * 关窗口流程的其余部分（问了之后怎么走）已经有 {@link CloseOnExitTest} 钉着。
 */
class CloseDialogTest {

    @Test
    void 点不保存_对话框返回的是不保存() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "没有显示器就弹不了对话框");
        Theme.install();

        JFrame frame = onEdtAndGet(() -> {
            JFrame f = new JFrame("问一句");
            f.setSize(400, 300);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            return f;
        });
        try {
            AtomicReference<Integer> answer = new AtomicReference<>();
            // 模态对话框一旦弹出来就会占住调用线程，所以先排好"点按钮"这件事再弹
            javax.swing.Timer clicker = new javax.swing.Timer(300, e -> {
                AbstractButton button = findButton("不保存");
                assertNotNull(button, "对话框里应当有「不保存」这个按钮");
                button.doClick();
            });
            clicker.setRepeats(false);
            clicker.start();
            answer.set(MainPanel.askToSaveForTest(frame));

            assertEquals(JOptionPane.NO_OPTION, answer.get(),
                    "点「不保存」要返回 NO_OPTION，否则关闭流程会把它当成别的东西");
        } finally {
            SwingUtilities.invokeAndWait(() -> frame.dispose());
        }
    }

    @Test
    void 对话框里的两个按钮各就各位() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "没有显示器就弹不了对话框");
        Theme.install();

        JFrame frame = onEdtAndGet(() -> {
            JFrame f = new JFrame("问一句");
            f.setSize(400, 300);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            return f;
        });
        try {
            AtomicReference<String> seen = new AtomicReference<>();
            javax.swing.Timer clicker = new javax.swing.Timer(300, e -> {
                AbstractButton save = findButton("保存");
                AbstractButton discard = findButton("不保存");
                assertNotNull(save, "应当有「保存」");
                assertNotNull(discard, "应当有「不保存」");
                assertTrue(save != discard, "两个按钮必须是两个东西");
                seen.set(save.getText() + "|" + discard.getText());
                // 点「不保存」：它必须返回 NO_OPTION；「保存」同理返回 YES_OPTION
                discard.doClick();
            });
            clicker.setRepeats(false);
            clicker.start();
            int answer = MainPanel.askToSaveForTest(frame);

            assertEquals("保存|不保存", seen.get());
            assertEquals(JOptionPane.NO_OPTION, answer);
        } finally {
            SwingUtilities.invokeAndWait(() -> frame.dispose());
        }
    }

    /** 在所有窗口里找那个按钮——对话框是独立窗口，不是主窗口的子组件。 */
    private static AbstractButton findButton(String text) {
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            AbstractButton found = findButton(window, text);
            if (found != null) {
                return found;
            }
        }
        return null;
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

    private static <T> T onEdtAndGet(java.util.concurrent.Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return result.get();
    }
}

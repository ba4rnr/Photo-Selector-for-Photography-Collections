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
            // 整个过程都排在界面线程上：对话框弹出时界面线程会进入自己的事件循环，
            // 定时器就在那里面跑。放到别的线程上试过，弹出与点击之间会错开一拍，
            // 结果偶发地"对话框还没等点就关了"。
            javax.swing.Timer clicker = new javax.swing.Timer(100, null);
            clicker.addActionListener(e -> {
                AbstractButton button = findButton(frame, "不保存");
                if (button == null) {
                    return; // 还没弹出来，下一拍再试
                }
                button.doClick();
                clicker.stop();
            });
            SwingUtilities.invokeLater(() -> {
                clicker.start();
                answer.set(MainPanel.askToSaveForTest(frame));
            });

            waitUntil(() -> answer.get() != null, "对话框给出答案");
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
            // 反复试而不是只试一次：对话框弹出需要一点时间，一次没找到就没机会了
            //（这个测试就因此翻过一次车）
            javax.swing.Timer clicker = new javax.swing.Timer(100, null);
            clicker.addActionListener(e -> {
                AbstractButton discard = findButton(frame, "不保存");
                if (discard == null) {
                    return; // 还没弹出来，下一拍再试
                }
                AbstractButton save = findButton(frame, "保存");
                seen.set((save == null ? "?" : save.getText()) + "|" + discard.getText());
                // 点「不保存」：它必须返回 NO_OPTION；「保存」同理返回 YES_OPTION
                discard.doClick();
                clicker.stop();
            });
            AtomicReference<Integer> answerRef = new AtomicReference<>();
            SwingUtilities.invokeLater(() -> {
                clicker.start();
                answerRef.set(MainPanel.askToSaveForTest(frame));
            });

            waitUntil(() -> answerRef.get() != null, "对话框给出答案");
            assertEquals("保存|不保存", seen.get());
            assertEquals(JOptionPane.NO_OPTION, answerRef.get());
        } finally {
            SwingUtilities.invokeAndWait(() -> frame.dispose());
        }
    }

    /**
     * 在"这个窗口自己弹出的对话框"里找按钮。
     *
     * <p>不能在全部窗口里瞎找：上一个测试留下的对话框可能还在，
     * 会点到它的按钮上去（这个测试就因此吃过一次假失败）。
     */
    private static AbstractButton findButton(JFrame owner, String text) {
        for (java.awt.Window window : owner.getOwnedWindows()) {
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

    /** 等一个条件成立（对话框是异步给出答案的）。 */
    private static void waitUntil(java.util.function.BooleanSupplier condition, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "等不到：" + what);
    }
}

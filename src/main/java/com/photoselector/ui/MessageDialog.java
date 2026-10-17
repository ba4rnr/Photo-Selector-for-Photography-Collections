package com.photoselector.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;

import com.photoselector.ui.theme.Theme;

/**
 * 深色消息框：替掉 {@code JOptionPane} 那种"系统蓝图标 + 立体按钮"的样子。
 *
 * <p>照 C1 的做法：顶上一条写着标题的窄条，正文左对齐、按窗口宽度折行，
 * 右下角只放一颗按钮，没有多余的图标和渐变。标题本身已经说清了这是什么事
 * （"保存""打开失败"），内容区里不再重复画一个大符号。
 */
@SuppressWarnings("serial")
final class MessageDialog extends JDialog {

    /** 正文区的宽度（含两侧留白）；再宽就不像消息框了，长句子在这个宽度里折行。 */
    private static final int WIDTH = 560;

    /** 左右留白。 */
    private static final int PAD = 18;

    /** 正文最多这么高；保存的失败清单可能很长，再多就出滚动条。 */
    private static final int MAX_TEXT_HEIGHT = 320;

    MessageDialog(Window owner, String title, String message) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setResizable(false);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        content.add(buildHeader(title), BorderLayout.NORTH);
        content.add(buildBody(message), BorderLayout.CENTER);
        content.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(content);

        // Esc = 关掉。回车交给默认按钮"确定"
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setLocationRelativeTo(owner);
    }

    /** 弹一个消息框，等用户点"确定"（或按回车 / Esc）。 */
    static void show(Window owner, String title, String message) {
        MessageDialog dialog = new MessageDialog(owner, title, message);
        dialog.setVisible(true);
        dialog.dispose();
    }

    private static JComponent buildHeader(String title) {
        JLabel label = new JLabel(title);
        label.setFont(Theme.FONT);
        label.setForeground(Theme.TEXT);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(12, PAD, 12, PAD)));
        header.add(label, BorderLayout.WEST);
        return header;
    }

    private static JComponent buildBody(String message) {
        JTextArea area = new JTextArea(message);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setForeground(Theme.TEXT);
        area.setFont(Theme.FONT);
        area.setBorder(null);
        area.setMargin(new Insets(0, 0, 0, 0));

        // 先量一遍"这个宽度下折完行有多高"：JTextArea 的折行高度跟当前宽度有关，
        // 不先给宽度的话，量出来的是不折行的一整条
        int textWidth = WIDTH - PAD * 2;
        area.setSize(new Dimension(textWidth, Short.MAX_VALUE));
        int textHeight = area.getPreferredSize().height;
        area.setPreferredSize(new Dimension(textWidth, Math.min(textHeight, MAX_TEXT_HEIGHT)));

        JComponent bodyContent = area;
        if (textHeight > MAX_TEXT_HEIGHT) {
            JScrollPane scroll = new JScrollPane(area,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(null);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            Theme.themeScrollBar(scroll.getVerticalScrollBar());
            bodyContent = scroll;
        }

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(Theme.PANEL);
        body.setBorder(BorderFactory.createEmptyBorder(16, PAD, 16, PAD));
        body.add(bodyContent, BorderLayout.CENTER);
        return body;
    }

    private JComponent buildFooter() {
        FlatButton ok = new FlatButton("确定", true);
        ok.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setBackground(Theme.PANEL);
        buttons.add(ok);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(Theme.PANEL);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(10, PAD, 10, PAD)));
        footer.add(buttons, BorderLayout.EAST);

        getRootPane().setDefaultButton(ok);
        return footer;
    }
}

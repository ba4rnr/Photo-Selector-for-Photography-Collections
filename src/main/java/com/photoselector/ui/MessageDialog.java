package com.photoselector.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;

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
        int textWidth = WIDTH - PAD * 2;
        JLabel text = new JLabel();
        text.setFont(Theme.FONT);
        text.setForeground(Theme.TEXT);
        text.setVerticalAlignment(SwingConstants.TOP);
        // HTML 那个块会收缩到文字宽，光靠 text-align 只在这个块里生效；
        // 把标签本身的水平对齐也设成居中，整块才会落在框的中线上
        text.setHorizontalAlignment(SwingConstants.CENTER);

        // 自己按像素宽度折行，再把每行用 <br> 拼成居中的 HTML。
        // 不能把 width 写进 HTML：Swing 的 HTML 会把 px 按点距再放大一截，
        // 排版宽度和标签实际宽度对不上，字会整块被推到右边去（实测偏了 80 像素）。
        FontMetrics metrics = text.getFontMetrics(Theme.FONT);
        text.setText(centeredHtml(wrap(message, metrics, textWidth)));

        int naturalHeight = text.getPreferredSize().height;
        Dimension wanted = new Dimension(textWidth, Math.min(naturalHeight, MAX_TEXT_HEIGHT));
        text.setPreferredSize(wanted);

        JComponent bodyContent = text;
        if (naturalHeight > MAX_TEXT_HEIGHT) {
            JScrollPane scroll = new JScrollPane(text,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(null);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            Theme.themeScrollBar(scroll.getVerticalScrollBar());
            scroll.setPreferredSize(wanted);
            bodyContent = scroll;
        }

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(Theme.PANEL);
        body.setBorder(BorderFactory.createEmptyBorder(16, PAD, 16, PAD));
        body.add(bodyContent, BorderLayout.CENTER);
        return body;
    }

    /** 把每一行拼成居中的 HTML；转义交给 {@link #escape}。 */
    private static String centeredHtml(List<String> lines) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                body.append("<br>");
            }
            body.append(escape(lines.get(i)));
        }
        return "<html><div style='text-align:center;'>" + body + "</div></html>";
    }

    /**
     * 按像素宽度折行。
     *
     * <p>逐字量宽再断行——中文没有词间空格，这样最稳妥；换行符本身也保留成空行。
     */
    private static List<String> wrap(String message, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : message.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            int width = 0;
            for (int i = 0; i < paragraph.length(); i++) {
                char c = paragraph.charAt(i);
                int charWidth = metrics.charWidth(c);
                if (width + charWidth > maxWidth && line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                    width = 0;
                }
                line.append(c);
                width += charWidth;
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

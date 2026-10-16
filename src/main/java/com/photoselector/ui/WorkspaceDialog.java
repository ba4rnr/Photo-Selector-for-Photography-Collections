package com.photoselector.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import com.photoselector.core.Workspace;
import com.photoselector.ui.theme.Theme;

/**
 * 「选择工作区」对话框：选素材根文件夹、勾掉不想看的子文件夹、选目标文件夹。
 *
 * <p>中间那棵目录树和主界面左栏是同一个组件——设计里就说了这两处要共用，
 * 免得同一套"选中 / 剔除"的交互写两遍、还越走越偏。
 *
 * <p>选文件夹交给自制的 {@link FolderPickerDialog}：JDK 自带的 {@code JFileChooser}
 * 是浅色系统外观，在这套深色界面里很跳，而且风格不受我们控制。
 *
 * <p>用模态对话框：工作区没定下来之前，主界面上的操作没有意义。
 */
@SuppressWarnings("serial")
final class WorkspaceDialog extends JDialog {

    private final SourceTreePanel tree = new SourceTreePanel();
    private final JTextField sourceField = pathField();
    private final JTextField targetField = pathField();
    private final JButton confirmButton = button("确定");

    /** 点了"确定"之后才有值；点取消或直接关掉就是 null。 */
    private Workspace result;

    WorkspaceDialog(Window owner, Workspace current) {
        super(owner, "选择工作区", ModalityType.APPLICATION_MODAL);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(buildTreeArea(), BorderLayout.CENTER);
        content.add(buildButtons(), BorderLayout.SOUTH);

        setContentPane(content);
        getRootPane().setDefaultButton(confirmButton); // 回车即确定

        if (current != null) {
            sourceField.setText(current.sourceRoot().toString());
            tree.setRoot(current.sourceRoot());
            tree.setExcluded(current.excludedFolders());
            if (current.targetFolder() != null) {
                targetField.setText(current.targetFolder().toString());
            }
        }

        pack();
        setLocationRelativeTo(owner);
    }

    /** 打开对话框；用户点了"确定"就返回新的工作区。 */
    static Optional<Workspace> show(Window owner, Workspace current) {
        WorkspaceDialog dialog = new WorkspaceDialog(owner, current);
        dialog.setVisible(true);
        return Optional.ofNullable(dialog.result);
    }

    /** 只给测试用：当前在树上勾掉的文件夹。 */
    java.util.Set<Path> excludedForTest() {
        return tree.excludedFolders();
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(Theme.PANEL);

        header.add(pathRow("素材根文件夹", sourceField, this::chooseSource));
        header.add(Box.createVerticalStrut(6));
        header.add(hint("展开下面这棵树，取消勾选就是不纳入范围（剔除）。"));
        header.add(Box.createVerticalStrut(6));
        return header;
    }

    private JPanel buildTreeArea() {
        JPanel area = new JPanel(new BorderLayout(0, 10));
        area.setBackground(Theme.PANEL);

        tree.setPreferredSize(new Dimension(520, 260));
        area.add(tree, BorderLayout.CENTER);
        area.add(pathRow("目标文件夹", targetField, this::chooseTarget), BorderLayout.SOUTH);
        return area;
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBackground(Theme.PANEL);

        JButton cancel = button("取消");
        cancel.addActionListener(e -> dispose());

        confirmButton.addActionListener(e -> confirmAndClose());

        buttons.add(cancel);
        buttons.add(confirmButton);
        return buttons;
    }

    private void confirmAndClose() {
        Path root = textOf(sourceField);
        if (root == null || !Files.isDirectory(root)) {
            JOptionPane.showMessageDialog(this, "请先选一个存在的素材根文件夹。",
                    "还差一步", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path target = textOf(targetField);
        if (target != null && !Files.isDirectory(target)) {
            JOptionPane.showMessageDialog(this, "目标文件夹不存在：" + target,
                    "还差一步", JOptionPane.WARNING_MESSAGE);
            return;
        }

        result = new Workspace(root, tree.excludedFolders(), target);
        dispose();
    }

    private void chooseSource() {
        Path picked = chooseDirectory("选择素材根文件夹", textOf(sourceField));
        if (picked != null) {
            sourceField.setText(picked.toString());
            tree.setRoot(picked); // 换根之后剔除项自然作废，重新勾
        }
    }

    private void chooseTarget() {
        Path picked = chooseDirectory("选择目标文件夹", textOf(targetField));
        if (picked != null) {
            targetField.setText(picked.toString());
        }
    }

    private Path chooseDirectory(String title, Path initial) {
        return FolderPickerDialog.show(this, title, initial).orElse(null);
    }

    private JPanel pathRow(String label, JTextField field, Runnable onBrowse) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBackground(Theme.PANEL);
        row.add(label(label), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);

        JButton browse = button("浏览…");
        browse.addActionListener(e -> onBrowse.run());
        row.add(browse, BorderLayout.EAST);
        return row;
    }

    private static Path textOf(JTextField field) {
        String text = field.getText().trim();
        return text.isEmpty() ? null : Path.of(text);
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Theme.TEXT);
        label.setFont(Theme.FONT);
        return label;
    }

    private static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Theme.TEXT_MUTED);
        label.setFont(Theme.FONT_SMALL);
        return label;
    }

    private static JTextField pathField() {
        JTextField field = new JTextField();
        field.setBackground(Theme.CANVAS);
        field.setForeground(Theme.TEXT);
        field.setCaretColor(Theme.TEXT);
        field.setFont(Theme.FONT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        return field;
    }

    private static JButton button(String text) {
        return new FlatButton(text, true);
    }
}

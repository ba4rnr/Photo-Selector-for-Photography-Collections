package com.photoselector.ui;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JPanel;

import com.photoselector.ui.theme.Icons;
import com.photoselector.ui.theme.Theme;

/**
 * 顶部快捷工具栏。排布参照 Capture One，分三组：
 * 左组文件与历史、中组光标工具、右组视图辅助。
 *
 * <p>骨架阶段只搭出结构和"选中即高亮"的交互，
 * 每个按钮具体干什么，将在后续课程里逐个接上。
 */
@SuppressWarnings("serial")
final class ToolBarPanel extends JPanel {

    /** 中组那一排工具：它们互斥，同一时刻只有一个处于激活状态。 */
    private final List<ToolButton> cursorTools = new ArrayList<>();

    /** 当前选中的光标工具。默认"选择"——一开始就是要挑照片。 */
    private CursorTool activeTool = CursorTool.SELECT;

    /** 工具换了时通知外部（大图据此决定拖动是拖照片还是平移画面）。 */
    private java.util.function.Consumer<CursorTool> onToolChanged = tool -> { };

    /** 最右边的模式切换按钮。 */
    private final ToolButton modeButton = new ToolButton("整理模式", Icons.layout());

    /** 左组那两个要接实际功能的按钮。 */
    private final ToolButton chooseWorkspaceButton = new ToolButton("重新选择工作区", Icons.folder());
    private final ToolButton saveButton = new ToolButton("保存", Icons.save());
    private final ToolButton resetButton = new ToolButton("重置", Icons.reset());
    private final ToolButton undoButton = new ToolButton("撤销", Icons.undo());
    private final ToolButton redoButton = new ToolButton("重做", Icons.redo());
    private final ToolButton fitButton = new ToolButton("适应窗口", Icons.fitToWindow());

    ToolBarPanel() {
        super(new FlowLayout(FlowLayout.LEFT, 4, 5));
        setBackground(Theme.PANEL);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));

        // 左组 · 文件与历史
        add(chooseWorkspaceButton);
        add(saveButton);
        add(resetButton);
        add(undoButton);
        add(redoButton);

        add(Box.createHorizontalStrut(28)); // 用留白分组，不画竖线，跟 Capture One 一致

        // 中组 · 光标工具
        for (CursorTool cursorTool : CursorTool.values()) {
            ToolButton button = new ToolButton(cursorTool.label(), iconFor(cursorTool));
            button.addActionListener(e -> activate(button, cursorTool));
            cursorTools.add(button);
            add(button);
        }
        activate(cursorTools.get(0), CursorTool.SELECT); // 默认选中"选择"

        add(Box.createHorizontalStrut(28));

        // 右组 · 视图辅助
        add(new ToolButton("网格", Icons.grid()));
        add(fitButton);
        add(modeButton);
    }

    /** 供主面板挂监听：点一下就切换视图模式。 */
    ToolButton modeButton() {
        return modeButton;
    }

    /** 供外部挂监听：重新选择工作区。 */
    ToolButton chooseWorkspaceButton() {
        return chooseWorkspaceButton;
    }

    /** 供外部挂监听：保存。 */
    ToolButton saveButton() {
        return saveButton;
    }

    /** 供外部挂监听：重置（清空当前照片的全部图像编辑）。 */
    ToolButton resetButton() {
        return resetButton;
    }

    /** 供外部挂监听：撤销。 */
    ToolButton undoButton() {
        return undoButton;
    }

    /** 供外部挂监听：重做。 */
    ToolButton redoButton() {
        return redoButton;
    }

    /** 供外部挂监听：大图适应窗口。 */
    ToolButton fitButton() {
        return fitButton;
    }

    /** 当前选中的光标工具。 */
    CursorTool activeTool() {
        return activeTool;
    }

    /** 工具换了时通知外部。 */
    void setOnToolChanged(java.util.function.Consumer<CursorTool> listener) {
        this.onToolChanged = listener == null ? tool -> { } : listener;
    }

    /** 用代码选中某个工具，等价于点了那个按钮。 */
    void selectTool(CursorTool tool) {
        activate(cursorTools.get(tool.ordinal()), tool);
    }

    /** 兼容旧测试名；行为和 {@link #selectTool} 完全相同。 */
    void setActiveToolForTest(CursorTool tool) {
        selectTool(tool);
    }

    /** 取某个工具的按钮（中组那一排）。给测试用。 */
    ToolButton toolButton(CursorTool tool) {
        return cursorTools.get(tool.ordinal());
    }

    /** 切换模式后，更新按钮上的文字，让它显示"点了会去哪"。 */
    void setMode(ViewMode mode) {
        modeButton.setText(mode == ViewMode.BROWSE ? "整理模式" : "浏览模式");
    }

    /**
     * 选中一个工具。
     *
     * <p>旋转 / 翻转是"点一下马上做一次"的动作型工具：再点一次同一个按钮
     * 也要再做一次（所以动作型工具每次都通知外部）。选择 / 平移是"模式"，
     * 点在已经选中的那颗上不必重复通知。
     */
    private void activate(ToolButton tool, CursorTool which) {
        boolean isAction = which == CursorTool.ROTATE || which == CursorTool.FLIP;
        if (!isAction && activeTool == which) {
            return;
        }
        for (ToolButton other : cursorTools) {
            other.setActive(other == tool);
        }
        int index = cursorTools.indexOf(tool);
        if (index >= 0) {
            activeTool = which;
            onToolChanged.accept(activeTool);
        }
    }

    private static Icon iconFor(CursorTool tool) {
        return switch (tool) {
            case SELECT -> Icons.select();
            case PAN -> Icons.pan();
            case CROP -> Icons.crop();
            case ROTATE -> Icons.rotate();
            case FLIP -> Icons.flip();
        };
    }
}

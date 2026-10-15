package com.photoselector.ui;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JPanel;

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

    /** 最右边的模式切换按钮。 */
    private final ToolButton modeButton = new ToolButton("整理模式");

    ToolBarPanel() {
        super(new FlowLayout(FlowLayout.LEFT, 4, 5));
        setBackground(Theme.PANEL);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));

        // 左组 · 文件与历史
        add(new ToolButton("重新选择工作区"));
        add(new ToolButton("保存"));
        add(new ToolButton("重置"));
        add(new ToolButton("撤销"));
        add(new ToolButton("重做"));

        add(Box.createHorizontalStrut(28)); // 用留白分组，不画竖线，跟 Capture One 一致

        // 中组 · 光标工具
        for (String name : new String[] {"选择", "平移", "裁切", "旋转", "翻转"}) {
            ToolButton tool = new ToolButton(name);
            tool.addActionListener(e -> activate(tool));
            cursorTools.add(tool);
            add(tool);
        }
        activate(cursorTools.get(0)); // 默认选中"选择"

        add(Box.createHorizontalStrut(28));

        // 右组 · 视图辅助
        add(new ToolButton("网格"));
        add(new ToolButton("适应窗口"));
        add(modeButton);
    }

    /** 供主面板挂监听：点一下就切换视图模式。 */
    ToolButton modeButton() {
        return modeButton;
    }

    /** 切换模式后，更新按钮上的文字，让它显示"点了会去哪"。 */
    void setMode(ViewMode mode) {
        modeButton.setText(mode == ViewMode.BROWSE ? "整理模式" : "浏览模式");
    }

    private void activate(ToolButton tool) {
        for (ToolButton other : cursorTools) {
            other.setActive(other == tool);
        }
    }
}

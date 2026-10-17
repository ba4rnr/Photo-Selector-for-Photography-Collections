package com.photoselector.ui;

/**
 * 图像工具：工具栏中间那一排互斥的工具。
 *
 * <p>它决定"在大图上按住左键拖动"是什么意思——这正是选择与平移两个工具存在的理由：
 * <ul>
 *   <li>{@link #SELECT}：拖动是把照片拖出去（拖到目标文件夹那一块）</li>
 *   <li>{@link #PAN}：拖动是在画面里平移</li>
 * </ul>
 *
 * <p>声明顺序要和工具栏上摆的顺序一致，两者按序号对应。
 */
public enum CursorTool {

    SELECT("选择"),
    PAN("平移"),
    CROP("裁切"),
    ROTATE("旋转"),
    FLIP("翻转");

    private final String label;

    CursorTool(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

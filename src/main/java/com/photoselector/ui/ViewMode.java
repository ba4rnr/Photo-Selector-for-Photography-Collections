package com.photoselector.ui;

/**
 * 界面的两种状态（见《软件设计》第 1 节）。
 */
public enum ViewMode {

    /** 浏览模式：中间看素材大图，底部是素材照片条，右侧是目标文件夹预览。 */
    BROWSE("浏览模式"),

    /** 整理模式：中间放大目标文件夹的缩略图，底部是目标文件夹的照片，右侧是垃圾桶。 */
    ARRANGE("整理模式");

    private final String label;

    ViewMode(String label) {
        this.label = label;
    }

    /** 中文显示名，菜单里用。 */
    public String label() {
        return label;
    }
}

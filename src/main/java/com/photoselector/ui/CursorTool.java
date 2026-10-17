package com.photoselector.ui;

import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import com.photoselector.ui.theme.Icons;
import com.photoselector.ui.theme.Theme;

/**
 * 光标工具：工具栏中间那一排互斥的工具。
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

    /**
     * 这个工具对应的鼠标光标：<b>直接把工具栏上那个图标放大画出来</b>。
     *
     * <p>为什么不各画一套：按钮上的图和鼠标下的图必须是同一个形状，
     * 不然"我按的是这个，怎么光标长得不一样"。所以这里只从 {@link Icons}
     * 取一次图，缩放、加描边，再交给系统做光标——图标库永远是唯一出处。
     *
     * <p>光标只有 32 像素的方寸，细线条在浅色照片上会看不见，所以先画一层
     * 深色粗描边当"底"，再把图标本身用浅色叠上去：相当于自带描边。
     */
    public Cursor cursor() {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 18.0, size / 18.0); // 图标是按 18×18 设计的，等比放大

        // 底：深色粗一圈，保证在浅色照片上也看得见（深色界面里靠上面那层浅色撑住）
        g.setColor(Theme.CANVAS);
        g.setStroke(new java.awt.BasicStroke(2.8f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        Icons.paintShape(this, g);

        // 面：浅色细一点，形状和按钮上的图标完全一致
        g.setColor(Theme.TEXT);
        g.setStroke(new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        Icons.paintShape(this, g);
        g.dispose();

        Image scaled = image;
        return java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
                scaled, new Point(size / 2, size / 2), name());
    }
}

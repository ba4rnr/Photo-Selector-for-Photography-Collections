package com.photoselector.ui.theme;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

import javax.swing.Icon;

/**
 * 工具栏图标的矢量绘制。
 *
 * <p>为什么不用图片文件：矢量画出来的图标天然清晰，任何缩放都不糊；
 * 颜色能跟着按钮的状态走（普通是浅灰、激活是暖橙），不必为每个状态各导一套图。
 * 全部图标都画在 18×18 的方格内，线条风格统一。
 */
public final class Icons {

    /** 图标画布边长。 */
    private static final int SIZE = 18;

    /** 线条粗细，1.4 在深色底上不刺眼又看得清。 */
    private static final float STROKE = 1.4f;

    private Icons() {
    }

    // ─────────────────────────── 左组：文件与历史 ───────────────────────────

    /** 文件：一页带折角的纸。目录树里给照片文件用。 */
    public static Icon file() {
        return stroke(g -> {
            Path2D page = new Path2D.Double();
            page.moveTo(4, 2);
            page.lineTo(10.5, 2);
            page.lineTo(14, 5.5);
            page.lineTo(14, 16);
            page.lineTo(4, 16);
            page.closePath();
            g.draw(page);
            // 折角
            g.draw(new Line2D.Double(10.5, 2, 10.5, 5.5));
            g.draw(new Line2D.Double(10.5, 5.5, 14, 5.5));
        });
    }

    /** 目录树上展开 / 收起用的小三角。 */
    public static Icon triangle(boolean pointingDown) {
        return filled(g -> {
            Path2D shape = new Path2D.Double();
            if (pointingDown) {
                shape.moveTo(1, 3);
                shape.lineTo(9, 3);
                shape.lineTo(5, 8);
            } else {
                shape.moveTo(3, 1);
                shape.lineTo(3, 9);
                shape.lineTo(8, 5);
            }
            shape.closePath();
            g.fill(shape);
        });
    }

    /** 重新选择工作区：文件夹。 */
    public static Icon folder() {
        return stroke(g -> {
            Path2D path = new Path2D.Double();
            path.moveTo(2, 5.5);
            path.lineTo(7, 5.5);
            path.lineTo(9, 8);
            path.lineTo(16, 8);
            path.lineTo(16, 15);
            path.lineTo(2, 15);
            path.closePath();
            g.draw(path);
        });
    }

    /** 保存：向下写进托盘。 */
    public static Icon save() {
        return stroke(g -> {
            g.draw(new Line2D.Double(9, 2.5, 9, 11));
            arrowHead(g, 9, 11, 0, 1);
            g.draw(new Line2D.Double(3, 12.5, 3, 15.5));
            g.draw(new Line2D.Double(3, 15.5, 15, 15.5));
            g.draw(new Line2D.Double(15, 15.5, 15, 12.5));
        });
    }

    /** 重置：绕一圈回到起点。 */
    public static Icon reset() {
        return stroke(g -> arcArrow(g, 9, 9, 6, 60, 280));
    }

    /** 撤销：向左回卷的箭头。 */
    public static Icon undo() {
        return stroke(g -> arcArrow(g, 9, 10, 5.5, 20, 140));
    }

    /** 重做：撤销的镜像。 */
    public static Icon redo() {
        return stroke(g -> arcArrow(g, 9, 10, 5.5, 160, -140));
    }

    // ─────────────────────────── 中组：光标工具 ───────────────────────────

    /** 选择：经典箭头光标。 */
    public static Icon select() {
        return stroke(g -> {
            Path2D path = new Path2D.Double();
            path.moveTo(3.5, 2);
            path.lineTo(3.5, 13);
            path.lineTo(6.5, 10.2);
            path.lineTo(8.6, 15);
            path.lineTo(10.4, 14.2);
            path.lineTo(8.4, 9.6);
            path.lineTo(12.4, 9.4);
            path.closePath();
            g.draw(path);
        });
    }

    /** 平移：一只手。 */
    public static Icon pan() {
        return stroke(g -> {
            g.draw(new RoundRectangle2D.Double(4.6, 9, 9.6, 6.6, 3.4, 3.4));
            // 三根手指的底边正好落在掌沿上，于是线条自然连成一体
            finger(g, 6.0, 4.6);
            finger(g, 8.4, 3.4);
            finger(g, 10.8, 4.6);
            // 拇指
            Path2D thumb = new Path2D.Double();
            thumb.moveTo(4.6, 11.6);
            thumb.quadTo(2.2, 11.4, 2.4, 9.4);
            thumb.quadTo(2.6, 7.8, 4.6, 8.6);
            g.draw(thumb);
        });
    }

    /** 裁切：两道直角裁切标。 */
    public static Icon crop() {
        return stroke(g -> {
            g.draw(new Line2D.Double(5, 2, 5, 13));
            g.draw(new Line2D.Double(5, 13, 16, 13));
            g.draw(new Line2D.Double(2, 5, 13, 5));
            g.draw(new Line2D.Double(13, 5, 13, 16));
        });
    }

    /** 旋转：相片上方一道弧形箭头。 */
    public static Icon rotate() {
        return stroke(g -> {
            // 中间是相片，外面绕一圈带箭头的弧，一眼能看出"把这张转一下"
            g.draw(new Rectangle2D.Double(5.5, 5.5, 7, 7));
            arcArrow(g, 9, 9, 7, 20, 140);
        });
    }

    /** 翻转：中轴两侧的镜像三角。 */
    public static Icon flip() {
        return stroke(g -> {
            // 中轴画成虚线，一眼就能看出"沿这条线翻"
            g.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[] {2.5f, 2.5f}, 0f));
            g.draw(new Line2D.Double(9, 2, 9, 16));
            g.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            Path2D left = new Path2D.Double();
            left.moveTo(2, 4.5);
            left.lineTo(7, 9);
            left.lineTo(2, 13.5);
            left.closePath();
            g.draw(left);

            Path2D right = new Path2D.Double();
            right.moveTo(16, 4.5);
            right.lineTo(11, 9);
            right.lineTo(16, 13.5);
            right.closePath();
            g.draw(right);
        });
    }

    // ─────────────────────────── 右组：视图辅助 ───────────────────────────

    /**
     * 目录树上的勾选框：勾上表示这个文件夹纳入范围。
     *
     * <p>占据的是树节点的"图标位"，所以它同时也就是点击热区——
     * 点在它上面是切换勾选，点在别处是选中节点。
     */
    public static Icon checkBox(boolean checked) {
        return stroke(g -> {
            g.draw(new Rectangle2D.Double(3, 3, 12, 12));
            if (checked) {
                Path2D tick = new Path2D.Double();
                tick.moveTo(5.8, 9.2);
                tick.lineTo(8.2, 11.6);
                tick.lineTo(12.6, 6.2);
                g.draw(tick);
            }
        });
    }

    /** 网格：田字。 */
    public static Icon grid() {
        return stroke(g -> {
            g.draw(new Rectangle2D.Double(2.5, 2.5, 13, 13));
            g.draw(new Line2D.Double(9, 2.5, 9, 15.5));
            g.draw(new Line2D.Double(2.5, 9, 15.5, 9));
        });
    }

    /** 适应窗口：四角向外撑开。 */
    public static Icon fitToWindow() {
        return stroke(g -> {
            g.draw(new Rectangle2D.Double(6, 6, 6, 6));
            corner(g, 6, 6, -1, -1);
            corner(g, 12, 6, 1, -1);
            corner(g, 6, 12, -1, 1);
            corner(g, 12, 12, 1, 1);
        });
    }

    /** 视图模式：一个窗口，底部带一条照片条。 */
    public static Icon layout() {
        return stroke(g -> {
            g.draw(new Rectangle2D.Double(2.5, 3.5, 13, 11));
            g.draw(new Line2D.Double(2.5, 11, 15.5, 11));
        });
    }

    // ─────────────────────────── 绘制辅助 ───────────────────────────

    /**
     * 把一段绘制代码包装成 Icon。
     *
     * <p>颜色不是写死的，而是取按钮当前的前景色——
     * 按钮被激活时会把前景色改成暖橙，图标自然就跟着亮了。
     */
    private static Icon stroke(Consumer<Graphics2D> painter) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.translate(x, y);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(c == null ? Theme.TEXT : c.getForeground());
                    g2.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    painter.accept(g2);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return SIZE;
            }

            @Override
            public int getIconHeight() {
                return SIZE;
            }
        };
    }

    /** 和 {@link #stroke} 一样，只是把颜色填满而不是描边。 */
    private static Icon filled(Consumer<Graphics2D> painter) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.translate(x, y);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(c == null ? Theme.TEXT : c.getForeground());
                    painter.accept(g2);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return 10;
            }

            @Override
            public int getIconHeight() {
                return 10;
            }
        };
    }

    /** 画一段圆弧，并在弧的终点补上箭头；extent 为负表示顺时针。 */
    private static void arcArrow(Graphics2D g, double cx, double cy, double r,
                                 double startDeg, double extentDeg) {
        g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, startDeg, extentDeg, Arc2D.OPEN));

        double endRad = Math.toRadians(startDeg + extentDeg);
        double sign = Math.signum(extentDeg);
        // 屏幕坐标里 y 轴朝下，所以"逆时针"的切线方向是 (-sin, -cos)
        arrowHead(g,
                cx + r * Math.cos(endRad),
                cy - r * Math.sin(endRad),
                sign * -Math.sin(endRad),
                sign * -Math.cos(endRad));
    }

    /** 从 (x,y) 朝 (dx,dy) 方向画一小段带箭头的短线，长度克制些免得挤成一团。 */
    private static void corner(Graphics2D g, double x, double y, double dx, double dy) {
        double length = 3.4;
        double norm = Math.hypot(dx, dy);
        double tipX = x + dx / norm * length;
        double tipY = y + dy / norm * length;
        g.draw(new Line2D.Double(x, y, tipX, tipY));
        arrowHead(g, tipX, tipY, dx, dy, 2.6);
    }

    /** 在 (x,y) 处画一个 V 形箭头，开口朝 (dx,dy) 的反方向，即"箭头指向 (dx,dy)"。 */
    private static void arrowHead(Graphics2D g, double x, double y, double dx, double dy) {
        arrowHead(g, x, y, dx, dy, 3.4);
    }

    private static void arrowHead(Graphics2D g, double x, double y, double dx, double dy, double length) {
        double norm = Math.hypot(dx, dy);
        if (norm == 0) {
            return;
        }
        double angle = Math.atan2(dy / norm, dx / norm);
        double spread = Math.toRadians(32);

        g.draw(new Line2D.Double(x, y,
                x - length * Math.cos(angle - spread), y - length * Math.sin(angle - spread)));
        g.draw(new Line2D.Double(x, y,
                x - length * Math.cos(angle + spread), y - length * Math.sin(angle + spread)));
    }

    /** 一根手指：两条竖线加一个圆头，底边不封口，好和手掌连成一体。 */
    private static void finger(Graphics2D g, double x, double topY) {
        double width = 2.2;
        double r = width / 2;
        Path2D path = new Path2D.Double();
        path.moveTo(x, 9);
        path.lineTo(x, topY + r);
        path.quadTo(x, topY, x + r, topY);
        path.quadTo(x + width, topY, x + width, topY + r);
        path.lineTo(x + width, 9);
        g.draw(path);
    }
}

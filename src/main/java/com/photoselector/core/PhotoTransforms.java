package com.photoselector.core;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * 把"虚拟编辑"画到像素上：顺时针转 90° 的整数倍，再按需上下翻。
 *
 * <p>分成独立的一个类，是为了"画面怎么变"这件事只有一处定义——
 * 大图和保存重写都用它生成同一张结果图。
 * 两处各写一份的话，早晚会出现"屏幕上和存下来不一样"。
 */
public final class PhotoTransforms {

    private PhotoTransforms() {
    }

    /**
     * 按编辑状态生成一张新的图；没编辑时原样返回（不拷贝）。
     *
     * <p>用仿射变换一次画完，而不是分几步先转再翻：少一次中间图，
     * 像素只经过一次重采样，画质损失最小。
     */
    public static BufferedImage apply(BufferedImage source, EditState edits) {
        if (source == null || edits == null || edits.isNone()) {
            return source;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = edits.quarters() % 2 == 1; // 转 90°/270° 时宽高互换

        BufferedImage out = new BufferedImage(
                swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 目标坐标 = 旋转矩阵 · 源坐标 + 平移。四种角度的平移量按"转完之后
        // 图片要落在画布内、不裁不空"来定（宽高互换时用另一套）。
        AffineTransform t = switch (edits.quarters()) {
            case 1 -> new AffineTransform(0, 1, -1, 0, h, 0);   // 顺时针 90°
            case 2 -> new AffineTransform(-1, 0, 0, -1, w, h);  // 180°
            case 3 -> new AffineTransform(0, -1, 1, 0, 0, w);   // 顺时针 270°
            default -> new AffineTransform();                   // 不转
        };
        if (edits.flipVertical()) {
            // 上下翻是在"已经转好的画面"上做的：绕画布中线翻。
            // 画布高度 = 转完之后的高度（swap ? w : h）
            int outHeight = swap ? w : h;
            t.preConcatenate(new AffineTransform(1, 0, 0, -1, 0, outHeight));
        }
        g.drawImage(source, t, null);
        g.dispose();
        return out;
    }
}

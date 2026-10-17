package com.photoselector.core;

/**
 * 相对最终显示画面的一块裁切区域，四个值都在 0..1 之间。
 *
 * <p>用比例而不是像素：同一组编辑既要能作用在屏幕预览上，也要能作用在原尺寸图上，
 * 保存时换成原图像素才不会因为预览缩放而偏一截。
 */
public record CropRect(double x, double y, double width, double height) {

    private static final double EPSILON = 1e-6;

    public CropRect {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(width) || !Double.isFinite(height)) {
            throw new IllegalArgumentException("裁切参数必须是有限数值");
        }
        if (width <= EPSILON || height <= EPSILON) {
            throw new IllegalArgumentException("裁切宽高必须为正");
        }
        // 旋转 / 镜像后浮点运算偶尔会给出 1.0000000000000002；夹回边界即可。
        x = snap(clamp01(x));
        y = snap(clamp01(y));
        width = snap(Math.min(width, 1.0 - x));
        height = snap(Math.min(height, 1.0 - y));
        if (width <= EPSILON || height <= EPSILON) {
            throw new IllegalArgumentException("裁切区域必须落在画面以内");
        }
    }

    /** 把区域绕画面中心顺时针转一个直角。 */
    public CropRect rotateClockwise() {
        return new CropRect(1.0 - y - height, x, height, width);
    }

    /** 水平镜像。 */
    public CropRect flipHorizontally() {
        return new CropRect(1.0 - x - width, y, width, height);
    }

    /** 垂直镜像。 */
    public CropRect flipVertically() {
        return new CropRect(x, 1.0 - y - height, width, height);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 连续旋转 / 镜像会产生 0.09999999999999992 这类尾差；固定到纳米精度。 */
    private static double snap(double value) {
        return Math.round(value * 1_000_000_000d) / 1_000_000_000d;
    }
}

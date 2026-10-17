package com.photoselector.core;

/**
 * 一张照片的虚拟编辑：转了多少个 90°，以及要不要上下镜像。
 *
 * <p>为什么不用"角度 + 两个翻转开关"：那样同一个画面能有好几种写法
 * （转 90° 再左右翻 = 转 90° 再上下翻），保存和撤销时比来比去很容易出错。
 * 这里固定成一种写法——**先顺时针转 n 个 90°，再按需上下翻**——八个方向
 * 一一对应，没有第二种表示。
 *
 * <p>左右翻（镜像）不单独存，转成"转 180° + 上下翻"来记：
 * 因为 {[1,0;0,-1] · R180} 正好等于水平镜像矩阵。所以连续点翻转也不会越记越乱。
 */
public record EditState(int quarters, boolean flipVertical) {

    /** 没动过的状态：不转、不翻。 */
    public static final EditState NONE = new EditState(0, false);

    public EditState {
        quarters = Math.floorMod(quarters, 4); // 0..3，转四圈回到原样
    }

    /** 顺（逆）时针转一个直角。 */
    public EditState rotate(int quarterTurns) {
        return new EditState(quarters + quarterTurns, flipVertical);
    }

    /**
     * 左右镜像。
     *
     * <p>推导：先把当前的画面左右翻，等于在当前方向的基础上
     * "转 180° 并把上下翻反过来"。这样无论之前怎么转过，翻出来的都是你要的那一侧。
     */
    public EditState flipHorizontal() {
        return new EditState(quarters + 2, !flipVertical);
    }

    /** 上下镜像。 */
    public EditState flipVertically() {
        return new EditState(quarters, !flipVertical);
    }

    /** 是不是什么都没改。 */
    public boolean isNone() {
        return quarters == 0 && !flipVertical;
    }
}

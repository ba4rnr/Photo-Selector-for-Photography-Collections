package com.photoselector.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/**
 * 把编辑状态画到像素上：宽高、四角的位置都要对得上。
 */
class PhotoTransformsTest {

    /** 2×3 的图，左上角红、右上绿、左下蓝、右下黄。 */
    private static BufferedImage corners() {
        BufferedImage img = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFF0000);
        img.setRGB(1, 0, 0x00FF00);
        img.setRGB(0, 2, 0x0000FF);
        img.setRGB(1, 2, 0xFFFF00);
        return img;
    }

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    @Test
    void 没有编辑时原样返回_不拷贝() {
        BufferedImage source = corners();
        assertSame(source, PhotoTransforms.apply(source, EditState.NONE));
    }

    @Test
    void 顺时针转90度_宽高互换_四角各就各位() {
        BufferedImage out = PhotoTransforms.apply(corners(), EditState.NONE.rotate(1));

        assertEquals(3, out.getWidth(), "2×3 转 90° 之后是 3×2");
        assertEquals(2, out.getHeight());
        // 顺时针：原左上 → 右上；右下 → 左下
        assertEquals(0xFF0000, rgb(out, 2, 0));
        assertEquals(0xFFFF00, rgb(out, 0, 1));
    }

    @Test
    void 上下翻_红线跑到下边() {
        BufferedImage out = PhotoTransforms.apply(corners(), EditState.NONE.flipVertically());

        assertEquals(2, out.getWidth(), "上下翻不换宽高");
        assertEquals(3, out.getHeight());
        assertEquals(0xFF0000, rgb(out, 0, 2), "左上角的红翻到左下");
        assertEquals(0x00FF00, rgb(out, 1, 2));
    }

    @Test
    void 左右翻_红线跑到右边() {
        BufferedImage out = PhotoTransforms.apply(corners(), EditState.NONE.flipHorizontal());

        assertEquals(2, out.getWidth());
        assertEquals(3, out.getHeight());
        assertEquals(0xFF0000, rgb(out, 1, 0), "左上角的红翻到右上");
        assertEquals(0x00FF00, rgb(out, 0, 0));
    }

    @Test
    void 转两次等于上下颠倒() {
        BufferedImage twice = PhotoTransforms.apply(corners(), EditState.NONE.rotate(2));
        BufferedImage flipped = PhotoTransforms.apply(corners(),
                EditState.NONE.flipHorizontal().flipVertically());

        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 2; x++) {
                assertEquals(rgb(flipped, x, y), rgb(twice, x, y),
                        "转 180° 应当和「左右翻 + 上下翻」是同一张图");
            }
        }
    }
}

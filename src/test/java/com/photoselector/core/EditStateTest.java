package com.photoselector.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 虚拟编辑状态的代数：转四次回到原地、翻两次回到原样、左右翻和上下翻差 180°。
 */
class EditStateTest {

    @Test
    void 转四次回到原样() {
        EditState state = EditState.NONE;
        for (int i = 0; i < 4; i++) {
            state = state.rotate(1);
        }
        assertEquals(EditState.NONE, state, "转四个直角就是没转");
        assertTrue(state.isNone());
    }

    @Test
    void 逆着转也能回到原样() {
        EditState state = EditState.NONE.rotate(-1); // 逆时针 90°
        assertEquals(3, state.quarters(), "逆 90° 记成顺时针 270°");
        assertEquals(0, state.rotate(1).quarters(), "再顺 90° 就回来了");
    }

    @Test
    void 翻转两次回到原样() {
        EditState state = EditState.NONE.flipHorizontal().flipHorizontal();
        assertTrue(state.isNone(), "左右翻两次不该留下痕迹");
        assertTrue(EditState.NONE.flipVertically().flipVertically().isNone());
    }

    @Test
    void 左右翻等于转180度再上下翻() {
        EditState leftRight = EditState.NONE.flipHorizontal();
        EditState turnAndFlip = new EditState(2, true);
        assertEquals(turnAndFlip, leftRight, "同一种画面只能有一种写法");
    }

    @Test
    void 转过之后左右翻_仍然是在屏幕上看的那一侧() {
        // 先顺时针转 90°（照片变成竖的），再左右翻：
        // 结果应当等于"再转 180° 并把上下翻反过来"——这是我们约定的规范写法
        EditState rotated = EditState.NONE.rotate(1);
        EditState flipped = rotated.flipHorizontal();

        assertEquals(3, flipped.quarters(), "90° + 180° = 270°");
        assertTrue(flipped.flipVertical(), "翻转位被反过来");
        assertFalse(flipped.isNone());
    }

    @Test
    void 裁切框跟着旋转保持在同一块画面上() {
        CropRect crop = new CropRect(0.1, 0.2, 0.3, 0.4);
        EditState rotated = EditState.NONE.withCrop(crop).rotate(1);

        assertEquals(new CropRect(0.4, 0.1, 0.4, 0.3), rotated.crop(),
                "顺时针转画面，选框也要跟着转，不能留在原来的坐标");
        assertEquals(crop, rotated.rotate(-1).crop(),
                "再逆时针转回来，选框应当回到原位");
    }

    @Test
    void 裁切框跟着镜像翻转() {
        CropRect crop = new CropRect(0.1, 0.2, 0.3, 0.4);
        EditState h = EditState.NONE.withCrop(crop).flipHorizontal();
        EditState v = EditState.NONE.withCrop(crop).flipVertically();

        assertEquals(new CropRect(0.6, 0.2, 0.3, 0.4), h.crop());
        assertEquals(new CropRect(0.1, 0.4, 0.3, 0.4), v.crop());
    }
}

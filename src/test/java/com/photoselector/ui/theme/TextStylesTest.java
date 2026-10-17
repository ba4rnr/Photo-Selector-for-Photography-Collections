package com.photoselector.ui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.FontMetrics;

import javax.swing.JLabel;

import org.junit.jupiter.api.Test;

/**
 * 文字截断：放得下原样返回、放不下要截到宽度以内、路径要保住头和尾。
 */
class TextStylesTest {

    private static FontMetrics metrics() {
        return new JLabel().getFontMetrics(Theme.FONT_SMALL);
    }

    @Test
    void 末尾截断_开头留着_且不超过宽度() {
        FontMetrics m = metrics();
        String text = "L1008244.JPG";

        assertEquals(text, TextStyles.end(text, m, 400), "放得下就原样返回");

        String short_ = TextStyles.end(text, m, 40);
        assertTrue(m.stringWidth(short_) <= 40, "截完必须放得下：" + short_);
        assertTrue(short_.startsWith("L1"), "开头要留着，才认得出是哪一张");
        assertTrue(short_.endsWith("…"), "省略号收尾");
    }

    @Test
    void 中间截断_盘符和文件夹名都保住() {
        FontMetrics m = metrics();
        String path = "C:\\Users\\BA4RNR\\Desktop\\摄影集_大时代";

        String short_ = TextStyles.middle(path, m, 170);

        assertTrue(m.stringWidth(short_) <= 170, "截完必须放得下：" + short_);
        assertTrue(short_.startsWith("C:\\"), "盘符要留着");
        assertTrue(short_.endsWith("摄影集_大时代"), "最后一段文件夹名最有辨识度");
        assertTrue(short_.contains("…"), "省略号表示中间截掉了");
    }

    @Test
    void 宽到放不下时只留省略号_也不崩() {
        FontMetrics m = metrics();
        assertEquals("…", TextStyles.middle("C:\\a\\b", m, 3));
        assertEquals("", TextStyles.end("abc", m, 0));
        assertEquals("", TextStyles.middle(null, m, 100));
    }
}

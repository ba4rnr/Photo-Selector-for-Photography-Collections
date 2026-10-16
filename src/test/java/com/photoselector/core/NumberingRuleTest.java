package com.photoselector.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NumberingRuleTest {

    @Test
    void 默认规则下按位置编号() {
        NumberingRule rule = NumberingRule.DEFAULT;
        assertEquals("010", rule.nameFor(0));
        assertEquals("020", rule.nameFor(1));
        assertEquals("030", rule.nameFor(2));
        assertEquals("0230", rule.nameFor(22));
    }

    @Test
    void 数位自然变长_不补到固定宽度() {
        // 第 10 项 = 100 → "0100"；第 100 项 = 1000 → "01000"
        assertEquals("0100", NumberingRule.DEFAULT.nameFor(9));
        assertEquals("01000", NumberingRule.DEFAULT.nameFor(99));
    }

    @Test
    void 非法参数在创建时就被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new NumberingRule(-1, 10, "0%d"));
        assertThrows(IllegalArgumentException.class, () -> new NumberingRule(10, 0, "0%d"));
        // 格式串里没有 %d、或者混进了别的转换符，都不许放过
        assertThrows(IllegalArgumentException.class, () -> new NumberingRule(10, 10, "0123"));
        assertThrows(IllegalArgumentException.class, () -> new NumberingRule(10, 10, "%s%d"));
    }
}

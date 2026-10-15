package com.photoselector.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NumberingRuleTest {

    @Test
    void 默认规则下按位置编号() {
        NumberingRule rule = NumberingRule.DEFAULT;
        assertEquals("0010", rule.nameFor(0));
        assertEquals("0020", rule.nameFor(1));
        assertEquals("0030", rule.nameFor(2));
        assertEquals("0230", rule.nameFor(22));
    }

    @Test
    void 超过补零位数时自然变长() {
        // 第 1000 项 = 10 + 999*10 = 10000，4 位放不下，直接自然变长
        assertEquals("10000", NumberingRule.DEFAULT.nameFor(999));
    }

    @Test
    void 非法参数在创建时就被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new NumberingRule(-1, 10, 4));
        assertThrows(IllegalArgumentException.class, () -> new NumberingRule(10, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> new NumberingRule(10, 10, 0));
    }
}

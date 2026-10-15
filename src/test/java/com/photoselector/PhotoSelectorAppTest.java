package com.photoselector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 冒烟测试：确认测试链路本身是通的，后续真业务测试从这里开始长。
 */
class PhotoSelectorAppTest {

    @Test
    void 程序名称应为摄影集照片筛选器() {
        assertEquals("摄影集照片筛选器", PhotoSelectorApp.appName());
    }
}

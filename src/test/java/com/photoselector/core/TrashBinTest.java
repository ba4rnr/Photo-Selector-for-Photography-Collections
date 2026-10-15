package com.photoselector.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TrashBinTest {

    @Test
    void 指针按顺序记录_后进先出_清空() {
        TrashBin bin = new TrashBin();
        bin.add(new TargetEntry(Path.of("a.jpg"), Origin.EXISTING), 0);
        bin.add(new TargetEntry(Path.of("b.jpg"), Origin.PENDING), 3);

        assertEquals(2, bin.size());

        // 最后丢进来的先被取出来，且记住的是它原先的位置
        TrashedItem last = bin.removeLast();
        assertEquals("b.jpg", last.entry().fileName());
        assertEquals(3, last.originalIndex());

        bin.clear();
        assertTrue(bin.isEmpty());
    }

    @Test
    void 对空垃圾桶取最后一条会报错() {
        assertThrows(IllegalStateException.class, new TrashBin()::removeLast);
    }
}

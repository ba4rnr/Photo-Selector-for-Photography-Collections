package com.photoselector.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class SessionTest {

    private static final Path PHOTO_A = Path.of("photos", "L1000001.JPG");
    private static final Path PHOTO_B = Path.of("photos", "L1000002.JPG");
    private static final Path PHOTO_C = Path.of("photos", "L1000003.JPG");

    private static Session newSession() {
        return new Session(Path.of("target"), NumberingRule.DEFAULT);
    }

    @Test
    void 挑入后追加到末尾并按位置编号() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);

        assertEquals(List.of("0010", "0020"), session.names());
        assertEquals(Origin.PENDING, session.entryAt(0).origin());
        assertEquals("L1000001.JPG", session.entryAt(0).fileName());
    }

    @Test
    void 同一张照片只会加入一次() {
        Session session = newSession();
        assertTrue(session.add(PHOTO_A));
        assertFalse(session.add(PHOTO_A)); // 重复挑入被忽略
        assertEquals(1, session.size());
    }

    @Test
    void 挪动位置后编号跟着位置现算() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.add(PHOTO_C);

        session.move(0, 2); // A B C → B C A

        assertEquals(List.of("L1000002.JPG", "L1000003.JPG", "L1000001.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList());
        // 名字没有存在任何条目里，位置变了它自然就变
        assertEquals(List.of("0010", "0020", "0030"), session.names());
    }

    @Test
    void 丢进垃圾桶后序列变短且记住原位置() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.add(PHOTO_C);

        session.trash(1);

        assertEquals(2, session.size());
        assertEquals(List.of("0010", "0020"), session.names());
        TrashedItem item = session.trash().items().get(0);
        assertEquals("L1000002.JPG", item.entry().fileName());
        assertEquals(1, item.originalIndex());
    }

    @Test
    void 撤销按后进先出逐步恢复() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.add(PHOTO_C);
        session.trash(1); // A B C → A C

        assertTrue(session.undo()); // 撤销丢垃圾桶 → A B C
        assertEquals(3, session.size());
        assertTrue(session.trash().isEmpty());

        assertTrue(session.undo()); // 撤销挑入 C → A B
        assertEquals(List.of("L1000001.JPG", "L1000002.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList());

        assertTrue(session.undo()); // 撤销挑入 B → A
        assertTrue(session.undo()); // 撤销挑入 A → 空
        assertTrue(session.isEmpty());
        assertFalse(session.undo()); // 没得撤销了
    }

    @Test
    void 已在垃圾桶中的照片不能重复加入() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.trash(0);

        assertFalse(session.add(PHOTO_A));
        assertTrue(session.isEmpty());
        assertEquals(1, session.trash().size());
    }

    @Test
    void 换目标文件夹时清空一切() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.trash(0);

        session.clear();

        assertTrue(session.isEmpty());
        assertTrue(session.trash().isEmpty());
        assertFalse(session.canUndo());
    }

    @Test
    void 索引越界会立刻报错() {
        Session session = newSession();
        session.add(PHOTO_A);
        assertThrows(IndexOutOfBoundsException.class, () -> session.move(5, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> session.trash(5));
    }

    @Test
    void 目标位置越界时会被夹住而不是把序列改坏() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.add(PHOTO_C);

        session.move(0, -5); // 夹到 0，等于原地不动
        assertEquals(List.of("L1000001.JPG", "L1000002.JPG", "L1000003.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList());

        session.move(0, 99); // 夹到末尾
        assertEquals(List.of("L1000002.JPG", "L1000003.JPG", "L1000001.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList());
    }

    @Test
    void 越界失败之后序列必须原封不动() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);

        // 起点的校验必须发生在任何改动之前，否则条目已经离开序列，模型就废了
        assertThrows(IndexOutOfBoundsException.class, () -> session.move(9, 0));
        assertEquals(2, session.size());
        assertEquals(List.of("L1000001.JPG", "L1000002.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList());
    }

    @Test
    void 同一张照片的不同路径写法算同一张() {
        Session session = newSession();

        assertTrue(session.add(PHOTO_A));
        assertFalse(session.add(Path.of("photos", ".", "L1000001.JPG")),
                "带 ./ 的写法不该绕过重复检查");
        assertFalse(session.add(PHOTO_A.toAbsolutePath()),
                "绝对路径也不该绕过重复检查");
        assertEquals(1, session.size());
    }
}

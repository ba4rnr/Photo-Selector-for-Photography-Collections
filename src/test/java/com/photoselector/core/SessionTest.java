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

        assertEquals(List.of("010", "020"), session.names());
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
        assertEquals(List.of("010", "020", "030"), session.names());
    }

    @Test
    void 丢进垃圾桶后序列变短且记住原位置() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.add(PHOTO_C);

        session.trash(1);

        assertEquals(2, session.size());
        assertEquals(List.of("010", "020"), session.names());
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

    @Test
    void 用已有文件铺底之后新挑的照片排在后面() {
        Session session = newSession();
        Path existingA = Path.of("target", "L1000001.JPG");
        Path existingB = Path.of("target", "L1000002.JPG");

        session.seedExisting(List.of(existingA, existingB));

        assertEquals(2, session.size());
        assertEquals(Origin.EXISTING, session.entryAt(0).origin(), "铺底的条目是真实文件");
        assertEquals(List.of("010", "020"), session.names());

        assertTrue(session.add(PHOTO_A));
        assertEquals(3, session.size());
        assertEquals(Origin.PENDING, session.entryAt(2).origin(), "新挑的还没复制过去");
        assertEquals(List.of("010", "020", "030"), session.names());
    }

    @Test
    void 铺底会清掉之前的撤销记录和垃圾桶() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.trash(0);

        session.seedExisting(List.of(Path.of("target", "x.jpg")));

        assertEquals(1, session.size());
        assertTrue(session.trash().isEmpty());
        assertFalse(session.canUndo(), "换了目标文件夹，旧步骤不该还能撤销");
    }

    @Test
    void 从垃圾桶捡回来_插到指定位置() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.add(PHOTO_C);
        session.trash(1); // B 进了垃圾桶

        assertTrue(session.untrash(PHOTO_B, 0), "垃圾桶里有 B，应当捡得回来");

        assertEquals(3, session.size());
        assertEquals(List.of("L1000002.JPG", "L1000001.JPG", "L1000003.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList(),
                "B 应当插在第 0 项之前");
        assertTrue(session.trash().isEmpty(), "捡回来之后垃圾桶里没有它了");
        assertTrue(session.isDirty(), "序列变了，和磁盘又不一样了");
    }

    @Test
    void 连续丢两张再分别捡回来_顺序都对() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.add(PHOTO_C);
        session.trash(0); // 丢 A（原位置 0）
        session.trash(0); // 丢 B（现在排在 0）

        assertEquals(1, session.size(), "只剩 C");
        assertEquals(2, session.trash().size());
        // items() 是按"丢进去的先后"排的：A 在前、B 在后
        assertEquals("L1000001.JPG", session.trash().items().get(0).entry().fileName());
        assertEquals("L1000002.JPG", session.trash().items().get(1).entry().fileName());

        assertTrue(session.untrash(PHOTO_B, 1), "把后丢的那张捡回来，插在 C 后面");

        assertEquals(List.of("L1000003.JPG", "L1000002.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList());
        assertEquals(1, session.trash().size(), "垃圾桶里还剩 A");
        assertEquals("L1000001.JPG", session.trash().items().get(0).entry().fileName());

        assertTrue(session.untrash(PHOTO_A, 0), "再把 A 捡回来");
        assertEquals(List.of("L1000001.JPG", "L1000003.JPG", "L1000002.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList());
        assertTrue(session.trash().isEmpty());
    }

    @Test
    void 撤销捡回来_照片放回垃圾桶里原来那一格() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.add(PHOTO_C);
        session.trash(0); // A 先进
        session.trash(0); // B 后进 → 垃圾桶顺序 [A, B]
        session.untrash(PHOTO_B, 1);

        session.undo();

        assertEquals(2, session.trash().size());
        assertEquals("L1000001.JPG", session.trash().items().get(0).entry().fileName(),
                "A 还在原来那一格");
        assertEquals("L1000002.JPG", session.trash().items().get(1).entry().fileName(),
                "B 回到它原来那一格，而不是被追加到末尾");
    }

    @Test
    void 重做_把撤销掉的挑入再捡回来_位置不丢() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.add(PHOTO_C);
        session.move(0, 2); // A B C → B C A

        assertTrue(session.undo());
        assertEquals(List.of("L1000001.JPG", "L1000002.JPG", "L1000003.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList());

        assertTrue(session.redo(), "撤销过的挪位应当能重做");
        assertEquals(List.of("L1000002.JPG", "L1000003.JPG", "L1000001.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList(),
                "重做要搬回撤销之前的样子");
        assertTrue(session.canUndo(), "重做过的这一步又可以撤销了");
        assertFalse(session.canRedo());
    }

    @Test
    void 重做_丢进垃圾桶也能重做() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.trash(0);
        assertEquals(1, session.trash().size());

        session.undo();
        assertTrue(session.trash().isEmpty(), "撤销之后垃圾桶是空的");
        assertEquals(2, session.size());

        assertTrue(session.redo());
        assertEquals(1, session.size());
        assertEquals(1, session.trash().size(), "重做又把它丢了进去");
        assertEquals("L1000001.JPG", session.trash().items().get(0).entry().fileName());
    }

    @Test
    void 重做_从垃圾桶捡回来也能重做() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.trash(1);
        session.untrash(PHOTO_B, 0);

        session.undo(); // 又回到垃圾桶
        assertEquals(1, session.trash().size());

        assertTrue(session.redo(), "捡回来这一步也该能重做");
        assertEquals(0, session.trash().size());
        assertEquals(List.of("L1000002.JPG", "L1000001.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList(),
                "重做捡回来，位置照旧插在第 0 位之前");
    }

    @Test
    void 撤销之后又做了新操作_重做就作废了() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.undo(); // 撤掉 B
        assertTrue(session.canRedo());

        session.add(PHOTO_C); // 从中间岔出去

        assertFalse(session.canRedo(), "新操作之后，原来那条'未来'不作数了");
        assertFalse(session.redo());
        assertEquals(List.of("L1000001.JPG", "L1000003.JPG"),
                session.entries().stream().map(TargetEntry::fileName).toList());
    }

    @Test
    void 重做栈也会被打扫干净_换文件夹与铺底() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.undo();
        assertTrue(session.canRedo());

        session.seedExisting(List.of(Path.of("target", "x.jpg")));
        assertFalse(session.canRedo(), "铺底之后不该还能重做到上一批照片上");

        session.add(PHOTO_B);
        session.undo();
        assertTrue(session.canRedo());
        session.clear();
        assertFalse(session.canRedo());
    }

    @Test
    void 不在垃圾桶里的照片捡不回来() {
        Session session = newSession();
        session.add(PHOTO_A);

        assertFalse(session.untrash(PHOTO_B, 0), "B 根本没进过垃圾桶");
        assertEquals(1, session.size());
    }

    @Test
    void 撤销捡回来_把它放回垃圾桶() {
        Session session = newSession();
        session.add(PHOTO_A);
        session.add(PHOTO_B);
        session.trash(1);
        session.untrash(PHOTO_B, 0);

        assertTrue(session.undo(), "撤销捡回来这一步");

        assertEquals(1, session.size());
        assertEquals("L1000001.JPG", session.entryAt(0).fileName(), "序列回到只有 A");
        assertEquals(1, session.trash().size(), "B 回到了垃圾桶里");
        assertEquals(PHOTO_B.toAbsolutePath().normalize(),
                session.trash().items().get(0).entry().source());
    }

    @Test
    void 改动过才算脏_铺底和清空都算干净() {
        Session session = newSession();
        assertFalse(session.isDirty(), "刚建起来的中间商和磁盘没有差别");

        session.seedExisting(List.of(Path.of("target", "x.jpg")));
        assertFalse(session.isDirty(), "照磁盘现状铺底，之后并不是脏的");

        session.add(PHOTO_A);
        assertTrue(session.isDirty(), "多挑了一张，磁盘上还没有它");

        // 保存就是把磁盘现状重新铺一遍底，所以这里等价于"保存之后"
        session.seedExisting(List.of(Path.of("target", "010.jpg")));
        assertFalse(session.isDirty(), "刚保存完就该是干净的");

        session.add(PHOTO_B);
        session.move(0, 1);
        assertTrue(session.isDirty());

        session.clear();
        assertFalse(session.isDirty(), "换了目标文件夹，新的一轮从干净开始");
    }

    @Test
    void 撤销之后回到磁盘基线_不再算脏() {
        Session session = newSession();
        session.seedExisting(List.of(Path.of("target", "x.jpg")));
        session.add(PHOTO_A);

        assertTrue(session.isDirty(), "刚加入一张时确实和磁盘不同");
        session.undo(); // 又变回铺底时的样子

        assertFalse(session.isDirty(),
                "撤销之后序列、垃圾桶、编辑都和铺底时一致，不该再拿保存去烦用户");
    }

    @Test
    void 旋转撤销之后也回到干净状态() {
        Session session = newSession();
        session.seedExisting(List.of(Path.of("target", "x.jpg")));

        session.setEdits(PHOTO_A, EditState.NONE.rotate(1));
        assertTrue(session.isDirty(), "编辑会让画面偏离磁盘");

        session.undo();
        assertFalse(session.isDirty(), "撤销编辑之后同样应当回到基线");
    }
}

package com.photoselector.core;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 中间商：本程序的抽象核心（见《软件设计》3.3）。
 *
 * <p>两层模型：界面上的每一步操作（挑入、排序、丢垃圾桶、撤销）都只修改
 * 这个内存对象；磁盘上的真实文件要到「保存」那一刻才被改动。
 *
 * <p>它持有的状态：
 * <ul>
 *   <li>目标序列：有序的目标条目列表，排第几就编几号</li>
 *   <li>垃圾桶：被移除条目的指针清单</li>
 *   <li>撤销栈：每步操作的反向动作，后进先出</li>
 * </ul>
 *
 * <p>线程模型：本类<b>不是</b>线程安全的，设计上只由界面线程（EDT）使用；
 * 真正碰磁盘的活交给保存器在后台做。
 */
public final class Session {

    private final Path targetFolder;
    private final NumberingRule numbering;

    private final List<TargetEntry> sequence = new ArrayList<>();
    private final TrashBin trash = new TrashBin();

    /**
     * 撤销栈：每次修改操作都压入一个"反向动作"（Runnable），
     * 撤销时按后进先出弹出来执行。因为所有操作都只改内存，
     * 反向动作可以简单地写成 lambda，不必为每个操作建一个类。
     */
    private final Deque<Runnable> undoStack = new ArrayDeque<>();

    public Session(Path targetFolder, NumberingRule numbering) {
        this.targetFolder = Objects.requireNonNull(targetFolder, "targetFolder 不能为空");
        this.numbering = Objects.requireNonNull(numbering, "numbering 不能为空");
    }

    public Path targetFolder() {
        return targetFolder;
    }

    public NumberingRule numbering() {
        return numbering;
    }

    public TrashBin trash() {
        return trash;
    }

    public int size() {
        return sequence.size();
    }

    public boolean isEmpty() {
        return sequence.isEmpty();
    }

    /** 只读视图，供界面渲染；跟随内部列表实时变化。 */
    public List<TargetEntry> entries() {
        return Collections.unmodifiableList(sequence);
    }

    public TargetEntry entryAt(int index) {
        return sequence.get(index);
    }

    /** 第 {@code index} 项将来在目标文件夹里的名字：编号规则 + 位置，现算现用。 */
    public String nameAt(int index) {
        return numbering.nameFor(index);
    }

    /** 整个序列的显示名，按当前顺序现算。 */
    public List<String> names() {
        List<String> names = new ArrayList<>(sequence.size());
        for (int i = 0; i < sequence.size(); i++) {
            names.add(nameAt(i));
        }
        return names;
    }

    /**
     * 从素材里挑入一张照片，追加到序列末尾。
     *
     * <p>同一张照片在中间商里只会出现一次：已经躺在序列里、或已经在
     * 垃圾桶里，都算重复，直接忽略（返回 {@code false}）。
     * 从垃圾桶里"捡回来"是后续功能，届时会在这里扩展。
     *
     * @param source 素材文件的路径
     * @return 是否真的加入了（重复时返回 false）
     */
    public boolean add(Path source) {
        Objects.requireNonNull(source, "source 不能为空");
        // 先按同一套规则规范化，再去重。
        // 条目里存的永远是规范化后的路径，这里不跟着规范化的话，
        // "photos/a.jpg" 和绝对路径会被当成两张不同的照片。
        Path normalized = source.toAbsolutePath().normalize();
        if (contains(normalized)) {
            return false;
        }
        TargetEntry entry = new TargetEntry(normalized, Origin.PENDING);
        sequence.add(entry);
        // 反向动作：把刚加入的这条拿掉。
        // 唯一性约束保证此刻序列里恰好只有这一条来自该 source。
        pushUndo(() -> sequence.remove(entry));
        return true;
    }

    /**
     * 用目标文件夹里已有的文件铺底（打开工作区时调用一次）。
     *
     * <p>这些条目是 {@link Origin#EXISTING}：保存时对它们的改动（重命名、删除）
     * 会真的落到磁盘上那些文件上。之后挑进来的照片排在它们后面。
     *
     * <p>会清空垃圾桶和撤销栈——换了目标文件夹，之前那些指针和步骤都没意义了。
     */
    public void seedExisting(List<Path> existingFiles) {
        sequence.clear();
        trash.clear();
        undoStack.clear();
        for (Path file : existingFiles) {
            sequence.add(new TargetEntry(file, Origin.EXISTING));
        }
    }

    /**
     * 把第 {@code from} 项挪到新位置 {@code to}——即移动结束后，
     * 原来在 {@code from} 的条目恰好位于索引 {@code to}（越界则落到末尾）。
     *
     * <p>界面上的拖动排序最终会调用它。只改内存，不碰磁盘；
     * 编号也不用维护，因为名字本来就是按位置现算的。
     */
    public void move(int from, int to) {
        checkIndex(from);
        // 目标位置先夹到合法范围，再动序列。
        // 校验必须发生在任何改动之前——否则一旦中途抛异常，
        // 条目已经离开序列，模型就处于"改了一半"的状态。
        int target = Math.max(0, Math.min(to, sequence.size() - 1));
        if (from == target) {
            return; // 原地没动，不产生无意义的历史
        }
        TargetEntry entry = sequence.remove(from);
        sequence.add(target, entry);
        // 反向动作：把它从新位置搬回原位置
        pushUndo(() -> {
            TargetEntry e = sequence.remove(target);
            sequence.add(Math.min(from, sequence.size()), e);
        });
    }

    /**
     * 把第 {@code index} 项丢进垃圾桶：从序列里移除，
     * 并在垃圾桶里记下"它 + 原位置"的指针。
     */
    public void trash(int index) {
        checkIndex(index);
        TargetEntry entry = sequence.remove(index);
        trash.add(entry, index);
        // 反向动作：从垃圾桶里把它捡出来，放回原位
        pushUndo(() -> {
            TrashedItem item = trash.removeLast();
            sequence.add(Math.min(item.originalIndex(), sequence.size()), item.entry());
        });
    }

    /** 撤销最近一步操作。 */
    public boolean undo() {
        Runnable reversal = undoStack.pollLast();
        if (reversal == null) {
            return false;
        }
        reversal.run();
        return true;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** 换目标文件夹时调用：中间商彻底清空，准备为新文件夹服务。 */
    public void clear() {
        sequence.clear();
        trash.clear();
        undoStack.clear();
    }

    private void pushUndo(Runnable reversal) {
        undoStack.addLast(reversal);
    }

    private boolean contains(Path source) {
        for (TargetEntry entry : sequence) {
            if (entry.source().equals(source)) {
                return true;
            }
        }
        for (TrashedItem item : trash.items()) {
            if (item.entry().source().equals(source)) {
                return true;
            }
        }
        return false;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= sequence.size()) {
            throw new IndexOutOfBoundsException("索引越界：" + index);
        }
    }
}

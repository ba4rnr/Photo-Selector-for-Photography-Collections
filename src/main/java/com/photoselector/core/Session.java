package com.photoselector.core;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * 撤销栈：每次修改操作都压入"正反两支"（见 {@link Action}），
     * 撤销时按后进先出弹出来执行它的反向动作。因为所有操作都只改内存，
     * 两支都能简单地写成 lambda，不必为每个操作建一个类。
     */
    private final Deque<Action> undoStack = new ArrayDeque<>();

    /**
     * 每张照片的虚拟编辑，按来源路径记。
     *
     * <p>为什么不塞进 {@link TargetEntry}：条目是"哪张照片、从哪来"的纯值，
     * 会被到处传递、比较；编辑是随照片走的附加状态，单独记一份更清爽，
     * 保存之后整份清掉也就是"编辑已经烧进 JPG 了"。
     */
    private final Map<Path, EditState> edits = new LinkedHashMap<>();

    /**
     * 重做栈：撤销过的动作原样扣在这儿，重做时再执行一遍它的"正向动作"。
     *
     * <p>为什么存同一份动作：撤销只是"把 A 做完的事反过来做一遍"，
     * 重做就是"再做一遍 A"。所以一次操作记下正反两支就够了，
     * 撤销时把整条从撤销栈搬到重做栈，重做时再搬回来。
     *
     * <p>新做任何一步操作都会把重做栈清空——这是撤销/重做的通例：
     * 从中间岔出去以后，原来那条"未来"就不作数了。
     */
    private final Deque<Action> redoStack = new ArrayDeque<>();

    /** 一次操作的正反两支：undo 把它退回去，redo 再把它做一遍。 */
    private record Action(Runnable undo, Runnable redo) {
    }

    /**
     * 有没有还没落盘的改动。
     *
     * <p>它不是"历史"而是"和磁盘的差距"：只要有一步操作让序列或垃圾桶偏离了
     * 磁盘现状，它就是 true。所以撤销也照样是 true——撤销改回来只是让差距变小，
     * 并没有消失。它唯一的消费者是关窗口时的那一问：没有改动就别拿
     * "是否保存"去烦用户。
     */
    private boolean dirty;

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

    /**
     * 某个素材文件现在排在序列的第几位；不在序列里返回 -1。
     *
     * <p>面板里拖动排序时要靠它把"拖哪一张"换成一个位置。按规范化后的路径比，
     * 不按文件名——目标文件夹里同名不同目录的两张是完全不同的照片。
     */
    public int indexOf(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        for (int i = 0; i < sequence.size(); i++) {
            if (sequence.get(i).source().equals(normalized)) {
                return i;
            }
        }
        return -1;
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
        return insert(sequence.size(), source);
    }

    /**
     * 在指定位置插入一张照片。
     *
     * <p>和 {@link #add} 是同一件事，只是位置不同——拖到某两张之间落下的就是它。
     * 位置会夹到合法范围；同一张照片仍然只允许出现一次。
     *
     * @param index 插在第几项<b>之前</b>；等于当前长度就是追加到末尾
     * @return 是否真的插进去了（重复时返回 false）
     */
    public boolean insert(int index, Path source) {
        Objects.requireNonNull(source, "source 不能为空");
        // 先按同一套规则规范化，再去重。
        // 条目里存的永远是规范化后的路径，这里不跟着规范化的话，
        // "photos/a.jpg" 和绝对路径会被当成两张不同的照片。
        Path normalized = source.toAbsolutePath().normalize();
        if (contains(normalized)) {
            return false;
        }
        int at = Math.max(0, Math.min(index, sequence.size()));
        TargetEntry entry = new TargetEntry(normalized, Origin.PENDING);
        sequence.add(at, entry);
        dirty = true; // 已经多出一条，和磁盘不一样了
        // 反向动作：把刚加入的这条拿掉（唯一性约束保证序列里恰好只有它这一条）。
        // 正向动作：再插回原来的位置——撤销之后又重做时用的就是它。
        pushAction(() -> sequence.remove(entry),
                () -> sequence.add(Math.min(at, sequence.size()), entry));
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
        redoStack.clear();
        edits.clear();
        // 铺底之后，中间商和磁盘是同一份东西——保存也刚走过这一步，
        // 所以"干净"要在最后统一置位，不能跟着前面的 clear 走
        dirty = false;
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
        doMoveEntry(from, target);
        dirty = true; // 位置变了，编号也就变了
        // 撤销 = 从新位置搬回原位置；重做 = 再搬一遍。
        // 都走 doMoveEntry（只动序列、不记历史），免得重做时又把重做栈清掉。
        pushAction(() -> doMoveEntry(target, from), () -> doMoveEntry(from, target));
    }

    /** 真正搬一格：把 {@code from} 处的条目挪到 {@code to} 处。两边都按当前长度夹住。 */
    private void doMoveEntry(int from, int to) {
        int at = Math.max(0, Math.min(from, sequence.size() - 1));
        TargetEntry entry = sequence.remove(at);
        sequence.add(Math.max(0, Math.min(to, sequence.size())), entry);
    }

    /**
     * 把第 {@code index} 项丢进垃圾桶：从序列里移除，
     * 并在垃圾桶里记下"它 + 原位置"的指针。
     */
    public void trash(int index) {
        checkIndex(index);
        doTrashEntry(index);
        dirty = true; // 它离开了序列，保存时得真的从文件夹里消失
        // 撤销 = 从垃圾桶里把它捡出来放回原位；重做 = 再丢进去
        pushAction(() -> {
            TrashedItem last = trash.removeLast();
            sequence.add(Math.min(last.originalIndex(), sequence.size()), last.entry());
        }, () -> doTrashEntry(index));
    }

    /** 真正丢一次：从序列里拿走，在垃圾桶末尾记一条。 */
    private void doTrashEntry(int index) {
        TargetEntry entry = sequence.remove(index);
        trash.add(entry, index);
    }

    /**
     * 把垃圾桶里的一张照片捡回来，插到指定位置——面板上"把照片从垃圾桶拖回目标栏"走这里。
     *
     * <p>和丢进去是对称的：同样只动内存，保存时按新序列重新落盘。
     * 拖回之后编号按新位置现算（垃圾桶里那一条记的还是它原来的号，只是用来对照）。
     *
     * @param source 哪一张（按规范化路径找，垃圾桶里每张照片只会在里面一次）
     * @param index  插到第几项之前；越界会夹到合法范围
     * @return 是否真的捡回来了
     */
    public boolean untrash(Path source, int index) {
        Objects.requireNonNull(source, "source 不能为空");
        Path normalized = source.toAbsolutePath().normalize();

        int at = -1;
        TrashedItem picked = null;
        for (int i = 0; i < trash.items().size(); i++) {
            if (trash.items().get(i).entry().source().equals(normalized)) {
                at = i;
                picked = trash.items().get(i);
                break;
            }
        }
        if (picked == null) {
            return false; // 不在垃圾桶里（可能是刚被换掉的旧目标文件夹），没什么可捡的
        }

        int to = Math.max(0, Math.min(index, sequence.size()));
        trash.removeAt(at);
        sequence.add(to, picked.entry());
        dirty = true;
        // 撤销 = 把刚插进去的这条再丢回垃圾桶，而且要插回它原来那一格——
        // 用 add 追加到末尾的话，垃圾桶的排序会悄悄变（界面按顺序渲染，看得出差别）。
        // 重做 = 再捡回来一次，位置照旧。
        int backTo = at;
        TrashedItem back = picked;
        pushAction(() -> {
            TargetEntry entry = sequence.remove(to);
            trash.insert(backTo, new TrashedItem(entry, back.originalIndex()));
        }, () -> {
            trash.removeAt(backTo);
            sequence.add(Math.min(to, sequence.size()), back.entry());
        });
        return true;
    }

    /** 撤销最近一步操作；没得撤销就返回 false。 */
    public boolean undo() {
        Action action = undoStack.pollLast();
        if (action == null) {
            return false;
        }
        action.undo().run();
        redoStack.addLast(action); // 撤掉的这一步随时可以重做
        return true;
    }

    /** 重做最近撤销掉的那一步；没得重做就返回 false。 */
    public boolean redo() {
        Action action = redoStack.pollLast();
        if (action == null) {
            return false;
        }
        action.redo().run();
        undoStack.addLast(action); // 重做过的这一步又可以撤销了
        return true;
    }

    /**
     * 某张照片现在的虚拟编辑；没动过就是 {@link EditState#NONE}。
     *
     * <p>按规范化路径查——照片可能先躺在素材里、后被复制进目标文件夹，
     * 但"是不是同一张"始终按路径判。
     */
    public EditState editsOf(Path source) {
        if (source == null) {
            return EditState.NONE;
        }
        return edits.getOrDefault(source.toAbsolutePath().normalize(), EditState.NONE);
    }

    /**
     * 给照片换一组编辑，并记进撤销历史。
     *
     * <p>这是"虚拟编辑"的核心：只改内存，画面上立刻能看到；磁盘上的 JPG
     * 要等「保存」才重写。撤销 / 重做也走这条——它记的是前后两组状态。
     *
     * @return 是否真的变了（和原来一样就不产生历史）
     */
    public boolean setEdits(Path source, EditState next) {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(next, "next 不能为空");
        Path key = source.toAbsolutePath().normalize();
        EditState before = edits.getOrDefault(key, EditState.NONE);
        if (before.equals(next)) {
            return false;
        }
        applyEdits(key, next);
        dirty = true; // 图像内容将来要跟着变，和磁盘不一样了
        pushAction(() -> applyEdits(key, before), () -> applyEdits(key, next));
        return true;
    }

    /** 清空某张照片的全部编辑（「重置」用）。 */
    public boolean clearEdits(Path source) {
        return setEdits(source, EditState.NONE);
    }

    /** 真正写进/移出编辑表：不记历史，撤销与重做都复用它。 */
    private void applyEdits(Path key, EditState state) {
        if (state.isNone()) {
            edits.remove(key); // 回到原样就别占着一格
        } else {
            edits.put(key, state);
        }
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** 有没有还没保存的改动（关窗口时用来决定要不要问"是否保存"）。 */
    public boolean isDirty() {
        return dirty;
    }

    /** 换目标文件夹时调用：中间商彻底清空，准备为新文件夹服务。 */
    public void clear() {
        sequence.clear();
        trash.clear();
        undoStack.clear();
        redoStack.clear();
        edits.clear();
        dirty = false;
    }

    /**
     * 记下一次操作的正反两支，并清空重做栈。
     *
     * <p>"新动作清空重做栈"是撤销/重做的通例：从中间岔出去以后，
     * 原来那条"未来"就不作数了（不然重做会做出一个世界上从没出现过的状态）。
     */
    private void pushAction(Runnable undo, Runnable redo) {
        undoStack.addLast(new Action(undo, redo));
        redoStack.clear();
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

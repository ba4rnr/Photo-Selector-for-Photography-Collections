package com.photoselector.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 垃圾桶：一份"被移除条目"的引用清单（指针），不是文件的存放处。
 *
 * <p>文件直到「保存」那一刻才有真实结局：
 * <ul>
 *   <li>指针指向真实文件 → 永久删除该文件</li>
 *   <li>指针指向待复制条目 → 什么都不做（文件从未被复制过）</li>
 * </ul>
 */
public final class TrashBin {

    private final List<TrashedItem> items = new ArrayList<>();

    /**
     * 把一条目标序列条目连同它原先的位置一起丢进垃圾桶。
     *
     * @param entry         被移除的条目
     * @param originalIndex 移除前它在序列里的索引（撤销还原用）
     */
    public void add(TargetEntry entry, int originalIndex) {
        items.add(new TrashedItem(Objects.requireNonNull(entry), originalIndex));
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** 清空（换目标文件夹等场景调用）。 */
    public void clear() {
        items.clear();
    }

    /**
     * 取出最后丢进来的条目，同时把它从垃圾桶里拿掉。
     * 撤销"丢进垃圾桶"这一步时由 {@link Session} 调用。
     */
    public TrashedItem removeLast() {
        if (items.isEmpty()) {
            throw new IllegalStateException("垃圾桶是空的");
        }
        return items.remove(items.size() - 1);
    }

    /** 只读视图，供界面渲染垃圾桶面板。视图跟随内部列表实时变化。 */
    public List<TrashedItem> items() {
        return Collections.unmodifiableList(items);
    }
}

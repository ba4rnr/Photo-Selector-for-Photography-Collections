package com.photoselector.core;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 工作区：这次要做的事情的范围。
 *
 * <p>三样东西：素材根文件夹、被剔除的文件夹、目标文件夹。
 * 它是"配置"，不是"状态"——只记录你选了什么；画面永远反映磁盘上真实有什么
 * （见《软件设计》4.1）。
 *
 * <p>不可变：每次改动都产生一个新对象，省得谁在背后悄悄改掉正在用的那一份。
 *
 * @param sourceRoot      素材根文件夹
 * @param excludedFolders 被剔除的子文件夹（不在范围里）
 * @param targetFolder    目标文件夹；还没选过时为 {@code null}
 */
public record Workspace(Path sourceRoot, Set<Path> excludedFolders, Path targetFolder) {

    public Workspace {
        Objects.requireNonNull(sourceRoot, "sourceRoot 不能为空");
        // 用 LinkedHashSet 保留顺序：写进配置文件时不至于每次顺序都不一样
        excludedFolders = Set.copyOf(new LinkedHashSet<>(excludedFolders));
    }

    /** 只有素材根文件夹、还没选目标文件夹的工作区。 */
    public static Workspace of(Path sourceRoot) {
        return new Workspace(sourceRoot, Set.of(), null);
    }

    /** 这个文件夹是否在范围内（没被剔除就是在）。 */
    public boolean isIncluded(Path folder) {
        return !excludedFolders.contains(folder);
    }

    /** 换一组剔除项。 */
    public Workspace withExcluded(Set<Path> excluded) {
        return new Workspace(sourceRoot, excluded, targetFolder);
    }

    /** 换目标文件夹。 */
    public Workspace withTarget(Path target) {
        return new Workspace(sourceRoot, excludedFolders, target);
    }
}

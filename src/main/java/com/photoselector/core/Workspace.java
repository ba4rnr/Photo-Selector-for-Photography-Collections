package com.photoselector.core;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 工作区：这次要做的事情的范围。
 *
 * <p>三样范围配置：素材根文件夹、被剔除的文件夹、目标文件夹；
 * 外加一个恢复指针：上次关闭时正在看的那张照片。
 *
 * <p>不可变：每次改动都产生一个新对象，省得谁在背后悄悄改掉正在用的那一份。
 *
 * @param sourceRoot      素材根文件夹
 * @param excludedFolders 被剔除的子文件夹（不在范围里）
 * @param targetFolder    目标文件夹；还没选过时为 {@code null}
 * @param lastViewedPhoto 上次关闭时正在浏览的照片；没有就是 {@code null}
 */
public record Workspace(Path sourceRoot, Set<Path> excludedFolders, Path targetFolder,
                        Path lastViewedPhoto) {

    public Workspace {
        Objects.requireNonNull(sourceRoot, "sourceRoot 不能为空");
        // 用 LinkedHashSet 保留顺序：写进配置文件时不至于每次顺序都不一样
        excludedFolders = Set.copyOf(new LinkedHashSet<>(excludedFolders));
    }

    /** 只有素材根文件夹、还没选目标文件夹的工作区。 */
    public static Workspace of(Path sourceRoot) {
        return new Workspace(sourceRoot, Set.of(), null, null);
    }

    /** 兼容只描述三个范围路径的调用；恢复指针为空。 */
    public Workspace(Path sourceRoot, Set<Path> excludedFolders, Path targetFolder) {
        this(sourceRoot, excludedFolders, targetFolder, null);
    }

    /** 这个文件夹是否在范围内（没被剔除就是在）。 */
    public boolean isIncluded(Path folder) {
        return !excludedFolders.contains(folder);
    }

    /** 换一组剔除项。 */
    public Workspace withExcluded(Set<Path> excluded) {
        return new Workspace(sourceRoot, excluded, targetFolder, lastViewedPhoto);
    }

    /** 换目标文件夹。 */
    public Workspace withTarget(Path target) {
        return new Workspace(sourceRoot, excludedFolders, target, lastViewedPhoto);
    }

    /** 记住上次浏览到哪张；照片路径不存在时由界面在恢复阶段自然忽略。 */
    public Workspace withLastViewedPhoto(Path photo) {
        return new Workspace(sourceRoot, excludedFolders, targetFolder, photo);
    }
}

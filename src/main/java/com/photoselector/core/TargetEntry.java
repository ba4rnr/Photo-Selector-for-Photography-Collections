package com.photoselector.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 目标序列里的一条：将来会出现在目标文件夹里的一张照片。
 *
 * <p>只记录两件事：
 * <ul>
 *   <li>{@code source}：内容来自哪个文件</li>
 *   <li>{@code origin}：这个文件现在在哪（见 {@link Origin}）</li>
 * </ul>
 *
 * <p>注意：这里<b>故意不存</b>"显示名"。显示名由位置决定（排第几就编几号），
 * 而位置随时会变；一旦把名字存进条目，每次挪动都得手动同步它，
 * 这份冗余状态正是最容易出 bug 的地方。所以名字一律由 {@link Session} 现算。
 */
public record TargetEntry(Path source, Origin origin) {

    public TargetEntry {
        Objects.requireNonNull(source, "source 不能为空");
        Objects.requireNonNull(origin, "origin 不能为空");
    }

    /** 文件名（不含目录），界面显示用。 */
    public String fileName() {
        return source.getFileName().toString();
    }
}

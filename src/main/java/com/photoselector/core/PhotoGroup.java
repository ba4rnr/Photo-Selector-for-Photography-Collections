package com.photoselector.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一组照片：一个子文件夹就是一组。
 *
 * <p>这是"范围选择"落实到浏览上的单位——设计里说"对每个子文件夹的照片进行连续地浏览"，
 * 底部素材条上每个可收起的框就是一个 {@code PhotoGroup}。
 *
 * @param name   显示名（通常就是文件夹名）
 * @param folder 对应的文件夹
 * @param photos 组里的照片，按文件名排好序
 */
public record PhotoGroup(String name, Path folder, List<Path> photos) {

    public PhotoGroup {
        Objects.requireNonNull(name, "name 不能为空");
        Objects.requireNonNull(folder, "folder 不能为空");
        photos = List.copyOf(photos); // 只读副本，免得调用方回头改掉
    }

    /** 这一组有几张。 */
    public int size() {
        return photos.size();
    }
}

package com.photoselector.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.photoselector.core.PhotoGroup;

/** 文件夹扫描：找出一个目录里我们能读的照片。 */
public final class PhotoFiles {

    private PhotoFiles() {
    }

    /**
     * 列出文件夹里的照片，按文件名排序。
     *
     * <p>相机编号是连续的（L1008244、L1008245……），所以按文件名排就是拍摄顺序。
     *
     * <p>只列这一层，不往下钻——把子文件夹当作分组单位是上层的事，
     * 扫描本身保持"一个文件夹一层"这样简单的语义。
     */
    public static List<Path> imagesIn(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> !Saver.isTemporaryFile(path))
                    .filter(ImageSource::isSupported)
                    .sorted()
                    .toList();
        }
    }

    /**
     * 把一个根文件夹扫成若干组。
     *
     * <p>分组单位是根目录下的<b>直接子文件夹</b>：每个子文件夹一组，
     * 根目录自己散落的照片另算一组（名字取根目录名）。空文件夹不成组。
     *
     * <p>不往下递归是有意的——`000_成片C` 下面还有一层按日期的文件夹，
     * 那种结构应该把 `000_成片C` 自己选成根，而不是指望扫描器替你想。
     */
    public static List<PhotoGroup> groupsIn(Path root) throws IOException {
        List<PhotoGroup> groups = new ArrayList<>();

        List<Path> loose = imagesIn(root);
        if (!loose.isEmpty()) {
            groups.add(new PhotoGroup(displayName(root), root, loose));
        }

        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                try {
                    List<Path> photos = imagesIn(child);
                    if (!photos.isEmpty()) {
                        groups.add(new PhotoGroup(child.getFileName().toString(), child, photos));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // 某一个子文件夹没权限/正在移动，不应让整个素材根目录都打不开。
                    // 能读的其他组照常显示，下一次文件监听事件还会再对齐。
                }
            }
        }
        return groups;
    }

    /** 根目录可能直接是盘符（getFileName() 为 null），这时用完整路径当组名。 */
    static String displayName(Path folder) {
        Path name = folder.getFileName();
        return name == null ? folder.toString() : name.toString();
    }
}

package com.photoselector.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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
                    .filter(ImageSource::isSupported)
                    .sorted()
                    .toList();
        }
    }
}

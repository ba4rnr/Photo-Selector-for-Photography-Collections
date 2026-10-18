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
                    .sorted(PhotoFiles::compareFileNames)
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
            for (Path child : children.filter(Files::isDirectory)
                    .sorted(PhotoFiles::compareFileNames).toList()) {
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

    /**
     * 文件名自然排序：连续数字按数值比较，而不是按字符字典序。
     *
     * <p>默认编号从 {@code 090} 到 {@code 0100} 时位数会增长；普通
     * {@link Path#compareTo(Path)} 会把 {@code 0100} 排在 {@code 020} 前面，
     * 保存后重新扫描就会让界面顺序看似全部乱掉。这里把数字段当成整数比较。
     */
    static int compareFileNames(Path left, Path right) {
        return compareNatural(left.getFileName().toString(), right.getFileName().toString());
    }

    private static int compareNatural(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i);
            char b = right.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int endA = digitEnd(left, i);
                int endB = digitEnd(right, j);
                int numberA = firstNonZero(left, i, endA);
                int numberB = firstNonZero(right, j, endB);
                int digitsA = endA - numberA;
                int digitsB = endB - numberB;
                if (digitsA != digitsB) {
                    return Integer.compare(digitsA, digitsB);
                }
                for (int k = 0; k < digitsA; k++) {
                    int diff = Character.compare(left.charAt(numberA + k),
                            right.charAt(numberB + k));
                    if (diff != 0) {
                        return diff;
                    }
                }
                // 数值相同但前导零数量不同：零少的排前面，保证排序稳定可预期。
                int widthA = endA - i;
                int widthB = endB - j;
                if (widthA != widthB) {
                    return Integer.compare(widthA, widthB);
                }
                i = endA;
                j = endB;
                continue;
            }

            int diff = Character.compare(Character.toLowerCase(a), Character.toLowerCase(b));
            if (diff != 0) {
                return diff;
            }
            i++;
            j++;
        }
        int byLength = Integer.compare(left.length(), right.length());
        return byLength != 0 ? byLength : left.compareTo(right);
    }

    private static int digitEnd(String text, int start) {
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        return end;
    }

    private static int firstNonZero(String text, int start, int end) {
        int at = start;
        while (at < end && text.charAt(at) == '0') {
            at++;
        }
        // 全零数字段仍要保留一个数字位，否则 000 会被当成空串。
        return at == end ? end - 1 : at;
    }
}

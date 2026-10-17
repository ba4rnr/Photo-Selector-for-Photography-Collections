package com.photoselector.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * properties 文件的安全写入。
 *
 * <p>配置文件和照片一样，不能直接覆盖原文件：如果程序恰好在写到一半时退出，
 * 原配置已经被截断，新配置也没写完，下次启动两份内容都没有了。这里先在同一目录
 * 写一个临时文件，刷到磁盘后再替换正式文件；替换这一步优先使用原子改名。
 */
final class PropertyFiles {

    private static final String TEMP_PREFIX = ".photoselector-";
    private static final String TEMP_SUFFIX = ".tmp";

    private PropertyFiles() {
    }

    /** 把 {@code properties} 完整、安全地写到 {@code file}。 */
    static void write(Path file, Properties properties, String comment) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("配置文件没有所在目录：" + file);
        }
        Files.createDirectories(parent);

        Path temp = Files.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 OutputStream out = Channels.newOutputStream(channel)) {
                properties.store(out, comment);
                out.flush();
                // 先确保临时文件本身已经落盘，再做改名；否则断电后可能只剩一个空壳。
                channel.force(true);
            }
            moveAtomically(temp, target);
            temp = null;
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** 同目录内优先原子替换；个别文件系统不支持时退回普通替换。 */
    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

package com.photoselector.io;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.photoselector.core.EditState;
import com.photoselector.core.Origin;
import com.photoselector.core.PhotoTransforms;
import com.photoselector.core.Session;
import com.photoselector.core.TargetEntry;
import com.photoselector.core.TrashedItem;

/**
 * 保存：把中间商里的改动一次性落到磁盘上。
 *
 * <p>做的四件事：把新挑进来的复制过去、按新顺序给原有的文件改名、
 * 把带旋转 / 翻转的照片重写成新 JPG、删掉垃圾桶里的真实文件。
 *
 * <p><b>为什么先要一份"计划"（{@link Plan}）</b>：保存跑在后台线程上，而中间商是活的——
 * 保存期间你在界面上再挑一张、按一下撤销，后台正在遍历的集合就变了。所以进后台之前，
 * 先在界面线程上把"要做哪些事"抄成一份纯数据，之后后台只认这份计划，再也不回头碰中间商。
 *
 * <p><b>顺序很讲究，不能随便换：</b>
 * <ol>
 *   <li>把要删的文件、以及要改名的文件，统统先挪到临时名——把文件名腾出来。</li>
 *   <li>复制新挑进来的。此时老名字都腾空了，复制不会撞上。</li>
 *   <li>把临时名改成最终名。</li>
 *   <li><b>最后才真删</b>垃圾桶里那些文件。放在最后是为了数据安全：
 *       万一前面任何一步失败，照片还在，只是名字临时了一点，不会出现"删了但新的没进来"。</li>
 * </ol>
 *
 * <p>任何一步出错都记进 {@code problems} 里继续往下做，不半途抛异常把用户晾在半路。
 */
public final class Saver {

    /** 待改名文件的临时前缀。万一中途断电，靠它认得出这些文件还没归位。 */
    private static final String RENAMING_PREFIX = "__renaming_";

    /** 待删除文件的临时前缀。同理，一眼看得出这些是"说好要删但还没删"的。 */
    private static final String TRASHED_PREFIX = "__trashed_";

    /** 正在复制 / 写图的临时前缀；这些只是半成品，不会被扫描成照片。 */
    private static final String COPYING_PREFIX = "__copying_";
    private static final String WRITING_PREFIX = "__writing_";

    /**
     * 一条要处理的条目。
     *
     * @param source 文件现在在哪
     * @param origin 是目标文件夹里原有的，还是从素材挑来待复制的
     * @param number 它在序列里的编号（配合原扩展名就是最终文件名）
     * @param edits  虚拟旋转 / 翻转；没动过就是 {@link EditState#NONE}
     */
    public record Item(Path source, Origin origin, String number, EditState edits) {

        public Item {
            edits = edits == null ? EditState.NONE : edits;
        }
    }

    /**
     * 保存计划：进后台之前拍下的快照。
     *
     * @param folder     目标文件夹
     * @param items      按顺序排列的条目
     * @param trashFiles 垃圾桶里那些<b>真实</b>文件（"待复制"的条目本来就只是指针，不在其中）
     */
    public record Plan(Path folder, List<Item> items, List<Path> trashFiles) {

        public Plan {
            folder = folder.toAbsolutePath().normalize();
            items = List.copyOf(items);
            trashFiles = List.copyOf(trashFiles);
        }

        /**
         * 照着中间商现在这一刻的样子拍一张快照。
         *
         * <p><b>必须在界面线程上调用</b>——它读的正是界面线程在改的那些集合。
         */
        public static Plan from(Session session) {
            List<Item> items = new ArrayList<>(session.size());
            for (int i = 0; i < session.size(); i++) {
                TargetEntry entry = session.entryAt(i);
                items.add(new Item(entry.source(), entry.origin(), session.nameAt(i),
                        session.editsOf(entry.source())));
            }
            List<Path> trashFiles = new ArrayList<>();
            for (TrashedItem item : session.trash().items()) {
                if (item.entry().origin() == Origin.EXISTING) {
                    trashFiles.add(item.entry().source());
                }
            }
            return new Plan(session.targetFolder(), items, trashFiles);
        }
    }

    /** 一次保存的结果。 */
    public record Result(int copied, int renamed, int rewritten, int deleted,
                         List<Path> finalOrder, Map<Path, Path> savedLocations,
                         List<Path> failedSources, List<String> problems) {

        public Result {
            finalOrder = List.copyOf(finalOrder);
            savedLocations = Map.copyOf(savedLocations);
            failedSources = List.copyOf(failedSources);
            problems = List.copyOf(problems);
        }

        public boolean hasProblems() {
            return !problems.isEmpty();
        }

        /** 一句话总结，用来弹给用户看。 */
        public String summary() {
            return "新增 " + copied + " 张，重命名 " + renamed + " 张，"
                    + "重写编辑 " + rewritten + " 张，删除 " + deleted + " 张";
        }
    }

    /** 一项待归位的临时搬运。 */
    private record TempMove(Path temp, Path target, Path original, EditState edits) {
    }

    /** 复制阶段的计数：新增了几张，其中几张因为带编辑而重写。 */
    private record CopyStats(int copied, int rewritten) {
    }

    /**
     * 执行保存。
     *
     * <p>纯文件操作，可能很慢，<b>不要</b>在界面线程上调用。
     */
    public Result save(Plan plan) {
        List<String> problems = new ArrayList<>();
        List<Path> failedSources = new ArrayList<>();

        // 每一项的最终落点：编号 + 原扩展名
        List<Path> finals = new ArrayList<>(plan.items().size());
        for (Item item : plan.items()) {
            finals.add(plan.folder().resolve(item.number() + extensionOf(item.source())));
        }

        List<TempMove> toDelete = moveTrashAside(plan, problems);
        List<TempMove> toRename = moveAsideForRenaming(plan, finals, problems);
        // 只有真正完成的操作才进 settled。不能等最后再看“目标路径有没有文件”：
        // 名字可能被外部文件占着，看到它存在并不等于这张照片已经保存成功。
        Set<Path> settled = new HashSet<>();
        for (int i = 0; i < plan.items().size(); i++) {
            Item item = plan.items().get(i);
            if (item.origin() == Origin.EXISTING
                    && item.source().equals(finals.get(i))
                    && item.edits().isNone()
                    && Files.exists(item.source())) {
                settled.add(finals.get(i));
            }
        }
        CopyStats copied = copyNewEntries(plan, finals, failedSources, problems, settled);
        List<TempMove> plainMoves = toRename.stream()
                .filter(move -> move.edits().isNone()).toList();
        List<TempMove> editedMoves = toRename.stream()
                .filter(move -> !move.edits().isNone()).toList();
        int renamed = moveToFinalNames(plainMoves, problems, settled);
        int rewritten = copied.rewritten()
                + rewriteMovedEntries(editedMoves, problems, settled);
        // 只有前面全部成功，才允许把垃圾桶里的真实文件永久删掉。
        // 任何一步失败都说明这次保存没有完整落地；此时先尝试把已挪开的垃圾桶文件
        // 放回原名，名字若已被别的照片占用，就继续保留在临时名下，绝不能删。
        int deleted;
        if (problems.isEmpty()) {
            deleted = deleteMovedAside(toDelete, problems);
        } else {
            restoreMovedAside(toDelete, problems, settled);
            restoreMovedAside(toRename, problems, settled);
            deleted = 0;
        }

        List<Path> finalOrder = collectFinalOrder(plan, finals, failedSources, settled);
        return new Result(copied.copied(), renamed, rewritten, deleted,
                finalOrder, collectSavedLocations(plan, finals, failedSources, settled),
                failedSources, problems);
    }

    /** 这个名字是不是保存过程留下的内部临时文件。 */
    public static boolean isTemporaryFile(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith(RENAMING_PREFIX)
                || name.startsWith(TRASHED_PREFIX)
                || name.startsWith(COPYING_PREFIX)
                || name.startsWith(WRITING_PREFIX);
    }

    /** 列出目标文件夹里残留的保存临时文件；用于启动时提醒，不自动删除。 */
    public static List<Path> residualFiles(Path folder) throws IOException {
        try (var entries = Files.list(folder)) {
            return entries.filter(Files::isRegularFile)
                    .filter(Saver::isTemporaryFile)
                    .sorted()
                    .toList();
        }
    }

    /**
     * 第一步之一：垃圾桶里那些真实文件先挪到临时名。
     *
     * <p>挪而不是直接删，是为了把"腾出文件名"和"真的删除"分成两步——
     * 后面任何一步失败，这些文件都还在，只是名字临时了一点。
     */
    private List<TempMove> moveTrashAside(Plan plan, List<String> problems) {
        List<TempMove> pending = new ArrayList<>();
        int index = 0;
        for (Path file : plan.trashFiles()) {
            if (!Files.exists(file)) {
                continue; // 已经被外部删掉了，那就没什么可做的
            }
            Path temp = plan.folder().resolve(TRASHED_PREFIX + System.nanoTime() + "_" + index++ + ".tmp");
            try {
                Files.move(file, temp);
                pending.add(new TempMove(temp, file, file, EditState.NONE));
            } catch (IOException e) {
                problems.add("删不掉：" + file.getFileName());
            }
        }
        return pending;
    }

    /** 第一步之二：需要改名的原有文件也先挪到临时名，免得互相覆盖。 */
    private static List<TempMove> moveAsideForRenaming(Plan plan, List<Path> finals,
                                                       List<String> problems) {
        List<TempMove> pending = new ArrayList<>();
        for (int i = 0; i < plan.items().size(); i++) {
            Item item = plan.items().get(i);
            Path finalPath = finals.get(i);
            if (item.origin() != Origin.EXISTING
                    || (item.source().equals(finalPath) && item.edits().isNone())) {
                continue;
            }
            // 保留原扩展名：带编辑的条目还要从这个临时文件重新解码，
            // 而 ImageSource 是按扩展名挑解码器的。
            Path temp = plan.folder().resolve(RENAMING_PREFIX + System.nanoTime()
                    + "_" + i + extensionOf(item.source()));
            try {
                Files.move(item.source(), temp);
                pending.add(new TempMove(temp, finalPath, item.source(), item.edits()));
            } catch (IOException e) {
                problems.add("改名前挪不动：" + item.source().getFileName());
            }
        }
        return pending;
    }

    /** 第二步：新挑进来的复制到最终名字。已经有人占了那个名字就不覆盖。 */
    private static CopyStats copyNewEntries(Plan plan, List<Path> finals,
                                            List<Path> failedSources, List<String> problems,
                                            Set<Path> settled) {
        int copied = 0;
        int rewritten = 0;
        for (int i = 0; i < plan.items().size(); i++) {
            Item item = plan.items().get(i);
            if (item.origin() != Origin.PENDING) {
                continue;
            }
            Path target = finals.get(i);
            if (Files.exists(target)) {
                problems.add("目标文件夹里已经有同名文件，没有覆盖：" + target.getFileName());
                failedSources.add(item.source());
                continue;
            }
            try {
                if (item.edits().isNone()) {
                    copyAtomically(item.source(), target);
                } else {
                    writeEditedAtomically(item.source(), target, item.edits());
                    rewritten++;
                }
                copied++;
                settled.add(target);
            } catch (IOException | RuntimeException e) {
                problems.add("复制或写图失败：" + item.source().getFileName()
                        + "（" + e.getMessage() + "）");
                failedSources.add(item.source());
            }
        }
        return new CopyStats(copied, rewritten);
    }

    /** 第三步：临时名改成最终名。 */
    private static int moveToFinalNames(List<TempMove> pending, List<String> problems,
                                        Set<Path> settled) {
        int renamed = 0;
        for (TempMove move : pending) {
            if (Files.exists(move.target())) {
                problems.add("最终名字被占了，这个文件还留在临时名下：" + move.temp().getFileName());
                continue;
            }
            try {
                moveAtomically(move.temp(), move.target());
                renamed++;
                settled.add(move.target());
            } catch (IOException e) {
                problems.add("改名失败，文件还留在临时名下：" + move.temp().getFileName());
            }
        }
        return renamed;
    }

    /**
     * 把带旋转 / 翻转的原有照片重写成新 JPG。
     *
     * <p>输入先经 {@link ImageSource#loadFull()} 按 EXIF 摆正，再套
     * {@link PhotoTransforms#apply}——和屏幕上的画法完全相同。写文件仍然走
     * 临时名再原子改名，写到一半失败不会留下半截正式文件。
     */
    private static int rewriteMovedEntries(List<TempMove> pending, List<String> problems,
                                           Set<Path> settled) {
        int rewritten = 0;
        for (TempMove move : pending) {
            if (Files.exists(move.target())) {
                problems.add("最终名字被占了，这张照片还留在临时名下：" + move.temp().getFileName());
                continue;
            }
            try {
                writeEditedAtomically(move.temp(), move.target(), move.edits());
                // 目标已经写好；即使随后清理临时名失败，结果也已经成功落位。
                settled.add(move.target());
                try {
                    Files.deleteIfExists(move.temp());
                } catch (IOException cleanupError) {
                    problems.add("编辑已保存，但临时文件没能清掉："
                            + move.temp().getFileName());
                }
                rewritten++;
            } catch (IOException | RuntimeException e) {
                problems.add("重写编辑失败，原图还留在临时名下：" + move.temp().getFileName());
            }
        }
        return rewritten;
    }

    /** 第四步：到这一步才真的删除。 */
    private static int deleteMovedAside(List<TempMove> pending, List<String> problems) {
        int deleted = 0;
        for (TempMove move : pending) {
            try {
                if (Files.deleteIfExists(move.temp())) {
                    deleted++;
                }
            } catch (IOException e) {
                problems.add("删不掉，文件还留在临时名下：" + move.temp().getFileName());
            }
        }
        return deleted;
    }

    /**
     * 保存中途失败时，把还没删的垃圾桶文件尽量放回原名。
     *
     * <p>如果原文件名已经被本次成功保存的另一张照片占了，就不覆盖它；文件继续
     * 留在 {@code __trashed_} 临时名下，并在问题清单里告诉用户具体名字。
     */
    private static void restoreMovedAside(List<TempMove> pending, List<String> problems,
                                          Set<Path> settled) {
        for (TempMove move : pending) {
            if (!Files.exists(move.temp())) {
                continue;
            }
            if (settled.contains(move.target())) {
                // 目标已经成功写好，只是临时名的清理失败了；不要再把同一张图
                // 放回原路径，否则会多出一份重复照片。
                continue;
            }
            if (Files.exists(move.original())) {
                problems.add("保存未完成，原文件保留在临时名下："
                        + move.temp().getFileName());
                continue;
            }
            try {
                moveAtomically(move.temp(), move.original());
            } catch (IOException e) {
                problems.add("保存未完成，原文件仍保留在临时名下："
                        + move.temp().getFileName());
            }
        }
    }

    /**
     * 保存完，按序列顺序看看每一项现在落在哪个文件上。
     *
     * <p>不看中间过程、只看磁盘上真实有什么。没复制成功的那几条不进来——
     * 调用方会拿 {@code failedSources} 把它们放回中间商，别让用户再挑一遍。
     */
    private static List<Path> collectFinalOrder(Plan plan, List<Path> finals,
                                                List<Path> failedSources,
                                                Set<Path> settled) {
        List<Path> order = new ArrayList<>(plan.items().size());
        for (int i = 0; i < plan.items().size(); i++) {
            Item item = plan.items().get(i);
            Path finalPath = finals.get(i);
            if (failedSources.contains(item.source())) {
                continue; // 这一条已经明确失败：就算目标处碰巧有东西也不能认
            }
            if (settled.contains(finalPath) && Files.exists(finalPath)) {
                order.add(finalPath);
            } else if (Files.exists(item.source())) {
                order.add(item.source()); // 没搬成功，那就还在原地
            }
        }
        return order;
    }

    /**
     * 记下“保存前来源 → 保存后真实文件”的对应关系。
     *
     * <p>界面保存后需要把大图和选中项切到最终文件；只靠最终顺序列表无法知道
     * 当前看的那张复制去了哪里，尤其是它从素材目录被复制成 `010.jpg` 时。
     */
    private static Map<Path, Path> collectSavedLocations(Plan plan, List<Path> finals,
                                                         List<Path> failedSources,
                                                         Set<Path> settled) {
        Map<Path, Path> locations = new LinkedHashMap<>();
        for (int i = 0; i < plan.items().size(); i++) {
            Path source = plan.items().get(i).source();
            if (failedSources.contains(source)) {
                continue;
            }
            Path finalPath = finals.get(i);
            Path actual = settled.contains(finalPath) && Files.exists(finalPath) ? finalPath
                    : Files.exists(source) ? source : null;
            if (actual != null) {
                locations.put(source.toAbsolutePath().normalize(),
                        actual.toAbsolutePath().normalize());
            }
        }
        return locations;
    }

    /**
     * 没编辑的照片：先复制到同目录临时文件，成功后再原子改名。
     *
     * <p>直接往最终名字复制的旧写法，在磁盘满或读写出错时会留下半截 JPG；
     * 这个半截文件还会被后续的“文件存在就算成功”误认。临时文件把失败挡在正式名字之外。
     */
    private static void copyAtomically(Path source, Path target) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), COPYING_PREFIX, ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            moveAtomically(temp, target);
            temp = null;
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** 解码、套编辑、以较高质量写成新 JPG，再原子改名到最终位置。 */
    private static void writeEditedAtomically(Path source, Path target, EditState edits)
            throws IOException {
        BufferedImage original = ImageSource.open(source).loadFull();
        BufferedImage result = PhotoTransforms.apply(original, edits);
        // 元数据按“输出图”的尺寸同步 EXIF 宽高，并断开旧缩略图 IFD。
        List<byte[]> metadata = JpegMetadataSegments.from(
                source, result.getWidth(), result.getHeight());

        Path temp = Files.createTempFile(target.getParent(), WRITING_PREFIX, ".tmp");
        try {
            writeJpeg(result, temp, metadata);
            moveAtomically(temp, target);
            temp = null;
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * 用 0.95 质量写 JPG。
     *
     * <p>{@code ImageIO.write} 的默认质量偏低，经过一次旋转就可能肉眼看出损失；
     * 这里显式指定压缩参数。编码完成后把原文件的 EXIF / XMP / ICC / IPTC 段原样带回，
     * 其中 EXIF 方向已在 {@link JpegMetadataSegments#from} 里改成 1。
     */
    private static void writeJpeg(BufferedImage image, Path file, List<byte[]> metadata)
            throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(encoded)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.95f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        Files.write(file, JpegMetadataSegments.inject(encoded.toByteArray(), metadata));
    }

    /** 同目录内优先原子改名；个别文件系统不支持时退回普通改名。 */
    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    /** 取扩展名（含点）；没有就返回空串。 */
    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }
}

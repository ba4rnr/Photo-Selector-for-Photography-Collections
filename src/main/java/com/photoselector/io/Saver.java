package com.photoselector.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.photoselector.core.Origin;
import com.photoselector.core.Session;
import com.photoselector.core.TargetEntry;
import com.photoselector.core.TrashedItem;

/**
 * 保存：把中间商里的改动一次性落到磁盘上。
 *
 * <p>做的三件事：把新挑进来的复制过去、按新顺序给原有的文件改名、删掉垃圾桶里的真实文件。
 *
 * <p><b>顺序很讲究，不能随便换：</b>
 * <ol>
 *   <li>先删垃圾桶里那些真实文件，把文件名腾出来。</li>
 *   <li>再把要改名的原有文件统一挪到<b>临时名</b>。这一步是关键：如果直接把 A 改成 B 的名字，
 *       而 B 又要改成别的名字，两者就会互相覆盖。先全部挪到临时名，这类纠缠就不存在了。</li>
 *   <li>然后复制新挑进来的。此时老名字都腾空了，复制不会撞上。</li>
 *   <li>最后把临时名改成最终名。</li>
 * </ol>
 *
 * <p>任何一步出错都记进 {@code problems} 里继续往下做，不会半途抛异常把用户晾在半路；
 * 已经做成的部分仍然算数，用户可以再按一次保存。
 */
public final class Saver {

    /**
     * 临时名的前缀。
     *
     * <p>万一中途断电，这些文件会留在目标文件夹里——前缀够扎眼，一眼能认出来，
     * 也方便以后加一个"清理上次没做完的改名"的功能。
     */
    private static final String TEMP_PREFIX = "__renaming_";

    /** 一次保存的结果。 */
    public record Result(int copied, int renamed, int deleted,
                         List<Path> finalOrder, List<String> problems) {

        public Result {
            finalOrder = List.copyOf(finalOrder);
            problems = List.copyOf(problems);
        }

        /** 有没有出问题。 */
        public boolean hasProblems() {
            return !problems.isEmpty();
        }

        /** 一句话总结，用来弹给用户看。 */
        public String summary() {
            return "新增 " + copied + " 张，重命名 " + renamed + " 张，删除 " + deleted + " 张";
        }
    }

    /** 一项待改名的临时搬运。 */
    private record TempMove(Path temp, Path target) {
    }

    /**
     * 执行保存。
     *
     * <p>这是纯文件操作，可能很慢，<b>不要</b>在界面线程上调用。
     */
    public Result save(Session session) {
        Path folder = session.targetFolder();
        List<String> problems = new ArrayList<>();
        int copied = 0;
        int renamed = 0;
        int deleted = 0;

        List<Path> finals = new ArrayList<>();
        for (int i = 0; i < session.size(); i++) {
            TargetEntry entry = session.entryAt(i);
            finals.add(folder.resolve(session.nameAt(i) + extensionOf(entry.source())));
        }

        deleted = deleteTrashed(session, problems);
        List<TempMove> pending = moveAsideForRenaming(session, finals, problems);
        copied = copyNewEntries(session, finals, problems);
        renamed = moveToFinalNames(pending, problems);

        return new Result(copied, renamed, deleted, collectFinalOrder(session, finals), problems);
    }

    /** 第一步：垃圾桶里的真实文件永久删除。队列里那些"待复制"的条目本来就只是个指针。 */
    private static int deleteTrashed(Session session, List<String> problems) {
        int deleted = 0;
        for (TrashedItem item : session.trash().items()) {
            if (item.entry().origin() != Origin.EXISTING) {
                continue;
            }
            Path file = item.entry().source();
            try {
                if (Files.deleteIfExists(file)) {
                    deleted++;
                }
            } catch (IOException e) {
                problems.add("删不掉：" + file.getFileName());
            }
        }
        return deleted;
    }

    /** 第二步：需要改名的原有文件先挪到临时名，免得互相覆盖。 */
    private static List<TempMove> moveAsideForRenaming(Session session, List<Path> finals,
                                                       List<String> problems) {
        List<TempMove> pending = new ArrayList<>();
        for (int i = 0; i < session.size(); i++) {
            TargetEntry entry = session.entryAt(i);
            Path finalPath = finals.get(i);
            if (entry.origin() != Origin.EXISTING || entry.source().equals(finalPath)) {
                continue;
            }
            Path temp = finalPath.getParent().resolve(TEMP_PREFIX + System.nanoTime() + "_" + i + ".tmp");
            try {
                Files.move(entry.source(), temp);
                pending.add(new TempMove(temp, finalPath));
            } catch (IOException e) {
                problems.add("改名前挪不动：" + entry.source().getFileName());
            }
        }
        return pending;
    }

    /** 第三步：新挑进来的复制到最终名字。已经有人占了那个名字就不覆盖。 */
    private static int copyNewEntries(Session session, List<Path> finals, List<String> problems) {
        int copied = 0;
        for (int i = 0; i < session.size(); i++) {
            TargetEntry entry = session.entryAt(i);
            if (entry.origin() != Origin.PENDING) {
                continue;
            }
            Path target = finals.get(i);
            if (Files.exists(target)) {
                problems.add("目标文件夹里已经有同名文件，没有覆盖：" + target.getFileName());
                continue;
            }
            try {
                Files.copy(entry.source(), target, StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            } catch (IOException e) {
                problems.add("复制失败：" + entry.source().getFileName());
            }
        }
        return copied;
    }

    /** 第四步：临时名改成最终名。 */
    private static int moveToFinalNames(List<TempMove> pending, List<String> problems) {
        int renamed = 0;
        for (TempMove move : pending) {
            if (Files.exists(move.target())) {
                problems.add("最终名字被占了，这个文件还留在临时名下：" + move.temp().getFileName());
                continue;
            }
            try {
                Files.move(move.temp(), move.target());
                renamed++;
            } catch (IOException e) {
                problems.add("改名失败，文件还留在临时名下：" + move.temp().getFileName());
            }
        }
        return renamed;
    }

    /**
     * 保存完，按序列顺序看看每一项现在落在哪个文件上。
     *
     * <p>不看中间过程、只看磁盘上真实有什么——某一步失败了，这里自然就不会把它算进来。
     */
    private static List<Path> collectFinalOrder(Session session, List<Path> finals) {
        List<Path> order = new ArrayList<>(session.size());
        for (int i = 0; i < session.size(); i++) {
            Path finalPath = finals.get(i);
            if (Files.exists(finalPath)) {
                order.add(finalPath);
            } else if (Files.exists(session.entryAt(i).source())) {
                order.add(session.entryAt(i).source()); // 没搬成功，那就还在原地
            }
        }
        return order;
    }

    /** 取扩展名（含点）；没有就返回空串。 */
    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }
}

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

    /**
     * 一条要处理的条目。
     *
     * @param source 文件现在在哪
     * @param origin 是目标文件夹里原有的，还是从素材挑来待复制的
     * @param number 它在序列里的编号（配合原扩展名就是最终文件名）
     */
    public record Item(Path source, Origin origin, String number) {
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
                items.add(new Item(entry.source(), entry.origin(), session.nameAt(i)));
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
    public record Result(int copied, int renamed, int deleted,
                         List<Path> finalOrder, List<Path> failedSources, List<String> problems) {

        public Result {
            finalOrder = List.copyOf(finalOrder);
            failedSources = List.copyOf(failedSources);
            problems = List.copyOf(problems);
        }

        public boolean hasProblems() {
            return !problems.isEmpty();
        }

        /** 一句话总结，用来弹给用户看。 */
        public String summary() {
            return "新增 " + copied + " 张，重命名 " + renamed + " 张，删除 " + deleted + " 张";
        }
    }

    /** 一项待归位的临时搬运。 */
    private record TempMove(Path temp, Path target) {
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
        int copied = copyNewEntries(plan, finals, failedSources, problems);
        int renamed = moveToFinalNames(toRename, problems);
        int deleted = deleteMovedAside(toDelete, problems);

        return new Result(copied, renamed, deleted,
                collectFinalOrder(plan, finals, failedSources), failedSources, problems);
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
                pending.add(new TempMove(temp, file));
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
            if (item.origin() != Origin.EXISTING || item.source().equals(finalPath)) {
                continue;
            }
            Path temp = plan.folder().resolve(RENAMING_PREFIX + System.nanoTime() + "_" + i + ".tmp");
            try {
                Files.move(item.source(), temp);
                pending.add(new TempMove(temp, finalPath));
            } catch (IOException e) {
                problems.add("改名前挪不动：" + item.source().getFileName());
            }
        }
        return pending;
    }

    /** 第二步：新挑进来的复制到最终名字。已经有人占了那个名字就不覆盖。 */
    private static int copyNewEntries(Plan plan, List<Path> finals, List<Path> failedSources,
                                      List<String> problems) {
        int copied = 0;
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
                Files.copy(item.source(), target, StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            } catch (IOException e) {
                problems.add("复制失败：" + item.source().getFileName());
                failedSources.add(item.source());
            }
        }
        return copied;
    }

    /** 第三步：临时名改成最终名。 */
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
     * 保存完，按序列顺序看看每一项现在落在哪个文件上。
     *
     * <p>不看中间过程、只看磁盘上真实有什么。没复制成功的那几条不进来——
     * 调用方会拿 {@code failedSources} 把它们放回中间商，别让用户再挑一遍。
     */
    private static List<Path> collectFinalOrder(Plan plan, List<Path> finals,
                                                List<Path> failedSources) {
        List<Path> order = new ArrayList<>(plan.items().size());
        for (int i = 0; i < plan.items().size(); i++) {
            Item item = plan.items().get(i);
            Path finalPath = finals.get(i);
            if (Files.exists(finalPath)) {
                order.add(finalPath);
            } else if (!failedSources.contains(item.source()) && Files.exists(item.source())) {
                order.add(item.source()); // 没搬成功，那就还在原地
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

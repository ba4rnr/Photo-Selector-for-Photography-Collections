package com.photoselector.core;

/**
 * 编号规则：决定目标序列里第 N 项将来叫什么名字。
 *
 * <p>三个参数：
 * <ul>
 *   <li>{@code start}：第 1 项（索引 0）的编号值，默认 10</li>
 *   <li>{@code step}：相邻两项的差值，默认 10。用 10 而不是 1，
 *       是为了以后想手工往中间插一张照片时留有余地</li>
 *   <li>{@code pattern}：名字的格式串，{@code %d} 会被替换成编号值。
 *       默认 {@code "0%d"}——编号值前面加一个 0，
 *       于是第 1 项是 {@code 010}、第 2 项是 {@code 020}、第 23 项是 {@code 0230}</li>
 * </ul>
 *
 * <p>为什么用格式串，而不是"补零到几位"：需求要的是"前后各一个 0"这种形状，
 * 它并不是固定宽度——数位会随着编号自然增长（第 23 项是四位 {@code 0230}）。
 * {@code "%04d"} 那种写死宽度的写法表达不了这个，所以换成格式串：
 * 直接写 {@code "0%d"} 就是"前面加一个 0"，想改成 {@code "%04d"} 也只是换个串。
 *
 * <p>设计成不可变的 record：规则创建之后就不会变，所有调用者共享同一份，
 * 天然线程安全，也杜绝了"谁悄悄改了我的编号规则"这类问题。
 */
public record NumberingRule(int start, int step, String pattern) {

    /** 默认规则：从 10 开始、步进 10、编号值前面加一个 0。 */
    public static final NumberingRule DEFAULT = new NumberingRule(10, 10, "0%d");

    /**
     * 紧凑构造器：record 生成字段赋值之前会先经过这里，是集中做参数校验的位置。
     * 参数非法时立刻抛出异常——"尽早失败"，好过让错误溜进数据里再追查。
     */
    public NumberingRule {
        if (start < 0) {
            throw new IllegalArgumentException("起始值不能为负：" + start);
        }
        if (step < 1) {
            throw new IllegalArgumentException("步进必须至少为 1：" + step);
        }
        // 只允许一个 %d，其余位置不许出现别的转换符——
        // 否则 String.format 会在真正生成名字的那一刻才炸，那时候已经晚了
        if (pattern == null || !pattern.contains("%d")
                || pattern.chars().filter(character -> character == '%').count() != 1) {
            throw new IllegalArgumentException("格式必须是「含且只含一个 %d」的字符串：" + pattern);
        }
    }

    /**
     * 计算第 {@code index} 项（从 0 开始）的显示名。
     *
     * <p>默认规则下：第 1 项 {@code 010}、第 2 项 {@code 020}、第 23 项 {@code 0230}。
     * 数位不够不会截断——编号值本身就是名字的骨架，只会自然变长。
     *
     * @param index 目标序列里的位置，从 0 开始
     * @return 按格式串生成的编号
     */
    public String nameFor(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("索引不能为负：" + index);
        }
        // 用 long 计算，避免 int 乘法溢出
        long value = start + (long) index * step;
        return String.format(pattern, value);
    }
}

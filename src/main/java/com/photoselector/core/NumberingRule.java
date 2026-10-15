package com.photoselector.core;

/**
 * 编号规则：决定目标序列里第 N 项将来叫什么名字。
 *
 * <p>三个参数：
 * <ul>
 *   <li>{@code start}：第 1 项（索引 0）的编号值，默认 10</li>
 *   <li>{@code step}：相邻两项的差值，默认 10。用 10 而不是 1，
 *       是为了以后想手工往中间插一张照片时留有余地</li>
 *   <li>{@code width}：补零后的位数，默认 4</li>
 * </ul>
 *
 * <p>默认规则下：第 1 项 = {@code 0010}，第 2 项 = {@code 0020}，第 23 项 = {@code 0230}。
 *
 * <p>设计成不可变的 record：规则创建之后就不会变，所有调用者共享同一份，
 * 天然线程安全，也杜绝了"谁悄悄改了我的编号规则"这类问题。
 */
public record NumberingRule(int start, int step, int width) {

    /** 默认规则：从 10 开始、步进 10、补零到 4 位。 */
    public static final NumberingRule DEFAULT = new NumberingRule(10, 10, 4);

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
        if (width < 1) {
            throw new IllegalArgumentException("补零位数必须至少为 1：" + width);
        }
    }

    /**
     * 计算第 {@code index} 项（从 0 开始）的显示名。
     *
     * <p>超过补零位数时不会截断，而是自然变长：
     * 默认规则下第 1000 项是 {@code 10010}，不会变成错误的名字。
     *
     * @param index 目标序列里的位置，从 0 开始
     * @return 补零后的编号字符串
     */
    public String nameFor(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("索引不能为负：" + index);
        }
        // 用 long 计算，避免 int 乘法溢出
        long value = start + (long) index * step;
        return String.format("%0" + width + "d", value);
    }
}

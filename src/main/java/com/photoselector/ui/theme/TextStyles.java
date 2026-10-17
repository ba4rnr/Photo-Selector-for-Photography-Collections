package com.photoselector.ui.theme;

import java.awt.FontMetrics;

/**
 * 文字的收尾样式：太长时怎么截。
 *
 * <p>界面上到处是"放不下就截断"的文字——文件名、路径、组名。以前每个面板
 * 各写一份，改一处就漏一处（还出现过两份实现，行为不一样）。现在只此一处。
 */
public final class TextStyles {

    /** 截断时用的省略号。用单字符的「…」，比三个点窄，看起来也更干净。 */
    private static final String ELLIPSIS = "…";

    private TextStyles() {
    }

    /**
     * 从<b>末尾</b>截：保留开头，省略号收尾。
     *
     * <p>文件名这类"前面有辨识度"的文字用它——`L1008244.JPG` 被截成
     * `L10082…` 还能认；反过来截掉开头就全成了 `.JPG`，没法区分。
     */
    public static String end(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        // 预算里必须留出省略号自己占的宽度——不然截完再补省略号，又会超出一点
        int room = maxWidth - metrics.stringWidth(ELLIPSIS);
        int end = text.length();
        while (end > 1 && metrics.stringWidth(text.substring(0, end)) > room) {
            end--;
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    /**
     * 从<b>中间</b>截：开头和结尾都留着。
     *
     * <p>路径用它：`C:\Users\…\摄影集_大时代`——盘符和文件夹名同时保住，
     * 中间那串用户目录截掉不心疼。
     *
     * <p>做法是"按路径分隔符一段一段丢"：先保住最后一段（最有辨识度的文件夹名），
     * 再从前往后尽量多留几段，放不下就整段舍弃——不会在半截单词上断，
     * 也不会出现 `…时代` 这种只剩半个名字的怪结果。
     */
    public static String middle(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        if (maxWidth <= metrics.stringWidth(ELLIPSIS)) {
            return ELLIPSIS;
        }

        // 按分隔符切段；Windows 的两种斜杠都认
        String normalized = text.replace('/', '\\');
        String[] parts = normalized.split("\\\\");
        if (parts.length <= 1) {
            return end(text, metrics, maxWidth); // 没有路径层级，退回末尾截断
        }

        String tail = parts[parts.length - 1]; // 最后一段：文件夹名或文件名
        if (metrics.stringWidth(ellipsisWithTail(tail)) > maxWidth) {
            return end(tail, metrics, maxWidth); // 连最后一段都放不下，只能截它
        }

        // 剩下的宽度留给头部；每段之间还要各加一个反斜杠
        int used = metrics.stringWidth(ellipsisWithTail(tail));
        String head = parts[0];
        if (metrics.stringWidth(head) + metrics.stringWidth("\\") + used > maxWidth) {
            return ellipsisWithTail(tail);
        }
        StringBuilder kept = new StringBuilder(head);
        for (int i = 1; i < parts.length - 1; i++) {
            int next = metrics.stringWidth(parts[i]) + metrics.stringWidth("\\");
            if (metrics.stringWidth(kept.toString()) + next + used > maxWidth) {
                break; // 这一段放不下就整段丢掉，不在半截上断
            }
            kept.append('\\').append(parts[i]);
        }
        return kept + "\\" + ellipsisWithTail(tail);
    }

    /** 「…\最后一段」——中间截断时留着的那条尾巴。 */
    private static String ellipsisWithTail(String tail) {
        return ELLIPSIS + "\\" + tail;
    }
}

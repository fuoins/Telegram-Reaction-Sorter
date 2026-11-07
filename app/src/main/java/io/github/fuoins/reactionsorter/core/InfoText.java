package io.github.fuoins.reactionsorter.core;

/**
 * Builds the short status line shown in the host's own ActionBar subtitle.
 *
 * <p>Pure Java on purpose: the whole behaviour is covered by JVM unit tests. The subtitle
 * line is narrow (next to the channel name), so the text is deliberately short and never
 * contains a newline — the previous long status line was the reason for "..." truncation.
 */
public final class InfoText {

    public static final String PREFIX = "回应排行";
    /** Chinese glyphs count as one char; keep enough headroom for small screens. */
    public static final int MAX_CHARS = 22;

    private InfoText() {}

    /**
     * @param count       number of ranked messages
     * @param max         highest reaction count (0 = unknown)
     * @param scanning    a local-database scan is running
     * @param windowOnly  only the loaded window is being ranked
     * @param problem     a short problem description, or {@code null}/{@code ""}
     */
    public static String of(int count, int max, boolean scanning,
                            boolean windowOnly, String problem) {
        String text;
        if (problem != null && !problem.isEmpty()) {
            text = PREFIX + " · " + problem;
        } else if (scanning) {
            text = PREFIX + " · 扫描中…";
        } else if (windowOnly) {
            text = PREFIX + " · 仅当前窗口";
        } else if (count <= 0) {
            text = PREFIX;
        } else {
            text = PREFIX + " · " + count + " 条";
            if (max > 0) {
                String longer = text + " · 最高 " + max;
                if (longer.length() <= MAX_CHARS) text = longer;
            }
        }
        if (text.length() > MAX_CHARS) text = PREFIX + " · " + count + " 条";
        if (text.length() > MAX_CHARS) text = PREFIX;
        return text.replace('\n', ' ').replace('\r', ' ');
    }
}

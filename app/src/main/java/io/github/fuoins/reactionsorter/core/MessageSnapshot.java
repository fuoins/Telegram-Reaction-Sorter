package io.github.fuoins.reactionsorter.core;

import java.util.Collections;
import java.util.List;

/**
 * Immutable, framework-free snapshot of one message. Kept free of android.* / Xposed types
 * so the ranking logic can be unit-tested on a plain JVM.
 */
public final class MessageSnapshot {

    public final int messageId;
    public final int date;
    public final long groupedId;
    public final String text;
    public final int totalReactions;
    public final List<ReactionEntry> breakdown;

    public MessageSnapshot(int messageId, int date, long groupedId, String text,
                           int totalReactions, List<ReactionEntry> breakdown) {
        this.messageId = messageId;
        this.date = date;
        this.groupedId = groupedId;
        this.text = text == null ? "" : text;
        this.totalReactions = Math.max(0, totalReactions);
        this.breakdown = breakdown == null
                ? Collections.<ReactionEntry>emptyList()
                : Collections.unmodifiableList(breakdown);
    }

    /** One reaction kind shown on a message, e.g. "👍" or "[自定义表情 123456]". */
    public static final class ReactionEntry {
        public final String label;
        public final int count;

        public ReactionEntry(String label, int count) {
            this.label = label == null ? "" : label;
            this.count = Math.max(0, count);
        }
    }

    /** Compact one-line label used in the ranking list. */
    public String breakdownText() {
        if (breakdown.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < breakdown.size(); i++) {
            if (i > 0) sb.append(' ');
            ReactionEntry e = breakdown.get(i);
            sb.append(e.label).append('×').append(e.count);
        }
        return sb.toString();
    }

    /** Text preview, capped so a very long message cannot blow up the list row. */
    public String preview(int maxChars) {
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.isEmpty()) return "[媒体 / 服务消息]";
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
    }
}

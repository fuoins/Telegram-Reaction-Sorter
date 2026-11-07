package io.github.fuoins.reactionsorter.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java ranking of messages by their total reaction count. No Android dependency on
 * purpose: the whole behaviour is covered by JVM unit tests.
 */
public final class ReactionRanker {

    private ReactionRanker() {}

    public static final class Options {
        /** true = most reactions first (default), false = fewest first. */
        public boolean descending = true;
        /** Messages with fewer reactions than this are dropped. */
        public int minReactions = 1;
        /** 0 = no limit. */
        public int topN = 0;
        /** Drop messages that have no reactions at all. */
        public boolean onlyReacted = true;
        /** Keep only the best message of an album (same grouped_id). */
        public boolean mergeAlbums = true;

        public Options() {}

        public Options(boolean descending, int minReactions, int topN,
                       boolean onlyReacted, boolean mergeAlbums) {
            this.descending = descending;
            this.minReactions = Math.max(0, minReactions);
            this.topN = Math.max(0, topN);
            this.onlyReacted = onlyReacted;
            this.mergeAlbums = mergeAlbums;
        }
    }

    /** Total number of reacted messages and reactions, useful for the dialog header. */
    public static final class Summary {
        public final int totalMessages;
        public final int reactedMessages;
        public final int totalReactions;
        public final int maxReactions;

        Summary(int totalMessages, int reactedMessages, int totalReactions, int maxReactions) {
            this.totalMessages = totalMessages;
            this.reactedMessages = reactedMessages;
            this.totalReactions = totalReactions;
            this.maxReactions = maxReactions;
        }
    }

    public static Summary summarize(List<MessageSnapshot> messages) {
        int reacted = 0, total = 0, max = 0;
        if (messages != null) {
            for (MessageSnapshot m : messages) {
                if (m == null) continue;
                total += m.totalReactions;
                if (m.totalReactions > 0) reacted++;
                if (m.totalReactions > max) max = m.totalReactions;
            }
        }
        return new Summary(messages == null ? 0 : messages.size(), reacted, total, max);
    }

    /** Returns a new ranked/filtered list; the input list is never modified. */
    public static List<MessageSnapshot> rank(List<MessageSnapshot> source, Options options) {
        List<MessageSnapshot> result = new ArrayList<>();
        if (source == null || source.isEmpty()) return result;
        final Options opt = options == null ? new Options() : options;

        List<MessageSnapshot> candidates = new ArrayList<>();
        for (MessageSnapshot m : source) {
            if (m == null) continue;
            if (opt.onlyReacted && m.totalReactions <= 0) continue;
            if (m.totalReactions < opt.minReactions) continue;
            candidates.add(m);
        }

        if (opt.mergeAlbums) {
            // Keep the representative with the most reactions; on a tie prefer the newer one.
            Map<Long, MessageSnapshot> best = new HashMap<>();
            List<MessageSnapshot> passThrough = new ArrayList<>();
            for (MessageSnapshot m : candidates) {
                if (m.groupedId == 0L) {
                    passThrough.add(m);
                    continue;
                }
                MessageSnapshot prev = best.get(m.groupedId);
                if (prev == null || isBetter(m, prev, true)) {
                    best.put(m.groupedId, m);
                }
            }
            candidates = new ArrayList<>(passThrough);
            candidates.addAll(best.values());
        }

        final boolean descending = opt.descending;
        candidates.sort(Comparator
                .comparingInt((MessageSnapshot m) -> descending ? -m.totalReactions : m.totalReactions)
                // On a tie: newer first, then stable by message id (deterministic output).
                .thenComparingInt((MessageSnapshot m) -> -m.date)
                .thenComparingInt((MessageSnapshot m) -> m.messageId));

        if (opt.topN > 0 && candidates.size() > opt.topN) {
            return new ArrayList<>(candidates.subList(0, opt.topN));
        }
        return candidates;
    }

    private static boolean isBetter(MessageSnapshot a, MessageSnapshot b, boolean byReactions) {
        if (a.totalReactions != b.totalReactions) {
            return byReactions && a.totalReactions > b.totalReactions;
        }
        if (a.date != b.date) return a.date > b.date;
        return a.messageId > b.messageId;
    }
}

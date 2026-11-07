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

    /** Ways the bottom bar can order the result list, mirroring Telegram's own filters. */
    public enum SortMode {
        REACTIONS_DESC, REACTIONS_ASC, DATE_DESC, DATE_ASC, ID_DESC, ID_ASC;

        public static SortMode parse(String raw, SortMode fallback) {
            if (raw != null) {
                try {
                    return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // fall through
                }
            }
            return fallback;
        }
    }

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
        /**
         * When set, overrides {@link #descending} with one of the explicit sort modes.
         * Stays {@code null} for callers using the legacy constructor so existing behaviour
         * (and its unit tests) is preserved.
         */
        public SortMode sortMode = null;

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

        candidates.sort(comparator(opt));

        if (opt.topN > 0 && candidates.size() > opt.topN) {
            return new ArrayList<>(candidates.subList(0, opt.topN));
        }
        return candidates;
    }

    /** Deterministic comparator for every sort mode; ties always fall back to a stable order. */
    private static Comparator<MessageSnapshot> comparator(Options opt) {
        SortMode mode = opt.sortMode;
        if (mode == null) {
            // Legacy behaviour driven by the descending flag.
            mode = opt.descending ? SortMode.REACTIONS_DESC : SortMode.REACTIONS_ASC;
        }
        switch (mode) {
            case REACTIONS_ASC:
                return Comparator.comparingInt((MessageSnapshot m) -> m.totalReactions)
                        .thenComparingInt(m -> -m.date)
                        .thenComparingInt(m -> m.messageId);
            case DATE_DESC:
                return Comparator.comparingInt((MessageSnapshot m) -> -m.date)
                        .thenComparingInt(m -> -m.totalReactions)
                        .thenComparingInt(m -> m.messageId);
            case DATE_ASC:
                return Comparator.comparingInt((MessageSnapshot m) -> m.date)
                        .thenComparingInt(m -> -m.totalReactions)
                        .thenComparingInt(m -> m.messageId);
            case ID_DESC:
                return Comparator.comparingInt((MessageSnapshot m) -> -m.messageId)
                        .thenComparingInt(m -> -m.totalReactions)
                        .thenComparingInt(m -> m.date);
            case ID_ASC:
                return Comparator.comparingInt((MessageSnapshot m) -> m.messageId)
                        .thenComparingInt(m -> -m.totalReactions)
                        .thenComparingInt(m -> m.date);
            case REACTIONS_DESC:
            default:
                return Comparator.comparingInt((MessageSnapshot m) -> -m.totalReactions)
                        // On a tie: newer first, then stable by message id.
                        .thenComparingInt(m -> -m.date)
                        .thenComparingInt(m -> m.messageId);
        }
    }

    private static boolean isBetter(MessageSnapshot a, MessageSnapshot b, boolean byReactions) {
        if (a.totalReactions != b.totalReactions) {
            return byReactions && a.totalReactions > b.totalReactions;
        }
        if (a.date != b.date) return a.date > b.date;
        return a.messageId > b.messageId;
    }
}

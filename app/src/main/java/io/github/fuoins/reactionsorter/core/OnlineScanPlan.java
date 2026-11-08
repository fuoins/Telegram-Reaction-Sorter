package io.github.fuoins.reactionsorter.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java planning for the server-side (online) reaction scan. No Android / Xposed types
 * so the paging and rate-limit logic is fully unit-testable on a plain JVM.
 *
 * <p>{@code messages.getHistory} is a read-only RPC that returns message <em>metadata</em>:
 * text, media <em>references</em> (a few dozen bytes) and the full {@code reactions} block.
 * The actual photo/video file bytes are fetched through a completely separate download RPC
 * that the client only issues when a message is actually rendered. So paging through the
 * history reads no media content at all — exactly the property the user observed
 * ("reactions are already there while the thumbnails are still blank").
 *
 * <p>The request supports {@code offset_id}, {@code add_offset}, {@code limit},
 * {@code max_id} and {@code min_id}. Using a fixed anchor ({@code max_id}) plus
 * {@code add_offset = k * limit} lets us fetch independent pages <b>in parallel</b> instead
 * of walking backwards one page at a time, which is what makes a large channel fast.
 */
public final class OnlineScanPlan {

    /** Telegram caps messages.getHistory at 100 per call in practice. */
    public static final int PAGE_SIZE = 100;
    /** Never issue more than this many parallel RPCs, to stay well below flood limits. */
    public static final int MAX_CONCURRENCY = 6;
    public static final int DEFAULT_CONCURRENCY = 4;
    /** Hard ceiling of messages per scan, independent of the user's setting. */
    public static final int HARD_MAX_MESSAGES = 60_000;

    private OnlineScanPlan() {}

    /**
     * Build the list of {@code add_offset} values for one scan. Pages are independent, so the
     * scanner groups them into concurrent waves itself; we do not pad the list here, otherwise
     * a small user cap would silently schedule requests beyond it.
     *
     * @param serverTotal total message count reported by the server ({@code messagesSlice.count}),
     *                    or {@code <= 0} when unknown (then the cap is used)
     * @param maxMessages user-configured cap, {@code <= 0} means the hard cap
     * @param alreadyFetched number of messages already collected by the probe page
     */
    public static List<Integer> offsets(int serverTotal, int maxMessages, int alreadyFetched) {
        int cap = Math.min(maxMessages <= 0 ? HARD_MAX_MESSAGES : maxMessages, HARD_MAX_MESSAGES);
        int total = serverTotal > 0 ? Math.min(serverTotal, cap) : cap;
        int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        if (pages <= 0) pages = 1;

        int start = Math.max(0, alreadyFetched / PAGE_SIZE);
        List<Integer> out = new ArrayList<>();
        for (int p = start; p < pages; p++) {
            out.add(p * PAGE_SIZE);
        }
        return out;
    }

    /** Parse "FLOOD_WAIT_30" / "FLOOD_PREMIUM_WAIT_10" into seconds; 0 when not a flood wait. */
    public static int floodWaitSeconds(String errorText) {
        if (errorText == null) return 0;
        String s = errorText.trim();
        int idx = s.indexOf("FLOOD_WAIT_");
        int digitStart = -1;
        if (idx >= 0) {
            digitStart = idx + "FLOOD_WAIT_".length();
        } else {
            idx = s.indexOf("FLOOD_PREMIUM_WAIT_");
            if (idx >= 0) digitStart = idx + "FLOOD_PREMIUM_WAIT_".length();
        }
        if (digitStart < 0) return 0;
        int seconds = 0;
        boolean any = false;
        for (int i = digitStart; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            any = true;
            seconds = seconds * 10 + (c - '0');
            if (seconds > 86400) break;
        }
        return any ? seconds : 0;
    }

    public static boolean isFloodWait(String errorText) {
        return floodWaitSeconds(errorText) > 0;
    }

    public static int clampConcurrency(int value) {
        if (value <= 0) return DEFAULT_CONCURRENCY;
        return Math.min(MAX_CONCURRENCY, value);
    }

    public static int clampMaxMessages(int value) {
        if (value <= 0) return HARD_MAX_MESSAGES;
        return Math.min(HARD_MAX_MESSAGES, Math.max(PAGE_SIZE, value));
    }
}

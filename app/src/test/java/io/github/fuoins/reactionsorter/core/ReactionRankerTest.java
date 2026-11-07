package io.github.fuoins.reactionsorter.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/** JVM unit tests for the pure ranking logic (no Android runtime required). */
public class ReactionRankerTest {

    private static MessageSnapshot msg(int id, int reactions, int date) {
        return msg(id, reactions, date, 0L);
    }

    private static MessageSnapshot msg(int id, int reactions, int date, long groupedId) {
        List<MessageSnapshot.ReactionEntry> breakdown =
                reactions > 0
                        ? Collections.singletonList(new MessageSnapshot.ReactionEntry("👍", reactions))
                        : Collections.<MessageSnapshot.ReactionEntry>emptyList();
        return new MessageSnapshot(id, date, groupedId, "message " + id, reactions, breakdown);
    }

    @Test
    public void defaultSortIsDescendingByTotalReactions() {
        List<MessageSnapshot> input = Arrays.asList(msg(1, 3, 100), msg(2, 42, 200), msg(3, 7, 300));
        List<MessageSnapshot> ranked = ReactionRanker.rank(input, new ReactionRanker.Options());
        assertEquals(3, ranked.size());
        assertEquals(2, ranked.get(0).messageId);
        assertEquals(3, ranked.get(1).messageId);
        assertEquals(1, ranked.get(2).messageId);
    }

    @Test
    public void ascendingOptionReversesPrimaryOrder() {
        ReactionRanker.Options options = new ReactionRanker.Options(false, 1, 0, true, false);
        List<MessageSnapshot> input = Arrays.asList(msg(1, 3, 100), msg(2, 42, 200), msg(3, 7, 300));
        List<MessageSnapshot> ranked = ReactionRanker.rank(input, options);
        assertEquals(1, ranked.get(0).messageId);
        assertEquals(3, ranked.get(1).messageId);
        assertEquals(2, ranked.get(2).messageId);
    }

    @Test
    public void zeroReactionMessagesAreFilteredByDefault() {
        List<MessageSnapshot> input = Arrays.asList(msg(1, 0, 100), msg(2, 5, 200));
        List<MessageSnapshot> ranked = ReactionRanker.rank(input, new ReactionRanker.Options());
        assertEquals(1, ranked.size());
        assertEquals(2, ranked.get(0).messageId);
    }

    @Test
    public void onlyReactedFalseKeepsZeroReactionMessages() {
        ReactionRanker.Options options = new ReactionRanker.Options(true, 0, 0, false, false);
        List<MessageSnapshot> ranked = ReactionRanker.rank(
                Arrays.asList(msg(1, 0, 100), msg(2, 5, 200)), options);
        assertEquals(2, ranked.size());
        assertEquals(1, ranked.get(1).messageId);
    }

    @Test
    public void minReactionsFiltersBelowThreshold() {
        ReactionRanker.Options options = new ReactionRanker.Options(true, 10, 0, true, false);
        List<MessageSnapshot> ranked = ReactionRanker.rank(
                Arrays.asList(msg(1, 3, 100), msg(2, 10, 200), msg(3, 11, 300)), options);
        assertEquals(2, ranked.size());
        assertEquals(3, ranked.get(0).messageId);
        assertEquals(2, ranked.get(1).messageId);
    }

    @Test
    public void topNTruncatesResult() {
        ReactionRanker.Options options = new ReactionRanker.Options(true, 1, 2, true, false);
        List<MessageSnapshot> ranked = ReactionRanker.rank(
                Arrays.asList(msg(1, 3, 100), msg(2, 42, 200), msg(3, 7, 300), msg(4, 9, 400)),
                options);
        assertEquals(2, ranked.size());
        assertEquals(2, ranked.get(0).messageId);
        assertEquals(4, ranked.get(1).messageId);
    }

    @Test
    public void albumsAreMergedKeepingTheHighestReactionMessage() {
        ReactionRanker.Options options = new ReactionRanker.Options(true, 1, 0, true, true);
        List<MessageSnapshot> ranked = ReactionRanker.rank(Arrays.asList(
                msg(1, 4, 100, 900L), msg(2, 30, 200, 900L), msg(3, 12, 300, 900L),
                msg(4, 20, 400, 0L)), options);
        assertEquals(2, ranked.size());
        assertEquals(2, ranked.get(0).messageId);
        assertEquals(4, ranked.get(1).messageId);
    }

    @Test
    public void tiesAreBrokenByNewerDateThenMessageId() {
        List<MessageSnapshot> ranked = ReactionRanker.rank(Arrays.asList(
                msg(7, 5, 100), msg(3, 5, 300), msg(9, 5, 300)),
                new ReactionRanker.Options());
        assertEquals(3, ranked.get(0).messageId);
        assertEquals(9, ranked.get(1).messageId);
        assertEquals(7, ranked.get(2).messageId);
    }

    @Test
    public void inputListIsNeverModified() {
        List<MessageSnapshot> input = new ArrayList<>(Arrays.asList(msg(1, 3, 100), msg(2, 42, 200)));
        List<MessageSnapshot> ranked = ReactionRanker.rank(input, new ReactionRanker.Options());
        assertNotSame(input, ranked);
        assertEquals(1, input.get(0).messageId);
        assertEquals(2, ranked.get(0).messageId);
    }

    @Test
    public void summaryCountsReactedMessagesAndTotalReactions() {
        ReactionRanker.Summary summary = ReactionRanker.summarize(
                Arrays.asList(msg(1, 0, 1), msg(2, 5, 2), msg(3, 7, 3)));
        assertEquals(3, summary.totalMessages);
        assertEquals(2, summary.reactedMessages);
        assertEquals(12, summary.totalReactions);
        assertEquals(7, summary.maxReactions);
    }

    @Test
    public void nullAndEmptyInputAreHandled() {
        assertTrue(ReactionRanker.rank(null, new ReactionRanker.Options()).isEmpty());
        assertTrue(ReactionRanker.rank(Collections.<MessageSnapshot>emptyList(), null).isEmpty());
        assertEquals(0, ReactionRanker.summarize(null).totalMessages);
    }

    @Test
    public void previewAndBreakdownAreReadable() {
        MessageSnapshot m = msg(11, 7, 100);
        assertEquals("👍×7", m.breakdownText());
        assertEquals("message 11", m.preview(64));
        MessageSnapshot empty = msg(12, 0, 100);
        assertEquals("—", empty.breakdownText());
    }
}

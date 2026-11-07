package io.github.fuoins.reactionsorter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Covers the sort modes offered by the bottom bar of the search-style panel. */
public class SortModeTest {

    private static MessageSnapshot msg(int id, int date, int reactions) {
        return new MessageSnapshot(id, date, 0L, "text-" + id, reactions,
                Collections.singletonList(new MessageSnapshot.ReactionEntry("👍", reactions)));
    }

    private static List<MessageSnapshot> sample() {
        return new ArrayList<>(Arrays.asList(
                msg(1, 100, 5),
                msg(2, 300, 5),
                msg(3, 200, 9),
                msg(4, 400, 2)));
    }

    private static List<Integer> ids(List<MessageSnapshot> list) {
        List<Integer> out = new ArrayList<>();
        for (MessageSnapshot m : list) out.add(m.messageId);
        return out;
    }

    private static List<Integer> rankFor(ReactionRanker.SortMode mode, boolean descending) {
        ReactionRanker.Options o = new ReactionRanker.Options();
        o.sortMode = mode;
        o.descending = descending;
        o.mergeAlbums = false;
        return ids(ReactionRanker.rank(sample(), o));
    }

    @Test
    public void reactionsDescending() {
        assertEquals(Arrays.asList(3, 2, 1, 4), rankFor(ReactionRanker.SortMode.REACTIONS_DESC, true));
    }

    @Test
    public void reactionsAscending() {
        assertEquals(Arrays.asList(4, 2, 1, 3), rankFor(ReactionRanker.SortMode.REACTIONS_ASC, true));
    }

    @Test
    public void dateModes() {
        assertEquals(Arrays.asList(4, 2, 3, 1), rankFor(ReactionRanker.SortMode.DATE_DESC, true));
        assertEquals(Arrays.asList(1, 3, 2, 4), rankFor(ReactionRanker.SortMode.DATE_ASC, true));
    }

    @Test
    public void messageIdModesFollowChatOrder() {
        assertEquals(Arrays.asList(4, 3, 2, 1), rankFor(ReactionRanker.SortMode.ID_DESC, true));
        assertEquals(Arrays.asList(1, 2, 3, 4), rankFor(ReactionRanker.SortMode.ID_ASC, true));
    }

    @Test
    public void sortModeOverridesLegacyDescendingFlag() {
        // Even with descending=true (legacy), the explicit mode must win.
        assertEquals(Arrays.asList(4, 2, 1, 3),
                rankFor(ReactionRanker.SortMode.REACTIONS_ASC, true));
        // And with descending=false, an explicit descending mode still wins.
        assertEquals(Arrays.asList(3, 2, 1, 4),
                rankFor(ReactionRanker.SortMode.REACTIONS_DESC, false));
    }

    @Test
    public void legacyOptionsKeepOldBehaviourWhenModeIsNull() {
        ReactionRanker.Options desc = new ReactionRanker.Options(true, 1, 0, true, false);
        ReactionRanker.Options asc = new ReactionRanker.Options(false, 1, 0, true, false);
        assertEquals(Arrays.asList(3, 2, 1, 4), ids(ReactionRanker.rank(sample(), desc)));
        assertEquals(Arrays.asList(4, 2, 1, 3), ids(ReactionRanker.rank(sample(), asc)));
    }

    @Test
    public void filtersStillApplyWithExplicitMode() {
        List<MessageSnapshot> messages = new ArrayList<>(sample());
        messages.add(msg(5, 500, 0));
        ReactionRanker.Options o = new ReactionRanker.Options();
        o.sortMode = ReactionRanker.SortMode.DATE_DESC;
        o.mergeAlbums = false;
        o.onlyReacted = true;
        o.minReactions = 3;
        assertEquals(Arrays.asList(2, 3, 1), ids(ReactionRanker.rank(messages, o)));

        o.minReactions = 0;
        o.onlyReacted = false;
        // DATE_DESC: msg 5 is the newest (date 500), then 4, 2, 3, 1.
        assertEquals(Arrays.asList(5, 4, 2, 3, 1), ids(ReactionRanker.rank(messages, o)));
    }

    @Test
    public void parseIsLenient() {
        ReactionRanker.SortMode fallback = ReactionRanker.SortMode.REACTIONS_DESC;
        assertEquals(ReactionRanker.SortMode.DATE_DESC,
                ReactionRanker.SortMode.parse("date_desc", fallback));
        assertEquals(ReactionRanker.SortMode.ID_ASC,
                ReactionRanker.SortMode.parse("  id_asc ", fallback));
        assertEquals(fallback, ReactionRanker.SortMode.parse(null, fallback));
        assertEquals(fallback, ReactionRanker.SortMode.parse("nonsense", fallback));
    }

    @Test
    public void rankingDoesNotModifyInput() {
        List<MessageSnapshot> source = sample();
        ReactionRanker.Options o = new ReactionRanker.Options();
        o.sortMode = ReactionRanker.SortMode.ID_DESC;
        o.mergeAlbums = false;
        ReactionRanker.rank(source, o);
        assertEquals(Arrays.asList(1, 2, 3, 4), ids(source));
        assertFalse(source.isEmpty());
        assertTrue(ReactionRanker.rank(Collections.<MessageSnapshot>emptyList(), o).isEmpty());
    }
}

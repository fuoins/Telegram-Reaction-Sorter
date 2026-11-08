package io.github.fuoins.reactionsorter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class ReactionRepositoryTest {

    private static final long DIALOG = -1001234567890L;

    private static MessageSnapshot msg(int id, int reactions) {
        return new MessageSnapshot(id, 1_700_000_000 + id, 0L, "text " + id, reactions, null);
    }

    private static MessageSnapshot find(List<MessageSnapshot> list, int id) {
        for (MessageSnapshot m : list) if (m.messageId == id) return m;
        return null;
    }

    @Test
    public void databaseOnlyMessagesAreKept() {
        ReactionRepository repo = new ReactionRepository();
        repo.putUi(DIALOG, Collections.singletonList(msg(10, 3)));
        repo.mergeDatabase(DIALOG, Arrays.asList(msg(1, 7), msg(2, 5), msg(10, 1)), 3);

        List<MessageSnapshot> all = repo.merged(DIALOG);
        assertEquals(3, all.size());
        assertEquals(7, find(all, 1).totalReactions);
        assertEquals(5, find(all, 2).totalReactions);
    }

    @Test
    public void uiValueWinsForDuplicateIds() {
        ReactionRepository repo = new ReactionRepository();
        repo.mergeDatabase(DIALOG, Collections.singletonList(msg(10, 1)), 1);
        repo.putUi(DIALOG, Collections.singletonList(msg(10, 42)));

        assertEquals(42, find(repo.merged(DIALOG), 10).totalReactions);
        assertEquals(1, repo.merged(DIALOG).size());
    }

    @Test
    public void successiveScansAccumulateInsteadOfReplacing() {
        ReactionRepository repo = new ReactionRepository();
        repo.mergeDatabase(DIALOG, Arrays.asList(msg(1, 1), msg(2, 1)), 2);
        repo.mergeDatabase(DIALOG, Arrays.asList(msg(3, 1), msg(4, 1)), 2);

        assertEquals(4, repo.databaseCount(DIALOG));
        // rowsRead describes the largest single scan, not a sum over repeated rescans.
        assertEquals(2, repo.rowsRead(DIALOG));
    }

    @Test
    public void rowsReadKeepsTheLargestReportedValue() {
        ReactionRepository repo = new ReactionRepository();
        repo.mergeDatabase(DIALOG, Collections.singletonList(msg(1, 1)), 900);
        repo.mergeDatabase(DIALOG, Collections.singletonList(msg(2, 1)), 120);
        assertEquals(900, repo.rowsRead(DIALOG));
    }

    @Test
    public void latestUiSnapshotReplacesThePreviousOne() {
        ReactionRepository repo = new ReactionRepository();
        repo.putUi(DIALOG, Collections.singletonList(msg(10, 1)));
        repo.putUi(DIALOG, Collections.singletonList(msg(10, 9)));

        assertEquals(1, repo.uiCount(DIALOG));
        assertEquals(9, find(repo.merged(DIALOG), 10).totalReactions);
    }

    @Test
    public void nullZeroIdAndNullEntriesAreIgnored() {
        ReactionRepository repo = new ReactionRepository();
        Collection<MessageSnapshot> input = Arrays.asList(null, msg(0, 5), msg(7, 2));
        repo.mergeDatabase(DIALOG, input, 3);

        assertEquals(1, repo.databaseCount(DIALOG));
        assertEquals(2, find(repo.merged(DIALOG), 7).totalReactions);
    }

    @Test
    public void chatsAreIsolatedAndClearWorks() {
        ReactionRepository repo = new ReactionRepository();
        repo.mergeDatabase(DIALOG, Collections.singletonList(msg(1, 1)), 1);
        repo.mergeDatabase(777L, Collections.singletonList(msg(2, 1)), 1);

        assertTrue(repo.hasFullScan(DIALOG));
        assertEquals(1, repo.merged(DIALOG).size());
        assertEquals(1, repo.merged(777L).size());

        repo.clear(DIALOG);
        assertFalse(repo.hasFullScan(DIALOG));
        assertTrue(repo.merged(DIALOG).isEmpty());
        assertEquals(1, repo.merged(777L).size());

        repo.clearAll();
        assertTrue(repo.merged(777L).isEmpty());
    }

    @Test
    public void capProtectsAgainstHugeDatabases() {
        ReactionRepository repo = new ReactionRepository();
        java.util.List<MessageSnapshot> bulk = new java.util.ArrayList<>();
        for (int i = 1; i <= ReactionRepository.MAX_PER_DIALOG + 50; i++) {
            bulk.add(msg(i, 1));
        }
        repo.mergeDatabase(DIALOG, bulk, bulk.size());
        assertEquals(ReactionRepository.MAX_PER_DIALOG, repo.databaseCount(DIALOG));
    }

    // ---- online (server-side) source -------------------------------------------

    @Test
    public void onlineScanFillsInMessagesMissingFromLocalCache() {
        ReactionRepository repo = new ReactionRepository();
        repo.putUi(DIALOG, Collections.singletonList(msg(90, 3)));
        repo.mergeDatabase(DIALOG, Arrays.asList(msg(90, 3), msg(91, 2)), 2);
        // Server history covers the entire chat, including messages never cached locally.
        repo.mergeOnline(DIALOG, Arrays.asList(msg(1, 30), msg(2, 20), msg(90, 5)), 92);

        List<MessageSnapshot> all = repo.merged(DIALOG);
        assertEquals(4, all.size());
        assertEquals(30, find(all, 1).totalReactions);
        assertEquals(20, find(all, 2).totalReactions);
        assertEquals(3, repo.onlineCount(DIALOG));
        assertEquals(92, repo.serverTotal(DIALOG));
        assertTrue(repo.hasOnlineScan(DIALOG));
    }

    @Test
    public void uiStillWinsOverOnlineAndDatabase() {
        ReactionRepository repo = new ReactionRepository();
        repo.mergeDatabase(DIALOG, Collections.singletonList(msg(10, 1)), 1);
        repo.mergeOnline(DIALOG, Collections.singletonList(msg(10, 8)), 50);
        repo.putUi(DIALOG, Collections.singletonList(msg(10, 42)));

        assertEquals(42, find(repo.merged(DIALOG), 10).totalReactions);
    }

    @Test
    public void onlineWinsOverStaleDatabaseForDuplicateIds() {
        ReactionRepository repo = new ReactionRepository();
        repo.mergeDatabase(DIALOG, Collections.singletonList(msg(10, 1)), 1);
        repo.mergeOnline(DIALOG, Collections.singletonList(msg(10, 8)), 50);

        assertEquals(8, find(repo.merged(DIALOG), 10).totalReactions);
    }

    @Test
    public void successiveOnlineScansAccumulateAndKeepLargestTotal() {
        ReactionRepository repo = new ReactionRepository();
        repo.mergeOnline(DIALOG, Arrays.asList(msg(1, 1), msg(2, 1)), 120);
        repo.mergeOnline(DIALOG, Arrays.asList(msg(3, 1), msg(4, 1)), 100);

        assertEquals(4, repo.onlineCount(DIALOG));
        assertEquals(120, repo.serverTotal(DIALOG));
    }

    @Test
    public void clearRemovesOnlineDataAndServerTotal() {
        ReactionRepository repo = new ReactionRepository();
        repo.mergeOnline(DIALOG, Collections.singletonList(msg(1, 1)), 100);
        assertTrue(repo.hasFullScan(DIALOG));

        repo.clear(DIALOG);
        assertFalse(repo.hasOnlineScan(DIALOG));
        assertFalse(repo.hasFullScan(DIALOG));
        assertEquals(0, repo.onlineCount(DIALOG));
        assertEquals(0, repo.serverTotal(DIALOG));
    }
}

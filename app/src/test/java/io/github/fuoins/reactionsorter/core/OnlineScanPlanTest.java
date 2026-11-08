package io.github.fuoins.reactionsorter.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the pure paging / rate-limit planning of the online history scan. */
public class OnlineScanPlanTest {

    @Test
    public void offsetsAreIndependentPagesStridingByPageSize() {
        assertEquals(Arrays.asList(0, 100, 200, 300),
                OnlineScanPlan.offsets(350, 20_000, 0));
    }

    @Test
    public void offsetsDoNotPadBeyondWhatIsNeeded() {
        // 250 messages -> exactly 3 pages; no wave padding that would overshoot the cap.
        List<Integer> offsets = OnlineScanPlan.offsets(250, 20_000, 0);
        assertEquals(Arrays.asList(0, 100, 200), offsets);
    }

    @Test
    public void offsetsRespectTheMessageCap() {
        assertEquals(Arrays.asList(0, 100, 200),
                OnlineScanPlan.offsets(100_000, 250, 0));
    }

    @Test
    public void offsetsFallBackToHardCapWhenServerTotalUnknown() {
        List<Integer> offsets = OnlineScanPlan.offsets(0, 0, 0);
        assertFalse(offsets.isEmpty());
        assertEquals(Integer.valueOf(0), offsets.get(0));
        int last = offsets.get(offsets.size() - 1);
        assertTrue(last < OnlineScanPlan.HARD_MAX_MESSAGES);
        assertEquals(OnlineScanPlan.HARD_MAX_MESSAGES / OnlineScanPlan.PAGE_SIZE,
                offsets.size());
    }

    @Test
    public void offsetsSkipTheAlreadyProbedFirstPage() {
        assertEquals(Arrays.asList(100, 200, 300),
                OnlineScanPlan.offsets(350, 20_000, 100));
    }

    @Test
    public void offsetsNeverExceedHardMax() {
        List<Integer> offsets = OnlineScanPlan.offsets(10_000_000, Integer.MAX_VALUE, 0);
        int last = offsets.get(offsets.size() - 1);
        assertTrue(last < OnlineScanPlan.HARD_MAX_MESSAGES);
        assertEquals(OnlineScanPlan.HARD_MAX_MESSAGES / OnlineScanPlan.PAGE_SIZE,
                offsets.size());
    }

    @Test
    public void probeAlreadyCoveredWholeHistoryYieldsNoFurtherPages() {
        assertTrue(OnlineScanPlan.offsets(80, 20_000, 100).isEmpty());
    }

    @Test
    public void floodWaitParsing() {
        assertEquals(30, OnlineScanPlan.floodWaitSeconds("FLOOD_WAIT_30"));
        assertEquals(2, OnlineScanPlan.floodWaitSeconds("FLOOD_WAIT_2"));
        assertEquals(10, OnlineScanPlan.floodWaitSeconds("FLOOD_PREMIUM_WAIT_10"));
        assertEquals(0, OnlineScanPlan.floodWaitSeconds("CHANNEL_INVALID"));
        assertEquals(0, OnlineScanPlan.floodWaitSeconds("FLOOD_WAIT_"));
        assertEquals(0, OnlineScanPlan.floodWaitSeconds(null));
        assertEquals(0, OnlineScanPlan.floodWaitSeconds(""));
    }

    @Test
    public void floodWaitDetection() {
        assertTrue(OnlineScanPlan.isFloodWait("FLOOD_WAIT_30"));
        assertFalse(OnlineScanPlan.isFloodWait("CHAT_WRITE_FORBIDDEN"));
    }

    @Test
    public void clamping() {
        assertEquals(OnlineScanPlan.DEFAULT_CONCURRENCY, OnlineScanPlan.clampConcurrency(0));
        assertEquals(OnlineScanPlan.MAX_CONCURRENCY, OnlineScanPlan.clampConcurrency(99));
        assertEquals(3, OnlineScanPlan.clampConcurrency(3));
        assertEquals(OnlineScanPlan.HARD_MAX_MESSAGES, OnlineScanPlan.clampMaxMessages(0));
        assertEquals(100, OnlineScanPlan.clampMaxMessages(5));
        assertEquals(500, OnlineScanPlan.clampMaxMessages(500));
        assertEquals(OnlineScanPlan.HARD_MAX_MESSAGES,
                OnlineScanPlan.clampMaxMessages(Integer.MAX_VALUE));
    }
}

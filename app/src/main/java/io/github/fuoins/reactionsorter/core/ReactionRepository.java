package io.github.fuoins.reactionsorter.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory accumulator of reaction snapshots for one chat. Framework-free so its merge
 * behaviour is covered by plain JVM unit tests.
 *
 * <p>Three sources feed the repository, in increasing precedence:
 * <ul>
 *   <li><b>DATABASE</b> — a read-only scan of the client's local {@code cache4.db}. Complete
 *       for everything the client has ever cached, but only a subset of the server history.</li>
 *   <li><b>ONLINE</b> — a scan of {@code messages.getHistory} metadata straight from the
 *       server. This is the <em>whole chat history</em>: every message, not just cached ones.
 *       It carries the authoritative total reported by the server ({@code messagesSlice.count}).</li>
 *   <li><b>UI</b> — the message list the chat screen currently holds. Always freshest (a new
 *       reaction the user just tapped is visible immediately) but only a small window.</li>
 * </ul>
 *
 * <p>Merging keeps every distinct message id; the freshest source wins for ids present in
 * several sources. Nothing is written to disk — chat content never leaves the host process.
 */
public final class ReactionRepository {

    /** Hard cap per chat, protects against pathological databases. */
    public static final int MAX_PER_DIALOG = 50_000;

    private final Map<Long, Map<Integer, MessageSnapshot>> ui = new HashMap<>();
    private final Map<Long, Map<Integer, MessageSnapshot>> database = new HashMap<>();
    private final Map<Long, Map<Integer, MessageSnapshot>> online = new HashMap<>();
    private final Map<Long, Integer> scannedRows = new HashMap<>();
    private final Map<Long, Integer> serverTotals = new HashMap<>();

    /** Replace the live UI window for this chat. */
    public synchronized void putUi(long dialogId, Collection<MessageSnapshot> snapshots) {
        Map<Integer, MessageSnapshot> target = new HashMap<>();
        absorb(target, snapshots);
        ui.put(dialogId, target);
    }

    /**
     * Merge a local-database scan into this chat. Successive scans accumulate instead of
     * replacing, so a partial earlier scan is never lost.
     */
    public synchronized void mergeDatabase(long dialogId, Collection<MessageSnapshot> snapshots,
                                           int rowsRead) {
        Map<Integer, MessageSnapshot> target = database.get(dialogId);
        if (target == null) {
            target = new HashMap<>();
            database.put(dialogId, target);
        }
        absorb(target, snapshots);
        scannedRows.put(dialogId, Math.max(scannedRows.getOrDefault(dialogId, 0), rowsRead));
    }

    /**
     * Merge a server-side history scan. Accumulates across runs (a later full scan adds to a
     * partial one) and remembers the server's reported total for the coverage label.
     */
    public synchronized void mergeOnline(long dialogId, Collection<MessageSnapshot> snapshots,
                                          int serverTotal) {
        Map<Integer, MessageSnapshot> target = online.get(dialogId);
        if (target == null) {
            target = new HashMap<>();
            online.put(dialogId, target);
        }
        absorb(target, snapshots);
        if (serverTotal > 0) {
            serverTotals.put(dialogId, Math.max(serverTotals.getOrDefault(dialogId, 0), serverTotal));
        }
    }

    /** All known messages for this chat, freshest source taking precedence. */
    public synchronized List<MessageSnapshot> merged(long dialogId) {
        Map<Integer, MessageSnapshot> result = new HashMap<>();
        Map<Integer, MessageSnapshot> dbValues = database.get(dialogId);
        if (dbValues != null) result.putAll(dbValues);
        Map<Integer, MessageSnapshot> onlineValues = online.get(dialogId);
        if (onlineValues != null) result.putAll(onlineValues);
        Map<Integer, MessageSnapshot> uiValues = ui.get(dialogId);
        if (uiValues != null) result.putAll(uiValues);
        return new ArrayList<>(result.values());
    }

    public synchronized int uiCount(long dialogId) {
        Map<Integer, MessageSnapshot> values = ui.get(dialogId);
        return values == null ? 0 : values.size();
    }

    public synchronized int databaseCount(long dialogId) {
        Map<Integer, MessageSnapshot> values = database.get(dialogId);
        return values == null ? 0 : values.size();
    }

    /** Number of messages obtained from the server history (not the local cache). */
    public synchronized int onlineCount(long dialogId) {
        Map<Integer, MessageSnapshot> values = online.get(dialogId);
        return values == null ? 0 : values.size();
    }

    /** Server-reported total message count for this chat, 0 when unknown. */
    public synchronized int serverTotal(long dialogId) {
        return serverTotals.getOrDefault(dialogId, 0);
    }

    public synchronized int rowsRead(long dialogId) {
        return scannedRows.getOrDefault(dialogId, 0);
    }

    public synchronized boolean hasFullScan(long dialogId) {
        return database.containsKey(dialogId) || online.containsKey(dialogId);
    }

    public synchronized boolean hasOnlineScan(long dialogId) {
        return online.containsKey(dialogId);
    }

    public synchronized void clear(long dialogId) {
        ui.remove(dialogId);
        database.remove(dialogId);
        online.remove(dialogId);
        scannedRows.remove(dialogId);
        serverTotals.remove(dialogId);
    }

    public synchronized void clearAll() {
        ui.clear();
        database.clear();
        online.clear();
        scannedRows.clear();
        serverTotals.clear();
    }

    private static void absorb(Map<Integer, MessageSnapshot> target,
                               Collection<MessageSnapshot> snapshots) {
        if (snapshots == null) return;
        for (MessageSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.messageId == 0) continue;
            if (target.size() >= MAX_PER_DIALOG && !target.containsKey(snapshot.messageId)) {
                continue;
            }
            target.put(snapshot.messageId, snapshot);
        }
    }
}

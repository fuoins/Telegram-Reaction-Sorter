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
 * <p>Two sources feed the repository:
 * <ul>
 *   <li><b>UI</b> — the message list the chat screen currently holds. Always fresh (a new
 *       reaction the user just tapped is visible immediately) but only a small window of
 *       the whole history, because Telegram loads messages in pages.</li>
 *   <li><b>DATABASE</b> — a full scan of the client's local message store. Complete for
 *       everything the client has ever cached, but possibly a few seconds stale.</li>
 * </ul>
 *
 * <p>Merging keeps every distinct message id: UI values win for ids present in both
 * (freshness), and database-only ids fill in all the history the UI has not paged in yet.
 * Nothing is written to disk — chat content never leaves the host process memory.
 */
public final class ReactionRepository {

    /** Hard cap per chat, protects against pathological databases. */
    public static final int MAX_PER_DIALOG = 50_000;

    private final Map<Long, Map<Integer, MessageSnapshot>> ui = new HashMap<>();
    private final Map<Long, Map<Integer, MessageSnapshot>> database = new HashMap<>();
    private final Map<Long, Integer> scannedRows = new HashMap<>();

    /** Replace the live UI window for this chat. */
    public synchronized void putUi(long dialogId, Collection<MessageSnapshot> snapshots) {
        Map<Integer, MessageSnapshot> target = new HashMap<>();
        absorb(target, snapshots);
        ui.put(dialogId, target);
    }

    /**
     * Merge a database scan into this chat. Successive scans (e.g. after loading older
     * history) accumulate instead of replacing, so a partial earlier scan is never lost.
     *
     * @param rowsRead number of message rows examined, used for the UI coverage label
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

    /** All known messages for this chat, UI values taking precedence. */
    public synchronized List<MessageSnapshot> merged(long dialogId) {
        Map<Integer, MessageSnapshot> result = new HashMap<>();
        Map<Integer, MessageSnapshot> dbValues = database.get(dialogId);
        if (dbValues != null) result.putAll(dbValues);
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

    public synchronized int rowsRead(long dialogId) {
        return scannedRows.getOrDefault(dialogId, 0);
    }

    public synchronized boolean hasFullScan(long dialogId) {
        return database.containsKey(dialogId);
    }

    public synchronized void clear(long dialogId) {
        ui.remove(dialogId);
        database.remove(dialogId);
        scannedRows.remove(dialogId);
    }

    public synchronized void clearAll() {
        ui.clear();
        database.clear();
        scannedRows.clear();
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

package io.github.fuoins.reactionsorter.core;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.fuoins.reactionsorter.util.LogX;

/**
 * Reads <em>all</em> messages the Telegram client has cached locally, bypassing the chat
 * screen's paged message list.
 *
 * <p>Telegram keeps a SQLite store at {@code <filesDir>/cache4.db}. Its main table is
 * {@code messages_v2(mid, uid, date, data BLOB, ...)} (older builds used {@code messages},
 * forum topics live in {@code messages_topics}). Each {@code data} blob is a TL-serialised
 * {@code TLRPC.Message}, which already carries the full {@code reactions} structure —
 * the same data the UI shows, and independent of whether photo/video files were ever
 * downloaded. Reactions therefore do not need media loading or a network request.
 *
 * <p>The database is opened read-only with the platform SQLite API (framework class names
 * are never obfuscated), tables/columns are discovered at runtime rather than hard-coded,
 * and every blob is decoded by the host's own {@link TlMessageDecoder}. Nothing is written
 * back and no chat content is copied out of the host process.
 */
public final class MessageDatabase {

    private static final int BATCH = 250;
    private static final String[] TABLES = {"messages_v2", "messages", "messages_topics"};

    public interface Progress {
        /** Return {@code false} to abort the scan. Called off the UI thread. */
        boolean onProgress(int rowsRead, int reactedMessages);
    }

    public static final class Result {
        public final List<MessageSnapshot> snapshots;
        public final int rowsRead;
        public final int reactedMessages;
        public final long dialogId;
        public final String detail;
        public final String error;
        public final boolean cancelled;

        Result(List<MessageSnapshot> snapshots, int rowsRead, int reactedMessages,
               long dialogId, String detail, String error, boolean cancelled) {
            this.snapshots = snapshots;
            this.rowsRead = rowsRead;
            this.reactedMessages = reactedMessages;
            this.dialogId = dialogId;
            this.detail = detail;
            this.error = error;
            this.cancelled = cancelled;
        }

        public boolean success() {
            return error == null;
        }
    }

    private static final class Columns {
        String mid;
        String date;
        String data;
        String uid;
    }

    private MessageDatabase() {}

    /**
     * @param context       the host application context (module code runs in the host process)
     * @param loader        host class loader, needed by the TL deserialiser
     * @param dialogId      current chat id; 0 enables auto-detection / full-store fallback
     * @param uiMessageIds  ids currently visible in the UI, used to identify the chat
     * @param maxRows       hard cap on rows examined
     */
    public static Result scan(Context context, ClassLoader loader, long dialogId,
                              List<Integer> uiMessageIds, int maxRows, Progress progress) {
        if (context == null) {
            return failure("宿主上下文不可用", dialogId);
        }
        TlMessageDecoder decoder = TlMessageDecoder.create(loader);
        if (decoder == null) {
            return failure("当前客户端未提供可用的消息反序列化接口", dialogId);
        }

        List<File> candidates = candidateFiles(context);
        String lastError = null;
        for (File file : candidates) {
            if (!file.isFile() || !file.canRead()) continue;
            SQLiteDatabase db = null;
            try {
                db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                        SQLiteDatabase.OPEN_READONLY);
                return scanDatabase(db, decoder, dialogId, uiMessageIds, maxRows, progress,
                        file.getName());
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                LogX.w("cannot read " + file.getAbsolutePath(), t);
            } finally {
                if (db != null) {
                    try { db.close(); } catch (Throwable ignored) {}
                }
            }
        }
        return failure("无法打开本地消息库 cache4.db" + (lastError == null ? "" : "（" + lastError + "）"),
                dialogId);
    }

    private static Result scanDatabase(SQLiteDatabase db, TlMessageDecoder decoder,
                                       long dialogId, List<Integer> uiMessageIds,
                                       int maxRows, Progress progress, String dbName) {
        Set<String> existing = tableNames(db);
        if (existing.isEmpty()) return failure("本地消息库里没有消息表", dialogId);

        long effectiveDialog = dialogId;
        if (effectiveDialog == 0) {
            effectiveDialog = guessDialog(db, uiMessageIds);
        }

        List<MessageSnapshot> out = new ArrayList<>();
        Map<Integer, MessageSnapshot> unique = new HashMap<>();
        int rowsRead = 0;
        int reacted = 0;
        boolean cancelled = false;
        StringBuilder detail = new StringBuilder(dbName).append("：");
        int usedTables = 0;

        outer:
        for (String table : TABLES) {
            if (!existing.contains(table)) continue;
            Columns cols = columns(db, table);
            if (cols == null || cols.data == null || cols.mid == null) continue;
            usedTables++;
            int before = rowsRead;
            long lastMid = Long.MAX_VALUE;

            while (rowsRead < maxRows) {
                int limit = Math.min(BATCH, maxRows - rowsRead);
                String[] args;
                String where;
                if (effectiveDialog != 0 && cols.uid != null) {
                    where = " WHERE " + cols.uid + " = ? AND " + cols.mid + " < ?";
                    args = new String[]{String.valueOf(effectiveDialog), String.valueOf(lastMid)};
                } else {
                    where = " WHERE " + cols.mid + " < ?";
                    args = new String[]{String.valueOf(lastMid)};
                }
                String dateColumn = cols.date != null ? cols.date : "0";
                String sql = "SELECT " + cols.mid + ", " + dateColumn + ", " + cols.data
                        + " FROM " + table + where
                        + " ORDER BY " + cols.mid + " DESC LIMIT " + limit;

                Cursor cursor = null;
                int batchCount = 0;
                try {
                    cursor = db.rawQuery(sql, args);
                    while (cursor.moveToNext()) {
                        long mid = cursor.getLong(0);
                        int date = cursor.getInt(1);
                        byte[] blob = cursor.getBlob(2);
                        lastMid = mid;
                        batchCount++;
                        rowsRead++;

                        if (blob == null || blob.length < 4) continue;
                        try {
                            Object owner = decoder.decode(blob);
                            MessageSnapshot snapshot =
                                    ReactionCounter.fromOwner(owner, (int) mid, date, 256);
                            if (snapshot == null || snapshot.messageId == 0) continue;
                            if (!unique.containsKey(snapshot.messageId)) {
                                unique.put(snapshot.messageId, snapshot);
                                out.add(snapshot);
                                if (snapshot.totalReactions > 0) reacted++;
                            }
                        } catch (Throwable ignored) {
                            // One corrupt / unknown-layer row must not abort the scan.
                        }
                    }
                } catch (Throwable t) {
                    LogX.w("query failed on " + table, t);
                    break;
                } finally {
                    if (cursor != null) {
                        try { cursor.close(); } catch (Throwable ignored) {}
                    }
                }

                if (progress != null && !progress.onProgress(rowsRead, reacted)) {
                    cancelled = true;
                    break outer;
                }
                if (batchCount < limit) break; // reached the oldest row of this table
            }
            detail.append(table).append(' ').append(rowsRead - before).append(" 行；");
        }

        if (usedTables == 0) return failure("未识别到消息表结构", effectiveDialog);
        if (effectiveDialog == 0) detail.append("未识别当前聊天，已扫描全部本地消息；");
        else detail.append("当前聊天 ").append(effectiveDialog).append('；');
        detail.append("共 ").append(rowsRead).append(" 行 / ").append(out.size()).append(" 条消息");

        return new Result(out, rowsRead, reacted, effectiveDialog,
                detail.toString(), null, cancelled);
    }

    /** Table names present in the store, restricted to a safe identifier whitelist. */
    private static Set<String> tableNames(SQLiteDatabase db) {
        Set<String> out = new HashSet<>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                if (safeIdentifier(name)) out.add(name);
            }
        } catch (Throwable t) {
            LogX.w("cannot list tables", t);
        } finally {
            close(cursor);
        }
        return out;
    }

    private static Columns columns(SQLiteDatabase db, String table) {
        Columns cols = new Columns();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (!safeIdentifier(name)) continue;
                if ("mid".equals(name)) cols.mid = name;
                else if ("date".equals(name)) cols.date = name;
                else if ("data".equals(name)) cols.data = name;
                else if ("uid".equals(name)) cols.uid = name;
            }
        } catch (Throwable t) {
            LogX.w("cannot read columns of " + table, t);
            return null;
        } finally {
            close(cursor);
        }
        if (cols.mid == null) cols.mid = "rowid"; // every Telegram message table has a rowid
        return cols;
    }

    /**
     * Fallback when {@code messageOwner.dialog_id} is unavailable: match the ids visible
     * in the UI against each dialog's {@code last_mid} in the {@code dialogs} table.
     */
    private static long guessDialog(SQLiteDatabase db, List<Integer> uiMessageIds) {
        if (uiMessageIds == null || uiMessageIds.isEmpty()) return 0L;
        Set<Integer> visible = new HashSet<>(uiMessageIds);
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT did, last_mid FROM dialogs", null);
            while (cursor.moveToNext()) {
                long did = cursor.getLong(0);
                int lastMid = cursor.getInt(1);
                if (did != 0 && visible.contains(lastMid)) return did;
            }
        } catch (Throwable t) {
            LogX.d("dialogs lookup unavailable: " + t);
        } finally {
            close(cursor);
        }
        return 0L;
    }

    private static List<File> candidateFiles(Context context) {
        Set<String> seen = new LinkedHashSet<>();
        List<File> out = new ArrayList<>();
        File filesDir = context.getFilesDir();
        add(out, seen, new File(filesDir, "cache4.db"));
        try {
            File fromFramework = context.getDatabasePath("cache4.db");
            if (fromFramework != null) add(out, seen, fromFramework);
        } catch (Throwable ignored) {}
        String pkg = context.getPackageName();
        add(out, seen, new File("/data/user/0/" + pkg + "/files/cache4.db"));
        add(out, seen, new File("/data/data/" + pkg + "/files/cache4.db"));
        try {
            File[] listed = filesDir.listFiles();
            if (listed != null) {
                for (File f : listed) {
                    if (f.isFile() && f.getName().endsWith(".db")) add(out, seen, f);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void add(List<File> out, Set<String> seen, File file) {
        if (file != null && seen.add(file.getAbsolutePath())) out.add(file);
    }

    private static boolean safeIdentifier(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_') return false;
        }
        return true;
    }

    private static void close(Cursor cursor) {
        if (cursor == null) return;
        try { cursor.close(); } catch (Throwable ignored) {}
    }

    private static Result failure(String error, long dialogId) {
        return new Result(new ArrayList<MessageSnapshot>(), 0, 0, dialogId, error, error, false);
    }
}

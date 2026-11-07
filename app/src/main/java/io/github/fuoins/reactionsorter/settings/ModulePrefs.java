package io.github.fuoins.reactionsorter.settings;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XSharedPreferences;
import io.github.fuoins.reactionsorter.core.ReactionRanker;
import io.github.fuoins.reactionsorter.util.LogX;

/**
 * Preferences written by the module's own SettingsActivity and read from the hooked process
 * through XSharedPreferences (LSPosed makes the module's prefs readable to the host).
 */
public final class ModulePrefs {

    public static final String MODULE_PACKAGE = "io.github.fuoins.reactionsorter";
    public static final String PREFS_NAME = "module_prefs";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SHOW_FAB = "show_fab";
    public static final String KEY_DESCENDING = "descending";
    public static final String KEY_ONLY_REACTED = "only_reacted";
    public static final String KEY_MERGE_ALBUMS = "merge_albums";
    public static final String KEY_MIN_REACTIONS = "min_reactions";
    public static final String KEY_TOP_N = "top_n";
    public static final String KEY_VERBOSE = "verbose";

    private static final long CACHE_TTL_MS = 3_000L;
    private static volatile long lastRead;
    private static volatile ReactionRanker.Options cachedOptions;
    private static volatile boolean cachedEnabled = true;
    private static volatile boolean cachedShowFab = true;

    private ModulePrefs() {}

    private static SharedPreferences writer(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static SharedPreferences local(Context context) {
        return writer(context);
    }

    private static XSharedPreferences reader() {
        XSharedPreferences prefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
        prefs.reload();
        return prefs;
    }

    private static synchronized void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (cachedOptions != null && now - lastRead < CACHE_TTL_MS) return;
        XSharedPreferences prefs = reader();
        cachedEnabled = prefs.getBoolean(KEY_ENABLED, true);
        cachedShowFab = prefs.getBoolean(KEY_SHOW_FAB, true);
        ReactionRanker.Options options = new ReactionRanker.Options();
        options.descending = prefs.getBoolean(KEY_DESCENDING, true);
        options.onlyReacted = prefs.getBoolean(KEY_ONLY_REACTED, true);
        options.mergeAlbums = prefs.getBoolean(KEY_MERGE_ALBUMS, true);
        options.minReactions = parseNonNegative(prefs.getString(KEY_MIN_REACTIONS, "1"), 1);
        options.topN = parseNonNegative(prefs.getString(KEY_TOP_N, "0"), 0);
        cachedOptions = options;
        LogX.setVerbose(prefs.getBoolean(KEY_VERBOSE, false));
        lastRead = now;
    }

    private static int parseNonNegative(String raw, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(raw == null ? "" : raw.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean isEnabled() {
        refreshIfStale();
        return cachedEnabled;
    }

    public static boolean showFloatingEntry() {
        refreshIfStale();
        return cachedShowFab;
    }

    public static ReactionRanker.Options options() {
        refreshIfStale();
        ReactionRanker.Options source = cachedOptions;
        // Hand out a copy so a dialog toggling the sort order cannot mutate the cached values.
        ReactionRanker.Options copy = new ReactionRanker.Options();
        copy.descending = source.descending;
        copy.onlyReacted = source.onlyReacted;
        copy.mergeAlbums = source.mergeAlbums;
        copy.minReactions = source.minReactions;
        copy.topN = source.topN;
        return copy;
    }

    /** Called by the settings screen after saving so the hooked process picks up changes promptly. */
    public static void markDirty() {
        lastRead = 0L;
    }
}

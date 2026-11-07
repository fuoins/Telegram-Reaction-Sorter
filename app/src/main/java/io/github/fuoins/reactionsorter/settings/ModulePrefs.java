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
    public static final String KEY_MENU_ENTRY = "menu_entry";
    public static final String KEY_SORT_MODE = "sort_mode";
    public static final String KEY_DESCENDING = "descending";
    public static final String KEY_ONLY_REACTED = "only_reacted";
    public static final String KEY_MERGE_ALBUMS = "merge_albums";
    public static final String KEY_MIN_REACTIONS = "min_reactions";
    public static final String KEY_TOP_N = "top_n";
    public static final String KEY_VERBOSE = "verbose";
    public static final String KEY_AUTO_FULL_SCAN = "auto_full_scan";
    public static final String KEY_SCAN_LIMIT = "scan_limit";
    public static final String KEY_ALLOW_HISTORY_LOAD = "allow_history_load";

    public static final int DEFAULT_SCAN_LIMIT = 20_000;
    public static final ReactionRanker.SortMode DEFAULT_SORT_MODE =
            ReactionRanker.SortMode.REACTIONS_DESC;

    private static final long CACHE_TTL_MS = 3_000L;
    private static volatile long lastRead;
    private static volatile ReactionRanker.Options cachedOptions;
    private static volatile ReactionRanker.SortMode cachedSortMode = DEFAULT_SORT_MODE;
    private static volatile boolean cachedEnabled = true;
    /** The floating button is only a compatibility fallback now; off by default. */
    private static volatile boolean cachedShowFab = false;
    private static volatile boolean cachedMenuEntry = true;
    private static volatile boolean cachedAutoFullScan = true;
    private static volatile boolean cachedAllowHistoryLoad = false;
    private static volatile int cachedScanLimit = DEFAULT_SCAN_LIMIT;

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
        cachedShowFab = prefs.getBoolean(KEY_SHOW_FAB, false);
        cachedMenuEntry = prefs.getBoolean(KEY_MENU_ENTRY, true);
        cachedAutoFullScan = prefs.getBoolean(KEY_AUTO_FULL_SCAN, true);
        cachedAllowHistoryLoad = prefs.getBoolean(KEY_ALLOW_HISTORY_LOAD, false);
        cachedSortMode = ReactionRanker.SortMode.parse(
                prefs.getString(KEY_SORT_MODE, DEFAULT_SORT_MODE.name()), DEFAULT_SORT_MODE);
        cachedScanLimit = parseNonNegative(prefs.getString(KEY_SCAN_LIMIT,
                String.valueOf(DEFAULT_SCAN_LIMIT)), DEFAULT_SCAN_LIMIT);
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

    /** Show the small floating button (compatibility fallback when menu injection fails). */
    public static boolean showFloatingEntry() {
        refreshIfStale();
        return cachedShowFab;
    }

    /** Inject the "回应排行" row into the chat's three-dot menu. */
    public static boolean menuEntryEnabled() {
        refreshIfStale();
        return cachedMenuEntry;
    }

    public static ReactionRanker.SortMode sortMode() {
        refreshIfStale();
        // Only the two reaction-based orders are offered now. A value saved by an older
        // version (date / chat order) must not leak into the new two-option UI.
        ReactionRanker.SortMode mode = cachedSortMode;
        if (mode != ReactionRanker.SortMode.REACTIONS_DESC
                && mode != ReactionRanker.SortMode.REACTIONS_ASC) {
            return DEFAULT_SORT_MODE;
        }
        return mode;
    }

    /** Scan the whole local message store as soon as the ranking panel opens. */
    public static boolean autoFullScan() {
        refreshIfStale();
        return cachedAutoFullScan;
    }

    /** Max number of message rows a single scan may examine. */
    public static int scanLimit() {
        refreshIfStale();
        return Math.max(100, cachedScanLimit);
    }

    /** Whether the "load older history" button is offered. Off by default: it uses network. */
    public static boolean allowHistoryLoad() {
        refreshIfStale();
        return cachedAllowHistoryLoad;
    }

    public static ReactionRanker.Options options() {
        refreshIfStale();
        ReactionRanker.Options source = cachedOptions;
        // Hand out a copy so the panel cannot mutate the cached values. sortMode stays null
        // here: the panel sets the explicit mode chosen in the bottom bar.
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

package io.github.fuoins.reactionsorter.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.fuoins.reactionsorter.core.ChatResolver;
import io.github.fuoins.reactionsorter.core.InfoText;
import io.github.fuoins.reactionsorter.core.MessageDatabase;
import io.github.fuoins.reactionsorter.core.MessageSnapshot;
import io.github.fuoins.reactionsorter.core.OnlineReactionScanner;
import io.github.fuoins.reactionsorter.core.ReactionCounter;
import io.github.fuoins.reactionsorter.core.ReactionRanker;
import io.github.fuoins.reactionsorter.core.ReactionRepository;
import io.github.fuoins.reactionsorter.settings.ModulePrefs;
import io.github.fuoins.reactionsorter.util.LogX;

/**
 * Search-style ranking UI that reuses Telegram's own visual language.
 *
 * <p>There is <b>no custom title bar</b>. The short status is written into the host's own
 * ActionBar subtitle (right under the channel name) by {@link ActionBarInfo}; colour,
 * strings and artwork come from {@link HostTheme}. The bottom bar reproduces the geometry
 * of the official {@code ChatActivity.searchContainer}: a 44dp rounded bubble with 7dp
 * side margins, a 15dp bold result counter, 44dp icon buttons and the official blue
 * "Show as List" / "Show as Chat" toggle (host resource {@code SearchAsList} /
 * {@code SearchAsChat}).
 *
 * <p>"上一个 / 下一个" call the host's own scroll-to-message method, exactly like chat
 * search. "以列表显示" expands a list whose rows follow the current sort order and mimic
 * the official search-result cells, with the reaction breakdown added.
 */
public final class RankingPanel {

    private static final String OVERLAY_TAG = "reaction_sorter_panel";
    private static final int MAX_UI_MESSAGES = 5000;
    /** Official searchContainerHeight, in dp. */
    private static final int BAR_HEIGHT_DP = 44;

    /** Only the two reaction-based orders are exposed, as requested. */
    private static final ReactionRanker.SortMode[] MODES = {
            ReactionRanker.SortMode.REACTIONS_DESC,
            ReactionRanker.SortMode.REACTIONS_ASC
    };
    private static final String[] MODE_LABELS = {
            "回应最多 → 最少", "回应最少 → 最多"
    };
    private static final String[] MODE_SHORT = {"回应 ↓", "回应 ↑"};

    /** In-memory only; never written to disk. */
    private static final ReactionRepository REPO = new ReactionRepository();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile ScanTask ACTIVE_SCAN;
    private static volatile Session CURRENT;

    private RankingPanel() {}

    private static final class Session {
        Activity activity;
        ClassLoader loader;
        ChatResolver.ChatHandle handle;
        HostTheme theme;
        boolean infoAttached;
        long dialogId;
        List<MessageSnapshot> uiSnapshots = new ArrayList<>();
        List<Integer> uiIds = new ArrayList<>();
        ReactionRanker.Options options;
        ReactionRanker.SortMode sortMode;
        boolean windowOnly;
        boolean onlineEnabled;
        int onlineConcurrency;
        boolean listVisible;
        boolean scanning;
        volatile boolean cancelled;
        int scanLimit;
        int currentIndex;
        /** Server-reported total message count, 0 when unknown. */
        int serverTotal;
        /** Messages obtained from the server so far. */
        int onlineFetched;
        /** A short problem hint, or "" — used by InfoText. */
        String problem = "";
        /** Longer detail shown only in the list header, never ellipsized. */
        String detail = "";

        FrameLayout overlay;
        TextView counterView;
        TextView sortView;
        TextView listToggle;
        TextView headerView;
        ListView list;
        RankAdapter adapter;
        List<MessageSnapshot> ranked = new ArrayList<>();
    }

    /** Entry point used by the overflow-menu item and (optionally) the floating fallback. */
    public static void open(Activity activity, ClassLoader loader) {
        try {
            close();
            ChatResolver.ChatHandle handle = ChatResolver.resolve(activity, loader);
            if (handle == null) {
                Toast.makeText(activity, "未检测到聊天窗口，请进入一个聊天后再试",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            final Session s = new Session();
            s.activity = activity;
            s.loader = loader;
            s.handle = handle;
            s.options = ModulePrefs.options();
            s.sortMode = ModulePrefs.sortMode();
            s.options.sortMode = s.sortMode;
            s.onlineEnabled = ModulePrefs.onlineScanEnabled();
            s.onlineConcurrency = ModulePrefs.onlineConcurrency();
            s.scanLimit = ModulePrefs.scanLimit();

            int count = 0;
            for (Object messageObject : handle.currentMessages()) {
                if (++count > MAX_UI_MESSAGES) break;
                MessageSnapshot snapshot = ReactionCounter.extract(messageObject);
                if (snapshot == null) continue;
                s.uiSnapshots.add(snapshot);
                if (snapshot.messageId != 0) s.uiIds.add(snapshot.messageId);
            }
            if (s.uiSnapshots.isEmpty()) {
                Toast.makeText(activity, "当前窗口没有读取到消息", Toast.LENGTH_SHORT).show();
                return;
            }
            s.dialogId = handle.dialogId();
            REPO.putUi(s.dialogId, s.uiSnapshots);

            View titleArea = ActionBarInfo.findTitleArea(activity);
            s.theme = HostTheme.from(activity, titleArea);
            s.infoAttached = ActionBarInfo.attach(activity, titleArea);

            LogX.i("panel opened: " + s.uiSnapshots.size() + " live messages, dialog="
                    + s.dialogId + ", jump=" + handle.canJump() + ", sort=" + s.sortMode
                    + ", subtitleInfo=" + s.infoAttached + ", night=" + s.theme.night);

            buildViews(s);
            CURRENT = s;
            refresh(s);
            startScan(s);
        } catch (Throwable t) {
            LogX.e("failed to open ranking panel", t);
            // The subtitle hook may already be installed; always release it on failure.
            close();
            Toast.makeText(activity, "排行失败，详见 Xposed 日志", Toast.LENGTH_SHORT).show();
        }
    }

    /** Remove the overlay, cancel any running scan and forget the session. */
    public static void close() {
        Session s = CURRENT;
        CURRENT = null;
        if (s != null) s.cancelled = true;
        cancelActiveScan();
        ActionBarInfo.detach();
        if (s == null) return;
        try {
            ViewGroup decor = (ViewGroup) s.activity.getWindow().getDecorView();
            View overlay = decor.findViewWithTag(OVERLAY_TAG);
            if (overlay != null) decor.removeView(overlay);
        } catch (Throwable t) {
            LogX.d("cannot remove panel overlay: " + t);
        }
    }

    private static void buildViews(final Session s) {
        Activity a = s.activity;
        HostTheme t = s.theme;

        final FrameLayout overlay = new FrameLayout(a) {
            @Override
            public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
                // Let touches fall through to the chat below our transparent middle area.
                return false;
            }
        };
        overlay.setTag(OVERLAY_TAG);
        overlay.setFitsSystemWindows(true);
        overlay.setFocusableInTouchMode(true);
        overlay.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                close();
                return true;
            }
            return false;
        });

        // ---- result list (official search-result cell style) ---------------------
        s.list = new ListView(a);
        s.list.setBackgroundColor(t.surface);
        s.list.setDivider(null);
        s.list.setDividerHeight(0);
        s.list.setFastScrollEnabled(true);
        s.list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        s.list.setVerticalScrollBarEnabled(true);

        // A non-clickable header carries the full status text. Unlike the old top bar it
        // wraps to several lines, so nothing is ever cut off with "...".
        FrameLayout headerWrap = new FrameLayout(a);
        headerWrap.setBackgroundColor(t.surface);
        s.headerView = new TextView(a);
        s.headerView.setTextSize(12f);
        s.headerView.setTextColor(t.secondary);
        s.headerView.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        s.headerView.setLineSpacing(dp(a, 2), 1f);
        headerWrap.addView(s.headerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View headerLine = new View(a);
        headerLine.setBackgroundColor(t.divider);
        headerWrap.addView(headerLine, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM));
        s.list.addHeaderView(headerWrap, null, false);

        s.adapter = new RankAdapter(s);
        s.list.setAdapter(s.adapter);
        s.list.setVisibility(View.GONE);
        FrameLayout.LayoutParams listLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        listLp.topMargin = t.actionBarBottom;
        listLp.bottomMargin = dp(a, BAR_HEIGHT_DP + 18);
        overlay.addView(s.list, listLp);
        s.list.setOnItemClickListener((parent, view, position, id) -> {
            // position 0 is the non-clickable header.
            int index = position - s.list.getHeaderViewsCount();
            if (index < 0 || index >= s.ranked.size()) return;
            s.currentIndex = index;
            s.listVisible = false;
            refresh(s);
            jump(s, s.ranked.get(index));
        });
        s.list.setOnItemLongClickListener((parent, view, position, id) -> {
            int index = position - s.list.getHeaderViewsCount();
            if (index < 0 || index >= s.ranked.size()) return true;
            copyId(s, s.ranked.get(index).messageId);
            return true;
        });

        // ---- bottom bar: official searchContainer geometry ----------------------
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(t.panelBackground());

        ImageView more = iconButton(a, t, null);
        more.setImageDrawable(t.moreIcon());
        if (more.getDrawable() == null) {
            // No host artwork: draw a compact three-dot glyph with a TextView instead.
            TextView dots = new TextView(a);
            dots.setText("⋮");
            dots.setTextSize(20f);
            dots.setTextColor(t.icon);
            dots.setGravity(Gravity.CENTER);
            dots.setMinWidth(dp(a, 32));
            dots.setBackground(t.selectorBackground());
            dots.setClickable(true);
            dots.setOnClickListener(v -> showMore(s));
            bar.addView(dots, new LinearLayout.LayoutParams(dp(a, 32),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            more.setOnClickListener(v -> showMore(s));
            bar.addView(more, new LinearLayout.LayoutParams(dp(a, 32),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        s.counterView = new TextView(a);
        s.counterView.setTextSize(15f);
        s.counterView.setTextColor(t.primary);
        s.counterView.setTypeface(s.counterView.getTypeface(), android.graphics.Typeface.BOLD);
        s.counterView.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        s.counterView.setSingleLine(true);
        s.counterView.setEllipsize(TextUtils.TruncateAt.END);
        s.counterView.setPadding(dp(a, 4), 0, dp(a, 2), 0);
        bar.addView(s.counterView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        s.sortView = new TextView(a);
        s.sortView.setTextSize(13f);
        s.sortView.setTextColor(t.blue);
        s.sortView.setTypeface(s.sortView.getTypeface(), android.graphics.Typeface.BOLD);
        s.sortView.setGravity(Gravity.CENTER);
        s.sortView.setSingleLine(true);
        s.sortView.setPadding(dp(a, 5), dp(a, 6), dp(a, 5), dp(a, 6));
        s.sortView.setBackground(t.selectorBackground());
        s.sortView.setClickable(true);
        s.sortView.setOnClickListener(v -> showSortDialog(s));
        bar.addView(s.sortView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView up = iconButton(a, t, t.arrow(true));
        up.setOnClickListener(v -> move(s, -1));
        bar.addView(up, new LinearLayout.LayoutParams(dp(a, 38),
                ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView down = iconButton(a, t, t.arrow(false));
        down.setOnClickListener(v -> move(s, 1));
        bar.addView(down, new LinearLayout.LayoutParams(dp(a, 38),
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Official "Show as List" / "Show as Chat" toggle.
        s.listToggle = new TextView(a);
        s.listToggle.setTextSize(15f);
        s.listToggle.setTextColor(t.blue);
        s.listToggle.setTypeface(s.listToggle.getTypeface(), android.graphics.Typeface.BOLD);
        s.listToggle.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        s.listToggle.setSingleLine(true);
        s.listToggle.setEllipsize(TextUtils.TruncateAt.END);
        s.listToggle.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        s.listToggle.setBackground(t.selectorBackground());
        s.listToggle.setClickable(true);
        s.listToggle.setOnClickListener(v -> toggleList(s));
        bar.addView(s.listToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout barWrap = new FrameLayout(a);
        barWrap.setBackground(t.panelBackground());
        barWrap.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, BAR_HEIGHT_DP)));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, BAR_HEIGHT_DP), Gravity.BOTTOM);
        barLp.leftMargin = dp(a, 7);
        barLp.rightMargin = dp(a, 7);
        barLp.bottomMargin = dp(a, 9);
        overlay.addView(barWrap, barLp);

        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        decor.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.requestFocus();
        s.overlay = overlay;
    }

    private static ImageView iconButton(Activity a, HostTheme t,
                                        android.graphics.drawable.Drawable icon) {
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setBackground(t.selectorBackground());
        iv.setClickable(true);
        if (icon != null) {
            iv.setImageDrawable(icon);
            icon.setColorFilter(t.icon, PorterDuff.Mode.SRC_IN);
        }
        return iv;
    }

    // ------------------------------------------------------------------ state ---

    private static void refresh(Session s) {
        List<MessageSnapshot> master = s.windowOnly
                ? new ArrayList<>(s.uiSnapshots)
                : REPO.merged(s.dialogId);
        s.ranked = ReactionRanker.rank(master, s.options);
        ReactionRanker.Summary sum = ReactionRanker.summarize(master);
        if (s.currentIndex >= s.ranked.size()) s.currentIndex = 0;
        if (s.currentIndex < 0) s.currentIndex = 0;

        updateStatusViews(s, sum);
        s.adapter.notifyDataSetChanged();
    }

    /** Updates the counter, official subtitle line and the list header. */
    private static void updateStatusViews(Session s, ReactionRanker.Summary sum) {
        HostTheme t = s.theme;
        int size = s.ranked.size();
        s.counterView.setText(size == 0
                ? t.noResult()
                : t.countText(s.currentIndex + 1, size));
        s.sortView.setText(MODE_SHORT[modeIndex(s.sortMode)]);
        s.listToggle.setText(s.listVisible ? t.showAsChat() : t.showAsList());
        s.list.setVisibility(s.listVisible ? View.VISIBLE : View.GONE);

        if (s.infoAttached) {
            ActionBarInfo.update(InfoText.of(size, sum.maxReactions, s.scanning,
                    s.windowOnly, s.problem));
        }

        StringBuilder header = new StringBuilder();
        header.append("共 ").append(sum.totalMessages).append(" 条消息 · ")
                .append(sum.reactedMessages).append(" 条有回应 · 合计 ")
                .append(sum.totalReactions).append(" 个回应 · 最高 ")
                .append(sum.maxReactions).append("\n");
        header.append("排序：").append(MODE_LABELS[modeIndex(s.sortMode)])
                .append("  ｜ 范围：").append(s.windowOnly ? "当前窗口" : "服务器全量 + 本地缓存")
                .append("\n");
        if (s.onlineEnabled && !s.windowOnly) {
            header.append("服务器：");
            if (s.serverTotal > 0) header.append("共 ").append(s.serverTotal).append(" 条 · ");
            header.append("已取 ").append(REPO.onlineCount(s.dialogId)).append(" 条");
            if (s.scanning && s.serverTotal > 0) {
                header.append("（").append(s.onlineFetched).append(" 读取中）");
            }
            header.append("\n");
        }
        header.append("本地库 ").append(REPO.databaseCount(s.dialogId))
                .append(" 条 / 窗口 ").append(REPO.uiCount(s.dialogId)).append(" 条");
        if (!s.detail.isEmpty()) header.append("\n").append(s.detail);
        s.headerView.setText(header.toString());
    }

    /** Previous / next: same semantics as Telegram's in-chat search navigation. */
    private static void move(Session s, int delta) {
        if (s.ranked.isEmpty()) {
            Toast.makeText(s.activity, "没有符合当前过滤条件的消息", Toast.LENGTH_SHORT).show();
            return;
        }
        int size = s.ranked.size();
        s.currentIndex = (s.currentIndex + delta + size) % size;
        MessageSnapshot target = s.ranked.get(s.currentIndex);
        s.counterView.setText(s.theme.countText(s.currentIndex + 1, size));
        s.adapter.notifyDataSetChanged();
        if (s.listVisible) s.list.smoothScrollToPosition(s.currentIndex + s.list.getHeaderViewsCount());
        jump(s, target);
    }

    private static void jump(Session s, MessageSnapshot target) {
        boolean jumped = s.handle.jumpTo(target.messageId);
        if (!jumped) {
            Toast.makeText(s.activity, "当前客户端未找到跳转接口，消息 #" + target.messageId,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Official "Show as List": expands the results in the current sort order. */
    private static void toggleList(Session s) {
        s.listVisible = !s.listVisible;
        refresh(s);
        if (s.listVisible && !s.ranked.isEmpty()) {
            s.list.setSelection(s.currentIndex + s.list.getHeaderViewsCount());
        }
    }

    private static void showSortDialog(final Session s) {
        final int keepId = (!s.ranked.isEmpty() && s.currentIndex < s.ranked.size())
                ? s.ranked.get(s.currentIndex).messageId : 0;
        new AlertDialog.Builder(s.activity)
                .setTitle("排序方式")
                .setSingleChoiceItems(MODE_LABELS, modeIndex(s.sortMode), (dialog, which) -> {
                    s.sortMode = MODES[which];
                    s.options.sortMode = s.sortMode;
                    dialog.dismiss();
                    int index = 0;
                    List<MessageSnapshot> master = s.windowOnly
                            ? new ArrayList<>(s.uiSnapshots) : REPO.merged(s.dialogId);
                    List<MessageSnapshot> next = ReactionRanker.rank(master, s.options);
                    for (int i = 0; i < next.size(); i++) {
                        if (next.get(i).messageId == keepId) {
                            index = i;
                            break;
                        }
                    }
                    s.currentIndex = index;
                    refresh(s);
                    Toast.makeText(s.activity, "已按「" + MODE_LABELS[which] + "」排序",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showMore(final Session s) {
        List<String> items = new ArrayList<>();
        items.add("重新扫描（服务器全量 + 本地库）");
        items.add(s.scanning ? "停止当前扫描" : "仅重新扫描本地消息库");
        items.add(s.windowOnly ? "切换到全量" : "仅当前窗口");
        items.add("关闭排行");
        new AlertDialog.Builder(s.activity)
                .setTitle("回应排行")
                .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
                    String picked = items.get(which);
                    if (picked.startsWith("重新扫描（")) {
                        REPO.clear(s.dialogId);
                        REPO.putUi(s.dialogId, s.uiSnapshots);
                        s.serverTotal = 0;
                        s.onlineFetched = 0;
                        startScan(s);
                    } else if (picked.startsWith("停止当前扫描")) {
                        cancelActiveScan();
                        s.scanning = false;
                        s.problem = "扫描已停止";
                        refresh(s);
                    } else if (picked.startsWith("仅重新扫描")) {
                        startScan(s);
                    } else if (picked.startsWith("切换到全量") || picked.startsWith("仅当前窗口")) {
                        s.windowOnly = !s.windowOnly;
                        s.currentIndex = 0;
                        s.problem = s.windowOnly ? "仅当前窗口" : "";
                        refresh(s);
                    } else {
                        close();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void copyId(Session s, int messageId) {
        String value = String.valueOf(messageId);
        ClipboardManager cm = (ClipboardManager) s.activity
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("message_id", value));
            Toast.makeText(s.activity, "已复制消息 ID：" + value, Toast.LENGTH_SHORT).show();
        }
    }

    private static int modeIndex(ReactionRanker.SortMode mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i] == mode) return i;
        return 0;
    }

    // ------------------------------------------------------------------ scan ---

    /**
     * Two-stage scan:
     * <ol>
     *   <li>Local database — instant, offline, covers everything the client has cached.
     *       Gives the panel a full ranking immediately while the network scan runs.</li>
     *   <li>Server history via {@code messages.getHistory} — the actual complete chat.
     *       Results stream in wave by wave and refresh the ranking progressively.</li>
     * </ol>
     */
    private static void startScan(final Session s) {
        cancelActiveScan();
        final ScanTask task = new ScanTask(s);
        ACTIVE_SCAN = task;
        s.scanning = true;
        s.problem = "";
        s.detail = s.onlineEnabled
                ? "正在读取服务器上的全部消息回应（只取元数据，不下载图片视频）…"
                : "正在扫描本地消息库…";
        updateStatusViews(s, ReactionRanker.summarize(
                s.windowOnly ? s.uiSnapshots : REPO.merged(s.dialogId)));
        new Thread(task, "ReactionSorter-scan").start();
    }

    private static void cancelActiveScan() {
        ScanTask task = ACTIVE_SCAN;
        if (task != null) task.cancelled = true;
        ACTIVE_SCAN = null;
    }

    private static final class ScanTask implements Runnable {
        final Session s;
        volatile boolean cancelled;

        ScanTask(Session s) {
            this.s = s;
        }

        private boolean stopped() {
            return cancelled || s.cancelled || CURRENT != s;
        }

        @Override
        public void run() {
            // ---- stage 1: local cache (fast, offline, instant full ranking) ----------
            if (ModulePrefs.autoFullScan()) {
                MessageDatabase.Result db = null;
                try {
                    db = MessageDatabase.scan(
                            s.activity.getApplicationContext(), s.loader, s.dialogId, s.uiIds,
                            s.scanLimit, (rows, reacted) -> {
                                if (stopped()) return false;
                                final String text = "正在扫描本地消息库… 已读取 " + rows
                                        + " 行，其中 " + reacted + " 条消息有回应";
                                MAIN.post(() -> {
                                    if (!stopped()) {
                                        s.detail = text;
                                        updateStatusViews(s, ReactionRanker.summarize(
                                                s.windowOnly ? s.uiSnapshots : REPO.merged(s.dialogId)));
                                    }
                                });
                                return true;
                            });
                } catch (Throwable t) {
                    LogX.e("local database scan crashed", t);
                }
                if (db != null && db.success()) {
                    long id = db.dialogId != 0 ? db.dialogId : s.dialogId;
                    if (id != 0 && id != s.dialogId) {
                        REPO.putUi(id, s.uiSnapshots);
                        s.dialogId = id;
                    }
                    REPO.mergeDatabase(id, db.snapshots, db.rowsRead);
                }
            }

            // ---- stage 2: server-side full history ----------------------------------
            if (!s.onlineEnabled) {
                MAIN.post(() -> {
                    if (stopped()) return;
                    s.scanning = false;
                    s.problem = "仅本地缓存";
                    s.detail = "在线全量扫描已在设置中关闭；当前只统计客户端本地已缓存的消息。"
                            + "如需服务器上该聊天的全部消息，请在模块设置里开启在线全量扫描。";
                    refresh(s);
                });
                return;
            }
            if (s.dialogId == 0) {
                MAIN.post(() -> {
                    if (stopped()) return;
                    s.scanning = false;
                    s.problem = "未识别聊天";
                    s.detail = "未能识别当前聊天 id，无法向服务器请求历史消息；已保留本地库结果。";
                    refresh(s);
                });
                return;
            }

            OnlineReactionScanner.Result online;
            try {
                online = OnlineReactionScanner.scan(
                        s.loader, s.dialogId, s.scanLimit, s.onlineConcurrency,
                        () -> stopped(),
                        (incremental, fetched, total, reacted, hint) -> {
                            if (stopped()) return false;
                            REPO.mergeOnline(s.dialogId, incremental, total);
                            MAIN.post(() -> {
                                if (stopped()) return;
                                s.serverTotal = Math.max(s.serverTotal, total);
                                s.onlineFetched = Math.max(s.onlineFetched, fetched);
                                s.detail = hint;
                                refresh(s);
                            });
                            return true;
                        });
            } catch (Throwable t) {
                LogX.e("online reaction scan crashed", t);
                online = null;
            }

            final OnlineReactionScanner.Result r = online;
            MAIN.post(() -> {
                if (stopped()) return;
                s.scanning = false;
                if (r == null) {
                    s.problem = "在线扫描失败";
                    s.detail = "在线扫描异常，已保留本地库结果；详见 Xposed 日志。";
                } else {
                    REPO.mergeOnline(s.dialogId, r.snapshots, r.serverTotal);
                    if (r.serverTotal > 0) s.serverTotal = r.serverTotal;
                    s.onlineFetched = Math.max(s.onlineFetched, r.fetched);
                    s.detail = r.detail;
                    if (!r.success()) {
                        s.problem = "在线不可用";
                    } else if (r.cancelled) {
                        s.problem = "扫描已停止";
                    } else if (r.floodWait) {
                        s.problem = "服务器限流";
                    } else if (r.partial) {
                        s.problem = "结果不完整";
                    }
                }
                refresh(s);
            });
        }
    }

    // ------------------------------------------------------- official-style cell ---

    private static final class RankAdapter extends BaseAdapter {
        private final Session s;

        RankAdapter(Session s) {
            this.s = s;
        }

        @Override
        public int getCount() {
            return s.ranked.size();
        }

        @Override
        public Object getItem(int position) {
            return s.ranked.get(position);
        }

        @Override
        public long getItemId(int position) {
            return s.ranked.get(position).messageId;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            FrameLayout cell;
            HostTheme t = s.theme;
            if (convertView instanceof FrameLayout && convertView.getTag() instanceof Holder) {
                cell = (FrameLayout) convertView;
                h = (Holder) cell.getTag();
            } else {
                Context c = s.activity;
                cell = new FrameLayout(c);
                LinearLayout body = new LinearLayout(c);
                body.setOrientation(LinearLayout.HORIZONTAL);
                body.setGravity(Gravity.CENTER_VERTICAL);
                body.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));

                h = new Holder();
                h.avatar = new TextView(c);
                h.avatar.setGravity(Gravity.CENTER);
                h.avatar.setTextColor(Color.WHITE);
                h.avatar.setTextSize(15f);
                h.avatar.setTypeface(h.avatar.getTypeface(), android.graphics.Typeface.BOLD);
                body.addView(h.avatar, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));

                LinearLayout textBlock = new LinearLayout(c);
                textBlock.setOrientation(LinearLayout.VERTICAL);
                textBlock.setPadding(dp(c, 12), 0, 0, 0);

                LinearLayout first = new LinearLayout(c);
                first.setOrientation(LinearLayout.HORIZONTAL);
                first.setGravity(Gravity.CENTER_VERTICAL);
                h.title = new TextView(c);
                h.title.setTextSize(15.5f);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                first.addView(h.title, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                h.time = new TextView(c);
                h.time.setTextSize(11.5f);
                h.time.setPadding(dp(c, 6), 0, 0, 0);
                h.time.setSingleLine(true);
                first.addView(h.time);
                textBlock.addView(first, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                LinearLayout second = new LinearLayout(c);
                second.setOrientation(LinearLayout.HORIZONTAL);
                second.setGravity(Gravity.CENTER_VERTICAL);
                second.setPadding(0, dp(c, 3), 0, 0);
                h.detail = new TextView(c);
                h.detail.setTextSize(12.5f);
                h.detail.setSingleLine(true);
                h.detail.setEllipsize(TextUtils.TruncateAt.END);
                second.addView(h.detail, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                h.badge = new TextView(c);
                h.badge.setTextColor(Color.WHITE);
                h.badge.setTextSize(11f);
                h.badge.setGravity(Gravity.CENTER);
                h.badge.setMinWidth(dp(c, 24));
                h.badge.setPadding(dp(c, 6), dp(c, 1), dp(c, 6), dp(c, 1));
                second.addView(h.badge);
                textBlock.addView(second, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                body.addView(textBlock, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                cell.addView(body, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 64)));

                View divider = new View(c);
                FrameLayout.LayoutParams dLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM);
                dLp.leftMargin = dp(c, 68);
                cell.addView(divider, dLp);
                cell.setTag(h);
            }

            MessageSnapshot m = s.ranked.get(position);
            boolean selected = position == s.currentIndex;
            cell.setBackgroundColor(selected ? t.highlight : Color.TRANSPARENT);

            h.avatar.setText(String.valueOf(position + 1));
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(selected ? t.blue : rankColor(position, t.blue));
            h.avatar.setBackground(circle);

            h.title.setTextColor(t.primary);
            h.time.setTextColor(t.secondary);
            h.detail.setTextColor(t.secondary);
            h.title.setText(m.preview(48));
            h.time.setText(formatTime(m.date));
            h.detail.setText(m.breakdownText() + "   ·   #" + m.messageId);
            h.badge.setText(String.valueOf(m.totalReactions));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.RECTANGLE);
            badgeBg.setCornerRadius(dp(s.activity, 12));
            badgeBg.setColor(t.blue);
            h.badge.setBackground(badgeBg);

            View divider = cell.getChildAt(cell.getChildCount() - 1);
            divider.setBackgroundColor(t.divider);
            return cell;
        }
    }

    private static final class Holder {
        TextView avatar;
        TextView title;
        TextView time;
        TextView detail;
        TextView badge;
    }

    private static int rankColor(int position, int accent) {
        if (position == 0) return 0xFFFFB300;
        if (position == 1) return 0xFF9AA4AC;
        if (position == 2) return 0xFFC8804A;
        return accent & 0x66FFFFFF;
    }

    private static String formatTime(int dateSeconds) {
        if (dateSeconds <= 0) return "";
        long millis = dateSeconds * 1000L;
        boolean today = System.currentTimeMillis() - millis < 86_400_000L
                && System.currentTimeMillis() > millis;
        String pattern = today ? "HH:mm" : "MM-dd";
        try {
            return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(millis));
        } catch (Throwable t) {
            return "";
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}

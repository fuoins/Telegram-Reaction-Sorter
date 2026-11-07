package io.github.fuoins.reactionsorter.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.fuoins.reactionsorter.core.ChatResolver;
import io.github.fuoins.reactionsorter.core.HistoryLoader;
import io.github.fuoins.reactionsorter.core.MessageDatabase;
import io.github.fuoins.reactionsorter.core.MessageSnapshot;
import io.github.fuoins.reactionsorter.core.ReactionCounter;
import io.github.fuoins.reactionsorter.core.ReactionRanker;
import io.github.fuoins.reactionsorter.core.ReactionRepository;
import io.github.fuoins.reactionsorter.settings.ModulePrefs;
import io.github.fuoins.reactionsorter.util.LogX;

/**
 * Search-style ranking UI, modelled on Telegram's own in-chat search.
 *
 * <p>Opening the "回应排行" item in the three-dot menu attaches a transparent overlay to
 * the Activity's decor view. Two thin bars (a title bar at the top, a 48dp navigation bar
 * at the bottom, like the official {@code searchContainer}) stay visible while the chat
 * itself remains visible and touchable. "上一个 / 下一个" move through the ranked messages
 * and call the host's own scroll-to-message method, exactly like search results do.
 *
 * <p>A "列表" toggle expands the result area into a list whose rows reproduce the look of
 * Telegram's search-result cells (round avatar slot, title, preview, time, unread-style
 * badge, inset divider) and add the reaction breakdown. All views are built in code: no
 * host resource ids, no androidx dependency.
 */
public final class RankingPanel {

    private static final String OVERLAY_TAG = "reaction_sorter_panel";
    private static final int MAX_UI_MESSAGES = 5000;
    private static final int HISTORY_BATCHES = 3;
    private static final long HISTORY_WAIT_MS = 1500L;

    private static final ReactionRanker.SortMode[] MODES = {
            ReactionRanker.SortMode.REACTIONS_DESC,
            ReactionRanker.SortMode.REACTIONS_ASC,
            ReactionRanker.SortMode.DATE_DESC,
            ReactionRanker.SortMode.DATE_ASC,
            ReactionRanker.SortMode.ID_DESC,
            ReactionRanker.SortMode.ID_ASC
    };
    private static final String[] MODE_LABELS = {
            "回应最多 → 最少", "回应最少 → 最多", "最新消息优先",
            "最早消息优先", "聊天顺序（新 → 旧）", "聊天顺序（旧 → 新）"
    };
    private static final String[] MODE_SHORT = {
            "回应 ↓", "回应 ↑", "最新", "最早", "顺序 ↓", "顺序 ↑"
    };

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
        long dialogId;
        List<MessageSnapshot> uiSnapshots = new ArrayList<>();
        List<Integer> uiIds = new ArrayList<>();
        ReactionRanker.Options options;
        ReactionRanker.SortMode sortMode;
        boolean windowOnly;
        boolean allowHistoryLoad;
        boolean listVisible;
        volatile boolean cancelled;
        int scanLimit;
        int currentIndex;
        String status = "";
        String scanDetail = "";

        FrameLayout overlay;
        TextView statusView;
        TextView counterView;
        TextView sortView;
        TextView listToggle;
        ListView list;
        RankAdapter adapter;
        List<MessageSnapshot> ranked = new ArrayList<>();

        int surface, barColor, primary, secondary, divider, accent, highlight;
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
            s.allowHistoryLoad = ModulePrefs.allowHistoryLoad();
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
            LogX.i("panel opened: " + s.uiSnapshots.size() + " live messages, dialog="
                    + s.dialogId + ", jump=" + handle.canJump() + ", sort=" + s.sortMode);

            buildViews(s);
            CURRENT = s;
            refresh(s);
            if (ModulePrefs.autoFullScan()) {
                startScan(s);
            } else {
                s.status = "仅统计当前窗口（全量扫描已在设置中关闭）";
                refresh(s);
            }
        } catch (Throwable t) {
            LogX.e("failed to open ranking panel", t);
            Toast.makeText(activity, "排行失败，详见 Xposed 日志", Toast.LENGTH_SHORT).show();
        }
    }

    /** Remove the overlay, cancel any running scan and forget the session. */
    public static void close() {
        Session s = CURRENT;
        CURRENT = null;
        if (s == null) return;
        s.cancelled = true;
        cancelActiveScan();
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
        boolean night = (a.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        s.surface = night ? 0xFF1B2128 : 0xFFFFFFFF;
        s.barColor = night ? 0xFF232B33 : 0xFFF4F6F8;
        s.primary = night ? 0xFFF2F4F6 : 0xFF1F2329;
        s.secondary = night ? 0xFF98A2AC : 0xFF7C858F;
        s.divider = night ? 0x22FFFFFF : 0x14000000;
        s.accent = night ? 0xFF6AB7F5 : 0xFF2AABEE;
        s.highlight = night ? 0x2A6AB7F5 : 0x1E2AABEE;

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

        // ---- top bar -------------------------------------------------------------
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(s.barColor);

        LinearLayout titleRow = new LinearLayout(a);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(a, 6), dp(a, 8), dp(a, 6), dp(a, 4));

        titleRow.addView(barButton(a, "✕", s.accent, v -> close()));

        TextView title = new TextView(a);
        title.setText("回应排行");
        title.setTextSize(17f);
        title.setTextColor(s.primary);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(a, 8), 0, dp(a, 4), 0);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        s.listToggle = barButton(a, "列表", s.accent, v -> toggleList(s));
        titleRow.addView(s.listToggle);
        titleRow.addView(barButton(a, "⋮", s.accent, v -> showMore(s)));
        top.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 48)));

        s.statusView = new TextView(a);
        s.statusView.setTextSize(11f);
        s.statusView.setTextColor(s.secondary);
        s.statusView.setSingleLine(true);
        s.statusView.setEllipsize(TextUtils.TruncateAt.END);
        s.statusView.setPadding(dp(a, 14), 0, dp(a, 14), dp(a, 6));
        top.addView(s.statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlay.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // ---- result list (official search-result cell style) ---------------------
        s.list = new ListView(a);
        s.list.setBackgroundColor(s.surface);
        s.list.setDivider(null);
        s.list.setDividerHeight(0);
        s.list.setFastScrollEnabled(true);
        s.list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        s.adapter = new RankAdapter(s);
        s.list.setAdapter(s.adapter);
        s.list.setVisibility(View.GONE);
        FrameLayout.LayoutParams listLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        listLp.setMargins(0, dp(a, 72), 0, dp(a, 50));
        overlay.addView(s.list, listLp);
        s.list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= s.ranked.size()) return;
            s.currentIndex = position;
            s.listVisible = false;
            refresh(s);
            jump(s, s.ranked.get(position));
        });
        s.list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= s.ranked.size()) return true;
            copyId(s, s.ranked.get(position).messageId);
            return true;
        });

        // ---- bottom navigation bar (mirrors Telegram's searchContainer) ---------
        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(s.barColor);
        bottom.setPadding(dp(a, 4), 0, dp(a, 4), 0);

        s.sortView = barButton(a, "⇅ " + MODE_SHORT[modeIndex(s.sortMode)], s.accent,
                v -> showSortDialog(s));
        bottom.addView(s.sortView);

        s.counterView = new TextView(a);
        s.counterView.setTextSize(15f);
        s.counterView.setTextColor(s.primary);
        s.counterView.setTypeface(s.counterView.getTypeface(), android.graphics.Typeface.BOLD);
        s.counterView.setGravity(Gravity.CENTER);
        s.counterView.setSingleLine(true);
        bottom.addView(s.counterView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        bottom.addView(barButton(a, "▲", s.accent, v -> move(s, -1)));
        bottom.addView(barButton(a, "▼", s.accent, v -> move(s, 1)));

        FrameLayout bottomWrap = new FrameLayout(a);
        bottomWrap.setBackgroundColor(s.barColor);
        bottomWrap.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 48)));
        View topLine = new View(a);
        topLine.setBackgroundColor(s.divider);
        bottomWrap.addView(topLine, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.TOP));
        overlay.addView(bottomWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 48), Gravity.BOTTOM));

        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        decor.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.requestFocus();
        s.overlay = overlay;
    }

    private static TextView barButton(Activity a, String text, int color,
                                      View.OnClickListener listener) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setMinWidth(dp(a, 44));
        tv.setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(0x332AABEE));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        tv.setBackground(states);
        tv.setClickable(true);
        tv.setOnClickListener(listener);
        return tv;
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

        s.counterView.setText(s.ranked.isEmpty()
                ? "0 / 0" : (s.currentIndex + 1) + " / " + s.ranked.size());
        s.sortView.setText("⇅ " + MODE_SHORT[modeIndex(s.sortMode)]);
        s.listToggle.setText(s.listVisible ? "聊天" : "列表");
        s.list.setVisibility(s.listVisible ? View.VISIBLE : View.GONE);

        StringBuilder status = new StringBuilder();
        status.append("共 ").append(sum.totalMessages).append(" 条 · ")
                .append(sum.reactedMessages).append(" 条有回应 · 合计 ")
                .append(sum.totalReactions).append(" 个 · 最高 ").append(sum.maxReactions)
                .append("  ｜ ").append(s.windowOnly ? "当前窗口" : "全部本地消息")
                .append(" · 本地库 ").append(REPO.databaseCount(s.dialogId)).append(" 条 / 窗口 ")
                .append(REPO.uiCount(s.dialogId)).append(" 条");
        if (!s.status.isEmpty()) status.append("  ｜ ").append(s.status);
        if (!s.scanDetail.isEmpty()) status.append("  ｜ ").append(s.scanDetail);
        s.statusView.setText(status.toString());
        s.adapter.notifyDataSetChanged();
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
        s.counterView.setText((s.currentIndex + 1) + " / " + size);
        s.adapter.notifyDataSetChanged();
        if (s.listVisible) s.list.smoothScrollToPosition(s.currentIndex);
        jump(s, target);
    }

    private static void jump(Session s, MessageSnapshot target) {
        boolean jumped = s.handle.jumpTo(target.messageId);
        if (!jumped) {
            Toast.makeText(s.activity, "当前客户端未找到跳转接口，消息 #" + target.messageId,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static void toggleList(Session s) {
        s.listVisible = !s.listVisible;
        refresh(s);
        if (s.listVisible && !s.ranked.isEmpty()) {
            s.list.setSelection(s.currentIndex);
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
                    // Re-rank first, then restore the selection by message id.
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
        items.add("重新扫描本地消息库");
        items.add(s.windowOnly ? "切换到全量（本地库）" : "仅当前窗口");
        if (s.allowHistoryLoad) items.add("加载更早历史");
        items.add("关闭排行");
        new AlertDialog.Builder(s.activity)
                .setTitle("更多")
                .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
                    String picked = items.get(which);
                    if (picked.startsWith("重新扫描")) {
                        startScan(s);
                    } else if (picked.startsWith("切换到全量") || picked.startsWith("仅当前窗口")) {
                        s.windowOnly = !s.windowOnly;
                        s.currentIndex = 0;
                        refresh(s);
                    } else if (picked.startsWith("加载更早")) {
                        loadOlder(s);
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

    private static void startScan(final Session s) {
        cancelActiveScan();
        final ScanTask task = new ScanTask(s);
        ACTIVE_SCAN = task;
        s.status = "正在扫描本地消息库…";
        refresh(s);
        new Thread(task, "ReactionSorter-dbscan").start();
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

        @Override
        public void run() {
            MessageDatabase.Result result = null;
            try {
                result = MessageDatabase.scan(
                        s.activity.getApplicationContext(), s.loader, s.dialogId, s.uiIds,
                        s.scanLimit, (rows, reacted) -> {
                            if (cancelled || s.cancelled) return false;
                            final String text = "正在扫描本地消息库… 已读取 " + rows
                                    + " 行，其中 " + reacted + " 条消息有回应";
                            MAIN.post(() -> {
                                if (!s.cancelled) {
                                    s.status = text;
                                    refresh(s);
                                }
                            });
                            return true;
                        });
            } catch (Throwable t) {
                LogX.e("full database scan crashed", t);
            }
            final MessageDatabase.Result r = result;
            MAIN.post(() -> {
                if (cancelled || s.cancelled || CURRENT != s) return;
                if (r == null) {
                    s.status = "扫描失败，详见 Xposed 日志；已回退到当前窗口";
                } else if (r.success()) {
                    long id = r.dialogId != 0 ? r.dialogId : s.dialogId;
                    if (id != s.dialogId) {
                        REPO.putUi(id, s.uiSnapshots);
                        s.dialogId = id;
                    }
                    REPO.mergeDatabase(id, r.snapshots, r.rowsRead);
                    s.scanDetail = r.detail;
                    s.status = r.cancelled
                            ? "扫描已停止（可点「重新扫描」继续）"
                            : "全量扫描完成：本地库共 " + REPO.databaseCount(id) + " 条消息";
                } else {
                    s.status = "本地消息库不可用：" + r.error + "；已回退到当前窗口";
                }
                refresh(s);
            });
        }
    }

    /** Ask the host to fetch older history, then merge the newly persisted rows. */
    private static void loadOlder(final Session s) {
        if (!s.allowHistoryLoad) {
            Toast.makeText(s.activity, "请先在模块设置里开启「允许联网补全更早历史」",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (s.dialogId == 0) {
            Toast.makeText(s.activity, "未识别到当前聊天，无法请求更早历史",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        s.status = "正在请求更早的历史消息（会消耗少量流量）…";
        refresh(s);

        new Thread(() -> {
            int addedTotal = 0;
            String message = "没有更多历史消息（或客户端未返回新数据）";
            try {
                for (int batch = 0; batch < HISTORY_BATCHES && !s.cancelled; batch++) {
                    final int oldest = oldestId(s);
                    if (oldest <= 0) break;
                    final long dialogId = s.dialogId;
                    final int index = batch;
                    final boolean[] sent = new boolean[1];
                    final CountDownLatch latch = new CountDownLatch(1);
                    MAIN.post(() -> {
                        sent[0] = HistoryLoader.request(s.loader, dialogId, oldest, index);
                        latch.countDown();
                    });
                    latch.await(2, TimeUnit.SECONDS);
                    if (!sent[0]) {
                        message = "当前客户端不支持自动加载更早历史，请在聊天里手动上翻后重开排行";
                        break;
                    }
                    Thread.sleep(HISTORY_WAIT_MS);
                    MessageDatabase.Result r = MessageDatabase.scan(
                            s.activity.getApplicationContext(), s.loader, s.dialogId,
                            s.uiIds, s.scanLimit, null);
                    if (!r.success()) break;
                    int before = REPO.merged(s.dialogId).size();
                    REPO.mergeDatabase(s.dialogId, r.snapshots, r.rowsRead);
                    int added = REPO.merged(s.dialogId).size() - before;
                    addedTotal += added;
                    if (added == 0) break;
                    final String progress = "已加载更早历史，新增 " + addedTotal + " 条消息…";
                    MAIN.post(() -> {
                        if (!s.cancelled) {
                            s.status = progress;
                            refresh(s);
                        }
                    });
                }
                if (addedTotal > 0) {
                    message = "本次新增 " + addedTotal + " 条历史消息，已并入排行";
                }
            } catch (Throwable t) {
                LogX.w("history load failed", t);
                message = "加载失败，详见 Xposed 日志";
            }
            final String finalMessage = message;
            MAIN.post(() -> {
                if (s.cancelled || CURRENT != s) return;
                s.status = finalMessage;
                refresh(s);
            });
        }, "ReactionSorter-history").start();
    }

    private static int oldestId(Session s) {
        int min = Integer.MAX_VALUE;
        for (MessageSnapshot m : REPO.merged(s.dialogId)) {
            if (m.messageId > 0 && m.messageId < min) min = m.messageId;
        }
        return min == Integer.MAX_VALUE ? 0 : min;
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
                h.title.setTextColor(s.primary);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                first.addView(h.title, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                h.time = new TextView(c);
                h.time.setTextSize(11.5f);
                h.time.setTextColor(s.secondary);
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
                h.detail.setTextColor(s.secondary);
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
                divider.setBackgroundColor(s.divider);
                FrameLayout.LayoutParams dLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM);
                dLp.leftMargin = dp(c, 68);
                cell.addView(divider, dLp);
                cell.setTag(h);
            }

            MessageSnapshot m = s.ranked.get(position);
            boolean selected = position == s.currentIndex;
            cell.setBackgroundColor(selected ? s.highlight : Color.TRANSPARENT);

            h.avatar.setText(String.valueOf(position + 1));
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(selected ? s.accent : rankColor(position, s.accent));
            h.avatar.setBackground(circle);

            h.title.setText(m.preview(48));
            h.time.setText(formatTime(m.date));
            h.detail.setText(m.breakdownText() + "   ·   #" + m.messageId);
            h.badge.setText(String.valueOf(m.totalReactions));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.RECTANGLE);
            badgeBg.setCornerRadius(dp(s.activity, 12));
            badgeBg.setColor(s.accent);
            h.badge.setBackground(badgeBg);
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
        return 0x6633A9DC;
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

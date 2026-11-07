package io.github.fuoins.reactionsorter.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
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
 * Ranked list of messages, built entirely in code (no host resource ids are used).
 *
 * <p>On open the small live UI window is shown immediately, then a background scan of the
 * client's local message store fills in every cached message of the chat. The user can
 * switch back to "current window only", rescan, or (when explicitly enabled) ask the host
 * to page in even older history.
 */
public final class RankingDialog {

    private static final int MAX_UI_MESSAGES = 5000;
    private static final int HISTORY_BATCHES = 3;
    private static final long HISTORY_WAIT_MS = 1500L;

    /** In-memory only; never written to disk. */
    private static final ReactionRepository REPO = new ReactionRepository();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile ScanTask ACTIVE_SCAN;

    private RankingDialog() {}

    private static final class Session {
        Activity activity;
        ClassLoader loader;
        ChatResolver.ChatHandle handle;
        long dialogId;
        List<MessageSnapshot> uiSnapshots = new ArrayList<>();
        List<Integer> uiIds = new ArrayList<>();
        ReactionRanker.Options options;
        boolean windowOnly;
        boolean allowHistoryLoad;
        int scanLimit;
        String status = "";
        String scanDetail = "";
        volatile boolean cancelled;

        AlertDialog dialog;
        TextView header;
        TextView statusView;
        TextView sourceView;
        TextView emptyView;
        ListView list;
        Button scopeButton;
        Button olderButton;
        int primary;
        int secondary;
        List<MessageSnapshot> ranked = new ArrayList<>();
        RankAdapter adapter;
    }

    /** Entry point: resolve the chat, snapshot the live window, then load everything else. */
    public static void open(Activity activity, ClassLoader loader) {
        try {
            ChatResolver.ChatHandle handle = ChatResolver.resolve(activity, loader);
            if (handle == null) {
                Toast.makeText(activity, "未检测到聊天窗口，请进入一个聊天后再试", Toast.LENGTH_SHORT).show();
                return;
            }
            final Session s = new Session();
            s.activity = activity;
            s.loader = loader;
            s.handle = handle;
            s.options = ModulePrefs.options();
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
            LogX.i("resolved " + s.uiSnapshots.size() + " live messages from "
                    + handle.fragmentClassName() + ", dialog=" + s.dialogId
                    + ", jump=" + handle.canJump());

            show(s);
            if (ModulePrefs.autoFullScan()) {
                startScan(s);
            } else {
                s.status = "仅统计当前窗口（全量扫描已在设置中关闭）";
                refresh(s);
            }
        } catch (Throwable t) {
            LogX.e("failed to open ranking", t);
            Toast.makeText(activity, "排行失败，详见 Xposed 日志", Toast.LENGTH_SHORT).show();
        }
    }

    private static void show(final Session s) {
        boolean night = (s.activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        s.primary = night ? 0xFFF2F2F2 : 0xFF1F1F1F;
        s.secondary = night ? 0xFFB0B0B0 : 0xFF707070;

        LinearLayout root = new LinearLayout(s.activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(s.activity, 16);
        root.setPadding(pad, dp(s.activity, 6), pad, dp(s.activity, 4));

        s.header = smallText(s.activity, 12f, s.secondary);
        root.addView(s.header);

        s.statusView = smallText(s.activity, 12f, 0xFF2AABEE);
        s.statusView.setPadding(0, dp(s.activity, 3), 0, dp(s.activity, 2));
        root.addView(s.statusView);

        s.sourceView = smallText(s.activity, 11f, s.secondary);
        s.sourceView.setPadding(0, 0, 0, dp(s.activity, 6));
        root.addView(s.sourceView);

        LinearLayout actions = new LinearLayout(s.activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        s.scopeButton = actionButton(s.activity, "仅当前窗口", v -> {
            s.windowOnly = !s.windowOnly;
            refresh(s);
        });
        actions.addView(s.scopeButton, equalWeight());
        actions.addView(actionButton(s.activity, "重新扫描", v -> startScan(s)), equalWeight());
        if (s.allowHistoryLoad) {
            s.olderButton = actionButton(s.activity, "加载更早", v -> loadOlder(s));
            actions.addView(s.olderButton, equalWeight());
        }
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        s.emptyView = smallText(s.activity, 14f, s.secondary);
        s.emptyView.setText("没有符合当前过滤条件的消息，可在模块设置里放宽条件。");
        s.emptyView.setPadding(dp(s.activity, 8), dp(s.activity, 14), dp(s.activity, 8),
                dp(s.activity, 14));
        root.addView(s.emptyView);

        s.list = new ListView(s.activity);
        s.list.setDividerHeight(0);
        s.list.setFastScrollEnabled(true);
        s.adapter = new RankAdapter(s);
        s.list.setAdapter(s.adapter);
        root.addView(s.list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        AlertDialog.Builder builder = new AlertDialog.Builder(s.activity);
        builder.setTitle("消息回应排行");
        builder.setView(root);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("切换排序", (dialog, which) -> {
            ReactionRanker.Options o = s.options;
            s.options = new ReactionRanker.Options(!o.descending, o.minReactions, o.topN,
                    o.onlyReacted, o.mergeAlbums);
            refresh(s);
        });
        s.dialog = builder.create();
        s.dialog.setOnDismissListener(d -> {
            s.cancelled = true;
            cancelActiveScan();
        });
        s.dialog.setOnShowListener(d -> {
            Window window = s.dialog.getWindow();
            if (window == null) return;
            DisplayMetrics metrics = s.activity.getResources().getDisplayMetrics();
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (metrics.heightPixels * 0.8f));
        });
        s.dialog.show();

        s.list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= s.ranked.size()) return;
            MessageSnapshot selected = s.ranked.get(position);
            boolean jumped = s.handle.jumpTo(selected.messageId);
            Toast.makeText(s.activity, jumped
                            ? "已定位到消息 #" + selected.messageId
                            : "当前客户端未找到跳转接口，消息 #" + selected.messageId,
                    Toast.LENGTH_SHORT).show();
            s.dialog.dismiss();
        });
        s.list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= s.ranked.size()) return true;
            String value = String.valueOf(s.ranked.get(position).messageId);
            ClipboardManager clipboard =
                    (ClipboardManager) s.activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("message_id", value));
                Toast.makeText(s.activity, "已复制消息 ID：" + value, Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        refresh(s);
    }

    private static void refresh(Session s) {
        List<MessageSnapshot> master = s.windowOnly
                ? new ArrayList<>(s.uiSnapshots)
                : REPO.merged(s.dialogId);
        s.ranked = ReactionRanker.rank(master, s.options);
        ReactionRanker.Summary summary = ReactionRanker.summarize(master);

        s.header.setText("共 " + summary.totalMessages + " 条消息 · " + summary.reactedMessages
                + " 条有回应 · 合计 " + summary.totalReactions + " 个回应 · 最高 "
                + summary.maxReactions);
        s.statusView.setText(s.status);
        s.sourceView.setText("范围：" + (s.windowOnly ? "当前窗口" : "全部本地消息")
                + " · 本地库 " + REPO.databaseCount(s.dialogId) + " 条 / 窗口 "
                + REPO.uiCount(s.dialogId) + " 条"
                + (s.scanDetail.isEmpty() ? "" : "\n" + s.scanDetail));
        s.emptyView.setVisibility(s.ranked.isEmpty() ? View.VISIBLE : View.GONE);
        if (s.scopeButton != null) {
            s.scopeButton.setText(s.windowOnly ? "切换到全量" : "仅当前窗口");
        }
        if (s.dialog != null) {
            s.dialog.setTitle("消息回应排行 · "
                    + (s.options.descending ? "从多到少 ↓" : "从少到多 ↑"));
        }
        s.adapter.notifyDataSetChanged();
    }

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
                                if (!s.cancelled && s.statusView != null) s.statusView.setText(text);
                            });
                            return true;
                        });
            } catch (Throwable t) {
                LogX.e("full database scan crashed", t);
            }
            final MessageDatabase.Result r = result;
            MAIN.post(() -> {
                if (cancelled || s.cancelled || s.dialog == null || !s.dialog.isShowing()) return;
                if (r == null) {
                    s.status = "扫描失败，详见 Xposed 日志；已回退到当前窗口";
                } else if (r.success()) {
                    long id = r.dialogId != 0 ? r.dialogId : s.dialogId;
                    if (id != s.dialogId) {
                        // Auto-detected chat id: re-key the live window so both sources merge.
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
            Toast.makeText(s.activity, "未识别到当前聊天，无法请求更早历史", Toast.LENGTH_SHORT).show();
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
                    // The host controller touches UI helpers; keep the call on the main thread.
                    MAIN.post(() -> {
                        sent[0] = HistoryLoader.request(s.loader, dialogId, oldest, index);
                        latch.countDown();
                    });
                    latch.await(2, TimeUnit.SECONDS);
                    if (!sent[0]) {
                        message = "当前客户端不支持自动加载更早历史，请在聊天里手动上翻后重新打开排行";
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
                        if (!s.cancelled && s.statusView != null) s.statusView.setText(progress);
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
                if (s.cancelled) return;
                s.status = finalMessage;
                refresh(s);
            });
        }, "ReactionSorter-history").start();
    }

    /** Oldest positive message id known for this chat, used as the paging cursor. */
    private static int oldestId(Session s) {
        int min = Integer.MAX_VALUE;
        for (MessageSnapshot m : REPO.merged(s.dialogId)) {
            if (m.messageId > 0 && m.messageId < min) min = m.messageId;
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

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
            RowHolder holder;
            LinearLayout row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof RowHolder) {
                row = (LinearLayout) convertView;
                holder = (RowHolder) row.getTag();
            } else {
                row = new LinearLayout(s.activity);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(s.activity, 4), dp(s.activity, 9), dp(s.activity, 8),
                        dp(s.activity, 9));
                holder = new RowHolder();
                holder.title = new TextView(s.activity);
                holder.title.setTextSize(15f);
                holder.title.setTextColor(s.primary);
                holder.subtitle = new TextView(s.activity);
                holder.subtitle.setTextSize(12f);
                holder.subtitle.setTextColor(s.secondary);
                holder.subtitle.setPadding(0, dp(s.activity, 3), 0, 0);
                row.addView(holder.title);
                row.addView(holder.subtitle);
                row.setTag(holder);
            }
            MessageSnapshot m = s.ranked.get(position);
            holder.title.setText("#" + (position + 1) + "   " + m.totalReactions
                    + " 个回应    " + m.breakdownText());
            holder.subtitle.setText(m.preview(64) + "    ·    #" + m.messageId);
            return row;
        }
    }

    private static final class RowHolder {
        TextView title;
        TextView subtitle;
    }

    private static TextView smallText(Activity activity, float size, int color) {
        TextView tv = new TextView(activity);
        tv.setTextSize(size);
        tv.setTextColor(color);
        return tv;
    }

    private static Button actionButton(Activity activity, String text,
                                       View.OnClickListener listener) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextSize(11f);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2));
        button.setOnClickListener(listener);
        return button;
    }

    private static LinearLayout.LayoutParams equalWeight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}

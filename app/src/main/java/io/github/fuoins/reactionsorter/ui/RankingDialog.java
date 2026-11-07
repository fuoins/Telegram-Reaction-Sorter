package io.github.fuoins.reactionsorter.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import io.github.fuoins.reactionsorter.core.ChatResolver;
import io.github.fuoins.reactionsorter.core.MessageSnapshot;
import io.github.fuoins.reactionsorter.core.ReactionCounter;
import io.github.fuoins.reactionsorter.core.ReactionRanker;
import io.github.fuoins.reactionsorter.settings.ModulePrefs;
import io.github.fuoins.reactionsorter.util.LogX;

/** The ranked list of messages, built entirely in code (no host resource ids are used). */
public final class RankingDialog {

    private static final int MAX_MESSAGES = 5000;

    private RankingDialog() {}

    /** Entry point: resolve the current chat, snapshot its messages, then show the ranking. */
    public static void open(Activity activity, ClassLoader loader) {
        try {
            ChatResolver.ChatHandle handle = ChatResolver.resolve(activity, loader);
            if (handle == null) {
                Toast.makeText(activity, "未检测到聊天窗口，请进入一个聊天后再试", Toast.LENGTH_SHORT).show();
                return;
            }
            List<MessageSnapshot> snapshots = new ArrayList<>();
            int count = 0;
            for (Object messageObject : handle.currentMessages()) {
                if (++count > MAX_MESSAGES) break;
                MessageSnapshot snapshot = ReactionCounter.extract(messageObject);
                if (snapshot != null) snapshots.add(snapshot);
            }
            if (snapshots.isEmpty()) {
                Toast.makeText(activity, "当前窗口没有读取到消息", Toast.LENGTH_SHORT).show();
                return;
            }
            LogX.i("resolved " + snapshots.size() + " messages from " + handle.fragmentClassName()
                    + ", jump available=" + handle.canJump());
            show(activity, snapshots, handle, ModulePrefs.options());
        } catch (Throwable t) {
            LogX.e("failed to open ranking", t);
            Toast.makeText(activity, "排行失败，详见 Xposed 日志", Toast.LENGTH_SHORT).show();
        }
    }

    private static void show(final Activity activity, final List<MessageSnapshot> snapshots,
                             final ChatResolver.ChatHandle handle,
                             final ReactionRanker.Options options) {
        final List<MessageSnapshot> ranked = ReactionRanker.rank(snapshots, options);
        ReactionRanker.Summary summary = ReactionRanker.summarize(snapshots);

        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final int primary = night ? 0xFFF2F2F2 : 0xFF1F1F1F;
        final int secondary = night ? 0xFFB0B0B0 : 0xFF707070;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, dp(activity, 6), pad, dp(activity, 4));

        TextView header = new TextView(activity);
        header.setText("共 " + summary.totalMessages + " 条消息 · " + summary.reactedMessages
                + " 条有回应 · 合计 " + summary.totalReactions + " 个回应 · 最高 "
                + summary.maxReactions);
        header.setTextSize(12f);
        header.setTextColor(secondary);
        header.setPadding(0, 0, 0, dp(activity, 8));
        root.addView(header);

        TextView empty = new TextView(activity);
        empty.setText("没有符合当前过滤条件的消息，可在模块设置里放宽条件。");
        empty.setTextSize(14f);
        empty.setTextColor(secondary);
        empty.setPadding(dp(activity, 8), dp(activity, 16), dp(activity, 8), dp(activity, 16));
        if (ranked.isEmpty()) root.addView(empty);

        final ListView list = new ListView(activity);
        list.setDividerHeight(0);
        list.setFastScrollEnabled(true);
        list.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return ranked.size();
            }

            @Override
            public Object getItem(int position) {
                return ranked.get(position);
            }

            @Override
            public long getItemId(int position) {
                return ranked.get(position).messageId;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                RowHolder holder;
                LinearLayout row;
                if (convertView instanceof LinearLayout && convertView.getTag() instanceof RowHolder) {
                    row = (LinearLayout) convertView;
                    holder = (RowHolder) row.getTag();
                } else {
                    row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(activity, 4), dp(activity, 9), dp(activity, 8), dp(activity, 9));
                    holder = new RowHolder();
                    holder.title = new TextView(activity);
                    holder.title.setTextSize(15f);
                    holder.title.setTextColor(primary);
                    holder.subtitle = new TextView(activity);
                    holder.subtitle.setTextSize(12f);
                    holder.subtitle.setTextColor(secondary);
                    holder.subtitle.setPadding(0, dp(activity, 3), 0, 0);
                    row.addView(holder.title);
                    row.addView(holder.subtitle);
                    row.setTag(holder);
                }
                MessageSnapshot m = ranked.get(position);
                holder.title.setText("#" + (position + 1) + "   " + m.totalReactions
                        + " 个回应    " + m.breakdownText());
                holder.subtitle.setText(m.preview(64) + "    ·    #" + m.messageId);
                return row;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("消息回应排行 · " + (options.descending ? "从多到少 ↓" : "从少到多 ↑"));
        builder.setView(root);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("切换排序", (dialog, which) -> {
            ReactionRanker.Options flipped = new ReactionRanker.Options(
                    !options.descending, options.minReactions, options.topN,
                    options.onlyReacted, options.mergeAlbums);
            show(activity, snapshots, handle, flipped);
        });
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (metrics.heightPixels * 0.75f));
        });
        dialog.show();

        list.setOnItemClickListener((parent, view, position, id) -> {
            MessageSnapshot selected = ranked.get(position);
            boolean jumped = handle.jumpTo(selected.messageId);
            Toast.makeText(activity, jumped
                            ? "已定位到消息 #" + selected.messageId
                            : "当前客户端未找到跳转接口，消息 #" + selected.messageId,
                    Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            String value = String.valueOf(ranked.get(position).messageId);
            ClipboardManager clipboard =
                    (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("message_id", value));
                Toast.makeText(activity, "已复制消息 ID：" + value, Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }

    private static final class RowHolder {
        TextView title;
        TextView subtitle;
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}

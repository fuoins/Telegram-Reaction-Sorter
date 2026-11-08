package io.github.fuoins.reactionsorter.settings;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import io.github.fuoins.reactionsorter.core.ReactionRanker;

/**
 * Small dependency-free settings screen. All views are built in code so the module does not
 * need any androidx / Material dependency and stays easy to build in CI.
 */
public final class SettingsActivity extends Activity {

    private static final ReactionRanker.SortMode[] MODES = {
            ReactionRanker.SortMode.REACTIONS_DESC,
            ReactionRanker.SortMode.REACTIONS_ASC
    };
    private static final String[] MODE_LABELS = {
            "回应最多 → 最少", "回应最少 → 最多"
    };

    private CheckBox enabled, menuEntry, showFab, descending, onlyReacted, mergeAlbums, verbose;
    private CheckBox autoFullScan, onlineScan;
    private EditText minReactions, topN, scanLimit, onlineConcurrency;
    private Spinner sortMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("消息回应排行 · 设置");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header("在聊天右上角三点菜单里打开「回应排行」：状态显示在频道名下方，底栏复刻官方搜索栏，有上一个/下一个、排序方式和「以列表显示」。默认会向服务器分页读取该聊天的全部消息元数据（含表情回应数），不下载图片/视频/文件，也不写入本地库。"));
        root.addView(divider());

        root.addView(header("入口"));
        enabled = checkBox(root, "启用模块", "关闭后所有 hook 与菜单项都不生效", true);
        menuEntry = checkBox(root, "在三点菜单显示「回应排行」", "和官方搜索放在同一个溢出菜单里", true);
        showFab = checkBox(root, "显示悬浮入口按钮（兼容回退）",
                "若你的客户端里三点菜单没有出现「回应排行」，再开启这个", false);

        root.addView(divider());
        root.addView(header("排序"));
        TextView sortLabel = new TextView(this);
        sortLabel.setText("默认排序方式（底栏里也能随时切换，仅按回应数排序）");
        sortLabel.setTextSize(14);
        sortLabel.setPadding(0, dp(8), 0, dp(2));
        root.addView(sortLabel);
        sortMode = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MODE_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sortMode.setAdapter(adapter);
        root.addView(sortMode, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        descending = checkBox(root, "兼容：按回应数降序", "仅在旧版排序逻辑下生效，通常无需改动", true);
        onlyReacted = checkBox(root, "只显示有回应的消息", "关闭后 0 回应的消息也会进入排行", true);
        mergeAlbums = checkBox(root, "合并相册（同组消息只保留最高的一条）", "", true);

        root.addView(divider());
        root.addView(header("过滤条件"));
        minReactions = numberField(root, "最小回应数", "1");
        topN = numberField(root, "只显示前 N 条（0 = 不限制）", "0");
        verbose = checkBox(root, "输出详细日志（Xposed 日志）", "排查菜单注入不生效时开启", false);

        root.addView(divider());
        root.addView(header("全量回应来源"));
        onlineScan = checkBox(root, "在线全量：读取服务器上该聊天的全部消息回应（默认开启）",
                "用 messages.getHistory 分页读取所有历史消息的元数据，只解析 id / 时间 / 表情回应数；图片、视频、文件的二进制不会下载，也不会写进本地库。这是唯一能拿到「全部消息」而非「本地已缓存」的方式", true);
        onlineConcurrency = numberField(root, "在线扫描并发请求数（1-6，默认 4）",
                String.valueOf(ModulePrefs.DEFAULT_ONLINE_CONCURRENCY));
        autoFullScan = checkBox(root, "同时扫描本地消息库作为即时兜底",
                "打开排行时先秒出本地缓存的排行，再由在线扫描补全；关闭可省一次磁盘扫描", true);
        scanLimit = numberField(root, "单次扫描消息数上限（默认 20000，硬上限 60000）",
                String.valueOf(ModulePrefs.DEFAULT_SCAN_LIMIT));

        root.addView(divider());
        Button save = new Button(this);
        save.setText("保存设置");
        save.setOnClickListener(v -> save());
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header("保存后请强制停止 Telegram 客户端再重新打开，设置会在下次打开排行时生效。"));
        root.addView(header("模块不申请任何权限、不落盘、不上传聊天内容。开启在线全量时，会由 Telegram 客户端自身向服务器发起只读的 messages.getHistory 请求（只取消息元数据，不含媒体文件）；关闭该开关即可完全离线。遇到 FLOOD_WAIT 限流会自动停止并保留已取到的部分结果。"));
        setContentView(scroll);
        load();
    }

    private void load() {
        SharedPreferences prefs = ModulePrefs.local(this);
        enabled.setChecked(prefs.getBoolean(ModulePrefs.KEY_ENABLED, true));
        menuEntry.setChecked(prefs.getBoolean(ModulePrefs.KEY_MENU_ENTRY, true));
        showFab.setChecked(prefs.getBoolean(ModulePrefs.KEY_SHOW_FAB, false));
        descending.setChecked(prefs.getBoolean(ModulePrefs.KEY_DESCENDING, true));
        onlyReacted.setChecked(prefs.getBoolean(ModulePrefs.KEY_ONLY_REACTED, true));
        mergeAlbums.setChecked(prefs.getBoolean(ModulePrefs.KEY_MERGE_ALBUMS, true));
        verbose.setChecked(prefs.getBoolean(ModulePrefs.KEY_VERBOSE, false));
        minReactions.setText(prefs.getString(ModulePrefs.KEY_MIN_REACTIONS, "1"));
        topN.setText(prefs.getString(ModulePrefs.KEY_TOP_N, "0"));
        autoFullScan.setChecked(prefs.getBoolean(ModulePrefs.KEY_AUTO_FULL_SCAN, true));
        onlineScan.setChecked(prefs.getBoolean(ModulePrefs.KEY_ONLINE_SCAN, true));
        scanLimit.setText(prefs.getString(ModulePrefs.KEY_SCAN_LIMIT,
                String.valueOf(ModulePrefs.DEFAULT_SCAN_LIMIT)));
        onlineConcurrency.setText(prefs.getString(ModulePrefs.KEY_ONLINE_CONCURRENCY,
                String.valueOf(ModulePrefs.DEFAULT_ONLINE_CONCURRENCY)));
        ReactionRanker.SortMode mode = ReactionRanker.SortMode.parse(
                prefs.getString(ModulePrefs.KEY_SORT_MODE,
                        ModulePrefs.DEFAULT_SORT_MODE.name()),
                ModulePrefs.DEFAULT_SORT_MODE);
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i] == mode) {
                sortMode.setSelection(i);
                break;
            }
        }
    }

    private void save() {
        int selected = sortMode.getSelectedItemPosition();
        String mode = (selected >= 0 && selected < MODES.length)
                ? MODES[selected].name() : ModulePrefs.DEFAULT_SORT_MODE.name();
        ModulePrefs.local(this).edit()
                .putBoolean(ModulePrefs.KEY_ENABLED, enabled.isChecked())
                .putBoolean(ModulePrefs.KEY_MENU_ENTRY, menuEntry.isChecked())
                .putBoolean(ModulePrefs.KEY_SHOW_FAB, showFab.isChecked())
                .putBoolean(ModulePrefs.KEY_DESCENDING, descending.isChecked())
                .putBoolean(ModulePrefs.KEY_ONLY_REACTED, onlyReacted.isChecked())
                .putBoolean(ModulePrefs.KEY_MERGE_ALBUMS, mergeAlbums.isChecked())
                .putBoolean(ModulePrefs.KEY_VERBOSE, verbose.isChecked())
                .putString(ModulePrefs.KEY_SORT_MODE, mode)
                .putString(ModulePrefs.KEY_MIN_REACTIONS, digits(minReactions, 1))
                .putString(ModulePrefs.KEY_TOP_N, digits(topN, 0))
                .putBoolean(ModulePrefs.KEY_AUTO_FULL_SCAN, autoFullScan.isChecked())
                .putBoolean(ModulePrefs.KEY_ONLINE_SCAN, onlineScan.isChecked())
                .putString(ModulePrefs.KEY_SCAN_LIMIT,
                        digits(scanLimit, ModulePrefs.DEFAULT_SCAN_LIMIT))
                .putString(ModulePrefs.KEY_ONLINE_CONCURRENCY,
                        digits(onlineConcurrency, ModulePrefs.DEFAULT_ONLINE_CONCURRENCY))
                .apply();
        ModulePrefs.markDirty();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private static String digits(EditText field, int fallback) {
        String raw = field.getText() == null ? "" : field.getText().toString().trim();
        try {
            return String.valueOf(Math.max(0, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return String.valueOf(fallback);
        }
    }

    private TextView header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Color.DKGRAY);
        tv.setPadding(0, dp(6), 0, dp(10));
        return tv;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x33000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, dp(6), 0, dp(10));
        v.setLayoutParams(lp);
        return v;
    }

    private CheckBox checkBox(LinearLayout parent, String title, String summary, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(title);
        cb.setChecked(def);
        cb.setTextSize(15);
        cb.setPadding(0, dp(6), 0, dp(2));
        parent.addView(cb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!summary.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(summary);
            sub.setTextSize(12);
            sub.setTextColor(Color.GRAY);
            sub.setPadding(dp(28), 0, 0, dp(8));
            parent.addView(sub);
        }
        return cb;
    }

    private EditText numberField(LinearLayout parent, String label, String def) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setPadding(0, dp(8), 0, dp(2));
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setText(def);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

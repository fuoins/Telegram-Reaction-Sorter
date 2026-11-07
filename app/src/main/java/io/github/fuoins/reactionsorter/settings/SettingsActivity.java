package io.github.fuoins.reactionsorter.settings;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Small dependency-free settings screen. All views are built in code so the module does not
 * need any androidx / Material dependency and stays easy to build in CI.
 */
public final class SettingsActivity extends Activity {

    private CheckBox enabled, showFab, descending, onlyReacted, mergeAlbums, verbose;
    private CheckBox autoFullScan, allowHistoryLoad;
    private EditText minReactions, topN, scanLimit;

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

        root.addView(header("统计当前聊天每条消息的表情回应数量并排序，点击结果可跳转定位到原消息。默认会读取客户端本地消息库，覆盖已缓存的全部历史。"));
        root.addView(divider());

        enabled = checkBox(root, "启用模块", "关闭后所有 hook 与悬浮按钮都不生效", true);
        showFab = checkBox(root, "显示悬浮入口按钮", "关闭后可从 LSPosed 模块设置重新打开", true);
        descending = checkBox(root, "默认按回应数降序", "关闭则按升序（最少回应优先）", true);
        onlyReacted = checkBox(root, "只显示有回应的消息", "关闭后 0 回应的消息也会进入排行", true);
        mergeAlbums = checkBox(root, "合并相册（同组消息只保留最高的一条）", "", true);

        root.addView(divider());
        root.addView(header("过滤条件"));
        minReactions = numberField(root, "最小回应数", "1");
        topN = numberField(root, "只显示前 N 条（0 = 不限制）", "0");
        verbose = checkBox(root, "输出详细日志（Xposed 日志）", "排查兼容性问题时开启", false);

        root.addView(divider());
        root.addView(header("全量回应加载"));
        autoFullScan = checkBox(root, "打开排行时自动扫描本地消息库", "关闭后只统计当前窗口已加载的消息", true);
        scanLimit = numberField(root, "单次扫描最多读取多少行（默认 20000）",
                String.valueOf(ModulePrefs.DEFAULT_SCAN_LIMIT));
        allowHistoryLoad = checkBox(root, "允许联网补全更早历史（默认关闭）",
                "开启后排行里出现「加载更早」按钮，点击才会由 Telegram 客户端自己向服务器请求历史消息，会消耗流量", false);

        root.addView(divider());
        Button save = new Button(this);
        save.setText("保存设置");
        save.setOnClickListener(v -> save());
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header("保存后请强制停止 Telegram 客户端再重新打开，设置会在下次点击排行时生效。"));
        root.addView(header("模块不申请任何权限、不落盘、不上传聊天内容；只有开启上面的开关并手动点「加载更早」时，才会由客户端发起一次网络请求。"));
        setContentView(scroll);
        load();
    }

    private void load() {
        SharedPreferences prefs = ModulePrefs.local(this);
        enabled.setChecked(prefs.getBoolean(ModulePrefs.KEY_ENABLED, true));
        showFab.setChecked(prefs.getBoolean(ModulePrefs.KEY_SHOW_FAB, true));
        descending.setChecked(prefs.getBoolean(ModulePrefs.KEY_DESCENDING, true));
        onlyReacted.setChecked(prefs.getBoolean(ModulePrefs.KEY_ONLY_REACTED, true));
        mergeAlbums.setChecked(prefs.getBoolean(ModulePrefs.KEY_MERGE_ALBUMS, true));
        verbose.setChecked(prefs.getBoolean(ModulePrefs.KEY_VERBOSE, false));
        minReactions.setText(prefs.getString(ModulePrefs.KEY_MIN_REACTIONS, "1"));
        topN.setText(prefs.getString(ModulePrefs.KEY_TOP_N, "0"));
        autoFullScan.setChecked(prefs.getBoolean(ModulePrefs.KEY_AUTO_FULL_SCAN, true));
        allowHistoryLoad.setChecked(prefs.getBoolean(ModulePrefs.KEY_ALLOW_HISTORY_LOAD, false));
        scanLimit.setText(prefs.getString(ModulePrefs.KEY_SCAN_LIMIT,
                String.valueOf(ModulePrefs.DEFAULT_SCAN_LIMIT)));
    }

    private void save() {
        ModulePrefs.local(this).edit()
                .putBoolean(ModulePrefs.KEY_ENABLED, enabled.isChecked())
                .putBoolean(ModulePrefs.KEY_SHOW_FAB, showFab.isChecked())
                .putBoolean(ModulePrefs.KEY_DESCENDING, descending.isChecked())
                .putBoolean(ModulePrefs.KEY_ONLY_REACTED, onlyReacted.isChecked())
                .putBoolean(ModulePrefs.KEY_MERGE_ALBUMS, mergeAlbums.isChecked())
                .putBoolean(ModulePrefs.KEY_VERBOSE, verbose.isChecked())
                .putString(ModulePrefs.KEY_MIN_REACTIONS, digits(minReactions, 1))
                .putString(ModulePrefs.KEY_TOP_N, digits(topN, 0))
                .putBoolean(ModulePrefs.KEY_AUTO_FULL_SCAN, autoFullScan.isChecked())
                .putBoolean(ModulePrefs.KEY_ALLOW_HISTORY_LOAD, allowHistoryLoad.isChecked())
                .putString(ModulePrefs.KEY_SCAN_LIMIT,
                        digits(scanLimit, ModulePrefs.DEFAULT_SCAN_LIMIT))
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

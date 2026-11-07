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
    private EditText minReactions, topN;

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

        root.addView(header("统计当前聊天窗口每条消息的表情回应数量并排序，点击结果可跳转定位到原消息。"));
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
        Button save = new Button(this);
        save.setText("保存设置");
        save.setOnClickListener(v -> save());
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header("保存后请强制停止 Telegram 客户端再重新打开，设置会在下次点击排行时生效。"));
        root.addView(header("本模块不申请任何权限、不联网、不保存或上传聊天内容。"));
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

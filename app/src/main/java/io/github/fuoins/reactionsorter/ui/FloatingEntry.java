package io.github.fuoins.reactionsorter.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.fuoins.reactionsorter.settings.ModulePrefs;
import io.github.fuoins.reactionsorter.util.LogX;

/**
 * A small floating button attached to the host Activity's decor view. Telegram switches
 * between chats with fragments inside one Activity, so the button stays visible and the
 * current chat is resolved on tap instead of on lifecycle events.
 */
public final class FloatingEntry {

    private static final Object TAG = "reaction_sorter_fab";

    private FloatingEntry() {}

    public static void attach(Activity activity, ClassLoader loader) {
        if (activity == null || activity.isFinishing()) return;
        if (!ModulePrefs.showFloatingEntry()) return;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View old = decor.findViewWithTag(TAG);
            if (old != null) decor.removeView(old);

            TextView fab = new TextView(activity);
            fab.setTag(TAG);
            fab.setText("回应\n排行");
            fab.setGravity(Gravity.CENTER);
            fab.setTextColor(Color.WHITE);
            fab.setTextSize(11f);
            fab.setLineSpacing(0f, 0.9f);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xE62AABEE);
            fab.setBackground(bg);
            fab.setAlpha(0.88f);
            fab.setElevation(dp(activity, 6));
            fab.setOnClickListener(v -> RankingDialog.open(activity, loader));
            fab.setOnLongClickListener(v -> {
                v.setVisibility(View.GONE);
                Toast.makeText(activity, "已隐藏排行按钮，重新进入界面可恢复", Toast.LENGTH_SHORT).show();
                return true;
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    dp(activity, 54), dp(activity, 54), Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, dp(activity, 16), dp(activity, 150));
            decor.addView(fab, lp);
        } catch (Throwable t) {
            LogX.e("cannot attach floating entry", t);
        }
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}

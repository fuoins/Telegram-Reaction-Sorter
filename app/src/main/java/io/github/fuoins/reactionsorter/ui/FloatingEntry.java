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
 * Compatibility fallback: a small floating button attached to the host Activity's decor
 * view. The primary entry point is the item injected into the three-dot menu (see
 * {@link io.github.fuoins.reactionsorter.hook.MenuEntry}); this button is off by default
 * and can be enabled in the module settings if a fork changes its menu implementation.
 */
public final class FloatingEntry {

    private static final Object TAG = "reaction_sorter_fab";

    private FloatingEntry() {}

    public static void attach(Activity activity, ClassLoader loader) {
        if (activity == null || activity.isFinishing()) return;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View old = decor.findViewWithTag(TAG);
            if (old != null) decor.removeView(old);
            if (!ModulePrefs.showFloatingEntry()) return;

            TextView fab = new TextView(activity);
            fab.setTag(TAG);
            fab.setText("回应");
            fab.setGravity(Gravity.CENTER);
            fab.setTextColor(Color.WHITE);
            fab.setTextSize(11f);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xE62AABEE);
            fab.setBackground(bg);
            fab.setAlpha(0.72f);
            fab.setElevation(dp(activity, 4));
            fab.setOnClickListener(v -> RankingPanel.open(activity, loader));
            fab.setOnLongClickListener(v -> {
                v.setVisibility(View.GONE);
                Toast.makeText(activity, "已隐藏悬浮按钮，重新进入界面可恢复",
                        Toast.LENGTH_SHORT).show();
                return true;
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    dp(activity, 46), dp(activity, 46), Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, dp(activity, 14), dp(activity, 150));
            decor.addView(fab, lp);
        } catch (Throwable t) {
            LogX.e("cannot attach floating entry", t);
        }
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}

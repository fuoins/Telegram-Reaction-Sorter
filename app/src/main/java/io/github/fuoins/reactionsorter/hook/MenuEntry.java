package io.github.fuoins.reactionsorter.hook;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import io.github.fuoins.reactionsorter.core.ChatResolver;
import io.github.fuoins.reactionsorter.settings.ModulePrefs;
import io.github.fuoins.reactionsorter.ui.RankingPanel;
import io.github.fuoins.reactionsorter.util.LogX;

/**
 * Adds a "回应排行" item to the chat's three-dot (overflow) menu, in the same place where
 * Telegram puts its own search entry.
 *
 * <p>Telegram builds that menu with {@code ActionBarMenuSubItem} views inside an
 * {@code ActionBarPopupWindow} (a {@link PopupWindow} subclass). Its class name is
 * obfuscated, so instead of hooking a named class we hook the <b>framework</b> methods
 * {@code PopupWindow.showAsDropDown / showAtLocation} — those can never be renamed — and
 * locate the menu purely by structure:
 *
 * <ul>
 *   <li>a vertical group holding at least three clickable rows, each with a labelled TextView;</li>
 *   <li>the popup is anchored near the top of the screen (the action bar, not the input field);</li>
 *   <li>the current Activity resolves to a real chat screen ({@link ChatResolver});</li>
 *   <li>it is not the text-formatting popup (bold / italic / mono / ...).</li>
 * </ul>
 *
 * The injected row copies the padding, text size, colours and selector drawable from an
 * existing official row, so it looks native without depending on any host resource id or
 * obfuscated class name. Every step is wrapped so a mismatch can never break the host menu.
 */
public final class MenuEntry {

    private static final String TAG = "reaction_sorter_menu_item";
    private static final int MIN_ITEMS = 3;

    /** Words that identify the text-formatting popup, which must never receive our item. */
    private static final String[] FORMAT_WORDS = {
            "bold", "italic", "mono", "strike", "underline", "spoiler", "quote",
            "regular", "加粗", "斜体", "等宽", "删除线", "下划线", "剧透", "引用", "常规", "格式"
    };

    private static volatile WeakReference<Activity> cachedActivity;
    private static volatile long cachedAt;
    private static volatile boolean cachedInChat;

    private MenuEntry() {}

    public static void install(final ClassLoader loader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // Never let an unexpected host layout escape as an exception: that would
                // abort the original showAsDropDown call and break the whole menu.
                try {
                    if (param.thisObject instanceof PopupWindow) {
                        inject((PopupWindow) param.thisObject, loader,
                                param.method.getName(), param.args);
                    }
                } catch (Throwable t) {
                    LogX.d("menu inject skipped: " + t);
                }
            }
        };

        // Three framework overloads are used across Telegram versions / menu positions.
        hookOne("showAsDropDown", hook, View.class, int.class, int.class);
        hookOne("showAsDropDown", hook, View.class, int.class, int.class, int.class);
        hookOne("showAtLocation", hook, View.class, int.class, int.class, int.class);
    }

    private static void hookOne(String name, XC_MethodHook hook, Class<?>... params) {
        try {
            XposedHelpers.findAndHookMethod(PopupWindow.class, name,
                    concat(params, hook));
        } catch (Throwable t) {
            LogX.d("cannot hook PopupWindow." + name + "/" + params.length + ": " + t);
        }
    }

    private static Object[] concat(Class<?>[] types, XC_MethodHook hook) {
        Object[] args = new Object[types.length + 1];
        System.arraycopy(types, 0, args, 0, types.length);
        args[types.length] = hook;
        return args;
    }

    private static void inject(PopupWindow popup, ClassLoader loader,
                               String method, Object[] args) {
        if (!ModulePrefs.isEnabled() || !ModulePrefs.menuEntryEnabled()) return;
        View content = popup.getContentView();
        if (content == null) return;

        Activity activity = activityOf(content.getContext());
        if (activity == null || activity.isFinishing()) return;

        // Cheap structural filters first; ChatResolver walks the object graph, so only run
        // it once the popup really looks like a top-anchored, labelled overflow menu.
        if (!topAnchored(activity, method, args)) return;
        ViewGroup container = findMenuContainer(content);
        if (container == null || container.findViewWithTag(TAG) != null) return;
        List<View> items = menuItems(container);
        if (items.size() < MIN_ITEMS) return;
        List<String> labels = labels(items);
        if (isFormattingMenu(labels)) return;
        if (!inChat(activity, loader)) return;

        View sample = items.get(0);
        TextView sampleText = firstTextView(sample);
        View row = buildRow(activity, popup, loader, sample, sampleText);
        int index = insertIndex(container, items, labels);
        container.addView(row, Math.min(index, container.getChildCount()));
        content.requestLayout();
        content.invalidate();
        LogX.i("injected ranking item into overflow menu ("
                + items.size() + " existing rows)");
    }

    private static View buildRow(Activity activity, PopupWindow popup, ClassLoader loader,
                                 View sample, TextView sampleText) {
        FrameLayout row = new FrameLayout(activity);
        row.setTag(TAG);

        // Reuse the official selector (ripple / pressed state) when available.
        try {
            Drawable bg = sample.getBackground();
            if (bg != null) {
                Drawable copy = bg.getConstantState() != null
                        ? bg.getConstantState().newDrawable().mutate()
                        : bg.mutate();
                row.setBackground(copy);
            }
        } catch (Throwable ignored) {
            // A non-cloneable drawable is fine; the row still works without a highlight.
        }

        int height = dp(activity, 48);
        ViewGroup.LayoutParams sampleLp = sample.getLayoutParams();
        if (sampleLp != null && sampleLp.height > 0) height = sampleLp.height;
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));

        TextView label = new TextView(activity);
        label.setText("回应排行");
        boolean rtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
                == View.LAYOUT_DIRECTION_RTL;
        if (sampleText != null) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sampleText.getTextSize());
            label.setTextColor(sampleText.getTextColors());
            label.setTypeface(sampleText.getTypeface());
            label.setPadding(sampleText.getPaddingLeft(), sampleText.getPaddingTop(),
                    sampleText.getPaddingRight(), sampleText.getPaddingBottom());
        } else {
            label.setTextSize(16f);
            label.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
        }
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT));
        row.addView(label, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            try {
                popup.dismiss();
            } catch (Throwable ignored) {
                // Dismiss is best-effort; the panel is still usable.
            }
            RankingPanel.open(activity, loader);
        });
        return row;
    }

    /** Put our row right after the official "Search" entry when we can find it. */
    private static int insertIndex(ViewGroup container, List<View> items, List<String> labels) {
        for (int i = 0; i < labels.size(); i++) {
            String l = labels.get(i).toLowerCase(Locale.ROOT);
            if (l.contains("搜索") || l.contains("search")) {
                return container.indexOfChild(items.get(i)) + 1;
            }
        }
        return container.indexOfChild(items.get(items.size() - 1)) + 1;
    }

    private static boolean isFormattingMenu(List<String> labels) {
        int hits = 0;
        for (String label : labels) {
            String lower = label.toLowerCase(Locale.ROOT);
            for (String word : FORMAT_WORDS) {
                if (lower.contains(word)) {
                    hits++;
                    break;
                }
            }
        }
        return hits >= 2;
    }

    /** The overflow menu is anchored to the action bar at the top; input-field popups are not. */
    private static boolean topAnchored(Activity activity, String method, Object[] args) {
        try {
            int y = -1;
            if ("showAsDropDown".equals(method) && args.length >= 1 && args[0] instanceof View) {
                int[] loc = new int[2];
                ((View) args[0]).getLocationOnScreen(loc);
                y = loc[1];
            } else if ("showAtLocation".equals(method) && args.length >= 4
                    && args[3] instanceof Integer) {
                y = (Integer) args[3];
            }
            if (y <= 0) return true; // unknown layout, do not filter it out
            int limit = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.34f);
            return y <= limit;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean inChat(Activity activity, ClassLoader loader) {
        long now = System.currentTimeMillis();
        Activity last = cachedActivity == null ? null : cachedActivity.get();
        // A positive answer is reused longer; a negative one is retried soon, because the
        // user may have just entered a chat when the menu is opened.
        long ttl = cachedInChat ? 5_000L : 1_500L;
        if (last == activity && now - cachedAt < ttl) return cachedInChat;
        boolean ok;
        try {
            ok = ChatResolver.resolve(activity, loader) != null;
        } catch (Throwable t) {
            ok = false;
        }
        cachedActivity = new WeakReference<>(activity);
        cachedAt = now;
        cachedInChat = ok;
        return ok;
    }

    /** Depth-first search for the vertical group that holds the most labelled, clickable rows. */
    private static ViewGroup findMenuContainer(View root) {
        Deque<View> queue = new ArrayDeque<>();
        queue.add(root);
        ViewGroup best = null;
        int bestScore = 0;
        int budget = 400;
        while (!queue.isEmpty() && budget-- > 0) {
            View view = queue.poll();
            if (!(view instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) view;
            int score = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!child.isClickable()) continue;
                TextView tv = firstTextView(child);
                if (tv != null && tv.getText() != null && tv.getText().length() > 0) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best = group;
            }
            for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
        }
        return bestScore >= MIN_ITEMS ? best : null;
    }

    private static List<View> menuItems(ViewGroup container) {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!child.isClickable()) continue;
            TextView tv = firstTextView(child);
            if (tv != null && tv.getText() != null && tv.getText().length() > 0) out.add(child);
        }
        return out;
    }

    private static List<String> labels(List<View> items) {
        List<String> out = new ArrayList<>();
        for (View item : items) {
            TextView tv = firstTextView(item);
            out.add(tv == null || tv.getText() == null ? "" : tv.getText().toString());
        }
        return out;
    }

    private static TextView firstTextView(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView tv = firstTextView(group.getChildAt(i));
                if (tv != null) return tv;
            }
        }
        return null;
    }

    private static Activity activityOf(Context context) {
        Context current = context;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) break;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}

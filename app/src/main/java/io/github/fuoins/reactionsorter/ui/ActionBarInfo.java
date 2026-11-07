package io.github.fuoins.reactionsorter.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import io.github.fuoins.reactionsorter.util.LogX;

/**
 * Puts the ranking status into the <b>official</b> ActionBar subtitle line — the small text
 * right under the channel name — instead of drawing a custom title bar.
 *
 * <p>{@code org.telegram.ui.*} is obfuscated: in a chat the title lives in a
 * {@code ChatAvatarContainer} with {@code SimpleTextView} /
 * {@code AnimatedTextView} children, and {@code ActionBar} lays out its children manually,
 * so adding a view to it does not work. What cannot be renamed is a method's <b>signature</b>:
 * both text views expose {@code setText(CharSequence)} (returning {@code boolean} or
 * {@code void}). We therefore
 *
 * <ol>
 *   <li>find the top title area and its lowest, left-most text view by structure;</li>
 *   <li>collect the {@code setText}-shaped methods from that view's class;</li>
 *   <li>{@link XposedBridge#hookMethod} them and replace the argument with our short
 *       status while the ranking panel is open.</li>
 * </ol>
 *
 * Hooking (rather than just calling {@code setText} once) matters because the host keeps
 * refreshing the subtitle (member count, "typing…", etc.). The hook only rewrites calls
 * made on that one view, is installed only while the panel is open, and is removed on close
 * together with restoring the original text and visibility. If anything cannot be found the
 * caller simply shows no subtitle info — no custom bar is ever created.
 */
public final class ActionBarInfo {

    private static final int WALK_BUDGET = 1200;
    private static final Map<Class<?>, Boolean> TEXT_LIKE_CACHE = new HashMap<>();

    private static volatile WeakReference<View> targetRef;
    private static volatile CharSequence originalText;
    private static volatile int originalVisibility = View.GONE;
    private static volatile String info;
    private static final List<XC_MethodHook.Unhook> HOOKS = new ArrayList<>();
    private static final List<Method> SETTERS = new ArrayList<>();
    private static Method primarySetter;
    private static Method getter;

    private ActionBarInfo() {}

    /** Finds the top title area (ActionBar or its ChatAvatarContainer) by structure. */
    public static View findTitleArea(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        Deque<View> queue = new ArrayDeque<>();
        queue.add(decor);
        int budget = WALK_BUDGET;
        View best = null;
        int bestScore = 1;
        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (!(v instanceof ViewGroup)) continue;
            ViewGroup g = (ViewGroup) v;
            int[] loc = new int[2];
            g.getLocationOnScreen(loc);
            if (loc[1] > screenH * 0.25f) continue;
            // The title area is a short strip at the top. Excluding full-height containers
            // (contentView, chat list) prevents message text views from being mistaken for
            // the channel subtitle.
            int height = g.getHeight();
            if (height > 0 && height > screenH * 0.18f) {
                for (int i = 0; i < g.getChildCount(); i++) queue.add(g.getChildAt(i));
                continue;
            }
            int score = textLikeScore(g);
            if (score >= 2 && score > bestScore) {
                bestScore = score;
                best = g;
            }
            for (int i = 0; i < g.getChildCount(); i++) queue.add(g.getChildAt(i));
        }
        return best;
    }

    /**
     * Installs the subtitle hook. Returns false when the host layout is not recognised;
     * the panel then simply omits the status line.
     */
    public static boolean attach(Activity activity, View titleArea) {
        detach();
        View area = titleArea != null ? titleArea : findTitleArea(activity);
        if (area == null) {
            LogX.i("no official title area found; ranking status will not be shown");
            return false;
        }
        View sub = findSubtitle(area, activity);
        if (sub == null) {
            LogX.i("no official subtitle view found; ranking status will not be shown");
            return false;
        }
        collectMethods(sub.getClass());
        if (primarySetter == null || SETTERS.isEmpty()) {
            LogX.i("no setText(CharSequence) on the subtitle view: " + sub.getClass().getName());
            return false;
        }

        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    View t = targetRef == null ? null : targetRef.get();
                    String s = info;
                    if (t != null && s != null && param.thisObject == t
                            && param.args != null && param.args.length > 0) {
                        param.args[0] = s;
                    }
                } catch (Throwable ignored) {
                    // Never interfere with the host's own call.
                }
            }
        };
        for (Method m : SETTERS) {
            try {
                HOOKS.add(XposedBridge.hookMethod(m, callback));
            } catch (Throwable t) {
                LogX.d("cannot hook subtitle setter: " + t);
            }
        }
        if (HOOKS.isEmpty()) return false;

        targetRef = new WeakReference<>(sub);
        originalVisibility = sub.getVisibility();
        originalText = readText(sub);
        info = null;
        if (originalVisibility != View.VISIBLE) {
            try {
                sub.setVisibility(View.VISIBLE);
            } catch (Throwable t) {
                LogX.d("cannot reveal subtitle: " + t);
            }
        }
        LogX.i("ranking status attached to official subtitle ("
                + sub.getClass().getName() + ")");
        return true;
    }

    /** Updates the short status text shown in the host subtitle line. */
    public static void update(String text) {
        info = text;
        View t = targetRef == null ? null : targetRef.get();
        if (t == null) return;
        try {
            if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
            // best effort
        }
        invokeSetText(t, text);
    }

    /** Removes the hook and restores the host's own subtitle. */
    public static void detach() {
        info = null;
        View t = targetRef == null ? null : targetRef.get();
        targetRef = null;
        // Restore the text while the hook is still installed but inactive, so a host
        // refresh racing with detach cannot re-apply our status line.
        if (t != null && originalText != null) invokeSetText(t, originalText);
        if (t != null) {
            try {
                t.setVisibility(originalVisibility);
            } catch (Throwable ignored) {
                // best effort
            }
        }
        for (XC_MethodHook.Unhook h : HOOKS) {
            try {
                h.unhook();
            } catch (Throwable ignored) {
                // best effort
            }
        }
        HOOKS.clear();
        SETTERS.clear();
        primarySetter = null;
        getter = null;
        originalText = null;
    }

    // --------------------------------------------------------------- view lookup ---

    private static int textLikeScore(ViewGroup group) {
        int n = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isTextLike(child)) {
                n++;
            } else if (child instanceof ViewGroup) {
                ViewGroup nested = (ViewGroup) child;
                for (int j = 0; j < nested.getChildCount(); j++) {
                    if (isTextLike(nested.getChildAt(j))) n++;
                }
            }
        }
        return n;
    }

    private static View findSubtitle(View area, Activity activity) {
        List<View> candidates = new ArrayList<>();
        Deque<View> queue = new ArrayDeque<>();
        queue.add(area);
        int budget = WALK_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (isTextLike(v)) candidates.add(v);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) queue.add(g.getChildAt(i));
            }
        }
        // At least two text views (title + subtitle) — otherwise we would overwrite the
        // channel name itself.
        if (candidates.size() < 2) return null;

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int topLimit = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.20f);
        int[] loc = new int[2];
        View best = null;
        int bestKey = Integer.MIN_VALUE;
        for (View v : candidates) {
            v.getLocationOnScreen(loc);
            if (loc[0] > screenW * 0.62f) continue; // right-side menu / search field
            if (loc[1] > topLimit) continue;        // not part of the top title strip
            boolean visible = v.getVisibility() == View.VISIBLE;
            int key = (visible ? 1_000_000 : 0) + loc[1];
            if (key > bestKey) {
                bestKey = key;
                best = v;
            }
        }
        return best;
    }

    /** A text view is identified by a {@code setText(CharSequence)}-shaped method, not a name. */
    private static boolean isTextLike(View view) {
        if (view == null || view instanceof ViewGroup) return false;
        Class<?> cls = view.getClass();
        Boolean cached = TEXT_LIKE_CACHE.get(cls);
        if (cached != null) return cached;
        boolean ok = false;
        for (Method m : cls.getMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 1 && ps[0] == CharSequence.class
                    && (m.getReturnType() == void.class || m.getReturnType() == boolean.class)
                    && !isFramework(m.getDeclaringClass())) {
                ok = true;
                break;
            }
        }
        TEXT_LIKE_CACHE.put(cls, ok);
        return ok;
    }

    // ------------------------------------------------------------ method lookup ---

    private static void collectMethods(Class<?> cls) {
        SETTERS.clear();
        primarySetter = null;
        getter = null;
        for (Method m : cls.getMethods()) {
            if (isFramework(m.getDeclaringClass())) continue;
            Class<?>[] ps = m.getParameterTypes();
            Class<?> ret = m.getReturnType();
            try {
                if (ps.length == 0 && CharSequence.class.isAssignableFrom(ret)) {
                    if (getter == null) {
                        m.setAccessible(true);
                        getter = m;
                    }
                } else if (ps.length >= 1 && ps.length <= 3 && ps[0] == CharSequence.class
                        && (ret == void.class || ret == boolean.class)) {
                    m.setAccessible(true);
                    if (ps.length == 1 && primarySetter == null) primarySetter = m;
                    SETTERS.add(m);
                }
            } catch (Throwable ignored) {
                // A method we cannot make accessible is simply skipped.
            }
        }
    }

    private static CharSequence readText(View view) {
        Method m = getter;
        if (m == null) return null;
        try {
            Object value = m.invoke(view);
            return value instanceof CharSequence ? (CharSequence) value : null;
        } catch (Throwable t) {
            LogX.d("cannot read original subtitle: " + t);
            return null;
        }
    }

    private static void invokeSetText(View view, CharSequence text) {
        Method m = primarySetter;
        if (m == null) return;
        try {
            m.invoke(view, text);
        } catch (Throwable t) {
            LogX.d("cannot write subtitle: " + t);
        }
    }

    private static boolean isFramework(Class<?> cls) {
        String n = cls.getName();
        return n.startsWith("android.") || n.startsWith("androidx.")
                || n.startsWith("java.") || n.startsWith("javax.")
                || n.startsWith("kotlin.");
    }
}

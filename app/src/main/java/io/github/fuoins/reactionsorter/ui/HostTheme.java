package io.github.fuoins.reactionsorter.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import java.util.Locale;

import io.github.fuoins.reactionsorter.util.LogX;

/**
 * Reuses the host's own look instead of inventing a new one.
 *
 * <p>Telegram does not define its theme colours as {@code R.color} entries (they live in
 * {@code Theme.java} as code constants), and {@code org.telegram.ui.ActionBar.*} is
 * obfuscated. Two things are still reliable:
 *
 * <ul>
 *   <li>the ActionBar's background is normally a {@link ColorDrawable}, so its exact
 *       theme colour can be read from the live view. Light/dark is derived from its
 *       luminance and used for text, icons and the panel surface;</li>
 *   <li>Android resource <b>names</b> are never renamed by R8. Looking up
 *       {@code SearchAsList}, {@code SearchAsChat}, {@code Of}, {@code NoResult} and
 *       the icon drawables by name yields the host's own localised strings and artwork.</li>
 * </ul>
 *
 * Every lookup has a fallback, so a fork that removes a resource still gets a usable panel.
 */
public final class HostTheme {

    private final Activity activity;
    private final Resources res;
    private final String pkg;

    public final boolean night;
    public final int actionBarColor;
    public final int panelBg;
    public final int surface;
    public final int primary;
    public final int secondary;
    public final int blue;
    public final int icon;
    public final int divider;
    public final int selector;
    public final int highlight;
    /** Bottom edge of the official ActionBar, in pixels from the top of the decor view. */
    public final int actionBarBottom;

    private HostTheme(Activity activity, View actionBar) {
        this.activity = activity;
        this.res = activity.getResources();
        this.pkg = activity.getPackageName();

        int barColor = 0;
        View barView = null;
        try {
            // The title container (ChatAvatarContainer) is transparent. Walk up to the
            // ActionBar, whose background is normally a solid ColorDrawable.
            View node = actionBar;
            for (int i = 0; i < 8 && node != null; i++) {
                Drawable bg = node.getBackground();
                if (bg instanceof ColorDrawable) {
                    barColor = ((ColorDrawable) bg).getColor();
                    barView = node;
                    break;
                }
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
        } catch (Throwable t) {
            LogX.d("cannot read action bar colour: " + t);
        }
        boolean systemNight = (res.getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        this.night = barColor != 0 ? luminance(barColor) < 0.5 : systemNight;
        this.actionBarColor = barColor != 0 ? barColor
                : (night ? 0xFF1B2128 : 0xFFFFFFFF);

        // The official search bar is drawn on top of the input bubble, which in most
        // themes shares the ActionBar colour. Reusing it keeps custom themes consistent.
        this.panelBg = actionBarColor;
        this.surface = night ? 0xFF172029 : 0xFFFFFFFF;
        this.primary = night ? 0xFFF2F4F6 : 0xFF1F2329;
        this.secondary = night ? 0xFF9AA6B2 : 0xFF7C858F;
        int blueFromRes = colorOrZero("windowBackgroundWhiteBlueText2");
        this.blue = blueFromRes != 0 ? blueFromRes : (night ? 0xFF6AB7F5 : 0xFF2AABEE);
        int iconFromRes = colorOrZero("chat_searchPanelIcons");
        this.icon = iconFromRes != 0 ? iconFromRes : secondary;
        this.divider = night ? 0x22FFFFFF : 0x14000000;
        this.selector = night ? 0x33FFFFFF : 0x1F000000;
        this.highlight = night ? 0x2A6AB7F5 : 0x1E2AABEE;

        int bottom = dp(64);
        try {
            View anchor = barView != null ? barView : actionBar;
            if (anchor != null) {
                int[] barLoc = new int[2];
                int[] decorLoc = new int[2];
                anchor.getLocationOnScreen(barLoc);
                activity.getWindow().getDecorView().getLocationOnScreen(decorLoc);
                int height = Math.max(anchor.getHeight(), dp(56));
                bottom = barLoc[1] + height - decorLoc[1];
            }
        } catch (Throwable t) {
            LogX.d("cannot measure action bar: " + t);
        }
        this.actionBarBottom = Math.max(dp(48), bottom);
    }

    public static HostTheme from(Activity activity, View actionBar) {
        return new HostTheme(activity, actionBar);
    }

    // ------------------------------------------------------------- official strings ---

    /** Host-localised "Show as List" (Chinese: 以列表显示). */
    public String showAsList() {
        return string("SearchAsList", "以列表显示");
    }

    /** Host-localised "Show as Chat" (Chinese: 以聊天显示). */
    public String showAsChat() {
        return string("SearchAsChat", "以聊天显示");
    }

    public String noResult() {
        return string("NoResult", "无结果");
    }

    /** Host-localised "1 of 47" from the official {@code Of} format. */
    public String countText(int index, int total) {
        String pattern = string("Of", "%1$d of %2$d");
        if (pattern.contains("%1") && pattern.contains("%2")) {
            try {
                return String.format(Locale.getDefault(), pattern, index, total);
            } catch (Throwable ignored) {
                // A fork may ship an incompatible format; fall through.
            }
        }
        return index + " / " + total;
    }

    public String string(String name, String fallback) {
        try {
            int id = res.getIdentifier(name, "string", pkg);
            if (id != 0) {
                CharSequence value = res.getText(id);
                if (value != null && value.length() > 0) return value.toString();
            }
        } catch (Throwable t) {
            LogX.d("string resource " + name + " unavailable: " + t);
        }
        return fallback;
    }

    // ------------------------------------------------------------ official artwork ---

    /**
     * Returns the host's own arrow drawable when available (Telegram ships
     * {@code msg_mini_arrow_mediabold} / {@code msg_mini_arrow_mediathin} / {@code msg_arrowright}),
     * otherwise a clean triangle drawn in the host's icon colour.
     */
    public Drawable arrow(boolean up) {
        String[] candidates = {"msg_mini_arrow_mediabold", "msg_mini_arrow_mediathin",
                "msg_arrowright"};
        for (String name : candidates) {
            Drawable d = drawable(name);
            if (d == null) continue;
            // The stock arrow points down/right; the caller rotates the ImageView for "up".
            return up ? rotate(d) : d;
        }
        return new TriangleDrawable(icon, up);
    }

    /** The three-dot "more" glyph, using the host artwork when present. */
    public Drawable moreIcon() {
        Drawable d = drawable("ic_ab_other");
        return d != null ? d : null;
    }

    public Drawable drawable(String name) {
        try {
            int id = res.getIdentifier(name, "drawable", pkg);
            if (id == 0) return null;
            Drawable d = res.getDrawable(id, activity.getTheme());
            return d == null ? null : d.mutate();
        } catch (Throwable t) {
            LogX.d("drawable " + name + " unavailable: " + t);
            return null;
        }
    }

    private int colorOrZero(String name) {
        try {
            int id = res.getIdentifier(name, "color", pkg);
            return id == 0 ? 0 : res.getColor(id, activity.getTheme());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Rounded bubble background, mirroring {@code chatInputBubbleContainer}. */
    public GradientDrawable panelBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(panelBg);
        g.setCornerRadius(dp(22));
        return g;
    }

    /** Pressed-state background for the small icon buttons. */
    public android.graphics.drawable.StateListDrawable selectorBackground() {
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(selector));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    public int dp(int value) {
        return (int) (value * res.getDisplayMetrics().density + 0.5f);
    }

    private static double luminance(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }

    private static Drawable rotate(Drawable source) {
        return new Drawable() {
            private final Drawable inner = source;

            @Override
            public void draw(Canvas canvas) {
                int save = canvas.save();
                Rect b = getBounds();
                canvas.rotate(180, b.centerX(), b.centerY());
                inner.setBounds(b);
                inner.draw(canvas);
                canvas.restoreToCount(save);
            }

            @Override
            public void setAlpha(int alpha) { inner.setAlpha(alpha); }

            @Override
            public void setColorFilter(ColorFilter cf) { inner.setColorFilter(cf); }

            @Override
            public int getOpacity() { return PixelFormat.TRANSLUCENT; }

            @Override
            public int getIntrinsicWidth() { return inner.getIntrinsicWidth(); }

            @Override
            public int getIntrinsicHeight() { return inner.getIntrinsicHeight(); }
        };
    }

    /** Minimal filled triangle used when the host has no suitable arrow asset. */
    public static final class TriangleDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean up;

        public TriangleDrawable(int color, boolean up) {
            this.up = up;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            path.reset();
            float cx = b.centerX(), cy = b.centerY();
            float hx = Math.min(b.width(), b.height()) * 0.30f;
            float hy = hx * 0.62f;
            if (up) {
                path.moveTo(cx, cy - hy);
                path.lineTo(cx - hx, cy + hy);
                path.lineTo(cx + hx, cy + hy);
            } else {
                path.moveTo(cx, cy + hy);
                path.lineTo(cx - hx, cy - hy);
                path.lineTo(cx + hx, cy - hy);
            }
            path.close();
        }

        @Override
        public void draw(Canvas canvas) { canvas.drawPath(path, paint); }

        @Override
        public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override
        public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }

        @Override
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}

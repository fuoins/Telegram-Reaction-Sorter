package io.github.fuoins.reactionsorter.hook;

import android.app.Activity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import io.github.fuoins.reactionsorter.core.ChatResolver;
import io.github.fuoins.reactionsorter.settings.ModulePrefs;
import io.github.fuoins.reactionsorter.ui.FloatingEntry;
import io.github.fuoins.reactionsorter.ui.RankingPanel;
import io.github.fuoins.reactionsorter.util.LogX;
import io.github.fuoins.reactionsorter.util.Reflect;

/** Installs the framework-level hooks the module needs inside the host process. */
public final class HostHook {

    private HostHook() {}

    public static void install(final ClassLoader loader) {
        // Primary entry: add a row to the chat's three-dot overflow menu. This hooks
        // android.widget.PopupWindow, which is framework code and never obfuscated.
        MenuEntry.install(loader);

        // Activity.onResume is framework code too. Telegram switches chats as fragments
        // inside one Activity, so the chat is resolved lazily on tap / on menu show.
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!ModulePrefs.isEnabled()) return;
                    Activity activity = (Activity) param.thisObject;
                    // Skip web/voip/permission activities that do not belong to Telegram's UI.
                    if (!Reflect.shouldDescend(activity.getClass())) return;
                    ChatResolver.invalidate(activity);
                    RankingPanel.close();
                    FloatingEntry.attach(activity, loader);
                } catch (Throwable t) {
                    LogX.e("onResume hook failed", t);
                }
            }
        });
    }
}

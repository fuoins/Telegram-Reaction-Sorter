package io.github.fuoins.reactionsorter.hook;

import android.app.Activity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import io.github.fuoins.reactionsorter.core.ChatResolver;
import io.github.fuoins.reactionsorter.settings.ModulePrefs;
import io.github.fuoins.reactionsorter.ui.FloatingEntry;
import io.github.fuoins.reactionsorter.util.LogX;
import io.github.fuoins.reactionsorter.util.Reflect;

/** Installs the single framework-level hook the module needs inside the host process. */
public final class HostHook {

    private HostHook() {}

    public static void install(final ClassLoader loader) {
        // Activity.onResume is Android framework code, so its name is never obfuscated.
        // Telegram switches chats as fragments inside one Activity; the floating button
        // therefore stays attached and the active chat is resolved lazily on tap.
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!ModulePrefs.isEnabled()) return;
                    Activity activity = (Activity) param.thisObject;
                    // Skip web/voip/permission activities that do not belong to Telegram's UI.
                    if (!Reflect.shouldDescend(activity.getClass())) return;
                    ChatResolver.invalidate(activity);
                    FloatingEntry.attach(activity, loader);
                } catch (Throwable t) {
                    LogX.e("onResume hook failed", t);
                }
            }
        });
    }
}

package io.github.fuoins.reactionsorter;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.fuoins.reactionsorter.core.Scope;
import io.github.fuoins.reactionsorter.hook.HostHook;
import io.github.fuoins.reactionsorter.util.LogX;

/**
 * Xposed / LSPosed entry point. Declared in assets/xposed_init.
 *
 * <p>The module is deliberately passive: it installs one Activity lifecycle hook and reads
 * in-memory / locally cached messages only when the user taps the floating button. No chat
 * content is stored on disk or uploaded. The single optional network path is the explicit
 * "load older history" button, which asks the host client to page in its own history and
 * is disabled by default.
 */
public final class HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    @Override
    public void initZygote(StartupParam startupParam) {
        LogX.i("zygote initialised, module apk=" + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!Scope.isSupported(lpparam.packageName)) return;
        LogX.i("attached to " + lpparam.packageName + " (process " + lpparam.processName + ")");
        try {
            HostHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            LogX.e("hook installation failed for " + lpparam.packageName, t);
        }
    }
}

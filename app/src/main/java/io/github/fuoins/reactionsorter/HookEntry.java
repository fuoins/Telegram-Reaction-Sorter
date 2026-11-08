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
 * <p>The module installs two framework-level hooks: {@code PopupWindow.showAsDropDown} to
 * add a "回应排行" row to the chat's three-dot menu, and {@code Activity.onResume} for
 * lifecycle bookkeeping. While the panel is open it additionally hooks the host's own
 * ActionBar subtitle text view by method signature, so the status appears under the
 * channel name instead of in a custom title bar.
 *
 * <p>When the ranking panel opens, it reads the current in-memory window, then scans the
 * client's local {@code cache4.db} as an instant offline fallback, and finally pages through
 * the <b>whole server history</b> via the host's own {@code messages.getHistory} RPC. That
 * RPC returns message metadata (including the full {@code reactions} block) — photo/video
 * file bytes are fetched over a separate download channel only when a message is rendered,
 * so the scan reads no media content. Results are kept in memory only: nothing is written
 * to disk, no NotificationCenter updates are posted, and no chat content is uploaded. The
 * online scan is on by default (it is the only way to cover more than the local cache) and
 * can be disabled in the module settings for fully offline operation.
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

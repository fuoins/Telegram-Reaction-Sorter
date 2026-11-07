package io.github.fuoins.reactionsorter.util;

import de.robv.android.xposed.XposedBridge;

/** Minimal logging helper. Everything goes to the Xposed log, never to the host app's logcat tag. */
public final class LogX {

    private static final String TAG = "[ReactionSorter] ";
    private static boolean verbose = false;

    private LogX() {}

    public static void setVerbose(boolean value) {
        verbose = value;
    }

    public static void i(String message) {
        XposedBridge.log(TAG + message);
    }

    public static void d(String message) {
        if (verbose) {
            XposedBridge.log(TAG + "[debug] " + message);
        }
    }

    public static void w(String message, Throwable t) {
        XposedBridge.log(TAG + "[warn] " + message + " -> " + t);
        XposedBridge.log(t);
    }

    public static void e(String message, Throwable t) {
        XposedBridge.log(TAG + "[error] " + message + " -> " + t);
        XposedBridge.log(t);
    }
}

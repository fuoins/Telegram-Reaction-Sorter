package io.github.fuoins.reactionsorter.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.fuoins.reactionsorter.util.LogX;

/**
 * Asks the Telegram client itself to page in older history, for messages that are not in
 * its local database yet.
 *
 * <p>The chat screen does this when the user scrolls up:
 * <pre>
 *   getMessagesController().loadMessages(dialog_id, 0, false, 50, oldestMessageId,
 *           0, !cacheEndReached, 0, classGuid, 0, 0, chatMode, 0, 0, loadIndex, false);
 * </pre>
 * {@code load_type = 0} means "older than max_id". The method is public and lives in
 * {@code org.telegram.messenger.MessagesController}, a class kept by the host's proguard
 * rules, but its parameter list changes between releases. We therefore locate it by
 * signature shape (long dialogId, int count, int max_id, ...) and fill unknown slots with
 * neutral values instead of hard-coding a version-specific signature.
 *
 * <p>A deliberately non-matching {@code classGuid} is used: the host still persists the
 * fetched messages to {@code cache4.db}, but its own chat UI ignores the callback, so the
 * user's visible conversation is not disturbed. This is the only path in the module that
 * causes network traffic, it is never triggered automatically, and it is disabled by default.
 */
public final class HistoryLoader {

    private static final int BATCH_SIZE = 50;
    /** Host UI guids are generated positive values; a far-negative one never collides. */
    private static final int CLASS_GUID = Integer.MIN_VALUE + 7;

    private HistoryLoader() {}

    /**
     * Dispatch one "load older messages" request. Must be called on the main thread
     * (the host method touches NotificationCenter / UI helpers internally).
     *
     * @return {@code true} when a request was actually dispatched
     */
    public static boolean request(ClassLoader loader, long dialogId, int oldestMessageId,
                                  int loadIndex) {
        if (loader == null || dialogId == 0L || oldestMessageId <= 0) return false;
        try {
            Class<?> type = Class.forName("org.telegram.messenger.MessagesController", false, loader);
            Object controller = instance(type, loader);
            if (controller == null) {
                LogX.i("MessagesController instance unavailable, skip history load");
                return false;
            }
            Method method = findLoadMessages(type);
            if (method == null) {
                LogX.i("no compatible loadMessages signature, skip history load");
                return false;
            }
            Class<?>[] params = method.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                if (p == long.class) {
                    args[i] = i == 0 ? dialogId : 0L;
                } else if (p == int.class) {
                    if (i == 3) args[i] = BATCH_SIZE;
                    else if (i == 4) args[i] = oldestMessageId;
                    else if (i == 8) args[i] = CLASS_GUID;
                    else if (i == 14) args[i] = loadIndex;
                    else args[i] = 0;
                } else if (p == boolean.class) {
                    args[i] = false; // fromCache=false / queryFromServer=false -> allow a network fetch
                } else {
                    args[i] = null;
                }
            }
            method.setAccessible(true);
            method.invoke(controller, args);
            return true;
        } catch (Throwable t) {
            LogX.w("history load request failed", t);
            return false;
        }
    }

    private static Object instance(Class<?> controllerClass, ClassLoader loader) {
        try {
            Method getInstance = controllerClass.getDeclaredMethod("getInstance", int.class);
            getInstance.setAccessible(true);
            return getInstance.invoke(null, selectedAccount(loader));
        } catch (Throwable t) {
            LogX.d("cannot get MessagesController instance: " + t);
            return null;
        }
    }

    private static int selectedAccount(ClassLoader loader) {
        try {
            Class<?> userConfig = Class.forName("org.telegram.messenger.UserConfig", false, loader);
            Field field = userConfig.getDeclaredField("selectedAccount");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Prefer the longest overload: it is the real implementation, not a delegating wrapper. */
    private static Method findLoadMessages(Class<?> type) {
        Method best = null;
        for (Method m : type.getDeclaredMethods()) {
            if (!"loadMessages".equals(m.getName())) continue;
            if (!Modifier.isPublic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 10) continue;
            if (p[0] != long.class || p[3] != int.class || p[4] != int.class) continue;
            if (best == null || p.length > best.getParameterCount()) best = m;
        }
        return best;
    }
}

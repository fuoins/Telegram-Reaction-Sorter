package io.github.fuoins.reactionsorter.core;

import java.lang.reflect.Field;

import io.github.fuoins.reactionsorter.util.Reflect;

/**
 * Telegram's own proguard rules contain:
 * <pre>
 *   -keep class org.telegram.tgnet.** { *; }
 *   -keep class org.telegram.messenger.* { *; }
 * </pre>
 * which means the whole TL data model and MessageObject keep their class, field and method
 * names across releases and forks. The UI layer (org.telegram.ui.ChatActivity etc.) is NOT
 * kept, so it is discovered structurally in {@link ChatResolver} instead.
 */
public final class TelegramClasses {

    public static final String MESSAGE_OBJECT = "org.telegram.messenger.MessageObject";
    public static final String TLRPC_MESSAGE = "org.telegram.tgnet.TLRPC$Message";
    public static final String TLRPC_MESSAGE_REACTIONS = "org.telegram.tgnet.TLRPC$TL_messageReactions";
    public static final String TLRPC_REACTION_COUNT = "org.telegram.tgnet.TLRPC$ReactionCount";
    public static final String TLRPC_REACTION = "org.telegram.tgnet.TLRPC$Reaction";

    private static volatile Class<?> cachedMessageObject;
    private static volatile ClassLoader cachedLoader;

    private TelegramClasses() {}

    public static Class<?> messageObject(ClassLoader loader) {
        ClassLoader cl = loader == null ? TelegramClasses.class.getClassLoader() : loader;
        Class<?> cached = cachedMessageObject;
        if (cached != null && cachedLoader == cl) return cached;
        try {
            Class<?> found = Class.forName(MESSAGE_OBJECT, false, cl);
            cachedMessageObject = found;
            cachedLoader = cl;
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Fast path by kept class name, with a structural fallback for heavily modified forks. */
    public static boolean isMessageObject(ClassLoader loader, Object candidate) {
        if (candidate == null) return false;
        Class<?> expected = messageObject(loader);
        if (expected != null) return expected.isInstance(candidate);
        return looksLikeMessageObject(candidate);
    }

    /**
     * Structural fallback: a MessageObject wraps a TL message in a field called
     * {@code messageOwner}, and that TL message owns a {@code reactions} field.
     */
    public static boolean looksLikeMessageObject(Object candidate) {
        if (candidate == null || Reflect.isPlatform(candidate.getClass())) return false;
        Object owner = Reflect.getByName(candidate, "messageOwner");
        if (owner == null) {
            for (Field f : Reflect.instanceFields(candidate.getClass())) {
                Class<?> ft = f.getType();
                if (ft.getName().startsWith("org.telegram.tgnet")) {
                    owner = Reflect.get(candidate, f);
                    if (owner != null) break;
                }
            }
        }
        if (owner == null) return false;
        if (Reflect.findField(owner.getClass(), "reactions") != null) return true;
        return Reflect.findField(owner.getClass(), "id", "message", "date") != null;
    }
}

package io.github.fuoins.reactionsorter.core;

import android.app.Activity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.fuoins.reactionsorter.util.LogX;
import io.github.fuoins.reactionsorter.util.Reflect;

/**
 * Finds the current chat screen without relying on obfuscated class or method names.
 *
 * <p>Telegram keeps its data model but obfuscates {@code org.telegram.ui.ChatActivity}.
 * Inspired by the "find hooks by behaviour" approach of Re: TeleVIP, this class walks the
 * Activity's object graph and looks for structural signatures instead:
 *
 * <ul>
 *   <li>a chat screen owns an {@code ArrayList} whose elements are
 *       {@code org.telegram.messenger.MessageObject} instances (its loaded messages);</li>
 *   <li>it also declares {@code scrollToMessageId(int, int, boolean, int, boolean, int, ...)}.
 *       R8 renames the method but cannot change its parameter types, so the signature is a
 *       stable fingerprint used for jump-to-message.</li>
 * </ul>
 *
 * The scan is bounded in depth and object count and only descends into Telegram's own
 * classes, so it stays cheap enough to run on a button tap.
 */
public final class ChatResolver {

    private static final int MAX_DEPTH = 5;
    private static final int MAX_OBJECTS = 2500;
    private static final int MAX_COLLECTION_ELEMENTS = 30;

    private ChatResolver() {}

    /** Resolved chat screen. Holds only weak references, never the message list itself. */
    public static final class ChatHandle {
        private final java.lang.ref.WeakReference<Object> fragmentRef;
        private final Field messagesField;
        private final Method scrollMethod;
        private final String fragmentClassName;

        ChatHandle(Object fragment, Field messagesField, Method scrollMethod) {
            this.fragmentRef = new java.lang.ref.WeakReference<>(fragment);
            this.messagesField = messagesField;
            this.scrollMethod = scrollMethod;
            this.fragmentClassName = fragment.getClass().getName();
        }

        /** Reads the live list each time, so newly loaded messages are always included. */
        public Collection<?> currentMessages() {
            Object fragment = fragmentRef.get();
            if (fragment == null) return Collections.emptyList();
            Object value = Reflect.get(fragment, messagesField);
            return value instanceof Collection ? (Collection<?>) value : Collections.emptyList();
        }

        public boolean canJump() {
            return fragmentRef.get() != null && scrollMethod != null;
        }

        public String fragmentClassName() {
            return fragmentClassName;
        }

        /**
         * Invokes the discovered scroll method with Telegram's own defaults:
         * {@code scrollToMessageId(id, 0, true, 0, true, 0)}.
         */
        public boolean jumpTo(int messageId) {
            Object fragment = fragmentRef.get();
            if (fragment == null || scrollMethod == null) return false;
            try {
                scrollMethod.setAccessible(true);
                scrollMethod.invoke(fragment, buildArgs(scrollMethod.getParameterTypes(), messageId));
                return true;
            } catch (Throwable t) {
                LogX.e("jumpTo(" + messageId + ") failed on " + fragmentClassName, t);
                return false;
            }
        }

        private static Object[] buildArgs(Class<?>[] params, int messageId) {
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                if (p == int.class) {
                    args[i] = i == 0 ? messageId : 0;
                } else if (p == boolean.class) {
                    args[i] = (i == 2 || i == 4);
                } else if (p == long.class) {
                    args[i] = 0L;
                } else if (p == float.class) {
                    args[i] = 0f;
                } else if (p == double.class) {
                    args[i] = 0d;
                } else if (p == short.class) {
                    args[i] = (short) 0;
                } else if (p == byte.class) {
                    args[i] = (byte) 0;
                } else if (p == char.class) {
                    args[i] = (char) 0;
                } else {
                    // Integer taskId, byte[] pollOptionId, Runnable inCaseLoading...
                    args[i] = null;
                }
            }
            return args;
        }
    }

    private static final class Node {
        final Object value;
        final int depth;

        Node(Object value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }

    private static final class Candidate {
        final Object owner;
        final Field field;
        final Collection<?> list;

        Candidate(Object owner, Field field, Collection<?> list) {
            this.owner = owner;
            this.field = field;
            this.list = list;
        }
    }

    /** Per-Activity cache of a successful resolution (invalidated on a new resolve). */
    private static final Map<Activity, ChatHandle> CACHE =
            Collections.synchronizedMap(new WeakHashMap<Activity, ChatHandle>());

    public static ChatHandle cached(Activity activity) {
        return activity == null ? null : CACHE.get(activity);
    }

    public static void invalidate(Activity activity) {
        if (activity != null) CACHE.remove(activity);
    }

    /**
     * Resolve the chat screen currently displayed by {@code activity}.
     * Returns {@code null} when the user is not inside a chat (e.g. on the chat list).
     */
    public static ChatHandle resolve(Activity activity, ClassLoader loader) {
        if (activity == null) return null;
        List<Candidate> candidates = scan(activity, loader);
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c.list.isEmpty()) continue;
            if (best == null || c.list.size() > best.list.size()) best = c;
        }
        if (best == null) {
            CACHE.remove(activity);
            return null;
        }
        Method scroll = findScrollMethod(best.owner.getClass());
        if (scroll == null) {
            LogX.i("chat found (" + best.owner.getClass().getName() + ") but no scroll method matched");
        }
        ChatHandle handle = new ChatHandle(best.owner, best.field, scroll);
        CACHE.put(activity, handle);
        return handle;
    }

    private static List<Candidate> scan(Object root, ClassLoader loader) {
        List<Candidate> out = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        queue.add(new Node(root, 0));
        int budget = MAX_OBJECTS;

        while (!queue.isEmpty() && budget-- > 0) {
            Node node = queue.poll();
            Object current = node.value;
            if (current == null || seen.containsKey(current) || node.depth > MAX_DEPTH) continue;
            seen.put(current, Boolean.TRUE);

            for (Field f : Reflect.instanceFields(current.getClass())) {
                Class<?> fieldType = f.getType();
                if (!interesting(fieldType)) continue;
                Object value = Reflect.get(current, f);
                if (value == null) continue;

                if (value instanceof Collection) {
                    Collection<?> collection = (Collection<?>) value;
                    Object first = firstNonNull(collection);
                    if (TelegramClasses.isMessageObject(loader, first)) {
                        out.add(new Candidate(current, f, collection));
                    }
                    if (node.depth < MAX_DEPTH) enqueueElements(collection, node.depth + 1, queue, loader);
                } else if (value instanceof Map) {
                    // Grouped messages (messagesByDays) live in maps. They are not used as a
                    // candidate because there is no stable field to read them from, but their
                    // elements are still walked so nested fragments stay reachable.
                    for (Object entry : ((Map<?, ?>) value).values()) {
                        if (entry instanceof Collection) {
                            if (node.depth < MAX_DEPTH) {
                                enqueueElements((Collection<?>) entry, node.depth + 1, queue, loader);
                            }
                        } else if (Reflect.shouldDescend(safeClass(entry))) {
                            queue.add(new Node(entry, node.depth + 1));
                        }
                    }
                } else if (Reflect.shouldDescend(safeClass(value)) && node.depth < MAX_DEPTH) {
                    queue.add(new Node(value, node.depth + 1));
                }
            }
        }
        return out;
    }

    private static boolean interesting(Class<?> type) {
        if (type.isPrimitive()) return false;
        if (type == String.class || type == Class.class || type == Object.class) return true;
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) return true;
        return Reflect.shouldDescend(type);
    }

    private static Class<?> safeClass(Object value) {
        return value == null ? null : value.getClass();
    }

    private static Object firstNonNull(Collection<?> collection) {
        int i = 0;
        for (Object element : collection) {
            if (element != null) return element;
            if (++i >= MAX_COLLECTION_ELEMENTS) break;
        }
        return null;
    }

    private static void enqueueElements(Collection<?> collection, int depth,
                                        ArrayDeque<Node> queue, ClassLoader loader) {
        int i = 0;
        for (Object element : collection) {
            if (element == null) continue;
            if (++i > MAX_COLLECTION_ELEMENTS) break;
            // MessageObjects are leaves; only descend into fragments / controllers.
            if (!TelegramClasses.isMessageObject(loader, element)
                    && Reflect.shouldDescend(element.getClass())) {
                queue.add(new Node(element, depth));
            }
        }
    }

    /**
     * Fingerprint of {@code scrollToMessageId}. First try the exact signature; if a fork
     * added/removed an overload, accept any void method starting with an int and carrying
     * at least 6 parameters with 2+ booleans.
     */
    private static Method findScrollMethod(Class<?> fragmentClass) {
        List<Method> matches = new ArrayList<>();
        List<Method> relaxed = new ArrayList<>();
        for (Method m : Reflect.allMethods(fragmentClass)) {
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 6 || p[0] != int.class) continue;
            boolean exact = p[1] == int.class && p[2] == boolean.class
                    && p[3] == int.class && p[4] == boolean.class && p[5] == int.class;
            if (exact) {
                matches.add(m);
            } else {
                int booleans = 0;
                for (Class<?> c : p) if (c == boolean.class) booleans++;
                if (booleans >= 2) relaxed.add(m);
            }
        }
        List<Method> chosen = matches.isEmpty() ? relaxed : matches;
        if (chosen.isEmpty()) return null;
        chosen.sort(Comparator.comparingInt(Method::getParameterCount));
        return chosen.get(0);
    }
}

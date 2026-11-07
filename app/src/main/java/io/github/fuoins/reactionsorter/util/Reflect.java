package io.github.fuoins.reactionsorter.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Defensive reflection helpers. The Telegram UI layer is obfuscated, so callers must never
 * assume a field/method name survives R8. All access here is null-safe and non-throwing.
 */
public final class Reflect {

    private Reflect() {}

    /** All non-static instance fields of {@code type} and its superclasses, up to (excluding) Object. */
    public static List<Field> instanceFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    out.add(f);
                }
            }
            current = current.getSuperclass();
        }
        return out;
    }

    public static Field findField(Class<?> type, String... candidateNames) {
        for (String name : candidateNames) {
            Class<?> current = type;
            while (current != null && current != Object.class) {
                try {
                    Field f = current.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static Object get(Object target, Field field) {
        if (target == null || field == null) return null;
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Read the first matching field by name. Returns {@code null} when nothing matches. */
    public static Object getByName(Object target, String... candidateNames) {
        if (target == null) return null;
        return get(target, findField(target.getClass(), candidateNames));
    }

    public static int getInt(Object target, String... candidateNames) {
        Object value = getByName(target, candidateNames);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    public static long getLong(Object target, String... candidateNames) {
        Object value = getByName(target, candidateNames);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    public static Long getLongOrNull(Object target, String... candidateNames) {
        Object value = getByName(target, candidateNames);
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    public static String getString(Object target, String... candidateNames) {
        Object value = getByName(target, candidateNames);
        return value instanceof CharSequence ? value.toString() : null;
    }

    /** First non-null String field on an object (used to read an unknown reaction type). */
    public static String firstStringField(Object target) {
        if (target == null) return null;
        for (Field f : instanceFields(target.getClass())) {
            if (f.getType() == String.class) {
                Object v = get(target, f);
                if (v instanceof String && !((String) v).isEmpty()) return (String) v;
            }
        }
        return null;
    }

    /** First long field, used to identify custom-emoji document ids. */
    public static Long firstLongField(Object target) {
        if (target == null) return null;
        for (Field f : instanceFields(target.getClass())) {
            if (f.getType() == long.class || f.getType() == Long.class) {
                Object v = get(target, f);
                if (v instanceof Number) return ((Number) v).longValue();
            }
        }
        return null;
    }

    public static List<Method> allMethods(Class<?> type) {
        List<Method> out = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            Collections.addAll(out, current.getDeclaredMethods());
            current = current.getSuperclass();
        }
        return out;
    }

    /** True for classes that are certainly framework/library classes, not Telegram's own code. */
    public static boolean isPlatform(Class<?> type) {
        if (type == null) return true;
        if (type.isPrimitive() || type.isArray() || type.isEnum() || type.isInterface()) return true;
        String n = type.getName();
        return n.startsWith("java.") || n.startsWith("javax.")
                || n.startsWith("kotlin.") || n.startsWith("kotlinx.")
                || n.startsWith("android.") || n.startsWith("androidx.")
                || n.startsWith("com.google.") || n.startsWith("com.android.")
                || n.startsWith("dalvik.") || n.startsWith("sun.")
                || n.startsWith("org.json.") || n.startsWith("org.jetbrains.");
    }

    /** Only descend into Telegram's own classes and Java collections; keeps the scan bounded. */
    public static boolean shouldDescend(Class<?> type) {
        if (type == null || isPlatform(type)) return false;
        String n = type.getName();
        return n.startsWith("org.telegram")
                || n.startsWith("tw.nekomimi") || n.startsWith("uz.unnarsx")
                || n.startsWith("nu.gpu") || n.startsWith("xyz.nextalone")
                || n.startsWith("momo.gram") || n.startsWith("org.forkgram")
                || n.startsWith("it.belloworld") || n.startsWith("ir.ilmili")
                || n.startsWith("ua.entaytion") || n.startsWith("org.telegram.plus")
                || n.startsWith("java.util.") || n.equals("java.lang.Object");
    }
}

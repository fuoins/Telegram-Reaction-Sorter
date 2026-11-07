package io.github.fuoins.reactionsorter.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.fuoins.reactionsorter.util.LogX;

/**
 * Decodes the {@code data} BLOB of Telegram's local message table into a live
 * {@code TLRPC.Message} object, using the host's own deserialiser.
 *
 * <p>Official storage code (MessagesStorage) writes the blob with
 * {@code message.serializeToStream(data)} and reads it back with:
 * <pre>
 *   TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
 * </pre>
 * i.e. the blob starts with the TL constructor id, followed by the object fields.
 * Delegating to the host's deserialiser means the module never has to reimplement the
 * TL schema: reactions, custom emoji ids and future layers are all handled by Telegram
 * itself. Class and field names under {@code org.telegram.tgnet.**} are kept by the
 * host's proguard rules, but the stream interface was renamed between releases
 * (AbstractSerializedData -&gt; InputSerializedData), so everything is located by
 * signature instead of by hard-coded type names.
 */
public final class TlMessageDecoder {

    private final Constructor<?> streamConstructor;
    private final Method readInt32;
    private final Method deserialize;

    private TlMessageDecoder(Constructor<?> streamConstructor, Method readInt32,
                             Method deserialize) {
        this.streamConstructor = streamConstructor;
        this.readInt32 = readInt32;
        this.deserialize = deserialize;
    }

    /** Returns {@code null} when the host does not expose a usable deserialiser. */
    public static TlMessageDecoder create(ClassLoader loader) {
        if (loader == null) return null;
        try {
            Class<?> streamClass = Class.forName("org.telegram.tgnet.SerializedData", false, loader);
            Constructor<?> constructor = findByteConstructor(streamClass);
            Method reader = findInt32Reader(streamClass);
            if (constructor == null || reader == null) {
                LogX.i("no SerializedData(byte[]) / readInt32(boolean) in host");
                return null;
            }
            Class<?> messageClass = Class.forName("org.telegram.tgnet.TLRPC$Message", false, loader);
            Method decoder = findDeserialize(messageClass, streamClass);
            if (decoder == null) {
                LogX.i("no Message.TLdeserialize(stream, int, boolean) in host");
                return null;
            }
            constructor.setAccessible(true);
            reader.setAccessible(true);
            decoder.setAccessible(true);
            return new TlMessageDecoder(constructor, reader, decoder);
        } catch (Throwable t) {
            LogX.w("cannot create TL message decoder", t);
            return null;
        }
    }

    /** Decode one blob. Throws on corrupt data; the caller skips that single row. */
    public Object decode(byte[] blob) throws Exception {
        if (blob == null || blob.length < 4) return null;
        Object stream = streamConstructor.newInstance((Object) blob);
        int constructor = (Integer) readInt32.invoke(stream, false);
        return deserialize.invoke(null, stream, constructor, false);
    }

    private static Constructor<?> findByteConstructor(Class<?> type) {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 1 && params[0] == byte[].class) return c;
        }
        return null;
    }

    private static Method findInt32Reader(Class<?> type) {
        for (Method m : type.getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if ("readInt32".equals(m.getName()) && params.length == 1
                    && params[0] == boolean.class && m.getReturnType() == int.class) {
                return m;
            }
        }
        return null;
    }

    private static Method findDeserialize(Class<?> messageClass, Class<?> streamClass) {
        for (Method m : messageClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (!"TLdeserialize".equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 3) continue;
            if (params[1] != int.class || params[2] != boolean.class) continue;
            if (!params[0].isAssignableFrom(streamClass)) continue;
            if (!messageClass.isAssignableFrom(m.getReturnType())) continue;
            return m;
        }
        return null;
    }
}

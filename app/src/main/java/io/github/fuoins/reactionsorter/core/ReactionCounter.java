package io.github.fuoins.reactionsorter.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.github.fuoins.reactionsorter.util.LogX;
import io.github.fuoins.reactionsorter.util.Reflect;

/**
 * Reads reaction counts out of a Telegram {@code MessageObject} through reflection.
 *
 * <p>Path (all names are kept by Telegram's proguard rules):
 * <pre>
 *   MessageObject.messageOwner            -> TLRPC.Message
 *   TLRPC.Message.reactions               -> TLRPC.TL_messageReactions
 *   TL_messageReactions.results           -> ArrayList&lt;TLRPC.ReactionCount&gt;
 *   ReactionCount.count / .reaction       -> int / TLRPC.Reaction
 * </pre>
 * Every step has a structural fallback, so a fork that tightens its keep rules still works.
 */
public final class ReactionCounter {

    private ReactionCounter() {}

    /** Returns {@code null} when the object is not a usable message. */
    public static MessageSnapshot extract(Object messageObject) {
        if (messageObject == null) return null;

        Object owner = Reflect.getByName(messageObject, "messageOwner");
        if (owner == null) owner = findOwnerStructurally(messageObject);
        if (owner == null) return null;

        int fallbackId = 0;
        Object viaMethod = callGetId(messageObject);
        if (viaMethod instanceof Number) fallbackId = ((Number) viaMethod).intValue();
        return fromOwner(owner, fallbackId, 0, 4096);
    }

    /**
     * Extract reactions from a raw {@code TLRPC.Message} (the type stored in the client's
     * local database), not from a {@code MessageObject} wrapper.
     *
     * @param fallbackId    message id from the database column, used if the TL field is empty
     * @param fallbackDate  same, for the date column
     * @param maxTextChars  cap the cached caption; 0 keeps the full text
     */
    public static MessageSnapshot fromOwner(Object owner, int fallbackId, int fallbackDate,
                                            int maxTextChars) {
        if (owner == null) return null;

        int id = Reflect.getInt(owner, "id");
        if (id == 0) id = fallbackId;
        int date = Reflect.getInt(owner, "date");
        if (date == 0) date = fallbackDate;
        long groupedId = Reflect.getLong(owner, "grouped_id");
        String text = Reflect.getString(owner, "message");
        if (text != null && maxTextChars > 0 && text.length() > maxTextChars) {
            text = text.substring(0, maxTextChars);
        }

        Object reactions = Reflect.getByName(owner, "reactions");
        if (reactions == null) return new MessageSnapshot(id, date, groupedId, text, 0, null);

        Object results = Reflect.getByName(reactions, "results");
        if (!(results instanceof Iterable)) results = findResultsStructurally(reactions);
        if (!(results instanceof Iterable)) {
            return new MessageSnapshot(id, date, groupedId, text, 0, null);
        }

        int total = 0;
        List<MessageSnapshot.ReactionEntry> breakdown = new ArrayList<>();
        try {
            int guard = 0;
            for (Object reactionCount : (Iterable<?>) results) {
                if (reactionCount == null || ++guard > 200) break;
                int count = Reflect.getInt(reactionCount, "count");
                if (count <= 0) continue;
                total += count;
                Object reaction = Reflect.getByName(reactionCount, "reaction");
                breakdown.add(new MessageSnapshot.ReactionEntry(describe(reaction), count));
            }
        } catch (Throwable t) {
            LogX.w("failed while iterating reaction results", t);
        }
        breakdown.sort(Comparator.comparingInt((MessageSnapshot.ReactionEntry e) -> -e.count));
        return new MessageSnapshot(id, date, groupedId, text, total, breakdown);
    }

    private static Object callGetId(Object messageObject) {
        try {
            return messageObject.getClass().getMethod("getId").invoke(messageObject);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object findOwnerStructurally(Object messageObject) {
        for (Field f : Reflect.instanceFields(messageObject.getClass())) {
            if (f.getType().getName().startsWith("org.telegram.tgnet")) {
                Object value = Reflect.get(messageObject, f);
                if (value != null && Reflect.findField(value.getClass(), "id", "message") != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** Find a collection field whose elements carry an int {@code count} field. */
    private static Object findResultsStructurally(Object reactions) {
        for (Field f : Reflect.instanceFields(reactions.getClass())) {
            if (!Collection.class.isAssignableFrom(f.getType())) continue;
            Object value = Reflect.get(reactions, f);
            if (!(value instanceof Iterable)) continue;
            for (Object element : (Iterable<?>) value) {
                if (element == null) continue;
                Field countField = Reflect.findField(element.getClass(), "count");
                if (countField != null && countField.getType() == int.class) return value;
                break;
            }
        }
        return Collections.emptyList();
    }

    /** Human-readable label for a TL reaction: emoji text, custom emoji id, or a placeholder. */
    private static String describe(Object reaction) {
        if (reaction == null) return "表情";
        String emoticon = Reflect.getString(reaction, "emoticon");
        if (emoticon != null && !emoticon.isEmpty()) return emoticon;
        Long documentId = Reflect.getLongOrNull(reaction, "document_id");
        if (documentId != null && documentId != 0L) return "[自定义#" + documentId + "]";
        String anyString = Reflect.firstStringField(reaction);
        if (anyString != null) return anyString;
        Long anyLong = Reflect.firstLongField(reaction);
        if (anyLong != null && anyLong != 0L) return "[贴纸#" + anyLong + "]";
        return "表情";
    }
}

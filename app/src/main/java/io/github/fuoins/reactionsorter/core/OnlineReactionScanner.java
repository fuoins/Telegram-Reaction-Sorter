package io.github.fuoins.reactionsorter.core;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.fuoins.reactionsorter.util.LogX;
import io.github.fuoins.reactionsorter.util.Reflect;

/**
 * Fetches reaction counts for <b>every message in a chat on the server</b> without loading
 * message content.
 *
 * <h3>Why this works</h3>
 * {@code messages.getHistory} is a read-only TL RPC. Its response ({@code messages.Messages})
 * contains message <em>metadata</em>: id, date, text, and the full {@code TL_messageReactions}
 * block. Photo / video / file <em>bytes</em> are never part of this response — they are only
 * downloaded by a separate {@code upload.getFile} / CDN request that the client issues when a
 * message is actually rendered on screen. That is exactly why reactions are visible while
 * thumbnails are still blank.
 *
 * <p>This scanner therefore pages through the whole history via the host's own
 * {@code ConnectionsManager.sendRequest} and reads only {@code id / date / reactions}
 * (plus a 64-character text preview for the ranking list) from each {@code TLRPC.Message}.
 * The text is already part of the metadata response; no extra fetch is issued for it.
 * It does <b>not</b>:
 * <ul>
 *   <li>write anything to {@code cache4.db},</li>
 *   <li>post NotificationCenter updates or insert messages into the chat UI,</li>
 *   <li>construct {@code MessageObject}s or touch any media / FileLoader path.</li>
 * </ul>
 * The TL response objects are discarded as soon as the tiny {@link MessageSnapshot}s are
 * extracted, so nothing is persisted and no chat content leaves the process.
 *
 * <h3>Why it is fast</h3>
 * The TL request has {@code add_offset}: with {@code offset_id = 0},
 * {@code add_offset = k * 100} addresses page {@code k} directly. Pages are independent, so
 * {@code concurrency} requests are in flight at once instead of one page at a time. A fixed
 * {@code max_id} anchor (the newest id seen in the probe request) keeps the page boundaries
 * stable even if new messages arrive during the scan.
 *
 * <p>All host classes used here are in {@code org.telegram.tgnet.**} /
 * {@code org.telegram.messenger.*}, which Telegram's own proguard rules keep unobfuscated,
 * so names are stable across versions. The callback is a {@link Proxy} implementing the
 * host's {@code RequestDelegate} interface — no compile-time dependency on host classes.
 */
public final class OnlineReactionScanner {

    private static final int PAGE_SIZE = OnlineScanPlan.PAGE_SIZE;
    /** Per-wave network timeout. A stalled wave never blocks the panel forever. */
    private static final long WAVE_TIMEOUT_MS = 12_000L;
    /** Absolute ceiling on waves, independent of the message cap. */
    private static final int MAX_WAVES = 220;

    private OnlineReactionScanner() {}

    public interface Cancel {
        boolean isCancelled();
    }

    public interface Progress {
        /**
         * Delivered after every completed wave, off the UI thread.
         *
         * @param incremental snapshots discovered since the previous callback; the panel can
         *                   merge them immediately so results appear progressively
         * @param fetched    total unique messages collected so far
         * @param serverTotal total messages reported by the server, 0 when unknown
         * @param reacted    number of collected messages that have reactions
         * @return {@code false} to abort the scan
         */
        boolean onProgress(List<MessageSnapshot> incremental, int fetched,
                           int serverTotal, int reacted, String hint);
    }

    public static final class Result {
        public final List<MessageSnapshot> snapshots;
        public final int fetched;
        public final int serverTotal;
        public final int reactedMessages;
        public final String detail;
        public final String error;
        public final boolean partial;
        public final boolean cancelled;
        public final boolean floodWait;

        Result(List<MessageSnapshot> snapshots, int fetched, int serverTotal,
               int reactedMessages, String detail, String error, boolean partial,
               boolean cancelled, boolean floodWait) {
            this.snapshots = snapshots;
            this.fetched = fetched;
            this.serverTotal = serverTotal;
            this.reactedMessages = reactedMessages;
            this.detail = detail;
            this.error = error;
            this.partial = partial;
            this.cancelled = cancelled;
            this.floodWait = floodWait;
        }

        /** A partial scan still yields usable data; only a hard failure has an error. */
        public boolean success() {
            return error == null;
        }
    }

    // ------------------------------------------------------------------ host ---

    /** Resolved reflection handles for one scan. */
    private static final class Host {
        ClassLoader loader;
        Class<?> requestClass;
        Class<?> delegateInterface;
        Object connections;
        Method sendRequest;
        Object inputPeer;
        Field fPeer, fOffsetId, fOffsetDate, fAddOffset, fLimit, fMaxId, fMinId, fHash;

        static Host create(ClassLoader loader, long dialogId) {
            Host h = new Host();
            h.loader = loader;
            try {
                h.requestClass = Class.forName(
                        "org.telegram.tgnet.TLRPC$TL_messages_getHistory", true, loader);
                h.delegateInterface = Class.forName(
                        "org.telegram.tgnet.RequestDelegate", true, loader);
                if (!h.delegateInterface.isInterface()) return null;
            } catch (Throwable t) {
                LogX.i("online scan: TL classes unavailable: " + t);
                return null;
            }

            int account = selectedAccount(loader);
            try {
                Class<?> cm = Class.forName("org.telegram.tgnet.ConnectionsManager", true, loader);
                Method getInstance = findStatic(cm, "getInstance", int.class);
                if (getInstance == null) return null;
                h.connections = getInstance.invoke(null, account);
            } catch (Throwable t) {
                LogX.i("online scan: ConnectionsManager unavailable: " + t);
                return null;
            }
            if (h.connections == null) return null;

            h.sendRequest = findSendRequest(h.connections.getClass(),
                    h.requestClass, h.delegateInterface);
            if (h.sendRequest == null) {
                LogX.i("online scan: no compatible sendRequest signature");
                return null;
            }

            h.inputPeer = resolveInputPeer(loader, account, dialogId);
            if (h.inputPeer == null) {
                LogX.i("online scan: cannot build InputPeer for dialog " + dialogId);
                return null;
            }

            h.fPeer = field(h.requestClass, "peer");
            h.fOffsetId = field(h.requestClass, "offset_id");
            h.fOffsetDate = field(h.requestClass, "offset_date");
            h.fAddOffset = field(h.requestClass, "add_offset");
            h.fLimit = field(h.requestClass, "limit");
            h.fMaxId = field(h.requestClass, "max_id");
            h.fMinId = field(h.requestClass, "min_id");
            h.fHash = field(h.requestClass, "hash");
            return h.fPeer != null && h.fLimit != null && h.fAddOffset != null ? h : null;
        }

        Object newRequest(int addOffset, int maxId) throws Exception {
            Object req = requestClass.getDeclaredConstructor().newInstance();
            set(req, fPeer, inputPeer);
            set(req, fOffsetId, 0);
            set(req, fOffsetDate, 0);
            set(req, fAddOffset, addOffset);
            set(req, fLimit, PAGE_SIZE);
            set(req, fMaxId, maxId);
            set(req, fMinId, 0);
            // hash must stay 0 (older layers have no such field): server would otherwise
            // answer "messagesNotModified" and we would see no messages at all.
            set(req, fHash, 0L);
            return req;
        }

        void send(Object req, Object callback) throws Exception {
            Class<?>[] p = sendRequest.getParameterTypes();
            Object[] args = new Object[p.length];
            args[0] = req;
            args[1] = callback;
            for (int i = 2; i < p.length; i++) {
                args[i] = defaultValue(p[i]);
            }
            sendRequest.setAccessible(true);
            sendRequest.invoke(connections, args);
        }
    }

    // ------------------------------------------------------------------ scan ---

    public static Result scan(ClassLoader loader, long dialogId, int maxMessages,
                              int concurrency, Cancel cancel, Progress progress) {
        if (loader == null || dialogId == 0L) {
            return failure("未识别当前聊天，无法向服务器请求历史消息");
        }
        Host host = Host.create(loader, dialogId);
        if (host == null) {
            return failure("当前客户端未提供可用的在线历史接口，已回退本地消息库");
        }

        final int cap = OnlineScanPlan.clampMaxMessages(maxMessages);
        final int waveSize = OnlineScanPlan.clampConcurrency(concurrency);
        final Map<Integer, MessageSnapshot> collected = new ConcurrentHashMap<>();

        final AtomicInteger serverTotal = new AtomicInteger(0);
        final AtomicInteger anchorId = new AtomicInteger(0);
        final AtomicBoolean exhausted = new AtomicBoolean(false);
        final AtomicBoolean flood = new AtomicBoolean(false);
        final AtomicInteger reacted = new AtomicInteger(0);
        boolean cancelled = false;
        boolean timedOut = false;

        try {
            // ---- probe: one page, no max_id yet. Gives us the server-reported total and
            //      the newest message id used to anchor all later pages. -----------------
            Wave probe = issueWave(host, singleOffset(0), 0, collected, serverTotal,
                    anchorId, exhausted, flood, reacted);
            if (!probe.allResponded) {
                LogX.i("online scan: probe timed out or failed");
                return partialOrFailure(collected, serverTotal.get(), reacted.get(),
                        "服务器没有响应（超时或网络不可用）", false, flood.get());
            }
            if (flood.get()) {
                return partialOrFailure(collected, serverTotal.get(), reacted.get(),
                        "服务器触发限流 FLOOD_WAIT，请稍后再试", false, true);
            }
            if (anchorId.get() == 0) {
                return partialOrFailure(collected, serverTotal.get(), reacted.get(),
                        "服务器未返回任何消息（可能无权限查看历史）", false, flood.get());
            }
            if (isCancelled(cancel)) {
                cancelled = true;
            }
            if (!cancelled && !report(progress, probe.added, collected.size(),
                    serverTotal.get(), reacted.get())) {
                cancelled = true;
            }

            int maxId = anchorId.get() + 1;
            List<Integer> offsets = OnlineScanPlan.offsets(
                    serverTotal.get(), cap, PAGE_SIZE);

            int waveIndex = 0;
            for (int from = 0; from < offsets.size() && waveIndex < MAX_WAVES; waveIndex++) {
                if (isCancelled(cancel)) { cancelled = true; break; }
                if (exhausted.get()) break;
                if (serverTotal.get() > 0 && collected.size() >= serverTotal.get()) break;

                int to = Math.min(offsets.size(), from + waveSize);
                List<Integer> waveOffsets = new ArrayList<>(offsets.subList(from, to));
                from = to;

                Wave w = issueWave(host, waveOffsets, maxId, collected, serverTotal,
                        anchorId, exhausted, flood, reacted);
                if (!w.allResponded) {
                    timedOut = true;
                    LogX.i("online scan: wave timed out, returning partial results");
                    break;
                }
                if (flood.get()) {
                    LogX.i("online scan: FLOOD_WAIT, returning partial results");
                    break;
                }
                if (!report(progress, w.added, collected.size(),
                        serverTotal.get(), reacted.get())) {
                    cancelled = true;
                    break;
                }
                if (w.allEmpty) break;
            }
        } catch (Throwable t) {
            LogX.e("online reaction scan crashed", t);
            return failure("在线扫描异常：" + t.getClass().getSimpleName());
        }

        List<MessageSnapshot> out = new ArrayList<>(collected.values());
        boolean partial = cancelled || timedOut || flood.get()
                || (serverTotal.get() > 0 && out.size() < serverTotal.get());

        StringBuilder detail = new StringBuilder();
        detail.append("服务器全量：已获取 ").append(out.size()).append(" 条消息");
        if (serverTotal.get() > 0) detail.append(" / 共 ").append(serverTotal.get()).append(" 条");
        detail.append("，其中 ").append(reacted.get()).append(" 条有回应");
        detail.append("；只读取回应元数据，未下载图片/视频/文件");
        if (cancelled) detail.append("；已手动停止");
        else if (flood.get()) detail.append("；触发服务器限流，结果不完整，请稍后重扫");
        else if (timedOut) detail.append("；部分请求超时，结果不完整");
        else if (partial) detail.append("；服务器计数与实收不一致，可能含已删除/服务消息");

        return new Result(out, out.size(), serverTotal.get(), reacted.get(),
                detail.toString(), null, partial, cancelled, flood.get());
    }

    // ------------------------------------------------------------------ waves ---

    private static final class Wave {
        boolean allResponded;
        boolean allEmpty = true;
        /** Snapshots newly added by this wave, handed to the panel for progressive refresh. */
        final List<MessageSnapshot> added = new ArrayList<>();
    }

    private static final class One {
        final int offset;
        final AtomicBoolean settled = new AtomicBoolean(false);
        volatile Object response;
        volatile Object error;
        volatile boolean responded;

        One(int offset) { this.offset = offset; }
    }

    private static Wave issueWave(Host host, List<Integer> offsets, int maxId,
                                  Map<Integer, MessageSnapshot> collected,
                                  AtomicInteger serverTotal, AtomicInteger anchorId,
                                  AtomicBoolean exhausted, AtomicBoolean flood,
                                  AtomicInteger reacted) {
        Wave wave = new Wave();
        if (offsets.isEmpty()) { wave.allResponded = true; return wave; }

        List<One> ones = new ArrayList<>();
        final CountDownLatch waveLatch = new CountDownLatch(offsets.size());

        for (int offset : offsets) {
            final One one = new One(offset);
            ones.add(one);
            try {
                Object req = host.newRequest(offset, maxId);
                InvocationHandler handler = (proxy, method, args) -> {
                    String name = method.getName();
                    if ("run".equals(name) && args != null && args.length >= 2
                            && one.settled.compareAndSet(false, true)) {
                        one.response = args[0];
                        one.error = args[1];
                        one.responded = true;
                        waveLatch.countDown();
                        return null;
                    }
                    // A host may call Object methods on the callback (logging, map keys).
                    if ("toString".equals(name)) return "OnlineReactionScanner$Callback";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);
                    return defaultValue(method.getReturnType());
                };
                Object callback = Proxy.newProxyInstance(host.loader,
                        new Class<?>[]{host.delegateInterface}, handler);
                host.send(req, callback);
            } catch (Throwable t) {
                LogX.w("online scan: failed to dispatch offset " + offset, t);
                if (one.settled.compareAndSet(false, true)) waveLatch.countDown();
            }
        }

        try {
            waveLatch.await(WAVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        wave.allResponded = true;
        for (One one : ones) {
            if (!one.responded) {
                wave.allResponded = false;
                continue;
            }
            if (one.error != null) {
                String text = Reflect.getString(one.error, "text");
                if (OnlineScanPlan.isFloodWait(text)) flood.set(true);
                LogX.i("online scan: error at offset " + one.offset + ": " + text);
                continue;
            }
            Object response = one.response;
            if (response == null) continue;

            int count = Reflect.getInt(response, "count");
            if (count > serverTotal.get()) serverTotal.set(count);

            Object messagesObj = Reflect.getByName(response, "messages");
            if (!(messagesObj instanceof Collection)) continue;
            Collection<?> messages = (Collection<?>) messagesObj;
            if (messages.isEmpty()) {
                // Requesting a page past the end returns an empty array — the reliable
                // "history exhausted" signal for offset_id/add_offset paging.
                exhausted.set(true);
                continue;
            }
            wave.allEmpty = false;
            // Small chats are answered with messages.messages (no `count` field), so
            // serverTotal would stay 0 and the planner would schedule dozens of empty
            // pages. A short page is the last page: infer the total from its absolute
            // position so both the planner and the UI coverage label stop correctly.
            // A non-empty short page is NOT treated as exhausted, so a mid-history short
            // page (rare, e.g. filtered service messages) can never truncate the scan.
            if (messages.size() < PAGE_SIZE && serverTotal.get() <= 0) {
                raiseMax(serverTotal, one.offset + messages.size());
            }
            for (Object message : messages) {
                if (message == null) continue;
                try {
                    int id = Reflect.getInt(message, "id");
                    raiseMax(anchorId, id);
                    MessageSnapshot snapshot = ReactionCounter.fromOwner(message, id, 0, 64);
                    if (snapshot == null || snapshot.messageId == 0) continue;
                    MessageSnapshot prev = collected.putIfAbsent(snapshot.messageId, snapshot);
                    if (prev == null) {
                        wave.added.add(snapshot);
                        if (snapshot.totalReactions > 0) reacted.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                    // A single unsupported message variant must not abort the wave.
                }
            }
        }
        return wave;
    }

    private static void raiseMax(AtomicInteger target, int value) {
        int current;
        while (value > (current = target.get())) {
            if (target.compareAndSet(current, value)) return;
        }
    }

    private static boolean report(Progress progress, List<MessageSnapshot> incremental,
                                  int fetched, int total, int reacted) {
        if (progress == null) return true;
        String hint = total > 0
                ? "正在读取服务器历史消息… 已取 " + fetched + " / 共 " + total + " 条（不下载图片视频）"
                : "正在读取服务器历史消息… 已取 " + fetched + " 条（不下载图片视频）";
        try {
            return progress.onProgress(incremental, fetched, total, reacted, hint);
        } catch (Throwable t) {
            LogX.w("online scan progress callback failed", t);
            return true;
        }
    }

    private static Result partialOrFailure(Map<Integer, MessageSnapshot> collected, int total,
                                           int reacted, String reason, boolean cancelled,
                                           boolean flood) {
        if (collected.isEmpty()) {
            return new Result(new ArrayList<>(), 0, total, 0, reason, reason, true, cancelled, flood);
        }
        List<MessageSnapshot> out = new ArrayList<>(collected.values());
        String detail = "只取到 " + out.size() + " 条消息：" + reason + "；已并入本地库结果";
        return new Result(out, out.size(), total, reacted, detail, null, true, cancelled, flood);
    }

    private static Result failure(String reason) {
        return new Result(new ArrayList<>(), 0, 0, 0, reason, reason, true, false, false);
    }

    private static boolean isCancelled(Cancel cancel) {
        try {
            return cancel != null && cancel.isCancelled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<Integer> singleOffset(int value) {
        List<Integer> out = new ArrayList<>(1);
        out.add(value);
        return out;
    }

    // -------------------------------------------------------------- reflection ---

    private static Object resolveInputPeer(ClassLoader loader, int account, long dialogId) {
        try {
            Class<?> mc = Class.forName("org.telegram.messenger.MessagesController", true, loader);
            Method getInstance = findStatic(mc, "getInstance", int.class);
            if (getInstance == null) return null;
            Object controller = getInstance.invoke(null, account);
            if (controller == null) return null;
            for (Method m : Reflect.allMethods(mc)) {
                if (!"getInputPeer".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != long.class) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                Object peer = m.invoke(controller, dialogId);
                if (peer != null) return peer;
            }
        } catch (Throwable t) {
            LogX.i("online scan: getInputPeer failed: " + t);
        }
        return null;
    }

    private static Method findStatic(Class<?> type, String name, Class<?>... params) {
        try {
            Method m = type.getDeclaredMethod(name, params);
            if (Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                return m;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Prefer the shortest {@code sendRequest} overload whose first parameter accepts the
     * TL request and whose second parameter is the RequestDelegate interface.
     */
    private static Method findSendRequest(Class<?> type, Class<?> reqClass, Class<?> delegate) {
        Method best = null;
        for (Method m : Reflect.allMethods(type)) {
            if (!"sendRequest".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 2) continue;
            if (!p[0].isAssignableFrom(reqClass)) continue;
            if (p[1] != delegate) continue;
            if (best == null || p.length < best.getParameterCount()) best = m;
        }
        return best;
    }

    private static Field field(Class<?> type, String name) {
        Field f = Reflect.findField(type, name);
        if (f != null) f.setAccessible(true);
        return f;
    }

    private static void set(Object target, Field f, Object value) {
        if (f == null) return;
        try {
            f.set(target, value);
        } catch (Throwable t) {
            LogX.d("cannot set field " + f.getName() + ": " + t);
        }
    }

    private static int selectedAccount(ClassLoader loader) {
        try {
            Class<?> userConfig = Class.forName("org.telegram.messenger.UserConfig", true, loader);
            Field f = userConfig.getDeclaredField("selectedAccount");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return 0;
    }
}

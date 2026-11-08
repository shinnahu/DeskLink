package com.desklink.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.desklink.core.Messages.CursorShape;
import com.desklink.core.Messages.HostInfo;
import com.desklink.core.Messages.VideoFormat;

/**
 * An authenticated session with a DeskLink host: reader/writer/ping threads, input, clipboard and file transfer.
 * Listener callbacks run on the reader thread (or on transfer threads for outgoing files).
 */
public final class HostConnection implements Closeable {

    public interface Listener {
        default void onHostInfo(HostInfo info) {}
        default void onVideoFormat(VideoFormat format) {}
        /** {@code data[off..off+len)} is Annex-B; the array is not reused by the connection. */
        default void onVideoFrame(int frameId, boolean keyframe, long timestampUs, byte[] data, int off, int len) {}
        default void onCursorShape(CursorShape shape) {}
        default void onCursorState(int x, int y, boolean visible, int shapeId) {}
        default void onHostStatus(int flags, String message) {}
        default void onClipboard(String text) {}
        default void onNotice(int level, String text) {}
        default void onRtt(int rttMs) {}
        default void onFileRequestNone() {}
        /** Return a sink to accept a file from the PC, or null to reject it. */
        default IncomingFileSink onIncomingFile(int id, String name, long size, String mime) { return null; }
        default void onIncomingFileProgress(int id, long received, long size) {}
        default void onIncomingFileDone(int id, int status, String error) {}
        default void onClosed(CloseInfo info) {}
    }

    public interface IncomingFileSink {
        void write(byte[] b, int off, int len) throws IOException;
        void finish() throws IOException;
        void abort();
    }

    public interface SendListener {
        void onProgress(long acked, long size);
        void onDone(int status, String info);
    }

    public static final class CloseInfo {
        public final boolean byPeer;
        public final int byeReason;
        public final String message;
        public final Throwable error;
        public final boolean local;
        public final boolean timeout;

        CloseInfo(boolean byPeer, int byeReason, String message, Throwable error, boolean local, boolean timeout) {
            this.byPeer = byPeer;
            this.byeReason = byeReason;
            this.message = message == null ? "" : message;
            this.error = error;
            this.local = local;
            this.timeout = timeout;
        }

        @Override
        public String toString() {
            if (local) return "closed locally";
            if (byPeer) return "PC said bye (" + Protocol.byeName(byeReason) + ")" + (message.isEmpty() ? "" : ": " + message);
            if (timeout) return "timeout: " + message;
            return "error: " + (error == null ? message : error.toString());
        }
    }

    private static final int KIND_NORMAL = 0, KIND_MOVE = 1, KIND_WHEEL = 2, KIND_ACK = 3, KIND_BYE = 4, KIND_REL = 5;

    private static final class Out {
        byte[] data;
        boolean taken;
        final int kind;
        int a, b; // wheel accumulators

        Out(byte[] data, int kind) {
            this.data = data;
            this.kind = kind;
        }
    }

    public static final int READ_TIMEOUT_MS = 5000;

    private final SecureChannel ch;
    private final Listener listener;
    private volatile HostInfo hostInfo;
    private final Object qLock = new Object();
    private final ArrayDeque<Out> high = new ArrayDeque<>();
    private final ArrayDeque<Out> low = new ArrayDeque<>();
    private Out lastMove, lastWheel, lastAck, lastRel;
    private volatile boolean closed;
    private final AtomicBoolean closeReported = new AtomicBoolean();
    private volatile long lastRecvNanos = System.nanoTime();
    private volatile long lastRecvWallMs = System.currentTimeMillis();
    private volatile int rttMs = -1;
    private final AtomicLong bytesIn = new AtomicLong();
    private int nextFileId = 1;
    private final Map<Integer, Outgoing> outgoing = new ConcurrentHashMap<>();
    private final Map<Integer, Incoming> incoming = new ConcurrentHashMap<>();

    private HostConnection(SecureChannel ch, HostInfo info, Listener listener) {
        this.ch = ch;
        this.hostInfo = info;
        this.listener = listener == null ? new Listener() {} : listener;
    }

    // =================================================================== connect / auth

    /** Connects to the best address, verifies the pinned identity, authenticates and starts the session threads. */
    public static HostConnection connect(PairedHost host, String deviceName, Listener listener, int timeoutMs) throws IOException {
        SecureChannel ch = Connector.open(host.candidates(), host.port, host.fingerprint, timeoutMs, 250, host.knockKey());
        try {
            HostInfo info = authenticate(ch, host, deviceName);
            return start(ch, info, listener);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    /** Starts the session threads on an authenticated channel. */
    public static HostConnection start(SecureChannel ch, HostInfo info, Listener listener) {
        HostConnection c = new HostConnection(ch, info, listener);
        c.startThreads();
        return c;
    }

    /** Sends AUTH, waits for AUTH_RESULT and HOST_INFO. Throws {@link AuthException} on a non-OK status. */
    public static HostInfo authenticate(SecureChannel ch, PairedHost host, String deviceName) throws IOException {
        ch.setReadTimeout(15000);
        ch.send(new ByteWriter(96).u8(Protocol.AUTH).bytes(fixed(host.deviceId, 16)).bytes(fixed(host.token, 32))
                .str(deviceName).u16(Protocol.CLIENT_VERSION).toByteArray());
        byte[] m = expect(ch, Protocol.AUTH_RESULT);
        ByteReader r = new ByteReader(m, 1, m.length - 1);
        int status = r.u8();
        String msg = r.remaining() >= 2 ? r.str() : "";
        if (status != Protocol.ST_OK) throw new AuthException(status, msg);
        m = expect(ch, Protocol.HOST_INFO);
        return HostInfo.parse(new ByteReader(m, 1, m.length - 1));
    }

    /**
     * Reads records until one of {@code types} arrives. Answers PINGs, ignores anything else,
     * and turns BYE into an IOException.
     */
    public static byte[] expect(SecureChannel ch, int... types) throws IOException {
        while (true) {
            byte[] m = ch.receive();
            if (m.length == 0) continue;
            int t = m[0] & 0xFF;
            for (int want : types) if (t == want) return m;
            if (t == Protocol.PING && m.length >= 9) {
                byte[] pong = m.clone();
                pong[0] = (byte) Protocol.PONG;
                ch.send(pong);
            } else if (t == Protocol.BYE) {
                ByteReader r = new ByteReader(m, 1, m.length - 1);
                int reason = r.remaining() >= 1 ? r.u8() : 0;
                String msg = r.remaining() >= 2 ? r.str() : "";
                throw new IOException("The PC closed the connection (" + Protocol.byeName(reason) + ")" + (msg.isEmpty() ? "" : ": " + msg));
            }
        }
    }

    static byte[] fixed(byte[] b, int n) {
        byte[] out = new byte[n];
        if (b != null) System.arraycopy(b, 0, out, 0, Math.min(n, b.length));
        return out;
    }

    // =================================================================== threads

    private void startThreads() {
        lastRecvNanos = System.nanoTime();
        Thread reader = new Thread(this::readLoop, "dl-reader");
        Thread writer = new Thread(this::writeLoop, "dl-writer");
        Thread pinger = new Thread(this::pingLoop, "dl-ping");
        reader.setDaemon(true);
        writer.setDaemon(true);
        pinger.setDaemon(true);
        reader.start();
        writer.start();
        pinger.start();
    }

    private void readLoop() {
        try {
            ch.setReadTimeout(READ_TIMEOUT_MS);
            while (!closed) {
                byte[] m = ch.receive();
                lastRecvNanos = System.nanoTime();
                lastRecvWallMs = System.currentTimeMillis();
                bytesIn.addAndGet(m.length + 20L);
                if (m.length == 0) continue;
                dispatch(m);
            }
        } catch (SocketTimeoutException e) {
            finish(new CloseInfo(false, 0, "No data from the PC for " + (READ_TIMEOUT_MS / 1000) + " s", e, false, true));
        } catch (IOException e) {
            finish(new CloseInfo(false, 0, e.getMessage(), e, false, false));
        } catch (RuntimeException e) {
            finish(new CloseInfo(false, 0, "Protocol error: " + e, e, false, false));
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                Out m;
                synchronized (qLock) {
                    while (high.isEmpty() && low.isEmpty() && !closed) qLock.wait();
                    if (closed) return;
                    m = !high.isEmpty() ? high.pollFirst() : low.pollFirst();
                    m.taken = true;
                }
                ch.send(m.data);
                if (m.kind == KIND_BYE) {
                    finish(new CloseInfo(false, 0, "disconnected", null, true, false));
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            finish(new CloseInfo(false, 0, e.getMessage(), e, false, false));
        }
    }

    private void pingLoop() {
        while (!closed) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            if (closed) return;
            enqueue(new ByteWriter(9).u8(Protocol.PING).u64(nowMs()).toByteArray(), false);
        }
    }

    private static long nowMs() { return System.nanoTime() / 1_000_000L; }

    // =================================================================== dispatch

    private void dispatch(byte[] m) throws IOException {
        int type = m[0] & 0xFF;
        ByteReader r = new ByteReader(m, 1, m.length - 1);
        switch (type) {
            case Protocol.PING: {
                byte[] pong = m.clone();
                pong[0] = (byte) Protocol.PONG;
                enqueue(pong, false);
                break;
            }
            case Protocol.PONG: {
                long t = r.u64();
                int rtt = (int) Math.max(0, nowMs() - t);
                rttMs = rtt;
                listener.onRtt(rtt);
                break;
            }
            case Protocol.HOST_INFO: {
                HostInfo info = HostInfo.parse(r);
                hostInfo = info;
                listener.onHostInfo(info);
                break;
            }
            case Protocol.VIDEO_FORMAT:
                listener.onVideoFormat(VideoFormat.parse(r));
                break;
            case Protocol.VIDEO_FRAME: {
                int id = r.i32();
                int flags = r.u8();
                long ts = r.u64();
                int off = r.position();
                listener.onVideoFrame(id, (flags & 1) != 0, ts, m, off, m.length - off);
                break;
            }
            case Protocol.CURSOR_SHAPE:
                listener.onCursorShape(CursorShape.parse(r));
                break;
            case Protocol.CURSOR_STATE: {
                int x = r.i32();
                int y = r.i32();
                boolean visible = r.u8() != 0;
                int shape = r.i32();
                listener.onCursorState(x, y, visible, shape);
                break;
            }
            case Protocol.HOST_STATUS: {
                int flags = r.u8();
                String msg = r.remaining() >= 2 ? r.str() : "";
                listener.onHostStatus(flags, msg);
                break;
            }
            case Protocol.CLIPBOARD:
                listener.onClipboard(r.tailUtf8());
                break;
            case Protocol.NOTICE: {
                int level = r.u8();
                listener.onNotice(level, r.str());
                break;
            }
            case Protocol.FILE_BEGIN: onFileBegin(r); break;
            case Protocol.FILE_DATA: onFileData(m, r); break;
            case Protocol.FILE_END: onFileEnd(r.i32()); break;
            case Protocol.FILE_CANCEL: onFileCancel(r.i32()); break;
            case Protocol.FILE_PROGRESS: {
                Outgoing o = outgoing.get(r.i32());
                if (o != null) o.acked(r.u64());
                break;
            }
            case Protocol.FILE_DONE: {
                int id = r.i32();
                int status = r.u8();
                String info = r.remaining() >= 2 ? r.str() : "";
                Outgoing o = outgoing.get(id);
                if (o != null) o.peerDone(status, info);
                break;
            }
            case Protocol.FILE_REQUEST_NONE:
                listener.onFileRequestNone();
                break;
            case Protocol.BYE: {
                int reason = r.remaining() >= 1 ? r.u8() : 0;
                String msg = r.remaining() >= 2 ? r.str() : "";
                finish(new CloseInfo(true, reason, msg, null, false, false));
                break;
            }
            default:
                // unknown / not for this phase: ignore (forward compatible)
                break;
        }
    }

    // =================================================================== incoming files

    private static final class Incoming {
        final int id;
        final long size;
        final IncomingFileSink sink;
        long received;
        long lastProgress;

        Incoming(int id, long size, IncomingFileSink sink) {
            this.id = id;
            this.size = size;
            this.sink = sink;
        }
    }

    private void onFileBegin(ByteReader r) {
        int id = r.i32();
        long size = r.u64();
        String name = r.str();
        String mime = r.remaining() >= 2 ? r.str() : "";
        IncomingFileSink sink = null;
        try {
            sink = listener.onIncomingFile(id, name, size, mime);
        } catch (RuntimeException ignored) {
        }
        if (sink == null) {
            enqueue(new ByteWriter(5).u8(Protocol.FILE_CANCEL).i32(id).toByteArray(), false);
            return;
        }
        incoming.put(id, new Incoming(id, size, sink));
    }

    private void onFileData(byte[] m, ByteReader r) {
        int id = r.i32();
        Incoming in = incoming.get(id);
        if (in == null) return;
        int off = r.position();
        int n = m.length - off;
        try {
            in.sink.write(m, off, n);
            in.received += n;
            if (in.received - in.lastProgress >= Protocol.FILE_PROGRESS_EVERY) {
                in.lastProgress = in.received;
                sendFileProgress(id, in.received);
                listener.onIncomingFileProgress(id, in.received, in.size);
            }
        } catch (IOException e) {
            incoming.remove(id);
            in.sink.abort();
            enqueue(new ByteWriter(5).u8(Protocol.FILE_CANCEL).i32(id).toByteArray(), false);
            listener.onIncomingFileDone(id, Protocol.FD_ERROR, e.getMessage());
        }
    }

    private void onFileEnd(int id) {
        Incoming in = incoming.remove(id);
        if (in == null) return;
        try {
            in.sink.finish();
            sendFileProgress(id, in.received);
            enqueue(new ByteWriter(8).u8(Protocol.FILE_DONE).i32(id).u8(Protocol.FD_OK).str("").toByteArray(), false);
            listener.onIncomingFileProgress(id, in.received, in.size);
            listener.onIncomingFileDone(id, Protocol.FD_OK, null);
        } catch (IOException e) {
            in.sink.abort();
            enqueue(new ByteWriter(32).u8(Protocol.FILE_DONE).i32(id).u8(Protocol.FD_ERROR).str(String.valueOf(e.getMessage())).toByteArray(), false);
            listener.onIncomingFileDone(id, Protocol.FD_ERROR, e.getMessage());
        }
    }

    private void onFileCancel(int id) {
        if ((id & 1) == 1) {
            Outgoing o = outgoing.get(id);
            if (o != null) o.peerCancelled();
        } else {
            Incoming in = incoming.remove(id);
            if (in != null) {
                in.sink.abort();
                listener.onIncomingFileDone(id, Protocol.FD_CANCELLED, null);
            }
        }
    }

    private void sendFileProgress(int id, long received) {
        enqueue(new ByteWriter(13).u8(Protocol.FILE_PROGRESS).i32(id).u64(received).toByteArray(), false);
    }

    // =================================================================== outgoing files

    /** Handle for a phone → PC transfer. */
    public final class Outgoing {
        public final int id;
        public final String name;
        public final long size;
        private final String mime;
        private final InputStream in;
        private final SendListener l;
        private final Object lock = new Object();
        private long acked;
        private boolean cancelled, peerCancelled, doneReported;
        private int peerStatus = -1;
        private String peerInfo = "";

        Outgoing(int id, String name, long size, String mime, InputStream in, SendListener l) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.mime = mime == null ? "application/octet-stream" : mime;
            this.in = in;
            this.l = l;
        }

        void acked(long v) {
            synchronized (lock) {
                if (v > acked) acked = v;
                lock.notifyAll();
            }
            if (l != null) l.onProgress(v, size);
        }

        void peerDone(int status, String info) {
            synchronized (lock) {
                peerStatus = status;
                peerInfo = info;
                lock.notifyAll();
            }
        }

        void peerCancelled() {
            synchronized (lock) {
                peerCancelled = true;
                lock.notifyAll();
            }
        }

        /** Aborts the transfer (sends FILE_CANCEL). */
        public void cancel() {
            synchronized (lock) {
                cancelled = true;
                lock.notifyAll();
            }
        }

        void connectionClosed() {
            synchronized (lock) { lock.notifyAll(); }
        }

        private void report(int status, String info) {
            synchronized (lock) {
                if (doneReported) return;
                doneReported = true;
            }
            outgoing.remove(id);
            if (l != null) l.onDone(status, info);
        }

        void run() {
            try {
                enqueue(new ByteWriter(64).u8(Protocol.FILE_BEGIN).i32(id).u64(Math.max(0, size)).str(name).str(mime).toByteArray(), false);
                byte[] buf = new byte[Protocol.FILE_CHUNK];
                long sent = 0;
                while (true) {
                    synchronized (lock) {
                        while (!cancelled && !peerCancelled && !closed && peerStatus < 0 && sent - acked >= Protocol.FILE_WINDOW) {
                            lock.wait(500);
                        }
                        if (cancelled || peerCancelled || closed || peerStatus >= 0) break;
                    }
                    int n = in.read(buf);
                    if (n < 0) {
                        enqueueLow(new ByteWriter(5).u8(Protocol.FILE_END).i32(id).toByteArray());
                        long waitUntil = System.currentTimeMillis() + 120_000;
                        synchronized (lock) {
                            while (!cancelled && !peerCancelled && !closed && peerStatus < 0) {
                                long left = waitUntil - System.currentTimeMillis();
                                if (left <= 0) break;
                                lock.wait(Math.min(left, 500));
                            }
                        }
                        break;
                    }
                    if (n == 0) continue;
                    byte[] msg = new byte[5 + n];
                    msg[0] = (byte) Protocol.FILE_DATA;
                    msg[1] = (byte) (id >>> 24);
                    msg[2] = (byte) (id >>> 16);
                    msg[3] = (byte) (id >>> 8);
                    msg[4] = (byte) id;
                    System.arraycopy(buf, 0, msg, 5, n);
                    enqueueLow(msg);
                    sent += n;
                }
                if (cancelled) {
                    enqueue(new ByteWriter(5).u8(Protocol.FILE_CANCEL).i32(id).toByteArray(), false);
                    report(Protocol.FD_CANCELLED, "cancelled");
                } else if (peerStatus >= 0) {
                    report(peerStatus, peerInfo);
                } else if (peerCancelled) {
                    report(Protocol.FD_CANCELLED, "rejected by the PC");
                } else if (closed) {
                    report(Protocol.FD_ERROR, "connection lost");
                } else {
                    report(Protocol.FD_ERROR, "no confirmation from the PC");
                }
            } catch (InterruptedException e) {
                report(Protocol.FD_ERROR, "interrupted");
            } catch (IOException e) {
                enqueue(new ByteWriter(5).u8(Protocol.FILE_CANCEL).i32(id).toByteArray(), false);
                report(Protocol.FD_ERROR, "read error: " + e.getMessage());
            } finally {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** Streams a file to the PC on a background thread. Size must be known (bytes). */
    public Outgoing sendFile(String name, long size, String mime, InputStream in, SendListener l) {
        int id;
        synchronized (this) {
            id = nextFileId;
            nextFileId += 2;
        }
        final Outgoing o = new Outgoing(id, name, size, mime, in, l);
        outgoing.put(id, o);
        Thread t = new Thread(o::run, "dl-send-" + id);
        t.setDaemon(true);
        t.start();
        return o;
    }

    // =================================================================== queue

    private void enqueue(byte[] data, boolean lowPriority) {
        synchronized (qLock) {
            if (closed) return;
            (lowPriority ? low : high).addLast(new Out(data, KIND_NORMAL));
            qLock.notifyAll();
        }
    }

    private void enqueueLow(byte[] data) { enqueue(data, true); }

    // =================================================================== public API: stream

    public void startStream(int monitor, int maxWidth, int maxHeight, int maxFps, int bitrateKbps, boolean clipboardSync) {
        startStream(monitor, maxWidth, maxHeight, maxFps, bitrateKbps, clipboardSync, 0, 0);
    }

    /**
     * decoderMaxWidth / Height: the largest picture this phone's decoder takes (0 = don't say). A picture of all the
     * PC's screens stays within it; older PCs ignore it.
     */
    public void startStream(int monitor, int maxWidth, int maxHeight, int maxFps, int bitrateKbps, boolean clipboardSync,
                            int decoderMaxWidth, int decoderMaxHeight) {
        ByteWriter w = new ByteWriter(16).u8(Protocol.STREAM_START).u8(monitor).u16(maxWidth).u16(maxHeight).u8(maxFps)
                .u32(bitrateKbps).u8(clipboardSync ? Protocol.STREAM_FLAG_CLIPBOARD : 0);
        if (decoderMaxWidth > 0 && decoderMaxHeight > 0) w.u16(decoderMaxWidth).u16(decoderMaxHeight);
        enqueue(w.toByteArray(), false);
    }

    public void pauseStream() { enqueue(ByteWriter.msg(Protocol.STREAM_PAUSE), false); }

    public void resumeStream() { enqueue(ByteWriter.msg(Protocol.STREAM_RESUME), false); }

    public void requestKeyframe() { enqueue(ByteWriter.msg(Protocol.KEYFRAME_REQUEST), false); }

    public void streamConfig(int maxWidth, int maxHeight, int maxFps, int bitrateKbps) {
        enqueue(new ByteWriter(10).u8(Protocol.STREAM_CONFIG).u16(maxWidth).u16(maxHeight).u8(maxFps).u32(bitrateKbps).toByteArray(), false);
    }

    public void setOptions(boolean clipboardSync) {
        enqueue(new ByteWriter(2).u8(Protocol.SET_OPTIONS).u8(clipboardSync ? Protocol.STREAM_FLAG_CLIPBOARD : 0).toByteArray(), false);
    }

    public void selectMonitor(int index) {
        enqueue(new ByteWriter(2).u8(Protocol.SELECT_MONITOR).u8(index).toByteArray(), false);
    }

    /** Acks frame {@code id} (coalesced: a newer ack replaces an unsent older one). */
    public void ackFrame(int id) {
        byte[] d = new ByteWriter(5).u8(Protocol.FRAME_ACK).i32(id).toByteArray();
        synchronized (qLock) {
            if (closed) return;
            if (lastAck != null && !lastAck.taken && high.peekLast() == lastAck) {
                lastAck.data = d;
            } else {
                lastAck = new Out(d, KIND_ACK);
                high.addLast(lastAck);
                qLock.notifyAll();
            }
        }
    }

    // =================================================================== public API: input

    /** Monitor-relative desktop pixels. Coalesced with an unsent previous move. */
    public void mouseMove(int x, int y) {
        byte[] d = new ByteWriter(9).u8(Protocol.MOUSE_MOVE).i32(x).i32(y).toByteArray();
        synchronized (qLock) {
            if (closed) return;
            if (lastMove != null && !lastMove.taken && high.peekLast() == lastMove) {
                lastMove.data = d;
            } else {
                lastMove = new Out(d, KIND_MOVE);
                high.addLast(lastMove);
                qLock.notifyAll();
            }
        }
    }

    public void mouseButton(int button, boolean down) {
        enqueue(new ByteWriter(3).u8(Protocol.MOUSE_BUTTON).u8(button).u8(down ? 1 : 0).toByteArray(), false);
    }

    /**
     * Moves the mouse by dx/dy counts, like a real mouse (only to a PC with CAP_REL_MOUSE): a game camera turns by
     * it. Added to an unsent previous one, so nothing is lost and nothing piles up.
     */
    public void mouseRel(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        synchronized (qLock) {
            if (closed) return;
            if (lastRel != null && !lastRel.taken && high.peekLast() == lastRel
                    && Math.abs(lastRel.a + dx) < 32000 && Math.abs(lastRel.b + dy) < 32000) {
                lastRel.a += dx;
                lastRel.b += dy;
                lastRel.data = new ByteWriter(5).u8(Protocol.MOUSE_REL).i16(lastRel.a).i16(lastRel.b).toByteArray();
            } else {
                dx = Math.max(-32000, Math.min(32000, dx));
                dy = Math.max(-32000, Math.min(32000, dy));
                Out o = new Out(new ByteWriter(5).u8(Protocol.MOUSE_REL).i16(dx).i16(dy).toByteArray(), KIND_REL);
                o.a = dx;
                o.b = dy;
                lastRel = o;
                high.addLast(o);
                qLock.notifyAll();
            }
        }
    }

    /** dy>0 = wheel up; 120 = one notch. Accumulated with an unsent previous wheel message. */
    public void mouseWheel(int dy, int dx) {
        if (dy == 0 && dx == 0) return;
        synchronized (qLock) {
            if (closed) return;
            if (lastWheel != null && !lastWheel.taken && high.peekLast() == lastWheel
                    && Math.abs(lastWheel.a + dy) < 32000 && Math.abs(lastWheel.b + dx) < 32000) {
                lastWheel.a += dy;
                lastWheel.b += dx;
                lastWheel.data = new ByteWriter(5).u8(Protocol.MOUSE_WHEEL).i16(lastWheel.a).i16(lastWheel.b).toByteArray();
            } else {
                Out o = new Out(new ByteWriter(5).u8(Protocol.MOUSE_WHEEL).i16(dy).i16(dx).toByteArray(), KIND_WHEEL);
                o.a = dy;
                o.b = dx;
                lastWheel = o;
                high.addLast(o);
                qLock.notifyAll();
            }
        }
    }

    public void key(int vk, boolean down) {
        enqueue(new ByteWriter(4).u8(Protocol.KEY).u16(vk).u8(down ? 1 : 0).toByteArray(), false);
    }

    public void keyTap(int vk) {
        key(vk, true);
        key(vk, false);
    }

    public void text(String s) {
        if (s == null || s.isEmpty()) return;
        enqueue(new ByteWriter(1 + s.length() * 3).u8(Protocol.TEXT).utf8Tail(s).toByteArray(), false);
    }

    public void releaseAll() { enqueue(ByteWriter.msg(Protocol.RELEASE_ALL), false); }

    public void action(int action) { enqueue(new ByteWriter(2).u8(Protocol.ACTION).u8(action).toByteArray(), false); }

    public void sendClipboard(String text) {
        if (text == null) return;
        byte[] utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(utf8.length, Protocol.MAX_CLIPBOARD_BYTES);
        while (n > 0 && n < utf8.length && (utf8[n] & 0xC0) == 0x80) n--;
        enqueue(new ByteWriter(1 + n).u8(Protocol.CLIPBOARD).bytes(utf8, 0, n).toByteArray(), false);
    }

    /** Asks the PC to show its file picker; chosen files arrive through {@link Listener#onIncomingFile}. */
    public void requestFile() { enqueue(ByteWriter.msg(Protocol.FILE_REQUEST), false); }

    // =================================================================== state

    public HostInfo hostInfo() { return hostInfo; }

    public String address() { return ch.address(); }

    public String fingerprint() { return ch.fingerprint(); }

    public long millisSinceLastReceive() { return (System.nanoTime() - lastRecvNanos) / 1_000_000L; }

    /** Same, by wall clock — unlike the monotonic clock it also counts time the device spent asleep. */
    public long millisSinceLastReceiveWall() { return Math.abs(System.currentTimeMillis() - lastRecvWallMs); }

    public int rttMs() { return rttMs; }

    public long bytesReceived() { return bytesIn.get(); }

    public boolean isClosed() { return closed; }

    /** Sends BYE and closes once it is on the wire (hard close after 1.5 s at the latest). */
    public void disconnect() {
        synchronized (qLock) {
            if (closed) return;
            low.clear(); // pending control messages (e.g. RELEASE_ALL) still go out before BYE
            high.addLast(new Out(new ByteWriter(3).u8(Protocol.BYE).u8(Protocol.BYE_NORMAL).str("").toByteArray(), KIND_BYE));
            qLock.notifyAll();
        }
        Thread t = new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            close();
        }, "dl-bye");
        t.setDaemon(true);
        t.start();
    }

    /** Immediate close without BYE. */
    @Override
    public void close() {
        finish(new CloseInfo(false, 0, "closed", null, true, false));
    }

    private void finish(CloseInfo info) {
        if (!closeReported.compareAndSet(false, true)) return;
        closed = true;
        synchronized (qLock) {
            high.clear();
            low.clear();
            qLock.notifyAll();
        }
        ch.close();
        List<Incoming> ins = new ArrayList<>(incoming.values());
        incoming.clear();
        for (Incoming in : ins) {
            in.sink.abort();
            listener.onIncomingFileDone(in.id, Protocol.FD_ERROR, "connection lost");
        }
        for (Outgoing o : new ArrayList<>(outgoing.values())) o.connectionClosed();
        listener.onClosed(info);
    }
}

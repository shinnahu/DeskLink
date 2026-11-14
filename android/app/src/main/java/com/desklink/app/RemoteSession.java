package com.desklink.app;

import android.os.Handler;
import android.os.Looper;

import com.desklink.core.AuthException;
import com.desklink.core.Discovery;
import com.desklink.core.HostConnection;
import com.desklink.core.IdentityMismatchException;
import com.desklink.core.Messages;
import com.desklink.core.PairedHost;
import com.desklink.core.Protocol;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

/**
 * Owns the connection to one PC: connect, auto-reconnect with backoff, liveness checks,
 * background pause / foreground resume. State callbacks run on the main thread; data callbacks
 * are forwarded to {@code data} on the network thread.
 */
final class RemoteSession {
    enum State { CONNECTING, WAITING_VIDEO, LIVE, LOST, ENDED, FAILED }

    // ENDED details
    static final int END_STOPPED_ON_PC = 1, END_REPLACED = 2, END_HOST_EXIT = 3;
    // FAILED details
    static final int FAIL_UNLINKED = 1, FAIL_IDENTITY = 2, FAIL_BUSY = 3, FAIL_VERSION = 4;
    // LOST detail: >= 0 is the reconnect attempt number; this one means "disconnected while in the background"
    static final int LOST_BACKGROUND = -1;
    // WAITING_VIDEO details
    static final int WAIT_NEW_STREAM = 0, WAIT_VIDEO_RESET = 1;

    interface StateListener {
        void onState(State state, int detail, String message);
        void onWeak(boolean weak);
        /** A new authenticated connection (first one or after a reconnect). */
        void onConnected(HostConnection c, boolean background);
    }

    private static final long WEAK_MS = 2500;
    private static final long LOST_MS = 5000;
    private static final long BACKGROUND_DISCONNECT_MS = 60_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HostStore store;
    private final String hostId;
    private final String deviceName;
    private final HostConnection.Listener data;
    private final StateListener ui;

    private volatile HostConnection conn;
    private State state = State.CONNECTING;
    private volatile int gen;
    private int attempt;
    private boolean started, stopped, background, everConnected, weak, connecting, needReconnect;

    private final Runnable retry = () -> connectNow();
    private final Runnable bgDisconnect = this::backgroundDisconnect;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            checkLiveness();
            main.postDelayed(this, 500);
        }
    };

    RemoteSession(HostStore store, String hostId, String deviceName, HostConnection.Listener data, StateListener ui) {
        this.store = store;
        this.hostId = hostId;
        this.deviceName = deviceName;
        this.data = data;
        this.ui = ui;
    }

    HostConnection connection() { return conn; }

    State state() { return state; }

    int attempt() { return attempt; }

    boolean everConnected() { return everConnected; }

    /** The README's screen pictures (PreviewShots, a unit test): no connections and no searching for PCs. */
    static boolean offline;

    void start() {
        if (started || offline) return;
        started = true;
        stopped = false;
        connectNow();
        main.post(tick);
    }

    /** User-initiated disconnect (or activity destroyed). */
    void stop() {
        stopped = true;
        main.removeCallbacks(retry);
        main.removeCallbacks(bgDisconnect);
        main.removeCallbacks(tick);
        gen++;
        HostConnection c = conn;
        conn = null;
        if (c != null) {
            ActiveSessions.remove(hostId, c);
            c.releaseAll();
            c.disconnect();
        }
    }

    /** "Reconnect" button / Retry. */
    void retryNow() {
        stopped = false;
        main.removeCallbacks(tick);
        main.post(tick);
        attempt = 0;
        main.removeCallbacks(retry);
        if (conn == null && !connecting) connectNow();
    }

    void onBackground() {
        background = true;
        main.removeCallbacks(tick); // no periodic work while in the background
        if (stopped || state == State.ENDED || state == State.FAILED) return; // nothing to pause; keep the message
        if (conn == null && !connecting) {
            main.removeCallbacks(retry); // reconnecting resumes when we come back
            needReconnect = true;
        }
        HostConnection c = conn;
        if (c != null) {
            c.releaseAll();
            c.pauseStream();
        }
        main.removeCallbacks(bgDisconnect);
        main.postDelayed(bgDisconnect, BACKGROUND_DISCONNECT_MS);
    }

    void onForeground() {
        background = false;
        main.removeCallbacks(bgDisconnect);
        if (stopped || state == State.FAILED || state == State.ENDED) return;
        main.removeCallbacks(tick);
        main.post(tick);
        HostConnection c = conn;
        // Wall-clock check too: the monotonic clock does not advance while the phone sleeps.
        if (c != null && !c.isClosed() && Math.max(c.millisSinceLastReceive(), c.millisSinceLastReceiveWall()) < 2000) {
            c.resumeStream();
            setState(State.WAITING_VIDEO, WAIT_NEW_STREAM, null);
            return;
        }
        if (connecting) return;
        // The old connection may have died silently while we were in the background: start fresh now.
        dropConnection();
        attempt = 0;
        main.removeCallbacks(retry);
        setState(State.LOST, 0, null);
        connectNow();
    }

    /** The decoder rendered the first frame of the current stream. */
    void firstFrame() {
        if (state == State.WAITING_VIDEO) setState(State.LIVE, 0, null);
    }

    /** The picture is no longer current (decoder reset, size/monitor change): wait for the next keyframe. */
    void videoReset() {
        if (state == State.LIVE) setState(State.WAITING_VIDEO, WAIT_VIDEO_RESET, null);
    }

    // ------------------------------------------------------------------ internals

    private void setState(State s, int detail, String message) {
        state = s;
        ui.onState(s, detail, message);
    }

    private void dropConnection() {
        HostConnection c = conn;
        conn = null;
        gen++; // ignore its onClosed
        if (c != null) {
            ActiveSessions.remove(hostId, c);
            c.close();
        }
    }

    private void backgroundDisconnect() {
        if (!background || stopped || state == State.ENDED || state == State.FAILED) return;
        HostConnection c = conn;
        if (c != null) {
            gen++;
            conn = null;
            ActiveSessions.remove(hostId, c);
            c.releaseAll();
            c.disconnect();
        }
        needReconnect = true;
        setState(State.LOST, LOST_BACKGROUND, null);
    }

    private void connectNow() {
        if (stopped || connecting) return;
        main.removeCallbacks(retry);
        final PairedHost host = store.get(hostId);
        if (host == null) {
            setState(State.FAILED, FAIL_UNLINKED, null);
            return;
        }
        if (!everConnected && attempt == 0) setState(State.CONNECTING, 0, null);
        else setState(State.LOST, attempt, null);
        connecting = true;
        final int myGen = ++gen;
        final ConnListener listener = new ConnListener(myGen);
        if (attempt % 3 == 0) {
            // In parallel: if the PC's LAN address changed, discovery finds it for the next attempt.
            new Thread(() -> discoverNewAddress(host), "dl-session-discovery").start();
        }
        new Thread(() -> {
            try {
                HostConnection c = HostConnection.connect(host, deviceName, listener, 7000);
                listener.conn = c;
                main.post(() -> onConnected(myGen, c));
            } catch (AuthException e) {
                main.post(() -> onAuthFailed(myGen, e));
            } catch (IdentityMismatchException e) {
                main.post(() -> onFatal(myGen, FAIL_IDENTITY, e.getMessage()));
            } catch (IOException | RuntimeException e) {
                main.post(() -> onConnectFailed(myGen, e));
            }
        }, "dl-connect-main").start();
    }

    private void discoverNewAddress(PairedHost host) {
        try {
            List<InetAddress> targets = Discovery.broadcastTargets();
            targets.addAll(Discovery.literalTargets(host.addresses));
            for (final Discovery.Found f : Discovery.scan(targets, 1500, null)) {
                if (!f.id.equalsIgnoreCase(host.hostId) || !f.fingerprint.equalsIgnoreCase(host.fingerprint)) continue;
                if (f.address.equals(host.lastAddress)) continue;
                store.update(hostId, h -> {
                    h.mergeAddresses(Collections.singletonList(f.address));
                    h.lastAddress = f.address;
                    h.lastSeenOnline = System.currentTimeMillis();
                });
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void onConnected(int myGen, HostConnection c) {
        connecting = false;
        if (myGen != gen || stopped) {
            c.close();
            return;
        }
        conn = c;
        everConnected = true;
        attempt = 0;
        needReconnect = false;
        weak = false;
        final Messages.HostInfo info = c.hostInfo();
        final String address = c.address();
        store.update(hostId, h -> {
            long now = System.currentTimeMillis();
            h.lastConnected = now;
            h.lastSeenOnline = now;
            h.lastAddress = address;
            if (info != null) {
                if (info.hostName != null && !info.hostName.isEmpty()) h.hostName = info.hostName;
                h.mergeAddresses(info.addresses); // includes the router's internet address while it forwards the port
                h.knock = info.can(Protocol.CAP_KNOCK);
                if (info.remoteFlags >= 0) h.remote = info.remoteFlags;
            }
            h.mergeAddresses(Collections.singletonList(address));
        });
        ActiveSessions.put(hostId, c);
        ui.onWeak(false);
        setState(State.WAITING_VIDEO, WAIT_NEW_STREAM, null);
        ui.onConnected(c, background);
        if (background) {
            c.pauseStream();
            main.removeCallbacks(bgDisconnect);
            main.postDelayed(bgDisconnect, BACKGROUND_DISCONNECT_MS);
        }
    }

    private void onAuthFailed(int myGen, AuthException e) {
        connecting = false;
        if (myGen != gen || stopped) return;
        switch (e.status) {
            case Protocol.ST_UNKNOWN_DEVICE:
            case Protocol.ST_BAD_TOKEN:
                onFatal(myGen, FAIL_UNLINKED, e.hostMessage);
                break;
            case Protocol.ST_BUSY:
                onFatal(myGen, FAIL_BUSY, e.hostMessage);
                break;
            case Protocol.ST_VERSION:
                onFatal(myGen, FAIL_VERSION, e.hostMessage);
                break;
            default:
                scheduleRetry();
        }
    }

    private void onFatal(int myGen, int kind, String message) {
        connecting = false;
        if (myGen != gen || stopped) return;
        setState(State.FAILED, kind, message);
    }

    private void onConnectFailed(int myGen, Exception e) {
        connecting = false;
        if (myGen != gen || stopped) return;
        if (background) {
            needReconnect = true;
            setState(State.LOST, LOST_BACKGROUND, e.getMessage());
            return;
        }
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (stopped) return;
        attempt++;
        long delay = Math.min(5000, 500L << Math.min(attempt - 1, 4));
        setState(State.LOST, attempt, null);
        main.removeCallbacks(retry);
        main.postDelayed(retry, delay);
    }

    private void onClosed(int myGen, HostConnection.CloseInfo info) {
        if (myGen != gen || stopped) return;
        HostConnection old = conn;
        conn = null;
        ActiveSessions.remove(hostId, old);
        if (info.local) return;
        if (info.byPeer) {
            if (info.byeReason == Protocol.BYE_STOPPED_ON_PC) {
                setState(State.ENDED, END_STOPPED_ON_PC, info.message);
                return;
            }
            // Replaced while we were in the background (e.g. our own reconnect or a share upload):
            // treat as lost and reconnect when the user comes back.
            if (info.byeReason == Protocol.BYE_REPLACED && !background) {
                setState(State.ENDED, END_REPLACED, info.message);
                return;
            }
        }
        if (background) {
            needReconnect = true;
            setState(State.LOST, LOST_BACKGROUND, null);
            return;
        }
        attempt = 0;
        scheduleRetry();
    }

    private void checkLiveness() {
        HostConnection c = conn;
        if (c == null || background) {
            if (weak) {
                weak = false;
                ui.onWeak(false);
            }
            return;
        }
        long since = c.millisSinceLastReceive();
        boolean w = since > WEAK_MS;
        if (w != weak) {
            weak = w;
            ui.onWeak(w);
        }
        if (since > LOST_MS) {
            dropConnection();
            attempt = 0;
            scheduleRetry();
        }
    }

    /** Per-connection listener: tags callbacks with the connection generation and acks frames. */
    private final class ConnListener implements HostConnection.Listener {
        final int myGen;
        volatile HostConnection conn;

        ConnListener(int myGen) { this.myGen = myGen; }

        private boolean current() { return myGen == gen; }

        @Override public void onHostInfo(Messages.HostInfo i) { if (current()) data.onHostInfo(i); }

        @Override public void onVideoFormat(Messages.VideoFormat f) { if (current()) data.onVideoFormat(f); }

        @Override public void onAudioFormat(Messages.AudioFormat f) { if (current()) data.onAudioFormat(f); }

        @Override
        public void onAudioData(long ts, byte[] d, int off, int len) { if (current()) data.onAudioData(ts, d, off, len); }

        @Override
        public void onVideoFrame(int id, boolean key, long ts, byte[] d, int off, int len) {
            HostConnection c = conn;
            if (c != null) c.ackFrame(id); // ack on receipt, before decoding
            if (current()) data.onVideoFrame(id, key, ts, d, off, len);
        }

        @Override public void onCursorShape(Messages.CursorShape s) { if (current()) data.onCursorShape(s); }

        @Override public void onCursorState(int x, int y, boolean v, int s) { if (current()) data.onCursorState(x, y, v, s); }

        @Override public void onHostStatus(int flags, String m) { if (current()) data.onHostStatus(flags, m); }

        @Override public void onClipboard(String t) { if (current()) data.onClipboard(t); }

        @Override public void onNotice(int level, String t) { if (current()) data.onNotice(level, t); }

        @Override public void onRtt(int ms) { if (current()) data.onRtt(ms); }

        @Override public void onFileRequestNone() { if (current()) data.onFileRequestNone(); }

        @Override
        public HostConnection.IncomingFileSink onIncomingFile(int id, String name, long size, String mime) {
            return current() ? data.onIncomingFile(id, name, size, mime) : null;
        }

        @Override
        public HostConnection.IncomingFileSink onIncomingFile(int id, String name, long size, String mime, int kind) {
            return current() ? data.onIncomingFile(id, name, size, mime, kind) : null;
        }

        @Override public void onScreenshotFailed(int reason, String detail) { if (current()) data.onScreenshotFailed(reason, detail); }

        @Override public void onIncomingFileProgress(int id, long r, long s) { data.onIncomingFileProgress(id, r, s); }

        @Override public void onIncomingFileDone(int id, int st, String err) { data.onIncomingFileDone(id, st, err); }

        @Override
        public void onClosed(HostConnection.CloseInfo info) {
            main.post(() -> RemoteSession.this.onClosed(myGen, info));
        }
    }
}

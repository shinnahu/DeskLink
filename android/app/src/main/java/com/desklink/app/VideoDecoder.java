package com.desklink.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.desklink.core.H264;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-latency H.264 decoder on its own thread, rendering straight to a Surface.
 * Frames are queued from the network thread; every decoded frame is rendered immediately.
 *
 * The phone's H.264 decoders are tried in order: hardware ones first (one that says it does low latency before the
 * others), then the software one. One the session gave up on ({@link DecoderWatch}: it gave no picture) goes to the
 * back of the line for {@link #FORGET_MS}. A hardware decoder that got stuck stays stuck until the app is closed, and
 * the software one doesn't need the video hardware.
 */
final class VideoDecoder {
    private static final String TAG = "DeskLinkDecoder";
    private static final String MIME = "video/avc";
    /** A decoder that gave no picture is tried last for this long. */
    static final long FORGET_MS = 10 * 60_000L;

    interface Callback {
        void onFirstFrame();
        void onFrameRendered();
        void onNeedKeyframe();
        /** The picture on screen is stale (decoder recreated / frames dropped) until the next first frame. */
        void onDecoderReset();
        /** What the decoder did, one line in English for the PC's log (decoder thread). */
        void onDecoderEvent(String line);
    }

    private static final class Frame {
        final byte[] data;
        final int off, len;
        final boolean key;
        final long pts;

        Frame(byte[] data, int off, int len, boolean key, long pts) {
            this.data = data;
            this.off = off;
            this.len = len;
            this.key = key;
            this.pts = pts;
        }
    }

    private final Surface surface;
    private final Callback cb;
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final DecoderWatch watch = new DecoderWatch();
    private volatile boolean running, exited;
    private volatile Runnable whenReleased;
    private final AtomicBoolean releasedTold = new AtomicBoolean();
    private volatile Thread thread;

    // decoder-thread state
    private MediaCodec codec;
    private byte[] sps, pps;
    private int width = 1920, height = 1080;
    private int cfgWidth, cfgHeight;
    private boolean needKey = true;
    private volatile boolean firstRendered;
    private int pendingOutputs;
    private long lastOutputNs;
    private long lastKeyRequestNs;
    private long lastPts = -1;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private volatile String codecName = "";
    private String lastStarted = "", lastFailure = ""; // said once until they change
    private int errorsInARow;

    VideoDecoder(Surface surface, Callback cb) {
        this.surface = surface;
        this.cb = cb;
    }

    String codecName() { return codecName; }

    /** The decoder running is the slower software one. */
    boolean software() {
        String n = codecName;
        return !n.isEmpty() && isSoftware(n);
    }

    /** Why this decoder should be given up on now, or null while it's fine (UI thread). */
    DecoderWatch.Trouble trouble() {
        if (running && exited) return new DecoderWatch.Trouble("its thread ended", codecName, false);
        return watch.trouble(System.nanoTime());
    }

    void start() {
        running = true;
        Thread t = new Thread(this::loop, "dl-decoder");
        t.setPriority(Thread.MAX_PRIORITY);
        thread = t;
        t.start();
    }

    /**
     * Stops decoding without waiting: the codec is let go on the decoder's own thread, then {@code whenDone} runs there
     * (never, if a codec call hangs). The surface must stay valid until then.
     */
    void release(Runnable whenDone) {
        whenReleased = whenDone;
        running = false;
        synchronized (queue) {
            queue.clear();
            queue.notifyAll();
        }
        if (exited) toldReleased();
    }

    private void toldReleased() {
        Runnable r = whenReleased;
        if (r != null && releasedTold.compareAndSet(false, true)) r.run();
    }

    void setSize(int w, int h) {
        synchronized (queue) {
            if (w > 0 && h > 0) {
                width = w;
                height = h;
            }
        }
    }

    /** Drop queued frames and wait for the next keyframe (new stream / reconnect). */
    void resetStream() {
        synchronized (queue) {
            queue.clear();
            needKey = true;
            firstRendered = false;
        }
        watch.reset();
    }

    void submit(byte[] data, int off, int len, boolean key, long ptsUs) {
        synchronized (queue) {
            if (!running) return;
            if (queue.size() >= 24) {
                // the decoder can't keep up: start over from a keyframe
                queue.clear();
                needKey = true;
                firstRendered = false;
                cb.onDecoderReset();
                requestKey(true);
                if (!key) return;
            }
            queue.addLast(new Frame(data, off, len, key, ptsUs));
            queue.notifyAll();
        }
    }

    private void requestKey(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastKeyRequestNs < 500_000_000L) return;
        lastKeyRequestNs = now;
        cb.onNeedKeyframe();
    }

    private void event(String line) {
        Log.i(TAG, line);
        cb.onDecoderEvent(line);
    }

    private void loop() {
        try {
            while (running) {
                Frame f;
                synchronized (queue) {
                    if (queue.isEmpty() && (codec == null || pendingOutputs <= 0)) {
                        try {
                            queue.wait(100);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    f = queue.pollFirst();
                }
                if (!running) break;
                try {
                    if (f != null) handle(f);
                    if (codec != null) drain(f == null ? 4000 : 0);
                } catch (RuntimeException e) {
                    Log.w(TAG, "decoder error, recreating", e);
                    errorsInARow++;
                    if (errorsInARow == 1 || errorsInARow % 10 == 0) {
                        event(codecName + " failed (" + brief(e) + "), making it again"
                                + (errorsInARow > 1 ? " (" + errorsInARow + " times in a row)" : ""));
                    }
                    releaseCodec();
                    synchronized (queue) {
                        needKey = true;
                        firstRendered = false;
                    }
                    cb.onDecoderReset();
                    requestKey(true);
                } finally {
                    watch.leave();
                }
            }
        } finally {
            releaseCodec();
            exited = true;
            toldReleased();
        }
    }

    private void handle(Frame f) {
        boolean need;
        synchronized (queue) { need = needKey; }
        if (f.key) {
            byte[][] ps = H264.findParameterSets(f.data, f.off, f.len);
            int w, h;
            synchronized (queue) {
                w = width;
                h = height;
            }
            if (ps != null && (codec == null || !Arrays.equals(ps[0], sps) || !Arrays.equals(ps[1], pps) || w != cfgWidth || h != cfgHeight)) {
                configure(ps[0], ps[1], w, h);
            }
            if (codec == null) {
                watch.noCodec(System.nanoTime());
                requestKey(false);
                return;
            }
            synchronized (queue) { needKey = false; }
            watch.keyIn(codecName, System.nanoTime());
        } else if (need || codec == null) {
            requestKey(false);
            return;
        }
        queueInput(f);
    }

    private void configure(byte[] newSps, byte[] newPps, int w, int h) {
        if (codec != null) {
            // stream format changed mid-session: the old picture stays until the new one renders
            firstRendered = false;
            cb.onDecoderReset();
        }
        releaseCodec();
        sps = newSps;
        pps = newPps;
        List<String> names = order();
        if (names.isEmpty()) names = Collections.singletonList(null); // the list couldn't be read: the phone's choice
        StringBuilder failed = new StringBuilder();
        for (String name : names) {
            String label = name != null ? name : "the phone's own choice";
            Exception last = null;
            for (int level = 0; level < 3 && running; level++) {
                MediaCodec c = null;
                try {
                    watch.enter("making", label, System.nanoTime());
                    c = name != null ? MediaCodec.createByCodecName(name) : MediaCodec.createDecoderByType(MIME);
                    String made = c.getName();
                    watch.enter("configuring", made, System.nanoTime());
                    c.configure(buildFormat(w, h, level), surface, null, 0);
                    watch.enter("starting", made, System.nanoTime());
                    c.start();
                    watch.leave();
                    codec = c;
                    codecName = made;
                    cfgWidth = w;
                    cfgHeight = h;
                    pendingOutputs = 0;
                    lastPts = -1;
                    watch.codecStarted();
                    lastFailure = "";
                    String canonical = Build.VERSION.SDK_INT >= 29 ? c.getCanonicalName() : made;
                    String line = "decoder " + made + (canonical.equals(made) ? "" : " (" + canonical + ")")
                            + (isSoftware(made) ? ", software," : "") + " " + w + "x" + h
                            + (level == 0 ? "" : level == 1 ? ", without vendor settings" : ", plain settings");
                    if (failed.length() > 0) {
                        event(line + " started after: " + failed);
                    } else if (!line.equals(lastStarted)) {
                        event(line + " started");
                    }
                    lastStarted = line;
                    return;
                } catch (Exception e) {
                    last = e;
                    if (c != null) {
                        watch.enter("letting go of", label, System.nanoTime());
                        try { c.release(); } catch (RuntimeException ignored) {}
                    }
                    watch.leave();
                }
            }
            if (!running) return;
            if (failed.length() > 0) failed.append("; ");
            failed.append(label).append(": ").append(last != null ? brief(last) : "?");
        }
        String line = "no decoder would start (" + failed + ")";
        Log.e(TAG, line);
        if (!line.equals(lastFailure)) {
            lastFailure = line;
            event(line);
        }
    }

    private MediaFormat buildFormat(int w, int h, int level) {
        MediaFormat f = MediaFormat.createVideoFormat(MIME, w, h);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
        f.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(w * h * 3 / 2, 1 << 20));
        if (level <= 1) {
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, 120);
        }
        if (level == 0) {
            f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
            f.setInteger("vendor.qti-ext-dec-picture-order.enable", 1);
            f.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1);
            f.setInteger("vendor.low-latency.enable", 1);
        }
        return f;
    }

    private void queueInput(Frame f) {
        long deadline = System.nanoTime() + 400_000_000L;
        while (running) {
            watch.enter("feeding", codecName, System.nanoTime());
            int idx = codec.dequeueInputBuffer(10_000);
            if (idx >= 0) {
                ByteBuffer ib = codec.getInputBuffer(idx);
                if (ib == null || ib.capacity() < f.len) {
                    codec.queueInputBuffer(idx, 0, 0, 0, 0);
                    Log.w(TAG, "frame larger than input buffer: " + f.len);
                    synchronized (queue) { needKey = true; }
                    requestKey(true);
                    return;
                }
                ib.clear();
                ib.put(f.data, f.off, f.len);
                long pts = f.pts <= lastPts ? lastPts + 1 : f.pts; // strictly increasing
                lastPts = pts;
                codec.queueInputBuffer(idx, 0, f.len, pts, f.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                pendingOutputs++;
                return;
            }
            drain(0);
            if (System.nanoTime() > deadline) throw new IllegalStateException("decoder input stalled");
        }
    }

    private void drain(long timeoutUs) {
        watch.enter("draining", codecName, System.nanoTime());
        int idx = codec.dequeueOutputBuffer(info, timeoutUs);
        while (true) {
            if (idx >= 0) {
                boolean render = info.size > 0 || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                codec.releaseOutputBuffer(idx, render);
                if (pendingOutputs > 0) pendingOutputs--;
                lastOutputNs = System.nanoTime();
                if (render) {
                    watch.pictureOut();
                    errorsInARow = 0;
                }
                if (!firstRendered) {
                    firstRendered = true;
                    cb.onFirstFrame();
                }
                cb.onFrameRendered();
            } else if (idx != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && idx != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                break;
            }
            idx = codec.dequeueOutputBuffer(info, 0);
        }
        // Some decoders silently drop frames; don't keep polling forever.
        if (pendingOutputs > 0 && System.nanoTime() - lastOutputNs > 300_000_000L) {
            synchronized (queue) {
                if (queue.isEmpty()) pendingOutputs = 0;
            }
        }
    }

    private void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        pendingOutputs = 0;
        if (c == null) return;
        String name = codecName;
        long t0 = System.nanoTime();
        watch.enter("letting go of", name, t0);
        try {
            // Android 11 and 12: release() can return before the surface is let go of, and the next decoder then
            // can't use it (as ExoPlayer found); stopping first waits for that
            if (Build.VERSION.SDK_INT >= 30 && Build.VERSION.SDK_INT < 33) c.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            c.release();
        } catch (RuntimeException ignored) {
        }
        watch.leave();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms >= 1000) event("letting go of " + name + " took " + ms + " ms");
    }

    private static String brief(Exception e) {
        String s = e.getClass().getSimpleName();
        if (e instanceof MediaCodec.CodecException) {
            MediaCodec.CodecException ce = (MediaCodec.CodecException) e;
            s += String.format(Locale.US, " 0x%08x", ce.getErrorCode());
        }
        String m = e.getMessage();
        if (m != null && !m.isEmpty()) s += ": " + m;
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    // =================================================================== which decoder

    /** One H.264 decoder of this phone. */
    static final class Candidate {
        final String name, canonical; // an alias names a codec that's also listed under its own (canonical) name
        final boolean hardware, lowLatency;
        final MediaCodecInfo info;    // null in the tests

        Candidate(String name, String canonical, boolean hardware, boolean lowLatency, MediaCodecInfo info) {
            this.name = name;
            this.canonical = canonical;
            this.hardware = hardware;
            this.lowLatency = lowLatency;
            this.info = info;
        }

        int score() { return (hardware ? 2 : 0) + (lowLatency ? 1 : 0); }
    }

    private static List<Candidate> listed;  // every H.264 decoder the phone lists, aliases too
    private static List<Candidate> ranked;  // best first, each codec once
    private static final Map<String, Long> gaveNothing = new HashMap<>(); // canonical name -> when (elapsedRealtime)

    private static synchronized List<Candidate> candidates() {
        if (ranked == null) {
            listed = listDecoders();
            ranked = rank(listed);
        }
        return ranked;
    }

    private static List<Candidate> listDecoders() {
        List<Candidate> list = new ArrayList<>();
        try {
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (ci.isEncoder()) continue;
                boolean supports = false;
                for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(MIME)) supports = true;
                if (!supports) continue;
                String name = ci.getName();
                if (name.toLowerCase(Locale.ROOT).endsWith(".secure")) continue;
                boolean software = softwareName(name);
                String canonical = name;
                if (Build.VERSION.SDK_INT >= 29) {
                    software = software || ci.isSoftwareOnly();
                    canonical = ci.getCanonicalName();
                }
                boolean lowLatency = false;
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        lowLatency = ci.getCapabilitiesForType(MIME).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
                    } catch (RuntimeException ignored) {
                    }
                }
                list.add(new Candidate(name, canonical, !software, lowLatency, ci));
            }
        } catch (RuntimeException ignored) {
        }
        return list;
    }

    private static boolean softwareName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.");
    }

    /**
     * Best first: hardware that says it does low latency, other hardware, software; the phone's own order within each.
     * A codec listed under several names comes once, under the first. (The first is the decoder DeskLink always used.)
     */
    static List<Candidate> rank(List<Candidate> list) {
        List<Candidate> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> b.score() - a.score()); // stable
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate c : sorted) if (seen.add(c.canonical)) out.add(c);
        return out;
    }

    /** The decoders to try now, by name: best first, then those that gave no picture lately, the earliest such first. */
    static synchronized List<String> order() {
        return order(candidates(), gaveNothing, SystemClock.elapsedRealtime());
    }

    static List<String> order(List<Candidate> ranked, Map<String, Long> gaveNothing, long nowMs) {
        List<String> out = new ArrayList<>();
        List<Candidate> later = new ArrayList<>();
        for (Candidate c : ranked) {
            Long t = gaveNothing.get(c.canonical);
            if (t == null || nowMs - t >= FORGET_MS) out.add(c.name);
            else later.add(c);
        }
        later.sort((a, b) -> Long.compare(gaveNothing.get(a.canonical), gaveNothing.get(b.canonical)));
        for (Candidate c : later) out.add(c.name);
        return out;
    }

    /** The session gave up on this decoder (it gave no picture): it's tried last for {@link #FORGET_MS}. */
    static synchronized void gaveNothing(String name) {
        if (name == null || name.isEmpty()) return;
        candidates();
        String canonical = name;
        for (Candidate c : listed) if (c.name.equals(name)) canonical = c.canonical;
        gaveNothing.put(canonical, SystemClock.elapsedRealtime());
    }

    /** The decoder the next one will be, by name ("" when the phone lists none). */
    static synchronized String next() {
        List<String> o = order();
        return o.isEmpty() ? "" : o.get(0);
    }

    static synchronized boolean isSoftware(String name) {
        candidates();
        for (Candidate c : listed) if (c.name.equals(name)) return !c.hardware;
        return softwareName(name);
    }

    private static int[] maxSize;

    /**
     * The largest picture the decoder takes, {width, height}: its widest, and the tallest it takes at that width
     * ({0, 0} when it can't tell). The PC keeps a picture of all its screens within it.
     */
    static synchronized int[] maxSize() {
        if (maxSize != null) return maxSize;
        int w = 0, h = 0;
        try {
            List<Candidate> c = candidates();
            MediaCodecInfo ci = c.isEmpty() ? null : c.get(0).info;
            if (ci != null) {
                MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(MIME).getVideoCapabilities();
                w = vc.getSupportedWidths().getUpper();
                h = vc.getSupportedHeightsFor(w).getUpper();
            }
        } catch (RuntimeException e) {
            w = 0;
            h = 0;
        }
        maxSize = new int[] {w, h};
        return maxSize;
    }
}

package com.desklink.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.desklink.core.H264;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Low-latency H.264 decoder on its own thread, rendering straight to a Surface.
 * Frames are queued from the network thread; every decoded frame is rendered immediately.
 */
final class VideoDecoder {
    private static final String TAG = "DeskLinkDecoder";
    private static final String MIME = "video/avc";

    interface Callback {
        void onFirstFrame();
        void onFrameRendered();
        void onNeedKeyframe();
        /** The picture on screen is stale (decoder recreated / frames dropped) until the next first frame. */
        void onDecoderReset();
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
    private volatile boolean running;
    private Thread thread;

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

    VideoDecoder(Surface surface, Callback cb) {
        this.surface = surface;
        this.cb = cb;
    }

    String codecName() { return codecName; }

    void start() {
        running = true;
        thread = new Thread(this::loop, "dl-decoder");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    void release() {
        running = false;
        synchronized (queue) {
            queue.clear();
            queue.notifyAll();
        }
        Thread t = thread;
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException ignored) {
            }
        }
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
                    releaseCodec();
                    synchronized (queue) {
                        needKey = true;
                        firstRendered = false;
                    }
                    cb.onDecoderReset();
                    requestKey(true);
                }
            }
        } finally {
            releaseCodec();
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
                requestKey(false);
                return;
            }
            synchronized (queue) { needKey = false; }
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
        Exception last = null;
        for (int attempt = 0; attempt < 3 && codec == null; attempt++) {
            MediaCodec c = null;
            try {
                c = createCodec(attempt == 0);
                c.configure(buildFormat(w, h, attempt), surface, null, 0);
                c.start();
                codec = c;
                codecName = c.getName();
                cfgWidth = w;
                cfgHeight = h;
                pendingOutputs = 0;
                lastPts = -1;
                Log.i(TAG, "decoder " + c.getName() + " " + w + "x" + h + " (attempt " + attempt + ")");
            } catch (Exception e) {
                last = e;
                if (c != null) {
                    try { c.release(); } catch (RuntimeException ignored) {}
                }
            }
        }
        if (codec == null) Log.e(TAG, "could not create a decoder", last);
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

    /** Prefers a hardware decoder that advertises FEATURE_LowLatency. */
    private static MediaCodec createCodec(boolean preferLowLatency) throws java.io.IOException {
        if (preferLowLatency) {
            MediaCodecInfo best = bestDecoder();
            if (best != null) return MediaCodec.createByCodecName(best.getName());
        }
        return MediaCodec.createDecoderByType(MIME);
    }

    /** The H.264 decoder DeskLink uses: hardware first, one that advertises FEATURE_LowLatency before others. */
    private static MediaCodecInfo bestDecoder() {
        MediaCodecInfo best = null;
        int bestScore = -1;
        try {
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (ci.isEncoder()) continue;
                boolean supports = false;
                for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(MIME)) supports = true;
                if (!supports) continue;
                String n = ci.getName().toLowerCase(java.util.Locale.ROOT);
                boolean software = n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.");
                if (Build.VERSION.SDK_INT >= 29) software = software || ci.isSoftwareOnly();
                int score = software ? 0 : 2;
                if (Build.VERSION.SDK_INT >= 30) {
                    MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType(MIME);
                    if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) score += 1;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = ci;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return best;
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
            MediaCodecInfo ci = bestDecoder();
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

    private void queueInput(Frame f) {
        long deadline = System.nanoTime() + 400_000_000L;
        while (running) {
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
        int idx = codec.dequeueOutputBuffer(info, timeoutUs);
        while (true) {
            if (idx >= 0) {
                boolean render = info.size > 0 || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                codec.releaseOutputBuffer(idx, render);
                if (pendingOutputs > 0) pendingOutputs--;
                lastOutputNs = System.nanoTime();
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
        if (c != null) {
            try { c.stop(); } catch (RuntimeException ignored) {}
            try { c.release(); } catch (RuntimeException ignored) {}
        }
        pendingOutputs = 0;
    }
}

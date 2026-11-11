package com.desklink.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.desklink.core.Messages;
import com.desklink.core.Protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The PC's sound: AAC frames from the network (AUDIO_DATA), decoded by MediaCodec on a thread of its own and played
 * through an AudioTrack as media, so the phone's volume keys set it. It stays close to live: playing starts once
 * PRIME_MS is waiting, the track holds BUFFER_MS at most (what doesn't fit is dropped), and past DRIFT_MS a chunk is
 * skipped. When nothing comes for IDLE_MS (the PC went quiet) it pauses, and the next sound primes again.
 */
final class AudioPlayer {
    private static final String TAG = "DeskLinkAudio";
    private static final int PRIME_MS = 60, DRIFT_MS = 140, BUFFER_MS = 160, CAPACITY_MS = 250, IDLE_MS = 300;
    private static final int MAX_QUEUE = 25; // AAC frames (about half a second) waiting for the decoder; older ones go

    private final Object lock = new Object();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private Messages.AudioFormat format; // a new one for the thread to take up
    private Thread thread;
    private volatile boolean running;

    // what it's doing, for the stats line: frames from the PC, decoded, and sound waiting in the track
    private final AtomicLong got = new AtomicLong(), decoded = new AtomicLong();
    private volatile String what = "";
    private volatile int waitingMs = -1; // -1: not playing
    private volatile String problem;     // a failure for the screen to say once (takeProblem)

    /** A new AUDIO_FORMAT (network thread): starts playing it, or starts over with it. */
    void start(Messages.AudioFormat f) {
        synchronized (lock) {
            format = f;
            queue.clear();
            got.set(0);
            decoded.set(0);
            what = "starting";
            if (thread == null) {
                running = true;
                thread = new Thread(this::loop, "dl-audio");
                thread.start();
            }
            lock.notifyAll();
        }
    }

    /** One AAC frame (network thread). */
    void feed(byte[] data, int off, int len) {
        byte[] b = Arrays.copyOfRange(data, off, off + len);
        synchronized (lock) {
            if (thread == null) return;
            got.incrementAndGet();
            while (queue.size() >= MAX_QUEUE) queue.pollFirst();
            queue.addLast(b);
            lock.notifyAll();
        }
    }

    boolean started() { return thread != null; }

    /** One line for the stats: empty while it isn't playing anything. */
    String describe() {
        if (thread == null) return "";
        int w = waitingMs;
        return String.format(Locale.US, "sound %s · got %d · decoded %d · %s", what, got.get(), decoded.get(),
                w < 0 ? "not playing" : w + " ms waiting");
    }

    /** A failure to say on the screen, once (main thread). */
    String takeProblem() {
        String p = problem;
        problem = null;
        return p;
    }

    /** Stops, and lets go of the decoder and the track (any thread). */
    void stop() {
        Thread t;
        synchronized (lock) {
            t = thread;
            thread = null;
            running = false;
            format = null;
            queue.clear();
            lock.notifyAll();
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        } catch (RuntimeException ignored) {
            // some phones refuse it: it plays at the normal priority
        }
        MediaCodec codec = null;
        AudioTrack track = null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int rate = 0, channels = 0;
        long written = 0, lastFrameAt = 0; // frames written to the track since it last started
        long pts = 0; // the decoder's input times, 1024 samples apart
        boolean playing = false;
        long lastHead = -1, headMovedAt = 0; // a track that "plays" but takes nothing is said once
        boolean stuckSaid = false;
        try {
            while (running) {
                Messages.AudioFormat f;
                byte[] frame;
                synchronized (lock) {
                    if (running && format == null && queue.isEmpty()) lock.wait(20);
                    if (!running) break;
                    f = format;
                    format = null;
                    frame = queue.pollFirst();
                }
                if (f != null) { // starts over with the new format
                    release(codec, track);
                    codec = null;
                    track = null;
                    written = 0;
                    pts = 0;
                    playing = false;
                    waitingMs = -1;
                    if (f.codec == Protocol.AUDIO_CODEC_AAC_LC && f.sampleRate > 0 && f.channels > 0) {
                        rate = f.sampleRate;
                        channels = f.channels;
                        try {
                            codec = newDecoder(f);
                            what = String.format(Locale.US, "AAC %d Hz %d ch (%s)", rate, channels, codec.getName());
                        } catch (IOException | RuntimeException e) {
                            Log.w(TAG, "no AAC decoder", e);
                            what = "no AAC decoder";
                            problem = "Sound: this phone's AAC decoder didn't start (" + e + ")";
                        }
                    } else {
                        what = "codec " + f.codec + " not played";
                    }
                }
                long now = SystemClock.uptimeMillis();
                if (codec == null) continue; // frames without a usable format go
                if (frame != null) {
                    lastFrameAt = now;
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        if (b != null && b.capacity() >= frame.length) {
                            b.clear();
                            b.put(frame);
                            codec.queueInputBuffer(in, 0, frame.length, pts, 0);
                            pts += 1024L * 1_000_000 / rate;
                        } else {
                            codec.queueInputBuffer(in, 0, 0, pts, 0);
                        }
                    }
                }
                for (;;) {
                    int out = codec.dequeueOutputBuffer(info, 0);
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        release(null, track);
                        track = null;
                        continue;
                    }
                    if (out < 0) break;
                    if (info.size > 0 && rate > 0 && channels > 0) {
                        decoded.incrementAndGet();
                        if (track == null) {
                            track = newTrack(rate, channels);
                            written = 0;
                            playing = false;
                        }
                        long waiting = written - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL);
                        if (!playing || waiting * 1000 < (long) DRIFT_MS * rate) { // past DRIFT_MS: this chunk is skipped
                            ByteBuffer ob = codec.getOutputBuffer(out);
                            if (ob != null) {
                                ob.position(info.offset);
                                ob.limit(info.offset + info.size);
                                int n = track.write(ob, info.size, AudioTrack.WRITE_NON_BLOCKING);
                                if (n > 0) written += n / (2 * channels);
                            }
                        }
                        long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                        waiting = written - head;
                        if (!playing && waiting * 1000 >= (long) PRIME_MS * rate) {
                            track.play();
                            playing = true;
                            lastHead = -1;
                            headMovedAt = now;
                        }
                        waitingMs = playing ? (int) (waiting * 1000 / rate) : -1;
                        if (playing && head != lastHead) {
                            lastHead = head;
                            headMovedAt = now;
                        } else if (playing && !stuckSaid && now - headMovedAt > 2000) {
                            stuckSaid = true;
                            what = "output not playing";
                            problem = "Sound: the phone's audio output takes the sound but doesn't play it";
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                }
                if (playing && track != null && now - lastFrameAt > IDLE_MS) { // the PC went quiet
                    track.pause();
                    track.flush();
                    written = 0;
                    playing = false;
                    waitingMs = -1;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "sound stopped", e);
            what = "stopped";
            problem = "Sound stopped: " + e;
        } finally {
            release(codec, track);
        }
    }

    private static MediaCodec newDecoder(Messages.AudioFormat f) throws IOException {
        MediaFormat mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, f.sampleRate, f.channels);
        mf.setByteBuffer("csd-0", ByteBuffer.wrap(f.config));
        mf.setInteger(MediaFormat.KEY_IS_ADTS, 0);
        MediaCodec c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        try {
            c.configure(mf, null, null, 0);
            c.start();
        } catch (RuntimeException e) {
            c.release();
            throw e;
        }
        return c;
    }

    private static AudioTrack newTrack(int rate, int channels) {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min, rate * channels * 2 * CAPACITY_MS / 1000);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build())
                .setBufferSizeInBytes(bytes) // (not "low latency": that may shrink it, and what doesn't fit is dropped)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        // A streaming track starts only once its buffer holds the start threshold, which is the whole buffer unless
        // set: kept under DRIFT_MS, a full-size buffer never filled and nothing played. So the buffer in use is
        // BUFFER_MS, and from Android 12 playing starts at PRIME_MS (before that, once BUFFER_MS is waiting).
        try {
            t.setBufferSizeInFrames(rate * BUFFER_MS / 1000);
            if (Build.VERSION.SDK_INT >= 31) t.setStartThresholdInFrames(rate * PRIME_MS / 1000);
        } catch (RuntimeException e) {
            Log.w(TAG, "buffer size / start threshold not taken", e);
        }
        return t;
    }

    private static void release(MediaCodec codec, AudioTrack track) {
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // already stopped or broken: it's released either way
            }
            codec.release();
        }
        if (track != null) {
            try {
                track.stop();
            } catch (RuntimeException ignored) {
                // never started
            }
            track.release();
        }
    }
}

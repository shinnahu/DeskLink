package com.desklink.app;

/**
 * When to give up on a video decoder. Some phones' hardware decoder gets stuck: it takes the video and gives no picture,
 * and new decoders made by the same app give none either, until the app is closed. Then the session would wait for the
 * screen forever. A decoder is given up on when, for {@link #GIVE_UP_NS}:
 * <ul>
 * <li>a keyframe was handed to it and no picture came out (also when it keeps failing and being made again),</li>
 * <li>its thread has been inside one call (making, starting, feeding or letting go of the codec), or</li>
 * <li>keyframes came and no decoder would start.</li>
 * </ul>
 * Only bookkeeping: the caller passes the time (System.nanoTime), so the unit tests drive it without a decoder. The
 * decoder thread reports, the UI thread asks.
 */
final class DecoderWatch {
    static final long GIVE_UP_NS = 4_000_000_000L;

    /** Why a decoder is given up on. */
    static final class Trouble {
        final String why;       // in English, for the PC's log
        final String codec;     // the decoder it was about ("" when none was running)
        final boolean surface;  // the surface may be the problem too: draw on a new one

        Trouble(String why, String codec, boolean surface) {
            this.why = why;
            this.codec = codec;
            this.surface = surface;
        }
    }

    private long keySince;      // a keyframe was handed over and no picture came out since (0: nothing waiting)
    private String keyCodec = "";
    private long busySince;     // the decoder thread went into a call then (0: not in one)
    private String busyWhat = "", busyCodec = "";
    private long noCodecSince;  // a keyframe came and no decoder would start since (0: one is running)

    /** A keyframe was handed to {@code codec}. */
    synchronized void keyIn(String codec, long now) {
        if (keySince == 0) keySince = now;
        keyCodec = codec;
    }

    /** A picture came out: all is well. */
    synchronized void pictureOut() {
        keySince = 0;
        noCodecSince = 0;
    }

    /** The decoder thread goes into a call that may hang ({@code what}: "starting", say), about {@code codec}. */
    synchronized void enter(String what, String codec, long now) {
        busyWhat = what;
        busyCodec = codec;
        busySince = now;
    }

    synchronized void leave() { busySince = 0; }

    /** A keyframe came and no decoder would start. */
    synchronized void noCodec(long now) {
        if (noCodecSince == 0) noCodecSince = now;
    }

    synchronized void codecStarted() { noCodecSince = 0; }

    /** A new stream: what went in before doesn't count. (Not when the decoder is only made again.) */
    synchronized void reset() {
        keySince = 0;
        noCodecSince = 0;
    }

    /** Why the decoder should be given up on now, or null while it's fine. */
    synchronized Trouble trouble(long now) {
        if (busySince != 0 && now - busySince >= GIVE_UP_NS)
            return new Trouble("stuck " + secs(now - busySince) + " " + busyWhat + " " + busyCodec, busyCodec, true);
        if (keySince != 0 && now - keySince >= GIVE_UP_NS)
            return new Trouble("no picture " + secs(now - keySince) + " after a keyframe", keyCodec, false);
        if (noCodecSince != 0 && now - noCodecSince >= GIVE_UP_NS)
            return new Trouble("no decoder would start for " + secs(now - noCodecSince), "", true);
        return null;
    }

    private static String secs(long ns) {
        return String.format(java.util.Locale.US, "%.1f s", ns / 1e9);
    }
}

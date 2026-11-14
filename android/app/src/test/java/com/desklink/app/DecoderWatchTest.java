package com.desklink.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When a decoder is given up on (DecoderWatch): what happened on 2026-11-14, a hardware decoder that took the video and
 * gave no picture while the session waited for the screen, must end within 4 s, and a working one never.
 */
public class DecoderWatchTest {
    private static final long S = 1_000_000_000L;
    private static final long T0 = 1_000 * S; // System.nanoTime() is any number

    @Test
    public void aDecoderThatGivesAPictureIsFine() {
        DecoderWatch w = new DecoderWatch();
        assertNull(w.trouble(T0));
        w.keyIn("OMX.qcom.video.decoder.avc", T0);
        w.pictureOut();
        assertNull(w.trouble(T0 + 60 * S));
    }

    @Test
    public void noPictureFourSecondsAfterAKeyframe() {
        DecoderWatch w = new DecoderWatch();
        w.keyIn("OMX.qcom.video.decoder.avc", T0);
        assertNull(w.trouble(T0 + 3_900_000_000L));
        DecoderWatch.Trouble t = w.trouble(T0 + 4 * S);
        assertNotNull(t);
        assertEquals("OMX.qcom.video.decoder.avc", t.codec);
        assertFalse(t.surface); // the decoder answers: the surface can be used again once it lets go of it
        assertTrue(t.why, t.why.startsWith("no picture 4.0 s"));
    }

    @Test
    public void moreKeyframesDontPutItOff() {
        // the PC sends a keyframe whenever the phone asks; a decoder that keeps failing and is made again keeps the
        // first one's time, or it would never be given up on
        DecoderWatch w = new DecoderWatch();
        w.keyIn("c2.qti.avc.decoder", T0);
        for (long t = T0 + S / 2; t < T0 + 4 * S; t += S / 2) w.keyIn("c2.qti.avc.decoder", t);
        assertNotNull(w.trouble(T0 + 4 * S));
    }

    @Test
    public void aNewStreamStartsOver() {
        DecoderWatch w = new DecoderWatch();
        w.keyIn("c2.qti.avc.decoder", T0);
        w.reset(); // reconnected, or the app came back: the PC starts a new stream
        assertNull(w.trouble(T0 + 10 * S));
        w.keyIn("c2.qti.avc.decoder", T0 + 10 * S);
        assertNull(w.trouble(T0 + 13 * S));
        assertNotNull(w.trouble(T0 + 14 * S));
    }

    @Test
    public void aCallThatHangs() {
        DecoderWatch w = new DecoderWatch();
        w.enter("starting", "OMX.qcom.video.decoder.avc", T0);
        assertNull(w.trouble(T0 + 3 * S));
        DecoderWatch.Trouble t = w.trouble(T0 + 5 * S);
        assertNotNull(t);
        assertEquals("OMX.qcom.video.decoder.avc", t.codec);
        assertTrue(t.surface); // it may never let go of the surface: the next one gets a new one
        assertTrue(t.why, t.why.contains("starting OMX.qcom.video.decoder.avc"));
        w.leave();
        assertNull(w.trouble(T0 + 6 * S));
    }

    @Test
    public void shortCallsAreFine() {
        DecoderWatch w = new DecoderWatch();
        for (long t = T0; t < T0 + 20 * S; t += S / 10) {
            w.enter("feeding", "c2.qti.avc.decoder", t);
            w.leave();
            assertNull(w.trouble(t + S / 20));
        }
    }

    @Test
    public void noDecoderWouldStart() {
        DecoderWatch w = new DecoderWatch();
        w.noCodec(T0);
        w.noCodec(T0 + 2 * S);
        DecoderWatch.Trouble t = w.trouble(T0 + 4 * S);
        assertNotNull(t);
        assertEquals("", t.codec);
        assertTrue(t.surface);
        w.codecStarted();
        assertNull(w.trouble(T0 + 5 * S));
    }
}

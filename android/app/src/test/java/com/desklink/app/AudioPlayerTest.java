package com.desklink.app;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.media.MediaFormat;

import com.desklink.core.Messages;
import com.desklink.core.Protocol;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioTrack;
import org.robolectric.shadows.ShadowMediaCodec;

import java.util.concurrent.atomic.AtomicInteger;

/** The player's own logic, with a stand-in decoder and speaker: frames from the PC must reach the speaker. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AudioPlayerTest {
    @Test
    public void framesFromThePcReachTheSpeaker() throws Exception {
        // the stand-in AAC decoder: each frame becomes 1024 stereo samples (4096 bytes)
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC, new ShadowMediaCodec.CodecConfig(2048, 4096,
                (in, out) -> {
                    in.position(in.limit());
                    while (out.hasRemaining()) out.put((byte) 1);
                }));
        AtomicInteger bytes = new AtomicInteger();
        ShadowAudioTrack.addAudioDataListener((track, data, format) -> bytes.addAndGet(data.length));

        AudioPlayer p = new AudioPlayer();
        Messages.AudioFormat f = new Messages.AudioFormat();
        f.codec = Protocol.AUDIO_CODEC_AAC_LC;
        f.sampleRate = 48000;
        f.channels = 2;
        f.config = new byte[] {0x11, (byte) 0x90};
        p.start(f);
        for (int i = 0; i < 10; i++) {
            p.feed(new byte[300], 0, 300);
            Thread.sleep(25);
        }
        Thread.sleep(300);
        String line = p.describe();
        String problem = p.takeProblem();
        p.stop();
        System.out.println("player: " + line + ", " + bytes.get() + " bytes to the speaker");
        assertNull(problem);
        assertTrue(line, bytes.get() > 0);
    }
}

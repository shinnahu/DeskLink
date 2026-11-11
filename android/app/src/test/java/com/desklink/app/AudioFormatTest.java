package com.desklink.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.desklink.core.ByteReader;
import com.desklink.core.Messages;
import com.desklink.core.Protocol;

import org.junit.Test;

/** AUDIO_FORMAT as the PC sends it (PROTOCOL.md): u8 codec, u32 sample rate, u8 channels, then the codec's config. */
public class AudioFormatTest {
    @Test
    public void aacFromThePc() {
        // what PC 2.7 sent in its end-to-end test: AAC-LC, 48 kHz, stereo, AudioSpecificConfig 11 90
        byte[] m = {0x01, 0x00, 0x00, (byte) 0xBB, (byte) 0x80, 0x02, 0x11, (byte) 0x90};
        Messages.AudioFormat f = Messages.AudioFormat.parse(new ByteReader(m));
        assertEquals(Protocol.AUDIO_CODEC_AAC_LC, f.codec);
        assertEquals(48000, f.sampleRate);
        assertEquals(2, f.channels);
        assertArrayEquals(new byte[] {0x11, (byte) 0x90}, f.config);
    }

    @Test
    public void noSoundOutputOnThePc() {
        byte[] m = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        Messages.AudioFormat f = Messages.AudioFormat.parse(new ByteReader(m));
        assertEquals(Protocol.AUDIO_CODEC_NONE, f.codec);
        assertEquals(0, f.config.length);
    }
}

package com.desklink.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which H.264 decoder is tried first (VideoDecoder.rank / order). The first is the one DeskLink always used; one that
 * gave no picture goes to the back of the line for 10 minutes, so the next try is another one, the software one last.
 */
public class DecoderOrderTest {
    private static VideoDecoder.Candidate hw(String name, String canonical, boolean lowLatency) {
        return new VideoDecoder.Candidate(name, canonical, true, lowLatency, null);
    }

    private static VideoDecoder.Candidate sw(String name, String canonical) {
        return new VideoDecoder.Candidate(name, canonical, false, false, null);
    }

    // as a Qualcomm phone may list them: OMX names as aliases of the Codec2 ones, the software decoder twice
    private static final List<VideoDecoder.Candidate> LISTED = Arrays.asList(
            sw("c2.android.avc.decoder", "c2.android.avc.decoder"),
            hw("OMX.qcom.video.decoder.avc", "c2.qti.avc.decoder", true),
            hw("c2.qti.avc.decoder", "c2.qti.avc.decoder", true),
            hw("c2.qti.avc.decoder.low_latency", "c2.qti.avc.decoder.low_latency", true),
            sw("OMX.google.h264.decoder", "c2.android.avc.decoder"));

    private static List<String> names(List<VideoDecoder.Candidate> l) {
        String[] out = new String[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i).name;
        return Arrays.asList(out);
    }

    @Test
    public void hardwareFirstEachCodecOnce() {
        assertEquals(Arrays.asList("OMX.qcom.video.decoder.avc", "c2.qti.avc.decoder.low_latency", "c2.android.avc.decoder"),
                names(VideoDecoder.rank(LISTED)));
    }

    @Test
    public void firstIsTheOneDeskLinkAlwaysUsed() {
        // 2.11 took the first decoder with the best score (hardware 2, low latency +1) in the phone's order
        VideoDecoder.Candidate best = null;
        for (VideoDecoder.Candidate c : LISTED) if (best == null || c.score() > best.score()) best = c;
        assertEquals(best.name, VideoDecoder.rank(LISTED).get(0).name);
    }

    @Test
    public void oneThatGaveNothingGoesLast() {
        List<VideoDecoder.Candidate> ranked = VideoDecoder.rank(LISTED);
        Map<String, Long> gave = new HashMap<>();
        long now = 5_000_000L;
        assertEquals("OMX.qcom.video.decoder.avc", VideoDecoder.order(ranked, gave, now).get(0));

        gave.put("c2.qti.avc.decoder", now - 1000); // kept by its canonical name: its alias goes with it
        assertEquals(Arrays.asList("c2.qti.avc.decoder.low_latency", "c2.android.avc.decoder", "OMX.qcom.video.decoder.avc"),
                VideoDecoder.order(ranked, gave, now));

        gave.put("c2.qti.avc.decoder.low_latency", now - 500); // both hardware ones gave nothing: software next
        assertEquals(Arrays.asList("c2.android.avc.decoder", "OMX.qcom.video.decoder.avc", "c2.qti.avc.decoder.low_latency"),
                VideoDecoder.order(ranked, gave, now));

        gave.put("c2.android.avc.decoder", now); // all of them: the one that failed longest ago first
        assertEquals(Arrays.asList("OMX.qcom.video.decoder.avc", "c2.qti.avc.decoder.low_latency", "c2.android.avc.decoder"),
                VideoDecoder.order(ranked, gave, now));
    }

    @Test
    public void forgottenAfterTenMinutes() {
        List<VideoDecoder.Candidate> ranked = VideoDecoder.rank(LISTED);
        Map<String, Long> gave = new HashMap<>();
        long now = 50_000_000L;
        gave.put("c2.qti.avc.decoder", now - VideoDecoder.FORGET_MS + 1);
        assertEquals("c2.qti.avc.decoder.low_latency", VideoDecoder.order(ranked, gave, now).get(0));
        gave.put("c2.qti.avc.decoder", now - VideoDecoder.FORGET_MS);
        assertEquals("OMX.qcom.video.decoder.avc", VideoDecoder.order(ranked, gave, now).get(0));
    }
}

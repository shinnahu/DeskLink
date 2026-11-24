package com.desklink.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the clipboard sync knows the phone clipboard holds (the keyboard putting that in at once is a paste), and what
 * the Send clipboard button sends.
 */
public class ClipSyncTest {
    /** The phone clipboard. */
    private String clip;
    private String label;
    private boolean text = true;       // false: a picture, say
    private boolean markedPrivate;     // by a password manager
    private boolean stamps = true;     // false: a phone that doesn't say when a copy was made
    private long stamp;
    private long seenStamp;
    private int seenHash;
    /** What went to the PC, in order: clipboard texts, and "share on" / "share off". */
    private final List<String> toPc = new ArrayList<>();

    private final ClipSync sync = new ClipSync(new ClipSync.Phone() {
        @Override public boolean canRead() { return true; }
        @Override public ClipSync.Info info() {
            return clip == null ? null : new ClipSync.Info(label, text, markedPrivate, stamps ? stamp : 0);
        }
        @Override public String text() { return clip == null ? "" : clip; }
        @Override public void set(String text) {
            clip = text;
            label = ClipSync.LABEL;
            stamp++;
        }
    }, new ClipSync.Seen() {
        @Override public long stamp() { return seenStamp; }
        @Override public int hash() { return seenHash; }
        @Override public void set(long s, int h) {
            seenStamp = s;
            seenHash = h;
        }
    });

    private final ClipSync.Pc pc = new ClipSync.Pc() {
        @Override public void send(String text) { toPc.add(text); }
        @Override public void share(boolean on) { toPc.add(on ? "share on" : "share off"); }
    };

    private void copyOnPhone(String text) {
        clip = text;
        label = "notes";
        stamp++;
    }

    @Test
    public void aCopySentToThePcIsKnown() {
        assertFalse(sync.holds("Edge has an enhance videos feature"));
        copyOnPhone("Edge has an enhance videos feature\n");
        assertTrue(sync.check(pc, 0));
        assertEquals(1, toPc.size());
        assertTrue(sync.holds("Edge has an enhance videos feature\n"));
        assertTrue("\r\n and \n are the same line break", sync.holds("Edge has an enhance videos feature\r\n"));
        assertFalse(sync.holds("Edge has an enhance videos feature"));
        assertFalse(sync.holds(""));
    }

    @Test
    public void thePcsClipboardPutOnThePhoneIsKnown() {
        sync.check(pc, 0); // looked at: nothing on the phone
        sync.fromPc("from the PC\r\nsecond line", 10_000);
        assertEquals("from the PC\r\nsecond line", clip);
        assertTrue(sync.holds("from the PC\nsecond line"));
    }

    @Test
    public void aPrivateCopyIsNotKnown() {
        copyOnPhone("first");
        sync.check(pc, 0);
        assertTrue(sync.holds("first"));
        copyOnPhone("a password");
        markedPrivate = true; // by a password manager: not sent, and the old text is no longer there
        sync.check(pc, 1);
        assertEquals(1, toPc.size());
        assertFalse(sync.holds("first"));
        assertFalse(sync.holds("a password"));
    }

    // ------------------------------------------------------------------ the Send clipboard button

    @Test
    public void sendClipboardSendsACopyThatAlreadyWent() {
        copyOnPhone("hello");
        assertTrue(sync.check(pc, 0));
        assertFalse("the sync sends a copy once", sync.check(pc, 1));
        assertTrue(sync.send(pc, true));
        assertEquals(Arrays.asList("hello", "hello"), toPc);
    }

    @Test
    public void withTheSyncOffTheClipboardsAreSharedForThatOneMessage() {
        copyOnPhone("hello");
        assertTrue(sync.send(pc, false));
        assertEquals(Arrays.asList("share on", "hello", "share off"), toPc);
    }

    @Test
    public void withNothingToSendTheSharingIsLeftAlone() {
        assertFalse(sync.send(pc, false));
        assertTrue(toPc.isEmpty());
    }

    @Test
    public void sendClipboardSendsWhatCameFromThePc() {
        sync.check(pc, 0);
        sync.fromPc("from the PC", 10_000);
        assertFalse("the sync doesn't send the PC its own text", sync.check(pc, 10_001));
        assertTrue(sync.send(pc, true));
        assertEquals(Collections.singletonList("from the PC"), toPc);
    }

    @Test
    public void sendClipboardSendsACopyMarkedPrivate() {
        copyOnPhone("a password");
        markedPrivate = true;
        assertFalse("the sync leaves it out", sync.check(pc, 0));
        assertTrue(sync.send(pc, true));
        assertEquals(Collections.singletonList("a password"), toPc);
        assertTrue(sync.holds("a password"));
    }

    @Test
    public void afterSendClipboardTheSyncDoesNotSendTheCopyAgain() {
        copyOnPhone("new on the phone");
        assertTrue(sync.send(pc, true));
        assertFalse(sync.check(pc, 0));
        assertEquals(1, toPc.size());
        assertTrue("the keyboard putting it in at once is a paste", sync.holds("new on the phone"));
    }

    @Test
    public void norOnAPhoneWithoutClipboardStamps() {
        stamps = false;
        copyOnPhone("new on the phone");
        assertTrue(sync.send(pc, true));
        assertFalse(sync.check(pc, 0));
        assertEquals(1, toPc.size());
    }

    @Test
    public void thePcsOlderClipboardDoesNotReplaceWhatWasSent() {
        // a stream starts: the PC's clipboard comes before the phone's has been looked at, and waits
        sync.restart();
        copyOnPhone("from the phone");
        sync.fromPc("older, from the PC", 0);
        assertTrue(sync.send(pc, true));
        sync.check(pc, 1);
        assertEquals("from the phone", clip);
        assertEquals(Collections.singletonList("from the phone"), toPc);
    }

    @Test
    public void thePcSendingTheTextBackIsNotANewCopy() {
        copyOnPhone("line one\nline two");
        assertTrue(sync.send(pc, true));
        long before = stamp;
        sync.fromPc("line one\r\nline two", 10_000); // with Windows' line breaks
        assertEquals("the phone clipboard is left as it is", before, stamp);
    }

    @Test
    public void aCopyMadeOnThePcRightAfterStillComesToThePhone() {
        copyOnPhone("from the phone");
        assertTrue(sync.send(pc, true));
        sync.fromPc("copied on the PC a moment later", 300);
        assertEquals("copied on the PC a moment later", clip);
    }

    @Test
    public void sendClipboardWithoutTextSendsNothing() {
        assertFalse("an empty clipboard", sync.send(pc, true));
        copyOnPhone("content://media/external/images/12");
        text = false; // a picture
        assertFalse(sync.send(pc, true));
        copyOnPhone("");
        text = true;
        assertFalse("text with nothing in it", sync.send(pc, true));
        assertTrue(toPc.isEmpty());
    }
}

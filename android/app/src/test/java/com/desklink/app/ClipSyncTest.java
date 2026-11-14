package com.desklink.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** What the clipboard sync knows the phone clipboard holds: the keyboard putting that in at once is a paste. */
public class ClipSyncTest {
    /** The phone clipboard. */
    private String clip;
    private String label;
    private boolean sendable = true;
    private long stamp;
    private long seenStamp;
    private int seenHash;
    private final List<String> toPc = new ArrayList<>();

    private final ClipSync sync = new ClipSync(new ClipSync.Phone() {
        @Override public boolean canRead() { return true; }
        @Override public ClipSync.Info info() { return clip == null ? null : new ClipSync.Info(label, sendable, stamp); }
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

    private void copyOnPhone(String text) {
        clip = text;
        label = "notes";
        stamp++;
    }

    @Test
    public void aCopySentToThePcIsKnown() {
        assertFalse(sync.holds("Edge has an enhance videos feature"));
        copyOnPhone("Edge has an enhance videos feature\n");
        assertTrue(sync.check(toPc::add, 0));
        assertEquals(1, toPc.size());
        assertTrue(sync.holds("Edge has an enhance videos feature\n"));
        assertTrue("\r\n and \n are the same line break", sync.holds("Edge has an enhance videos feature\r\n"));
        assertFalse(sync.holds("Edge has an enhance videos feature"));
        assertFalse(sync.holds(""));
    }

    @Test
    public void thePcsClipboardPutOnThePhoneIsKnown() {
        sync.check(toPc::add, 0); // looked at: nothing on the phone
        sync.fromPc("from the PC\r\nsecond line", 10_000);
        assertEquals("from the PC\r\nsecond line", clip);
        assertTrue(sync.holds("from the PC\nsecond line"));
    }

    @Test
    public void aPrivateCopyIsNotKnown() {
        copyOnPhone("first");
        sync.check(toPc::add, 0);
        assertTrue(sync.holds("first"));
        copyOnPhone("a password");
        sendable = false; // marked private by a password manager: not sent, and the old text is no longer there
        sync.check(toPc::add, 1);
        assertEquals(1, toPc.size());
        assertFalse(sync.holds("first"));
        assertFalse(sync.holds("a password"));
    }
}

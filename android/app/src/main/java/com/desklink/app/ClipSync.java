package com.desklink.app;

/**
 * Clipboard sync between the phone and the PC. The newest copy wins:
 * <ul>
 * <li>A copy made on the phone goes to the PC as soon as DeskLink is in front (Android lets an app read the
 * clipboard only then). Paste sends it first if that hasn't happened yet.</li>
 * <li>A copy made on the PC comes to the phone right away.</li>
 * <li>When a connection starts (or sync is turned on) the PC sends what its clipboard holds. That is applied only
 * after the phone clipboard has been looked at, and only if the phone has no newer copy of its own. It used to
 * replace a copy just made on the phone before the phone could send it.</li>
 * </ul>
 * The Send clipboard button ({@link #send}) is outside those rules: what the phone holds goes to the PC when asked.
 * Plain Java (the Android clipboard is behind {@link Phone}) so these rules can be checked off the phone.
 */
final class ClipSync {
    /** Label of what DeskLink puts on the phone clipboard: a clip with this label came from the PC. */
    static final String LABEL = "DeskLink";
    /** After a new copy on the phone, a PC clipboard arriving this soon is the older one (sent when a stream starts). */
    static final long IGNORE_PC_MS = 2000;

    /** The phone clipboard's description. Unlike reading the clip, reading this doesn't show Android's "pasted" notice. */
    static final class Info {
        final CharSequence label;
        final boolean text;     // it holds text
        final boolean sendable; // text, and not marked private by a password manager
        final long stamp;       // when it was copied (ClipDescription.getTimestamp()); 0 = this phone doesn't say

        Info(CharSequence label, boolean text, boolean markedPrivate, long stamp) {
            this.label = label;
            this.text = text;
            this.sendable = text && !markedPrivate;
            this.stamp = stamp;
        }
    }

    interface Phone {
        /** Android lets an app read the clipboard only while its window has focus. */
        boolean canRead();

        /** null if the clipboard is empty. */
        Info info();

        /** The clipboard as text, "" if none. Reading it makes Android 12+ show "DeskLink pasted from your clipboard". */
        String text();

        /** Puts text from the PC on the clipboard, labelled {@link #LABEL}. */
        void set(String text);
    }

    /** The last phone copy looked at, kept across sessions: its stamp, or with stamp 0 a hash of its text. */
    interface Seen {
        long stamp();

        int hash();

        void set(long stamp, int hash);
    }

    interface Pc {
        void send(String text);

        /** Tells the PC whether the two clipboards are shared (the Sync clipboard setting, on the PC's side). */
        default void share(boolean on) {}
    }

    private final Phone phone;
    private final Seen seen;
    private boolean checked;  // the phone clipboard was looked at since the PC was last asked for its clipboard
    private String held;      // the PC's clipboard, arrived before that
    private long ignoreUntil; // see IGNORE_PC_MS
    private String lastSent;  // normalized; the PC sending it back is an echo, not a new copy
    private String known;     // normalized: what the phone clipboard held when last looked at or set here; null: unknown

    ClipSync(Phone phone, Seen seen) {
        this.phone = phone;
        this.seen = seen;
    }

    /** The PC is about to send its clipboard (new connection, or sync turned on): hold it until the phone is checked. */
    void restart() {
        checked = false;
        held = null;
        ignoreUntil = 0;
    }

    /** Sends a copy made on the phone that hasn't gone to a PC yet. Returns true if it sent one. */
    boolean check(Pc pc, long now) {
        if (!phone.canRead()) return false;
        String copy = newPhoneCopy();
        boolean sent = copy != null && !copy.isEmpty();
        // a new copy on the phone, sent or not, is newer than the clipboard the PC sends when a stream starts
        if (copy != null) {
            ignoreUntil = now + IGNORE_PC_MS;
            known = sent ? normalize(copy) : null;
        }
        if (sent) {
            pc.send(copy);
            lastSent = normalize(copy);
        }
        if (!checked) {
            checked = true;
            String h = held;
            held = null;
            if (copy == null && h != null) apply(h); // nothing new on the phone: take the PC's
        }
        return sent;
    }

    /**
     * The Send clipboard button: the text the phone clipboard holds goes to the PC now, whatever the sync did with
     * it. A copy that went already goes again, and so do text that came from the PC and text a password manager
     * marked private (the sync leaves that one out; here it was asked for). shared: Sync clipboard is on. A PC takes
     * a clipboard only while the two are shared, so with the sync off they're shared for this one message and no
     * longer. Returns false, with nothing sent, when the clipboard holds no text or can't be read.
     */
    boolean send(Pc pc, boolean shared) {
        String s;
        try {
            Info d = phone.info();
            if (d == null || !d.text) return false;
            s = phone.text();
            if (s.isEmpty()) return false;
            // looked at: the sync doesn't send it a second time
            if (!isOurs(d)) seen.set(d.stamp, d.stamp != 0 ? 0 : normalize(s).hashCode());
        } catch (RuntimeException e) {
            return false; // not allowed right now
        }
        if (!shared) pc.share(true);
        pc.send(s);
        if (!shared) pc.share(false);
        lastSent = known = normalize(s);
        checked = true; // a clipboard the PC sent before this one is the older of the two
        held = null;
        return true;
    }

    /** The PC's clipboard arrived. */
    void fromPc(String text, long now) {
        if (text == null || text.isEmpty()) return;
        if (now < ignoreUntil) return;                // older than the copy just made on the phone
        if (normalize(text).equals(lastSent)) return; // the phone's own copy coming back
        if (!checked) {
            held = text; // until the phone clipboard has been looked at
            return;
        }
        apply(text);
    }

    /**
     * null if the phone clipboard holds nothing new: empty, put there by DeskLink, or already looked at. Otherwise
     * the text to send, or "" for a new copy that isn't sent (not text, or private), which still wins over the PC's.
     * The description comes first so the clip itself is only read (and Android's notice shown) for something new.
     */
    private String newPhoneCopy() {
        try {
            Info d = phone.info();
            if (d == null || isOurs(d)) return null;
            if (d.stamp != 0) {
                if (d.stamp == seen.stamp()) return null;
                seen.set(d.stamp, 0);
                return d.sendable ? phone.text() : "";
            }
            // a phone that doesn't keep clipboard timestamps: compare the text instead
            if (!d.sendable) return null;
            String s = phone.text();
            int h = normalize(s).hashCode();
            if (s.isEmpty() || h == seen.hash()) return null;
            seen.set(0, h);
            return s;
        } catch (RuntimeException e) {
            return null; // not allowed right now
        }
    }

    private void apply(String text) {
        lastSent = null;
        try {
            // the same text already came from the PC: don't set it again
            Info d = phone.canRead() ? phone.info() : null;
            if (d == null || !isOurs(d) || !normalize(phone.text()).equals(normalize(text))) phone.set(text);
            known = normalize(text);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * True if text is what the phone clipboard holds, as far as this knows: the copy it last sent to the PC, or the
     * PC's that it put there. The keyboard putting that in at once is a paste.
     */
    boolean holds(String text) {
        return known != null && text != null && !text.isEmpty() && known.equals(normalize(text));
    }

    private static boolean isOurs(Info d) {
        return d.label != null && LABEL.contentEquals(d.label);
    }

    /** The PC uses \r\n line ends; compare without them. */
    static String normalize(String s) {
        return s.replace("\r\n", "\n");
    }
}

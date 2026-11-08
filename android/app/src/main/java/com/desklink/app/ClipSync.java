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
        final boolean sendable; // text, and not marked private by a password manager
        final long stamp;       // when it was copied (ClipDescription.getTimestamp()); 0 = this phone doesn't say

        Info(CharSequence label, boolean sendable, long stamp) {
            this.label = label;
            this.sendable = sendable;
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
    }

    private final Phone phone;
    private final Seen seen;
    private boolean checked;  // the phone clipboard was looked at since the PC was last asked for its clipboard
    private String held;      // the PC's clipboard, arrived before that
    private long ignoreUntil; // see IGNORE_PC_MS
    private String lastSent;  // normalized; the PC sending it back is an echo, not a new copy

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
        if (copy != null) ignoreUntil = now + IGNORE_PC_MS;
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
            if (d != null && isOurs(d) && normalize(phone.text()).equals(normalize(text))) return;
            phone.set(text);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean isOurs(Info d) {
        return d.label != null && LABEL.contentEquals(d.label);
    }

    /** The PC uses \r\n line ends; compare without them. */
    static String normalize(String s) {
        return s.replace("\r\n", "\n");
    }
}

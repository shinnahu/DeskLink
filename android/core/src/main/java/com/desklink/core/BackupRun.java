package com.desklink.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A backup of the phone's photos and videos to a PC ({@link Protocol#CAP_BACKUP}), on a side connection: it asks the
 * PC what it has already (BACKUP_CHECK), sends the rest one file at a time, each from where the PC got to, and when
 * the connection drops it comes back by itself and goes on. {@link #run} works on the calling thread until the backup
 * is over or {@link #stop} is called. Nothing of Android in here: the app gives the list, opens the files, and says
 * when the network may carry the backup (not over mobile data).
 */
public final class BackupRun {
    /** One photo or video. */
    public static final class Item {
        /** Its folder on the phone, '/' between the parts ("DCIM/Camera"). */
        public final String folder;
        public final String name, mime;
        public final long size;
        /** When the phone last changed it (unix ms; 0 = not known): the file gets this date on the PC. */
        public final long modifiedMs;
        /** The app's handle for opening it (a content Uri). */
        public final Object ref;

        public Item(String folder, String name, String mime, long size, long modifiedMs, Object ref) {
            this.folder = folder == null ? "" : folder;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.modifiedMs = modifiedMs;
            this.ref = ref;
        }
    }

    /** An opened file: its data from the offset asked for, and its size as it is now. */
    public static final class Opened {
        public final InputStream in;
        public final long size;

        public Opened(InputStream in, long size) {
            this.in = in;
            this.size = size;
        }
    }

    public interface Files {
        /** The file's data from {@code offset} on, and its size now. IOException: it can't be read (deleted since). */
        Opened open(Item item, long offset) throws IOException;
    }

    /** From {@link Link#connect}: the PC is known to be older than 2.12 (it doesn't take backups). */
    public static final class OldPcException extends IOException {
        public OldPcException() { super("DeskLink on the PC is older than 2.12"); }
    }

    public interface Link {
        /** A side connection to the PC ({@link Protocol#AUTH_SIDE}); {@link OldPcException} for a PC too old. */
        HostConnection connect() throws IOException;

        /** Whether the network may carry the backup now (the app: Wi-Fi, not mobile data). */
        boolean allowed();
    }

    public enum Phase { CONNECTING, CHECKING, SENDING, WAITING_NETWORK, WAITING_PC }

    /** How it ended. */
    public enum Outcome {
        /** Everything is on the PC (except the files counted as failed: they couldn't be read). */
        DONE,
        /** {@link #stop} was called. */
        STOPPED,
        /** No progress for {@link #giveUpMs}: the PC couldn't be reached, or no Wi-Fi came. */
        UNREACHABLE,
        /** The PC's DeskLink is older than 2.12: it doesn't take backups. */
        OLD_PC,
        /** The PC turned file after file away (its disk is full, say). */
        PC_REFUSED
    }

    /** Where it is, for the notification and the Settings page. A copy is given to the listener each time. */
    public static final class Status {
        public Phase phase = Phase.CONNECTING;
        /** Photos and videos on the phone, and their bytes. */
        public int files;
        public long bytes;
        /** Of those: on the PC now (they were there already, or came this time), with the file being sent so far. */
        public int done;
        public long bytesDone;
        /** Sent this time, and their bytes. */
        public int sent;
        public long sentBytes;
        /** Couldn't be read on the phone, or the PC took them twice no more (left out). */
        public int failed;
        /** The last thing that went wrong (for the PC's log and the notification), or null. */
        public String problem;

        Status copy() {
            Status s = new Status();
            s.phase = phase;
            s.files = files;
            s.bytes = bytes;
            s.done = done;
            s.bytesDone = bytesDone;
            s.sent = sent;
            s.sentBytes = sentBytes;
            s.failed = failed;
            s.problem = problem;
            return s;
        }
    }

    public interface Listener {
        /** Any thread; at most about once a second while a file goes, and at each change of phase. */
        void onStatus(Status s);
    }

    /** No progress for this long while the network may be used (the PC can't be reached): {@link Outcome#UNREACHABLE}. */
    public long giveUpMs = 20 * 60_000L;
    /** No Wi-Fi for this long: {@link Outcome#UNREACHABLE} too. */
    public long waitNetworkMs = 2 * 60 * 60_000L;
    /** How long a BACKUP_CHECK may take. */
    public int checkTimeoutMs = 30_000;
    /** Wait between tries to reach the PC: it doubles from the first up to the last. */
    public long retryFirstMs = 2000, retryMaxMs = 30_000;

    private final List<Item> items;
    private final Files files;
    private final Link link;
    private final Listener listener;
    private final Status st = new Status();
    private volatile boolean stopped;
    private volatile HostConnection conn;
    private volatile HostConnection.Outgoing current;
    private volatile long fileAcked; // of the file being sent: what the PC has of it (set on the connection's thread)
    private final Object sleeper = new Object();
    private long lastReport;

    public BackupRun(List<Item> items, Files files, Link link, Listener listener) {
        this.items = new ArrayList<>(items);
        this.files = files;
        this.link = link;
        this.listener = listener;
        st.files = items.size();
        for (Item i : items) st.bytes += Math.max(0, i.size);
    }

    /** Stops it from any thread: the file being sent stops (the PC keeps its part for the next time). */
    public void stop() {
        stopped = true;
        HostConnection.Outgoing o = current;
        if (o != null) o.cancel();
        HostConnection c = conn;
        if (c != null) c.disconnect();
        synchronized (sleeper) {
            sleeper.notifyAll();
        }
    }

    public boolean stopped() { return stopped; }

    /** The backup, start to end, on this thread. */
    public Outcome run() {
        List<Item> pending = new ArrayList<>(items);
        Map<Item, Integer> tries = new IdentityHashMap<>();
        int refusedInARow = 0;
        long lastProgress = System.currentTimeMillis();
        long noNetworkSince = 0;
        long wait = retryFirstMs;
        while (!stopped) {
            if (pending.isEmpty()) return ended(null, Outcome.DONE);
            if (!link.allowed()) {
                long now = System.currentTimeMillis();
                if (noNetworkSince == 0) noNetworkSince = now;
                if (now - noNetworkSince > waitNetworkMs) return ended(null, Outcome.UNREACHABLE);
                phase(Phase.WAITING_NETWORK);
                sleep(3000);
                continue;
            }
            if (noNetworkSince != 0) { // Wi-Fi is back: the PC gets its full time again
                noNetworkSince = 0;
                lastProgress = System.currentTimeMillis();
                wait = retryFirstMs;
            }
            if (System.currentTimeMillis() - lastProgress > giveUpMs) return ended(null, Outcome.UNREACHABLE);
            HostConnection c;
            try {
                phase(Phase.CONNECTING);
                c = link.connect();
            } catch (OldPcException e) {
                return ended(null, Outcome.OLD_PC);
            } catch (IOException | RuntimeException e) {
                st.problem = "can't reach the PC: " + e.getMessage();
                phase(Phase.WAITING_PC);
                sleep(wait);
                wait = Math.min(wait * 2, retryMaxMs);
                continue;
            }
            conn = c;
            if (stopped) break;
            Messages.HostInfo info = c.hostInfo();
            if (info == null || !info.can(Protocol.CAP_BACKUP)) {
                c.disconnect();
                return ended(null, Outcome.OLD_PC);
            }
            try {
                // what the PC has already
                phase(Phase.CHECKING);
                Map<Item, Long> have = check(c, pending);
                for (Iterator<Item> it = pending.iterator(); it.hasNext(); ) {
                    Item i = it.next();
                    Long h = have.get(i);
                    if (h != null && h >= i.size) {
                        it.remove();
                        st.done++;
                        st.bytesDone += i.size;
                    }
                }
                lastProgress = System.currentTimeMillis();
                wait = retryFirstMs;
                report(true);
                // the rest, one at a time
                for (Item i : new ArrayList<>(pending)) {
                    if (stopped || c.isClosed() || !link.allowed()) break;
                    Long h = have.get(i);
                    long offset = h == null ? 0 : h;
                    Opened o;
                    try {
                        o = files.open(i, offset);
                        if (o.size != i.size && offset > 0) {
                            // changed since the list was made: all of it, as it is now (the PC had the old one's part)
                            closeQuietly(o.in);
                            offset = 0;
                            o = files.open(i, 0);
                        }
                    } catch (IOException | RuntimeException e) {
                        st.problem = "can't read " + i.name + ": " + e.getMessage();
                        pending.remove(i);
                        st.failed++;
                        st.done++;
                        report(true);
                        continue;
                    }
                    phase(Phase.SENDING);
                    long before = st.bytesDone;
                    int status = send(c, i, o, offset, before);
                    st.bytesDone = before;
                    if (status == Protocol.FD_OK) {
                        pending.remove(i);
                        st.done++;
                        st.bytesDone += i.size;
                        st.sent++;
                        st.sentBytes += o.size - offset;
                        lastProgress = System.currentTimeMillis();
                        refusedInARow = 0;
                        report(false);
                        continue;
                    }
                    if (stopped || c.isClosed()) break;
                    // The PC said no (its disk is full, a folder it can't make), or the file couldn't be read to its
                    // end: it goes again later, once; then it's left out. File after file turned away: the PC can't.
                    int n = tries.merge(i, 1, Integer::sum);
                    if (n >= 2) {
                        pending.remove(i);
                        st.failed++;
                        st.done++;
                    }
                    report(true);
                    if (++refusedInARow >= 5) {
                        c.disconnect();
                        return ended(null, Outcome.PC_REFUSED);
                    }
                }
                if (pending.isEmpty() && !stopped && !c.isClosed()) return ended(c, Outcome.DONE);
            } catch (IOException e) {
                st.problem = "connection: " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
            } finally {
                conn = null;
            }
            if (!c.isClosed()) c.disconnect(); // (Wi-Fi gone, or stopped)
            if (!stopped && link.allowed()) {
                phase(Phase.WAITING_PC);
                sleep(wait);
                wait = Math.min(wait * 2, retryMaxMs);
            }
        }
        return ended(null, Outcome.STOPPED);
    }

    // ------------------------------------------------------------------ internals

    private Map<Item, Long> check(HostConnection c, List<Item> pending) throws IOException {
        Map<Item, Long> have = new IdentityHashMap<>();
        for (int from = 0; from < pending.size() && !stopped; from += Protocol.BACKUP_CHECK_MAX) {
            List<Item> part = pending.subList(from, Math.min(pending.size(), from + Protocol.BACKUP_CHECK_MAX));
            String[] folders = new String[part.size()], names = new String[part.size()];
            long[] sizes = new long[part.size()];
            for (int k = 0; k < part.size(); k++) {
                folders[k] = part.get(k).folder;
                names[k] = part.get(k).name;
                sizes[k] = part.get(k).size;
            }
            long[] h = c.backupCheck(folders, names, sizes, checkTimeoutMs);
            for (int k = 0; k < part.size(); k++) have.put(part.get(k), h[k]);
        }
        return have;
    }

    /** One file; the PC's FILE_DONE status, or FD_ERROR when the connection ended first. */
    private int send(HostConnection c, Item i, Opened o, long offset, long doneBefore) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final int[] status = {Protocol.FD_ERROR};
        final String[] info = {""};
        fileAcked = offset;
        current = c.sendBackupFile(i.name, o.size, i.mime, i.folder, i.modifiedMs, offset, o.in,
                new HostConnection.SendListener() {
                    @Override
                    public void onProgress(long acked, long size) { fileAcked = acked; }

                    @Override
                    public void onDone(int s, String inf) {
                        status[0] = s;
                        info[0] = inf;
                        done.countDown();
                    }
                });
        while (!done.await(250, TimeUnit.MILLISECONDS)) {
            if (stopped) current.cancel();
            st.bytesDone = doneBefore + Math.min(fileAcked, i.size); // (the status is only touched on this thread)
            report(false);
        }
        current = null;
        if (status[0] != Protocol.FD_OK) st.problem = i.name + ": " + info[0];
        return status[0];
    }

    private Outcome ended(HostConnection c, Outcome o) {
        if (c != null) {
            c.backupEnd(st.sent, st.sentBytes, st.failed); // one notice on the PC
            c.disconnect(); // (BYE goes after it)
        }
        report(true);
        return o;
    }

    private void phase(Phase p) {
        boolean changed = st.phase != p;
        st.phase = p;
        report(changed);
    }

    private void report(boolean now) {
        long t = System.currentTimeMillis();
        if (!now && t - lastReport < 1000) return;
        lastReport = t;
        if (listener != null) listener.onStatus(st.copy());
    }

    private void sleep(long ms) {
        synchronized (sleeper) {
            if (stopped) return;
            try {
                sleeper.wait(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
            }
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
    }
}

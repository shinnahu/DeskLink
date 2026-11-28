package com.desklink.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.format.Formatter;

import com.desklink.core.BackupRun;
import com.desklink.core.HostConnection;
import com.desklink.core.Messages;
import com.desklink.core.PairedHost;
import com.desklink.core.Protocol;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Settings → Back up photos and videos: every photo and video on the phone goes to the PC (DeskLink 2.12), into
 * Pictures\DeskLink backup\<phone>, in the phone's folders, with their dates. What the PC has already stays where it
 * is, and a file cut off goes on from where it got to (BackupRun). Only over Wi-Fi: on mobile data it waits.
 *
 * It runs as a foreground service with a notification (how far it got, Stop), keeping the phone's processor and
 * Wi-Fi awake, so it goes on with the screen off or in another app. At the end, one notification says how it went.
 */
public final class BackupService extends Service {
    static final String ACTION_START = "com.desklink.app.backup.START";
    static final String ACTION_STOP = "com.desklink.app.backup.STOP";
    static final String EXTRA_HOST = "hostId";
    private static final String CHANNEL = "backup";
    private static final int NOTE_RUNNING = 21, NOTE_DONE = 22;

    // ------------------------------------------------------------------ what the Settings page shows (main thread)

    interface Watcher {
        void backupChanged();
    }

    private static final List<Watcher> watchers = new ArrayList<>();
    private static volatile boolean running;
    private static volatile String runningPc = "";
    private static volatile BackupRun.Status status;

    static boolean running() { return running; }

    static String runningPc() { return runningPc; }

    /** Where the backup is (null before it has looked at the phone). */
    static BackupRun.Status status() { return status; }

    static void watch(Watcher w) {
        if (!watchers.contains(w)) watchers.add(w);
    }

    static void unwatch(Watcher w) { watchers.remove(w); }

    private static void changed() {
        for (Watcher w : new ArrayList<>(watchers)) w.backupChanged();
    }

    /** Starts a backup to that PC (from the foreground: Settings, or Continue in the notification). */
    static void start(Context c, String hostId) {
        Intent i = new Intent(c, BackupService.class).setAction(ACTION_START).putExtra(EXTRA_HOST, hostId);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        if (!running) return;
        c.startService(new Intent(c, BackupService.class).setAction(ACTION_STOP));
    }

    /** Tests: the photos and videos to back up, instead of the media store's; how they're opened; and whether the
     *  network may be used. */
    static List<BackupRun.Item> testItems;
    static BackupRun.Files testFiles;
    static Predicate<Context> networkOk = BackupService::onWifi;

    /** The README's pictures (PreviewShots): a backup on show as if it ran (null: none). */
    static void showForPreview(String pc, BackupRun.Status s) {
        running = s != null;
        runningPc = pc;
        status = s;
        changed();
    }

    // ------------------------------------------------------------------ the service

    private final Handler main = new Handler(Looper.getMainLooper());
    private HostStore store;
    private BackupRun run;
    private Thread worker;
    private String hostId, pcName = "";
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private long lastNote;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new HostStore(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            BackupRun r = run;
            if (r != null) r.stop();
            else if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        String id = intent == null ? null : intent.getStringExtra(EXTRA_HOST);
        PairedHost h = id == null ? null : store.get(id);
        // (a start must always reach startForeground, even one that ends at once)
        foreground(running(h != null ? h.name() : pcName, null));
        if (worker != null) return START_NOT_STICKY; // one at a time: this one goes on
        if (h == null) {
            finishNow();
            return START_NOT_STICKY;
        }
        hostId = id;
        pcName = h.name();
        holdAwake();
        running = true;
        runningPc = pcName;
        status = null;
        changed();
        worker = new Thread(this::work, "dl-backup");
        worker.start();
        return START_NOT_STICKY;
    }

    /** Android 15: a data transfer may run 6 hours a day at most. It stops; Continue goes on from there. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        BackupRun r = run;
        if (r != null) r.stop();
    }

    @Override
    public void onDestroy() {
        BackupRun r = run;
        if (r != null) r.stop();
        letGo();
        super.onDestroy();
    }

    private void work() {
        List<BackupRun.Item> items = testItems != null ? testItems : BackupScan.list(this);
        final Context ctx = this;
        final String dev = Prefs.deviceName(this);
        BackupRun.Files files = testFiles != null ? testFiles : (item, offset) -> BackupScan.open(ctx, item, offset);
        BackupRun r = new BackupRun(items, files, new BackupRun.Link() {
            @Override
            public HostConnection connect() throws IOException {
                PairedHost host = store.get(hostId);
                if (host == null) throw new IOException("the PC was forgotten");
                // A PC before 2.12 would take this for a new remote session and close the one open: not to that one.
                HostConnection live = ActiveSessions.get(hostId);
                Messages.HostInfo info = live == null ? null : live.hostInfo();
                if (info != null && !info.can(Protocol.CAP_BACKUP)) throw new BackupRun.OldPcException();
                HostConnection c = HostConnection.connect(host, dev, null, 8000, Protocol.AUTH_SIDE);
                final String address = c.address();
                store.update(hostId, x -> {
                    x.lastSeenOnline = System.currentTimeMillis();
                    x.lastAddress = address;
                });
                return c;
            }

            @Override
            public boolean allowed() { return networkOk.test(ctx); }
        }, s -> {
            status = s;
            main.post(() -> {
                note(s);
                changed();
            });
        });
        run = r;
        final BackupRun.Outcome outcome = r.run();
        final BackupRun.Status last = status;
        main.post(() -> finished(outcome, last, items.size()));
    }

    private void finished(BackupRun.Outcome o, BackupRun.Status s, int files) {
        run = null;
        worker = null;
        letGo();
        running = false;
        if (o == BackupRun.Outcome.DONE) new Prefs(this).setLastBackup(System.currentTimeMillis(), pcName, files);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTE_DONE, result(o, s, files));
        finishNow();
        changed();
    }

    private void finishNow() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------ notifications

    private void foreground(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTE_RUNNING, n);
    }

    private void note(BackupRun.Status s) {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (now - lastNote < 900 && s.phase == BackupRun.Phase.SENDING) return; // (Android drops faster updates)
        lastNote = now;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTE_RUNNING, running(pcName, s));
    }

    private Notification.Builder builder() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.note_channel_backup),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return b.setSmallIcon(R.drawable.ic_upload)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setShowWhen(false);
    }

    /** While it runs: how far it got, and Stop. */
    private Notification running(String pc, BackupRun.Status s) {
        Notification.Builder b = builder()
                .setContentTitle(getString(R.string.backup_to, pc))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BackupService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(new Notification.Action.Builder(null, getString(R.string.backup_stop), stop).build());
        if (s == null) {
            b.setContentText(getString(R.string.backup_looking)).setProgress(0, 0, true);
        } else {
            b.setContentText(line(this, s, pc));
            boolean counting = s.phase == BackupRun.Phase.CONNECTING || s.phase == BackupRun.Phase.CHECKING;
            b.setProgress(1000, s.bytes > 0 ? (int) (s.bytesDone * 1000 / s.bytes) : 0, counting && s.done == 0);
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    /** "1,234 of 3,456 · 2.1 GB of 8.0 GB", or what it's waiting for. */
    static String line(Context c, BackupRun.Status s, String pc) {
        switch (s.phase) {
            case WAITING_NETWORK: return c.getString(R.string.backup_waiting_wifi);
            case WAITING_PC: return c.getString(R.string.backup_waiting_pc, pc);
            case CONNECTING:
                if (s.done == 0) return c.getString(R.string.backup_connecting, pc);
                break;
            case CHECKING:
                if (s.done == 0) return c.getString(R.string.backup_checking, pc);
                break;
            default:
                break;
        }
        NumberFormat n = NumberFormat.getIntegerInstance();
        return c.getString(R.string.backup_progress, n.format(s.done), n.format(s.files),
                Formatter.formatShortFileSize(c, s.bytesDone), Formatter.formatShortFileSize(c, s.bytes));
    }

    /** At the end: done, or stopped (and why), with Continue. */
    private Notification result(BackupRun.Outcome o, BackupRun.Status s, int files) {
        NumberFormat n = NumberFormat.getIntegerInstance();
        Notification.Builder b = builder().setAutoCancel(true).setShowWhen(true);
        String text;
        if (o == BackupRun.Outcome.DONE) {
            b.setContentTitle(getString(R.string.backup_done));
            text = getResources().getQuantityString(R.plurals.backup_done_files, files, n.format(files), pcName);
            if (s != null && s.failed > 0)
                text += " · " + getResources().getQuantityString(R.plurals.backup_left_out, s.failed, n.format(s.failed));
        } else {
            b.setContentTitle(getString(R.string.backup_stopped));
            switch (o) {
                case OLD_PC: text = getString(R.string.backup_old_pc, pcName); break;
                case PC_REFUSED: text = getString(R.string.backup_refused, pcName); break;
                case UNREACHABLE:
                    text = s != null && s.phase == BackupRun.Phase.WAITING_NETWORK ? getString(R.string.backup_no_wifi)
                            : getString(R.string.conn_unreachable, pcName);
                    break;
                default:
                    text = s == null ? "" : getString(R.string.backup_stopped_at, n.format(s.done), n.format(s.files));
            }
            if (o != BackupRun.Outcome.OLD_PC) {
                Intent go = new Intent(this, BackupService.class).setAction(ACTION_START).putExtra(EXTRA_HOST, hostId);
                PendingIntent p = Build.VERSION.SDK_INT >= 26
                        ? PendingIntent.getForegroundService(this, 2, go, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                        : PendingIntent.getService(this, 2, go, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                b.addAction(new Notification.Action.Builder(null, getString(R.string.backup_continue), p).build());
            }
        }
        return b.setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).build();
    }

    // ------------------------------------------------------------------ staying awake, on Wi-Fi

    /** Wi-Fi or a cable, not mobile data (a VPN counts as what it runs over). */
    @SuppressWarnings("deprecation") // getAllNetworks: what a VPN runs over
    static boolean onWifi(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        NetworkCapabilities nc = active == null ? null : cm.getNetworkCapabilities(active);
        if (nc == null) return false;
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true;
        if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities x = cm.getNetworkCapabilities(n);
            if (x != null && !x.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !x.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    && (x.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || x.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation") // WIFI_MODE_FULL_HIGH_PERF: Wi-Fi without its power saving while files go
    private void holdAwake() {
        if (wake == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeskLink:backup");
                wake.setReferenceCounted(false);
                wake.acquire(7 * 60 * 60_000L); // (released at the end; this is in case it never comes)
            }
        }
        if (wifi == null) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeskLink:backup");
                    wifi.setReferenceCounted(false);
                    wifi.acquire();
                }
            } catch (RuntimeException | LinkageError e) {
                wifi = null; // without it the files still go, maybe slower with the screen off (Robolectric: no Wi-Fi)
            }
        }
    }

    private void letGo() {
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        if (wifi != null && wifi.isHeld()) wifi.release();
        wifi = null;
    }
}

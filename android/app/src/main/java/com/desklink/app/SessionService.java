package com.desklink.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.lang.ref.WeakReference;

/**
 * Settings → Stay connected: while the session screen is in the background (the phone locked, another app in front),
 * this keeps DeskLink running, the phone's processor and Wi-Fi awake, and says so in a notification ("Connected to
 * OFFICE-PC", with Disconnect). The connection and the PC's sound stay with the session screen; this only keeps them
 * alive. Android asks for a notification for that.
 *
 * Started when the screen goes to the background and stopped when it's back: always through a command to the service
 * (never stopService), which calls startForeground first, as Android requires of a service started in the foreground.
 */
public final class SessionService extends Service {
    static final String ACTION_START = "com.desklink.app.session.START";
    static final String ACTION_STOP = "com.desklink.app.session.STOP";
    private static final String ACTION_DISCONNECT = "com.desklink.app.session.DISCONNECT";
    private static final String EXTRA_HOST = "hostName";
    private static final String CHANNEL = "connection";
    private static final int NOTE = 31;

    /** The session screen, told when Disconnect is tapped in the notification. */
    interface Owner {
        void disconnectFromNotification();
    }

    private static WeakReference<Owner> owner = new WeakReference<>(null);
    private static boolean on; // a start was asked for and no stop since (main thread)

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    /** The session screen went to the background: keep it alive. False if Android didn't let it (it's then as before). */
    static boolean begin(Context c, Owner o, String hostId, String hostName) {
        owner = new WeakReference<>(o);
        Intent i = new Intent(c, SessionService.class).setAction(ACTION_START).putExtra(EXTRA_HOST, hostName)
                .putExtra(SessionActivity.EXTRA_HOST_ID, hostId);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            on = true;
            return true;
        } catch (RuntimeException e) { // (Android 12 and later: not from the background, which it shouldn't be)
            return false;
        }
    }

    /** The session screen is back in front, or ended. */
    static void end(Context c) {
        if (!on) return;
        on = false;
        try {
            c.startService(new Intent(c, SessionService.class).setAction(ACTION_STOP));
        } catch (RuntimeException ignored) {
            // the service isn't running anymore
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            Owner o = owner.get();
            if (o != null) o.disconnectFromNotification();
            on = false;
            finishNow();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            finishNow();
            return START_NOT_STICKY;
        }
        String host = intent == null ? null : intent.getStringExtra(EXTRA_HOST);
        String hostId = intent == null ? null : intent.getStringExtra(SessionActivity.EXTRA_HOST_ID);
        Notification n = notification(host == null ? "" : host, hostId);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTE, n);
        holdAwake();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        letGo();
        super.onDestroy();
    }

    private void finishNow() {
        letGo();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private Notification notification(String host, String hostId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.note_channel_connection),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent open = new Intent(this, SessionActivity.class).putExtra(SessionActivity.EXTRA_HOST_ID, hostId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, immutable | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent bye = PendingIntent.getService(this, 1,
                new Intent(this, SessionService.class).setAction(ACTION_DISCONNECT), immutable | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_monitor)
                .setContentTitle(getString(R.string.note_connected, host))
                .setContentIntent(tap)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, getString(R.string.disconnect), bye).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    @SuppressWarnings("deprecation") // WIFI_MODE_FULL_HIGH_PERF: Wi-Fi without its power saving, so the sound doesn't stutter
    private void holdAwake() {
        if (wake == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeskLink:session");
                wake.setReferenceCounted(false);
                wake.acquire();
            }
        }
        if (wifi == null) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeskLink:session");
                    wifi.setReferenceCounted(false);
                    wifi.acquire();
                }
            } catch (RuntimeException | LinkageError e) {
                wifi = null; // the connection stays without it (Robolectric: no Wi-Fi)
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

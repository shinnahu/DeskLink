package com.desklink.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.desklink.core.PairedHost;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings → Back up photos and videos, tapped: to the PC of the session it's opened in, the only PC, or the one
 * picked; Android's permission to see the photos and videos (and to show the backup's notification) is asked for the
 * first time; then BackupService starts.
 */
final class BackupStart {
    private BackupStart() {}

    static final int REQ_ACCESS = 52;
    private static String waitingFor; // the PC, while Android asks for the permissions

    static void begin(Activity a, String sessionHostId) {
        HostStore store = new HostStore(a);
        if (sessionHostId != null && store.get(sessionHostId) != null) {
            withAccess(a, sessionHostId);
            return;
        }
        List<PairedHost> pcs = store.all();
        if (pcs.isEmpty()) {
            Sheet.message(a, null, a.getString(R.string.empty_title));
            return;
        }
        if (pcs.size() == 1) {
            withAccess(a, pcs.get(0).hostId);
            return;
        }
        final Sheet s = new Sheet(a, Sheet.BOTTOM);
        s.title(a.getString(R.string.backup_choose_pc), null);
        for (final PairedHost h : pcs) {
            s.row(R.drawable.ic_monitor, h.name(), null, false, Sheet.NORMAL, v -> {
                s.dismiss();
                withAccess(a, h.hostId);
            });
        }
        s.show();
    }

    private static void withAccess(Activity a, String hostId) {
        List<String> ask = new ArrayList<>();
        for (String p : wanted()) if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        if (ask.isEmpty() || (canSeeMedia(a) && ask.size() == 1 && ask.contains(notifications()))) {
            BackupService.start(a, hostId); // (once refused, the notification isn't asked for again)
            return;
        }
        waitingFor = hostId;
        a.requestPermissions(ask.toArray(new String[0]), REQ_ACCESS);
    }

    /** The activity's onRequestPermissionsResult. */
    static void onPermissions(Activity a, int requestCode) {
        if (requestCode != REQ_ACCESS || waitingFor == null) return;
        String hostId = waitingFor;
        waitingFor = null;
        if (canSeeMedia(a)) {
            BackupService.start(a, hostId);
            return;
        }
        // refused: Android may not ask again, so its page for DeskLink's permissions
        Sheet.confirm(a, a.getString(R.string.backup_no_access), null, a.getString(R.string.backup_allow), 0, Sheet.NORMAL, () -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.getPackageName(), null));
            try {
                a.startActivity(i);
            } catch (RuntimeException ignored) {
                // no such page on this phone
            }
        });
    }

    private static String notifications() { return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.POST_NOTIFICATIONS : ""; }

    private static List<String> wanted() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.READ_MEDIA_IMAGES);
            p.add(Manifest.permission.READ_MEDIA_VIDEO);
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            p.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return p;
    }

    /** All of them, or (Android 14: "Select photos and videos") the ones picked. */
    static boolean canSeeMedia(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (granted(c, Manifest.permission.READ_MEDIA_IMAGES) || granted(c, Manifest.permission.READ_MEDIA_VIDEO)) return true;
            return Build.VERSION.SDK_INT >= 34 && granted(c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        }
        return granted(c, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private static boolean granted(Context c, String p) {
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }
}

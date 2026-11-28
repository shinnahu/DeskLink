package com.desklink.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.TextView;

import com.desklink.core.HostConnection;
import com.desklink.core.PairedHost;
import com.desklink.core.Protocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Arrays;

/** A file from the PC, saved on the phone: Open on its note hands it to the app the phone has for it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class OpenFileTest {
    private static final String HOST_ID = "5d1e0c3a9b7f4e2a8c6d1f0e3b5a7c91";
    private Context ctx;
    private SessionActivity a;

    @Before
    public void setUp() {
        RemoteSession.offline = true;
        ctx = RuntimeEnvironment.getApplication();
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("apk", "application/vnd.android.package-archive");
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("jpg", "image/jpeg");
        PairedHost h = new PairedHost();
        h.hostId = HOST_ID;
        h.hostName = "OFFICE-PC";
        h.fingerprint = "7f3a9c2e5b8d1f4a6c0e2b9d7a5f3c1e8b6d4a2f0c9e7b5d3a1f8c6e4b2d0a9f";
        h.addresses.add("192.168.1.20");
        new HostStore(ctx).put(h);
        Intent i = new Intent(ctx, SessionActivity.class).putExtra(SessionActivity.EXTRA_HOST_ID, HOST_ID);
        a = Robolectric.buildActivity(SessionActivity.class, i).setup().get();
    }

    @After
    public void tearDown() {
        RemoteSession.offline = false;
    }

    @Test
    public void anApkGoesToAndroidsInstaller() throws Exception {
        // without this in the manifest the installer closes at once and nothing shows (AOSP InstallStart)
        PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertTrue("DeskLink may hand an APK to the installer",
                p.requestedPermissions != null && Arrays.asList(p.requestedPermissions).contains(Manifest.permission.REQUEST_INSTALL_PACKAGES));
        FileSaver.Target t = receive(7, "DeskLink.apk", "application/octet-stream"); // (Windows has no type for .apk)
        assertEquals(ctx.getString(R.string.files_received, "DeskLink.apk"), snack());
        tapOpen();
        Intent i = shadowOf(a).getNextStartedActivity();
        assertNotNull("something was asked to open it", i);
        assertEquals(Intent.ACTION_VIEW, i.getAction());
        assertEquals(t.uri, i.getData());
        assertEquals("application/vnd.android.package-archive", i.getType());
        assertTrue("the app that opens it may read it", (i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void aPictureGoesToItsViewer() throws Exception {
        FileSaver.Target t = receive(9, "photo.jpg", "image/jpeg");
        tapOpen();
        Intent i = shadowOf(a).getNextStartedActivity();
        assertNotNull(i);
        assertEquals(Intent.ACTION_VIEW, i.getAction());
        assertEquals(t.uri, i.getData());
        assertEquals("image/jpeg", i.getType());
        assertTrue((i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void whenNoAppOpensItTheNoteSaysSo() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true); // (no app here opens anything)
        receive(11, "setup.exe", "application/x-msdownload");
        tapOpen();
        assertEquals(ctx.getString(R.string.files_no_app, "setup.exe"), snack());
    }

    // ------------------------------------------------------------------ helpers

    /** A file from the PC, all of it saved, and its note on screen. */
    private FileSaver.Target receive(int id, String name, String mime) throws Exception {
        HostConnection.IncomingFileSink s = a.onIncomingFile(id, name, 3, mime, Protocol.FILE_KIND_FILE);
        assertNotNull("taken", s);
        s.write(new byte[] {1, 2, 3}, 0, 3);
        s.finish();
        a.onIncomingFileDone(id, Protocol.FD_OK, null);
        shadowOf(Looper.getMainLooper()).idle();
        FileSaver.Target t = (FileSaver.Target) s;
        assertNotNull("saved where other apps can open it", t.uri);
        return t;
    }

    private void tapOpen() throws Exception {
        TextView open = get(a, "snackAction");
        assertEquals(View.VISIBLE, open.getVisibility());
        assertEquals(ctx.getString(R.string.open), open.getText().toString());
        assertTrue(open.performClick());
        shadowOf(Looper.getMainLooper()).idle();
    }

    private String snack() throws Exception {
        View s = get(a, "snack");
        TextView t = get(a, "snackText");
        return s.getVisibility() == View.VISIBLE ? String.valueOf(t.getText()) : null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }
}

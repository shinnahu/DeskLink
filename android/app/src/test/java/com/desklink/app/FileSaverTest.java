package com.desklink.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentValues;
import android.content.Context;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.List;

/** Where files from the PC go: a screenshot with the pictures (galleries list it), anything else in the downloads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FileSaverTest {
    private Context c;

    @Before
    public void setUp() {
        c = RuntimeEnvironment.getApplication();
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("png", "image/png");
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("apk", "application/vnd.android.package-archive");
    }

    private ShadowContentResolver.InsertStatement lastInsert() {
        List<ShadowContentResolver.InsertStatement> l = shadowOf(c.getContentResolver()).getInsertStatements();
        return l.get(l.size() - 1);
    }

    @Test
    public void aScreenshotGoesWithThePictures() throws Exception {
        FileSaver.Target t = FileSaver.create(c, "Screenshot 2026-11-11 001844.png", "image/png", true);
        assertTrue(t.picture);
        ShadowContentResolver.InsertStatement ins = lastInsert();
        assertEquals(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ins.getUri());
        ContentValues v = ins.getContentValues();
        assertEquals("Pictures/DeskLink", v.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals("image/png", v.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals("Screenshot 2026-11-11 001844.png", v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("Pictures/DeskLink/Screenshot 2026-11-11 001844.png", t.where());
        t.write(new byte[] {1, 2, 3}, 0, 3);
        t.finish(); // no longer pending: it shows in the gallery
    }

    @Test
    public void aFileGoesWithTheDownloads() throws Exception {
        FileSaver.Target t = FileSaver.create(c, "DeskLink.apk", "application/octet-stream", false);
        assertFalse(t.picture);
        ShadowContentResolver.InsertStatement ins = lastInsert();
        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ins.getUri());
        ContentValues v = ins.getContentValues();
        assertEquals("Download/DeskLink", v.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        // the type Android has for the extension, so a second copy is named "DeskLink (1).apk"
        assertEquals("application/vnd.android.package-archive", v.getAsString(MediaStore.MediaColumns.MIME_TYPE));
    }

    @Test
    public void aScreenshotThatIsNoPictureGoesWithTheDownloads() throws Exception {
        FileSaver.Target t = FileSaver.create(c, "notes.bin", "application/octet-stream", true);
        assertFalse(t.picture);
        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, lastInsert().getUri());
    }
}

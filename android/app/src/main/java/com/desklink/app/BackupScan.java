package com.desklink.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.desklink.core.BackupRun;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The phone's photos and videos for a backup: what Android's media store lists, each with its folder and date. */
final class BackupScan {
    private BackupScan() {}

    /** Every photo and video (not those in the trash or still being written), newest first. Off the main thread. */
    static List<BackupRun.Item> list(Context c) {
        List<BackupRun.Item> out = new ArrayList<>();
        String sd = c.getString(R.string.backup_sd_card);
        add(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg", sd, out);
        add(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/mp4", sd, out);
        Collections.sort(out, (x, y) -> Long.compare(y.modifiedMs, x.modifiedMs));
        return out;
    }

    @SuppressWarnings("deprecation") // DATA: the only way to the folder before Android 10
    private static void add(Context c, Uri table, String defaultMime, String sdLabel, List<BackupRun.Item> out) {
        boolean q = Build.VERSION.SDK_INT >= 29;
        List<String> cols = new ArrayList<>();
        cols.add(MediaStore.MediaColumns._ID);
        cols.add(MediaStore.MediaColumns.DISPLAY_NAME);
        cols.add(MediaStore.MediaColumns.SIZE);
        cols.add(MediaStore.MediaColumns.DATE_MODIFIED);
        cols.add(MediaStore.MediaColumns.MIME_TYPE);
        cols.add("datetaken"); // (MediaColumns.DATE_TAKEN from Android 10; the images' and videos' own before)
        if (q) {
            cols.add(MediaStore.MediaColumns.RELATIVE_PATH);
            cols.add(MediaStore.MediaColumns.VOLUME_NAME);
        } else {
            cols.add(MediaStore.MediaColumns.DATA);
        }
        ContentResolver r = c.getContentResolver();
        try (Cursor cur = r.query(table, cols.toArray(new String[0]), null, null, null)) {
            if (cur == null) return;
            int iId = cur.getColumnIndex(MediaStore.MediaColumns._ID);
            int iName = cur.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = cur.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int iMod = cur.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int iMime = cur.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int iTaken = cur.getColumnIndex("datetaken");
            int iRel = q ? cur.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) : -1;
            int iVol = q ? cur.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME) : -1;
            int iData = q ? -1 : cur.getColumnIndex(MediaStore.MediaColumns.DATA);
            String storage = Environment.getExternalStorageDirectory().getAbsolutePath();
            while (cur.moveToNext()) {
                long id = cur.getLong(iId);
                String name = iName >= 0 ? cur.getString(iName) : null;
                long size = iSize >= 0 && !cur.isNull(iSize) ? cur.getLong(iSize) : -1;
                if (name == null || name.isEmpty() || size <= 0) continue;
                long modified = iMod >= 0 && !cur.isNull(iMod) ? cur.getLong(iMod) * 1000L : 0;
                if (modified <= 0 && iTaken >= 0 && !cur.isNull(iTaken)) modified = cur.getLong(iTaken);
                String mime = iMime >= 0 ? cur.getString(iMime) : null;
                String folder;
                if (q) {
                    String vol = iVol >= 0 ? cur.getString(iVol) : null;
                    folder = folderFor(iRel >= 0 ? cur.getString(iRel) : null,
                            vol == null || MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(vol), sdLabel);
                } else {
                    folder = folderFromPath(iData >= 0 ? cur.getString(iData) : null, storage, sdLabel);
                }
                out.add(new BackupRun.Item(folder, name, mime != null ? mime : defaultMime, size, modified,
                        ContentUris.withAppendedId(table, id)));
            }
        } catch (RuntimeException ignored) {
            // no access (the permission was taken back), or a media store that can't be read: nothing from it
        }
    }

    /**
     * The folder a file goes in on the PC, from where it is on the phone (RELATIVE_PATH, "DCIM/Camera/"). An app's
     * own media folder loses its start ("Android/media/com.whatsapp/WhatsApp/Media/…" → "WhatsApp/Media/…"), and what's
     * on a memory card goes under a folder of its own.
     */
    static String folderFor(String relativePath, boolean primary, String sdLabel) {
        String p = relativePath == null ? "" : relativePath.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        String media = "Android/media/";
        if (p.regionMatches(true, 0, media, 0, media.length())) {
            String rest = p.substring(media.length());
            int s = rest.indexOf('/');
            p = s >= 0 ? rest.substring(s + 1) : "";
        }
        if (!primary) p = p.isEmpty() ? sdLabel : sdLabel + "/" + p;
        return p;
    }

    /** The same from a file's full path (Android 9 and older): inside the phone's storage, or on a card. */
    static String folderFromPath(String path, String storage, String sdLabel) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String dir = slash > 0 ? p.substring(0, slash) : "";
        String root = storage == null ? "" : storage.replace('\\', '/');
        if (!root.isEmpty() && (dir.equals(root) || dir.startsWith(root + "/"))) {
            return folderFor(dir.substring(root.length()), true, sdLabel);
        }
        if (dir.startsWith("/storage/")) { // a card: /storage/1234-ABCD/DCIM/Camera
            String rest = dir.substring("/storage/".length());
            int s = rest.indexOf('/');
            return folderFor(s >= 0 ? rest.substring(s + 1) : "", false, sdLabel);
        }
        return folderFor(dir, true, sdLabel);
    }

    /** Opens one for sending, from {@code offset} on, with its size as it is now. */
    static BackupRun.Opened open(Context c, BackupRun.Item item, long offset) throws IOException {
        Uri u = (Uri) item.ref;
        ParcelFileDescriptor pfd = ContentResolver.SCHEME_FILE.equals(u.getScheme()) // (tests: files on the PC)
                ? ParcelFileDescriptor.open(new java.io.File(u.getPath()), ParcelFileDescriptor.MODE_READ_ONLY)
                : c.getContentResolver().openFileDescriptor(u, "r");
        if (pfd == null) throw new IOException("can't open it");
        long size = pfd.getStatSize();
        FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        try {
            if (offset > 0) in.getChannel().position(offset);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return new BackupRun.Opened(in, size >= 0 ? size : item.size);
    }
}

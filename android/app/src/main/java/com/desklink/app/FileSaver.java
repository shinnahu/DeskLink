package com.desklink.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.desklink.core.HostConnection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Saves files received from the PC into Downloads/DeskLink, and reads metadata of files to send. */
final class FileSaver {
    private FileSaver() {}

    /** A file being written. {@link #uri} is set on API 29+ (content://, can be opened). */
    static final class Target implements HostConnection.IncomingFileSink {
        final String name;
        final String mime;
        Uri uri;
        File file;
        private final ContentResolver resolver;
        private OutputStream out;

        Target(ContentResolver r, String name, String mime) {
            this.resolver = r;
            this.name = name;
            this.mime = mime;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void finish() throws IOException {
            out.close();
            if (uri != null && Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, v, null, null);
            }
        }

        @Override
        public void abort() {
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            try {
                if (uri != null) resolver.delete(uri, null, null);
                if (file != null) //noinspection ResultOfMethodCallIgnored
                    file.delete();
            } catch (RuntimeException ignored) {
            }
        }

        /** Human-readable location for messages. */
        String where() { return file != null ? file.getAbsolutePath() : "Downloads/DeskLink/" + name; }
    }

    static Target create(Context c, String rawName, String mime) throws IOException {
        String name = sanitize(rawName);
        if (mime == null || mime.isEmpty()) mime = guessMime(name);
        ContentResolver r = c.getContentResolver();
        Target t = new Target(r, name, mime);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DeskLink");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("cannot create download entry");
            t.uri = uri;
            OutputStream o = r.openOutputStream(uri);
            if (o == null) {
                r.delete(uri, null, null);
                throw new IOException("cannot open download entry");
            }
            t.out = o;
        } else {
            File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DeskLink");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File f = new File(dir, name);
            int i = 1;
            while (f.exists()) {
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : "";
                f = new File(dir, base + " (" + i++ + ")" + ext);
            }
            t.file = f;
            t.out = new FileOutputStream(f);
        }
        return t;
    }

    static String sanitize(String n) {
        if (n == null) n = "";
        n = n.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[\\x00-\\x1f:*?\"<>|]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "file";
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

    static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ outgoing

    static final class Source {
        String name = "file";
        long size = -1;
        String mime = "application/octet-stream";
        InputStream in;
        File temp;
    }

    /** Opens a picked/shared Uri; copies to a temp file when the size is unknown. Call off the main thread. */
    static Source open(Context c, Uri uri) throws IOException {
        Source s = new Source();
        ContentResolver r = c.getContentResolver();
        try (Cursor cur = r.query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = cur.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !cur.isNull(ni)) s.name = cur.getString(ni);
                if (si >= 0 && !cur.isNull(si)) s.size = cur.getLong(si);
            }
        } catch (RuntimeException ignored) {
        }
        if ((s.name == null || s.name.equals("file")) && uri.getLastPathSegment() != null) s.name = uri.getLastPathSegment();
        s.name = sanitize(s.name);
        String m = r.getType(uri);
        s.mime = m != null ? m : guessMime(s.name);
        InputStream in = r.openInputStream(uri);
        if (in == null) throw new IOException("cannot open " + uri);
        if (s.size < 0) {
            File tmp = File.createTempFile("send", ".bin", c.getCacheDir());
            try (OutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            } finally {
                in.close();
            }
            s.temp = tmp;
            s.size = tmp.length();
            in = new java.io.FileInputStream(tmp) {
                @Override
                public void close() throws IOException {
                    super.close();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            };
        }
        s.in = in;
        return s;
    }
}

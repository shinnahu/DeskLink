package com.desklink.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.desklink.core.HostConnection;
import com.desklink.core.PairedHost;
import com.desklink.core.Protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** "Send to PC" share target: connects without video, uploads the shared files (or text → PC clipboard). */
public final class ShareActivity extends Activity {
    private HostStore store;
    private LinearLayout box, choices;
    private TextView title, detail, primary, secondary;
    private ProgressBar spinner, bar;
    private final List<Uri> uris = new ArrayList<>();
    private String text;
    private volatile boolean cancelled;
    private volatile HostConnection conn;
    private volatile HostConnection.Outgoing current;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLock.install(this); // the optional fingerprint lock
        store = new HostStore(this);
        readIntent(getIntent());
        buildUi();
        setFinishOnTouchOutside(false);

        List<PairedHost> hosts = store.all();
        if (uris.isEmpty() && (text == null || text.isEmpty())) {
            showMessage(getString(R.string.share_nothing), false);
        } else if (hosts.isEmpty()) {
            showMessage(getString(R.string.share_no_pcs), false);
            setButton(primary, getString(R.string.share_open_app), () -> {
                startActivity(new Intent(this, MainActivity.class));
                finish();
            });
        } else if (hosts.size() == 1) {
            send(hosts.get(0));
        } else {
            title.setText(R.string.share_choose_pc);
            for (final PairedHost h : hosts) {
                TextView b = Ui.button(this, h.name(), false, v -> send(h));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Ui.dp(this, 8);
                choices.addView(b, lp);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void readIntent(Intent i) {
        String action = i.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u;
            if (Build.VERSION.SDK_INT >= 33) u = i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            else u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) text = t.toString();
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<? extends Parcelable> list;
            if (Build.VERSION.SDK_INT >= 33) list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            else list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Parcelable p : list) if (p instanceof Uri) uris.add((Uri) p);
        }
        if (uris.isEmpty() && i.getClipData() != null) {
            for (int k = 0; k < i.getClipData().getItemCount(); k++) {
                Uri u = i.getClipData().getItemAt(k).getUri();
                if (u != null) uris.add(u);
            }
        }
    }

    private void buildUi() {
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 24);
        box.setPadding(p, p, p, Ui.dp(this, 16));
        box.setMinimumWidth(Ui.dp(this, 300));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        spinner.setVisibility(View.GONE);
        head.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        title = Ui.text(this, getString(R.string.send_to_pc), 18, Ui.color(this, R.color.text), true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(this, 12));
        head.addView(title, tl);
        box.addView(head);

        detail = Ui.text(this, "", 14, Ui.color(this, R.color.text_dim), false);
        detail.setPadding(0, Ui.dp(this, 10), 0, 0);
        detail.setVisibility(View.GONE);
        box.addView(detail);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 12);
        box.addView(bar, bl);

        choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        box.addView(choices);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        secondary = Ui.button(this, getString(R.string.cancel), false, v -> cancel());
        primary = Ui.button(this, "", true, null);
        primary.setVisibility(View.GONE);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b1.setMarginStart(Ui.dp(this, 8));
        buttons.addView(secondary, b1);
        buttons.addView(primary, new LinearLayout.LayoutParams(b1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 18);
        box.addView(buttons, blp);
        box.setBackgroundColor(Ui.color(this, R.color.surface));
        setContentView(box);
    }

    private void setButton(TextView b, String label, Runnable r) {
        b.setVisibility(View.VISIBLE);
        b.setText(label);
        b.setOnClickListener(v -> r.run());
    }

    private void showMessage(String msg, boolean busy) {
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        title.setText(msg);
        detail.setVisibility(View.GONE);
        bar.setVisibility(View.GONE);
        secondary.setText(R.string.close);
    }

    private void send(final PairedHost h) {
        choices.removeAllViews();
        cancelled = false;
        spinner.setVisibility(View.VISIBLE);
        title.setText(getString(R.string.share_connecting, h.name()));
        detail.setVisibility(View.GONE);
        primary.setVisibility(View.GONE);
        secondary.setText(R.string.cancel);
        secondary.setOnClickListener(v -> cancel());
        final String dev = Prefs.deviceName(this);
        new Thread(() -> {
            HostConnection c = null;
            boolean own = false;
            try {
                // A remote session to this PC is open: upload through it. A second AUTH from this phone
                // would make the PC replace (close) that session.
                c = ActiveSessions.get(h.hostId);
                if (c == null) {
                    c = HostConnection.connect(h, dev, null, 8000);
                    own = true;
                    conn = c; // ours to close
                }
                final HostConnection cc = c;
                store.update(h.hostId, x -> {
                    x.lastSeenOnline = System.currentTimeMillis();
                    x.lastAddress = cc.address();
                });
                if (!uris.isEmpty()) {
                    for (int i = 0; i < uris.size(); i++) {
                        if (cancelled) throw new IOException("cancelled");
                        final FileSaver.Source s = FileSaver.open(this, uris.get(i));
                        final int index = i + 1, total = uris.size();
                        main.post(() -> {
                            title.setText(getString(R.string.share_sending, index, total, s.name));
                            bar.setVisibility(View.VISIBLE);
                            bar.setProgress(0);
                        });
                        final CountDownLatch done = new CountDownLatch(1);
                        final int[] status = {Protocol.FD_ERROR};
                        final String[] info = {""};
                        current = c.sendFile(s.name, s.size, s.mime, s.in, new HostConnection.SendListener() {
                            @Override
                            public void onProgress(long acked, long size) {
                                final int pct = size > 0 ? (int) Math.min(100, acked * 100 / size) : 0;
                                main.post(() -> bar.setProgress(pct));
                            }

                            @Override
                            public void onDone(int st, String inf) {
                                status[0] = st;
                                info[0] = inf;
                                done.countDown();
                            }
                        });
                        while (!done.await(250, TimeUnit.MILLISECONDS)) {
                            if (cancelled) current.cancel();
                        }
                        current = null;
                        if (status[0] != Protocol.FD_OK) throw new IOException(info[0]);
                    }
                } else {
                    c.sendClipboard(text);
                    Thread.sleep(400);
                }
                if (own) c.disconnect();
                conn = null;
                final boolean textOnly = uris.isEmpty();
                main.post(() -> {
                    spinner.setVisibility(View.GONE);
                    bar.setVisibility(View.GONE);
                    title.setText(textOnly ? getString(R.string.share_text_done) : getString(R.string.share_done, h.name()));
                    title.setTextColor(Ui.color(this, R.color.green));
                    secondary.setText(R.string.close);
                    secondary.setOnClickListener(v -> finish());
                    main.postDelayed(this::finish, 1400);
                });
            } catch (Exception e) {
                if (own && c != null) c.close(); // never close the remote session's connection
                conn = null;
                if (cancelled) return;
                final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> {
                    spinner.setVisibility(View.GONE);
                    bar.setVisibility(View.GONE);
                    title.setText(getString(R.string.share_failed, msg));
                    title.setTextColor(Ui.color(this, R.color.red));
                    secondary.setText(R.string.close);
                    secondary.setOnClickListener(v -> finish());
                    setButton(primary, getString(R.string.retry), () -> {
                        title.setTextColor(Ui.color(this, R.color.text));
                        send(h);
                    });
                });
            }
        }, "dl-share").start();
    }

    private void cancel() {
        abortTransfer();
        finish();
    }

    /** Stops the upload; closes the connection only if this screen opened it. */
    private void abortTransfer() {
        cancelled = true;
        HostConnection.Outgoing o = current;
        if (o != null) o.cancel();
        HostConnection c = conn; // null when we borrowed the remote session's connection
        conn = null;
        if (c != null) c.disconnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (isFinishing()) abortTransfer(); // Back / swipe-away must not keep uploading in the background
    }
}

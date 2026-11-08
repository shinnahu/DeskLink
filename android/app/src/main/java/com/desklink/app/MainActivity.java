package com.desklink.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.desklink.core.Discovery;
import com.desklink.core.IdentityMismatchException;
import com.desklink.core.Messages;
import com.desklink.core.PairedHost;
import com.desklink.core.Protocol;
import com.desklink.core.StatusProbe;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** List of linked PCs with live status (probed while visible). */
public final class MainActivity extends Activity {
    private static final int PROBE_INTERVAL_MS = 30_000;

    static final int ST_CHECKING = 0, ST_ONLINE = 1, ST_OFFLINE = 2, ST_UNLINKED = 3, ST_IDENTITY = 4;

    static final class Status {
        int kind = ST_CHECKING;
        boolean inUse, locked;
        String activeDevice = "";
    }

    private static final class Card {
        ImageView icon;
        View dot;
        TextView name, status;
    }

    private HostStore store;
    private LinearLayout list;
    private View emptyView;
    private ScrollView scroll;
    private TextView addButton;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private boolean visible;
    private final Map<String, Status> statuses = new HashMap<>();
    private final Map<String, Card> cards = new HashMap<>();

    private final Runnable probeTick = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            probeAll();
            main.postDelayed(this, PROBE_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLock.install(this); // the optional fingerprint lock
        Ui.edgeToEdge(this);
        store = new HostStore(this);
        pool = Executors.newCachedThreadPool();

        FrameLayout root = new PullLayout(this); // pull down at the top to check the PCs again
        root.setBackgroundColor(Ui.color(this, R.color.bg));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 28), pad, Ui.dp(this, 100));

        // title, with Settings (⚙) beside it
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, getString(R.string.app_name), 30, Ui.color(this, R.color.text), true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton gear = Ui.iconButton(this, R.drawable.ic_settings, Ui.color(this, R.color.text), getString(R.string.settings),
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        head.addView(gear, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        col.addView(head);
        TextView sub = Ui.text(this, getString(R.string.main_subtitle), 15, Ui.color(this, R.color.text_dim), false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = Ui.dp(this, 20);
        col.addView(sub, subLp);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setBackground(Ui.rounded(Ui.color(this, R.color.surface), Ui.dp(this, 16)));
        int ep = Ui.dp(this, 18);
        empty.setPadding(ep, ep, ep, ep);
        empty.addView(Ui.text(this, getString(R.string.empty_title), 17, Ui.color(this, R.color.text), true));
        TextView eb = Ui.text(this, getString(R.string.empty_body), 14, Ui.color(this, R.color.text_dim), false);
        eb.setPadding(0, Ui.dp(this, 6), 0, 0);
        empty.addView(eb);
        emptyView = empty;
        col.addView(empty);

        TextView tip = Ui.text(this, getString(R.string.tip_remote), 13, Ui.color(this, R.color.text_dim), false);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = Ui.dp(this, 18);
        col.addView(tip, tipLp);

        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setUpPullToRefresh(root);

        addButton = Ui.button(this, getString(R.string.add_pc), true, v -> startActivity(new Intent(this, AddPcActivity.class)));
        android.graphics.drawable.Drawable plus = getDrawable(R.drawable.ic_add);
        if (plus != null) {
            plus.setTint(Color.WHITE);
            int s = Ui.dp(this, 22);
            plus.setBounds(0, 0, s, s);
            addButton.setCompoundDrawablesRelative(plus, null, null, null);
            addButton.setCompoundDrawablePadding(Ui.dp(this, 8));
        }
        addButton.setElevation(Ui.dp(this, 6));
        final FrameLayout.LayoutParams fab = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        root.addView(addButton, fab);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Ui.Bars b = Ui.bars(insets, this);
            scroll.setPadding(b.left, b.top, b.right, b.bottom);
            topInset = b.top;
            fab.setMargins(0, 0, b.right + Ui.dp(this, 20), b.bottom + Ui.dp(this, 20));
            addButton.setLayoutParams(fab);
            return insets;
        });
        setContentView(root);
    }

    // ------------------------------------------------------------------ pull down to refresh

    private FrameLayout pullBadge;
    private int topInset, probesRunning;
    private float pullStartY = -1;
    private boolean pulling, refreshing;
    private final Runnable endRefresh = this::endRefresh;

    /** The spinner that comes down with the finger (the gesture itself is caught by PullLayout, the screen's root). */
    private void setUpPullToRefresh(FrameLayout root) {
        pullBadge = new FrameLayout(this);
        pullBadge.setBackground(Ui.circle(Ui.color(this, R.color.surface2)));
        pullBadge.setElevation(Ui.dp(this, 6));
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
        spin.setIndeterminateTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        int s = Ui.dp(this, 24);
        pullBadge.addView(spin, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        int b = Ui.dp(this, 40);
        root.addView(pullBadge, new FrameLayout.LayoutParams(b, b, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        pullBadge.setVisibility(View.GONE);
    }

    /**
     * The screen's root. At the top of the list, a finger that slides down (starting anywhere, on a PC card too)
     * pulls the spinner down; letting go far enough checks every PC again.
     */
    private final class PullLayout extends FrameLayout {
        private final int slop;
        private float downX, downY;

        PullLayout(android.content.Context c) {
            super(c);
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    pulling = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - downY, dx = Math.abs(e.getRawX() - downX);
                    if (!refreshing && scroll.getScrollY() == 0 && dy > slop && dy > dx * 1.5f) {
                        pulling = true;
                        pullStartY = downY;
                        return true; // from here on the touch is ours (the card under it gets a cancel)
                    }
                    break;
                }
                default:
                    break;
            }
            return false;
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (!pulling) return false;
            float d = e.getRawY() - pullStartY;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_MOVE:
                    if (d > 0) showPull(d);
                    else pullBadge.setVisibility(View.GONE);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    pulling = false;
                    if (d > pullDistance()) refresh();
                    else pullBadge.setVisibility(View.GONE);
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    pulling = false;
                    pullBadge.setVisibility(View.GONE);
                    return true;
                default:
                    return true;
            }
        }
    }

    private float pullDistance() { return Ui.dp(this, 72); }

    private void showPull(float d) {
        float t = Math.min(1f, d / pullDistance());
        pullBadge.setVisibility(View.VISIBLE);
        pullBadge.setAlpha(0.3f + 0.7f * t);
        pullBadge.setScaleX(0.6f + 0.4f * t);
        pullBadge.setScaleY(0.6f + 0.4f * t);
        pullBadge.setTranslationY(topInset + Math.min(d * 0.5f, Ui.dp(this, 64)));
    }

    /** Every PC goes back to "Checking…" and is asked again; the spinner stays until the answers are in. */
    private void refresh() {
        refreshing = true;
        pullBadge.setVisibility(View.VISIBLE);
        pullBadge.setAlpha(1f);
        pullBadge.setScaleX(1f);
        pullBadge.setScaleY(1f);
        pullBadge.animate().translationY(topInset + Ui.dp(this, 24)).setDuration(150).start();
        pullBadge.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        for (PairedHost h : store.all()) {
            statuses.put(h.hostId, new Status()); // "Checking…"
            render(h);
        }
        probeAll();
        main.removeCallbacks(endRefresh);
        main.postDelayed(endRefresh, StatusProbe.DEFAULT_TIMEOUT_MS + 3000); // in case an answer never comes
        if (probesRunning == 0) main.postDelayed(endRefresh, 600);
    }

    private void endRefresh() {
        if (!refreshing) return;
        refreshing = false;
        main.removeCallbacks(endRefresh);
        pullBadge.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (!refreshing) pullBadge.setVisibility(View.GONE);
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        rebuild();
        main.removeCallbacks(probeTick);
        main.post(probeTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        main.removeCallbacks(probeTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ list

    private void rebuild() {
        list.removeAllViews();
        cards.clear();
        List<PairedHost> hosts = store.all();
        emptyView.setVisibility(hosts.isEmpty() ? View.VISIBLE : View.GONE);
        for (PairedHost h : hosts) addCard(h);
    }

    private void addCard(final PairedHost h) {
        final String id = h.hostId;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        float r = Ui.dp(this, 16);
        card.setBackground(Ui.ripple(Ui.rounded(Ui.color(this, R.color.surface), r), r));
        int p = Ui.dp(this, 14);
        card.setPadding(p, p, Ui.dp(this, 4), p);
        card.setClickable(true);
        card.setOnClickListener(v -> onCardClick(id));
        card.setOnLongClickListener(v -> {
            showOptions(id);
            return true;
        });

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(Ui.rounded(Ui.color(this, R.color.surface2), Ui.dp(this, 12)));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_monitor);
        int ip = Ui.dp(this, 10);
        icon.setPadding(ip, ip, ip, ip);
        iconBox.addView(icon, new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        View dot = new View(this);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 12), Gravity.BOTTOM | Gravity.END);
        dotLp.setMargins(0, 0, Ui.dp(this, 5), Ui.dp(this, 5));
        iconBox.addView(dot, dotLp);
        card.addView(iconBox);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(this, 14));
        TextView name = Ui.text(this, h.name(), 17, Ui.color(this, R.color.text), true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView status = Ui.text(this, "", 13, Ui.color(this, R.color.text_dim), false);
        status.setMaxLines(2);
        texts.addView(name);
        texts.addView(status);
        card.addView(texts, tl);

        ImageButton edit = Ui.iconButton(this, R.drawable.ic_edit, Ui.color(this, R.color.text_dim), getString(R.string.rename), v -> rename(id));
        ImageButton del = Ui.iconButton(this, R.drawable.ic_delete, Ui.color(this, R.color.text_dim), getString(R.string.forget), v -> forget(id));
        card.addView(edit, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        card.addView(del, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        list.addView(card, lp);

        Card c = new Card();
        c.icon = icon;
        c.dot = dot;
        c.name = name;
        c.status = status;
        cards.put(id, c);
        render(h);
    }

    private void render(PairedHost h) {
        Card c = cards.get(h.hostId);
        if (c == null) return;
        Status s = statuses.get(h.hostId);
        c.name.setText(h.name());
        int dim = Ui.color(this, R.color.text_dim);
        int green = Ui.color(this, R.color.green), amber = Ui.color(this, R.color.amber), red = Ui.color(this, R.color.red);
        String text;
        int textColor = dim, dotColor = Color.TRANSPARENT, iconColor = dim;
        int kind = s == null ? ST_CHECKING : s.kind;
        switch (kind) {
            case ST_ONLINE: {
                String who = s.activeDevice == null ? "" : s.activeDevice;
                if (s.locked) {
                    text = s.inUse && !who.isEmpty() ? getString(R.string.status_locked_in_use, who) : getString(R.string.status_locked);
                    textColor = amber;
                    dotColor = amber;
                } else if (s.inUse) {
                    text = who.isEmpty() ? getString(R.string.status_in_use_unknown) : getString(R.string.status_in_use, who);
                    textColor = amber;
                    dotColor = amber;
                } else {
                    text = getString(R.string.status_online);
                    textColor = green;
                    dotColor = green;
                }
                iconColor = Ui.color(this, R.color.text);
                break;
            }
            case ST_OFFLINE:
                text = h.lastSeenOnline > 0 ? getString(R.string.status_offline_seen, relative(h.lastSeenOnline)) : getString(R.string.status_never);
                dotColor = 0xFF5A6270;
                break;
            case ST_UNLINKED:
                text = getString(R.string.status_unlinked);
                textColor = red;
                dotColor = red;
                break;
            case ST_IDENTITY:
                text = getString(R.string.status_identity);
                textColor = red;
                dotColor = red;
                break;
            default:
                text = getString(R.string.status_checking);
        }
        c.status.setText(text);
        c.status.setTextColor(textColor);
        c.icon.setImageTintList(ColorStateList.valueOf(iconColor));
        c.dot.setBackground(dotColor == Color.TRANSPARENT ? null : Ui.circle(dotColor));
    }

    private CharSequence relative(long t) {
        long now = System.currentTimeMillis();
        if (now - t < 60_000) return getString(R.string.just_now);
        return DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    // ------------------------------------------------------------------ probing

    private void probeAll() {
        final List<PairedHost> hosts = store.all();
        if (hosts.isEmpty() || RemoteSession.offline) return;
        for (final PairedHost h : hosts) {
            if (!statuses.containsKey(h.hostId)) {
                statuses.put(h.hostId, new Status());
                render(h);
            }
            probesRunning++; // counted so a pull-to-refresh knows when every answer is in
            if (!submit(() -> probeOne(h, true))) probesRunning--;
        }
        submit(() -> discover(hosts));
    }

    private boolean submit(Runnable r) {
        try {
            pool.execute(r);
            return true;
        } catch (RuntimeException ignored) {
            return false; // pool shut down
        }
    }

    private void probeOne(final PairedHost h, final boolean counted) {
        final Status s = new Status();
        try {
            final Messages.StatusResult res = StatusProbe.probe(h, StatusProbe.DEFAULT_TIMEOUT_MS);
            if (res.status == Protocol.ST_OK) {
                s.kind = ST_ONLINE;
                s.inUse = res.inUse();
                s.locked = res.secureDesktop();
                s.activeDevice = res.activeDeviceName;
                store.update(h.hostId, x -> {
                    x.lastSeenOnline = System.currentTimeMillis();
                    x.lastAddress = res.address;
                    if (res.hostName != null && !res.hostName.isEmpty()) x.hostName = res.hostName;
                });
            } else {
                s.kind = ST_UNLINKED;
            }
        } catch (IdentityMismatchException e) {
            s.kind = ST_IDENTITY;
        } catch (IOException | RuntimeException e) {
            s.kind = ST_OFFLINE;
        }
        main.post(() -> {
            if (isDestroyed()) return;
            if (counted && probesRunning > 0 && --probesRunning == 0 && refreshing) main.postDelayed(endRefresh, 300);
            Status prev = statuses.get(h.hostId);
            // A discovery hit may have just fixed the address; don't let a stale failure override "online".
            if (s.kind == ST_OFFLINE && prev != null && prev.kind == ST_ONLINE && prev != s && recentlyFound(h.hostId)) return;
            statuses.put(h.hostId, s);
            PairedHost fresh = store.get(h.hostId);
            if (fresh != null) render(fresh);
        });
    }

    private final Map<String, Long> foundAt = new HashMap<>();

    private boolean recentlyFound(String id) {
        Long t = foundAt.get(id);
        return t != null && System.currentTimeMillis() - t < 5000;
    }

    private void discover(List<PairedHost> hosts) {
        List<String> all = new ArrayList<>();
        for (PairedHost h : hosts) all.addAll(h.addresses);
        List<InetAddress> targets = Discovery.broadcastTargets();
        targets.addAll(Discovery.literalTargets(all));
        List<Discovery.Found> found = Discovery.scan(targets, 1500, null);
        for (final Discovery.Found f : found) {
            for (final PairedHost h : hosts) {
                if (!h.hostId.equalsIgnoreCase(f.id) || !h.fingerprint.equalsIgnoreCase(f.fingerprint)) continue;
                final boolean isNew = !h.addresses.contains(f.address) || !f.address.equals(h.lastAddress);
                final PairedHost updated = store.update(h.hostId, x -> {
                    x.lastSeenOnline = System.currentTimeMillis();
                    x.mergeAddresses(Collections.singletonList(f.address));
                    if (!isNew) return;
                    // a changed LAN address goes first next time
                    x.lastAddress = f.address;
                });
                main.post(() -> foundAt.put(h.hostId, System.currentTimeMillis()));
                if (isNew && updated != null) submit(() -> probeOne(updated, false));
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private void onCardClick(final String id) {
        final PairedHost h = store.get(id);
        if (h == null) return;
        Status s = statuses.get(id);
        if (s != null && s.kind == ST_UNLINKED) {
            Sheet.confirm(this, h.name(), getString(R.string.conn_unlinked, h.name()), getString(R.string.forget),
                    R.drawable.ic_delete, Sheet.DANGER, () -> {
                        store.remove(id);
                        rebuild();
                        startActivity(new Intent(this, AddPcActivity.class));
                    });
            return;
        }
        if (s != null && s.kind == ST_IDENTITY) {
            Sheet.confirm(this, h.name(), getString(R.string.conn_identity, h.name()), getString(R.string.forget),
                    R.drawable.ic_delete, Sheet.DANGER, () -> {
                        store.remove(id);
                        rebuild();
                    });
            return;
        }
        Intent i = new Intent(this, SessionActivity.class);
        i.putExtra(SessionActivity.EXTRA_HOST_ID, id);
        startActivity(i);
    }

    /** Touch and hold on a PC: rename it, edit its addresses, forget it. */
    private void showOptions(final String id) {
        final PairedHost h = store.get(id);
        if (h == null) return;
        final Sheet s = new Sheet(this, Sheet.BOTTOM);
        s.title(h.name(), null);
        s.row(R.drawable.ic_edit, getString(R.string.rename), null, false, Sheet.NORMAL, v -> {
            s.dismiss();
            rename(id);
        });
        s.row(R.drawable.ic_monitor, getString(R.string.edit_addresses), null, false, Sheet.NORMAL, v -> {
            s.dismiss();
            editAddresses(id);
        });
        s.row(R.drawable.ic_delete, getString(R.string.forget), null, false, Sheet.DANGER, v -> {
            s.dismiss();
            forget(id);
        });
        s.show();
    }

    private void rename(final String id) {
        final PairedHost h = store.get(id);
        if (h == null) return;
        Sheet.input(this, getString(R.string.rename_title), null, h.name(), false, getString(R.string.save), name -> {
            final String n = name.trim();
            store.update(id, x -> x.displayName = n.equals(x.hostName) ? "" : n);
            PairedHost fresh = store.get(id);
            if (fresh != null) render(fresh);
        });
    }

    private void editAddresses(final String id) {
        final PairedHost h = store.get(id);
        if (h == null) return;
        final Sheet s = new Sheet(this, Sheet.BOTTOM);
        s.title(getString(R.string.edit_addresses), getString(R.string.addresses_hint));
        final EditText addrs = s.field(android.text.TextUtils.join("\n", h.addresses), null, true, false);
        final EditText port = s.field(String.valueOf(h.port), getString(R.string.port), false, true);
        s.buttons(getString(R.string.cancel), null, getString(R.string.save), 0, Sheet.NORMAL, v -> {
            final List<String> list = new ArrayList<>();
            for (String line : addrs.getText().toString().split("[\\n,; ]+")) {
                String a = line.trim();
                if (!a.isEmpty() && !list.contains(a)) list.add(a);
            }
            int pp;
            try {
                pp = Integer.parseInt(port.getText().toString().trim());
            } catch (NumberFormatException e) {
                pp = Protocol.DEFAULT_PORT;
            }
            final int newPort = pp > 0 && pp < 65536 ? pp : Protocol.DEFAULT_PORT;
            s.dismiss();
            store.update(id, x -> {
                x.addresses = list;
                if (!list.contains(x.lastAddress)) x.lastAddress = "";
                x.port = newPort;
            });
            statuses.remove(id);
            rebuild();
            probeAll();
        });
        s.show();
        s.focus(addrs);
    }

    private void forget(final String id) {
        final PairedHost h = store.get(id);
        if (h == null) return;
        Sheet.confirm(this, getString(R.string.forget_title, h.name()), getString(R.string.forget_body),
                getString(R.string.forget), R.drawable.ic_delete, Sheet.DANGER, () -> {
                    store.remove(id);
                    statuses.remove(id);
                    rebuild();
                });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (!Sheet.dismissTop(this)) super.onBackPressed(); // (Android 13 and later: an open sheet takes Back itself)
    }
}

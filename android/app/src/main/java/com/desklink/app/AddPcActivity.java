package com.desklink.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.desklink.core.AuthException;
import com.desklink.core.Connector;
import com.desklink.core.Discovery;
import com.desklink.core.PairedHost;
import com.desklink.core.Pairing;
import com.desklink.core.Protocol;
import com.desklink.core.SecureChannel;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Find PCs on the LAN (or type an address) and pair with numeric comparison. */
public final class AddPcActivity extends Activity {
    private HostStore store;
    private LinearLayout foundList;
    private TextView emptyText;
    private ProgressBar scanSpinner;
    private EditText addrInput, portInput;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean visible;
    private volatile boolean scanning;
    private final Map<String, Discovery.Found> found = new LinkedHashMap<>();

    private Sheet pairDialog;
    private TextView pairButton;
    private TextView pairTitle, pairCode, pairMsg;
    private ProgressBar pairSpinner;
    private volatile SecureChannel pairChannel;
    private volatile boolean pairCancelled;
    private int pairGen;

    private final Runnable scanTick = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            scan();
            main.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLock.install(this); // the optional fingerprint lock
        Ui.edgeToEdge(this);
        store = new HostStore(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(Ui.color(this, R.color.bg));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 32));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton close = Ui.iconButton(this, R.drawable.ic_close, Ui.color(this, R.color.text), getString(R.string.close), v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView title = Ui.text(this, getString(R.string.add_title), 24, Ui.color(this, R.color.text), true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(Ui.dp(this, 6));
        header.addView(title, tlp);
        col.addView(header);

        TextView steps = Ui.text(this, getString(R.string.add_steps), 14, Ui.color(this, R.color.text_dim), false);
        steps.setLineSpacing(Ui.dp(this, 3), 1f);
        steps.setBackground(Ui.rounded(Ui.color(this, R.color.surface), Ui.dp(this, 14)));
        int sp = Ui.dp(this, 14);
        steps.setPadding(sp, sp, sp, sp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 12);
        slp.bottomMargin = Ui.dp(this, 22);
        col.addView(steps, slp);

        LinearLayout nearbyHeader = new LinearLayout(this);
        nearbyHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView nearby = Ui.text(this, getString(R.string.add_nearby), 16, Ui.color(this, R.color.text), true);
        nearbyHeader.addView(nearby, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scanSpinner = new ProgressBar(this);
        scanSpinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        nearbyHeader.addView(scanSpinner, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        ImageButton refresh = Ui.iconButton(this, R.drawable.ic_refresh, Ui.color(this, R.color.text_dim), getString(R.string.refresh), v -> scan());
        nearbyHeader.addView(refresh, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        col.addView(nearbyHeader);

        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        col.addView(foundList);
        emptyText = Ui.text(this, getString(R.string.add_scanning), 14, Ui.color(this, R.color.text_dim), false);
        emptyText.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        col.addView(emptyText);

        TextView manual = Ui.text(this, getString(R.string.add_manual), 16, Ui.color(this, R.color.text), true);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 26);
        mlp.bottomMargin = Ui.dp(this, 6);
        col.addView(manual, mlp);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addrInput = new EditText(this);
        addrInput.setHint(R.string.add_manual_hint);
        addrInput.setSingleLine(true);
        addrInput.setTextColor(Ui.color(this, R.color.text));
        addrInput.setHintTextColor(Ui.color(this, R.color.text_dim));
        addrInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addrInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        addrInput.setOnEditorActionListener((v, actionId, e) -> {
            connectManual();
            return true;
        });
        row.addView(addrInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        portInput = new EditText(this);
        portInput.setHint(String.valueOf(Protocol.DEFAULT_PORT));
        portInput.setSingleLine(true);
        portInput.setTextColor(Ui.color(this, R.color.text));
        portInput.setHintTextColor(Ui.color(this, R.color.text_dim));
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(this, 84), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMarginStart(Ui.dp(this, 8));
        row.addView(portInput, plp);
        col.addView(row);
        TextView connect = Ui.button(this, getString(R.string.add_connect), false, v -> connectManual());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 10);
        clp.gravity = Gravity.END;
        col.addView(connect, clp);

        scroll.addView(col);
        Ui.padForInsets(scroll, 0);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        main.removeCallbacks(scanTick);
        main.post(scanTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        main.removeCallbacks(scanTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelPairing();
    }

    // ------------------------------------------------------------------ discovery

    private void scan() {
        if (scanning) return;
        scanning = true;
        scanSpinner.setVisibility(View.VISIBLE);
        new Thread(() -> {
            Discovery.scan(Discovery.broadcastTargets(), 2000, f -> main.post(() -> addFound(f)));
            main.post(() -> {
                scanning = false;
                scanSpinner.setVisibility(View.INVISIBLE);
                emptyText.setText(R.string.add_none_found);
                emptyText.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }, "dl-discovery").start();
    }

    private void addFound(Discovery.Found f) {
        if (isDestroyed()) return;
        found.put(f.id.isEmpty() ? f.address : f.id, f);
        renderFound();
    }

    private void renderFound() {
        foundList.removeAllViews();
        emptyText.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
        List<com.desklink.core.PairedHost> linked = store.all();
        for (final Discovery.Found f : found.values()) {
            boolean isLinked = false;
            for (com.desklink.core.PairedHost h : linked) if (h.hostId.equalsIgnoreCase(f.id)) isLinked = true;

            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            float r = Ui.dp(this, 14);
            card.setBackground(Ui.ripple(Ui.rounded(Ui.color(this, R.color.surface), r), r));
            int p = Ui.dp(this, 14);
            card.setPadding(p, p, p, p);
            card.setClickable(true);
            card.setOnClickListener(v -> startPairing(f.address, f.port, f.name));

            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_monitor);
            icon.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.text)));
            card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tl.setMarginStart(Ui.dp(this, 14));
            texts.addView(Ui.text(this, f.name, 16, Ui.color(this, R.color.text), true));
            texts.addView(Ui.text(this, f.address, 13, Ui.color(this, R.color.text_dim), false));
            card.addView(texts, tl);

            int badgeColor;
            String badge;
            if (isLinked) {
                badge = getString(R.string.badge_linked);
                badgeColor = Ui.color(this, R.color.text_dim);
            } else if (f.pairing) {
                badge = getString(R.string.badge_pairing_open);
                badgeColor = Ui.color(this, R.color.green);
            } else {
                badge = getString(R.string.badge_pairing_closed);
                badgeColor = Ui.color(this, R.color.amber);
            }
            TextView b = Ui.text(this, badge, 12, badgeColor, true);
            card.addView(b);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 8);
            foundList.addView(card, lp);
        }
    }

    private void connectManual() {
        String a = addrInput.getText().toString().trim();
        if (a.isEmpty()) {
            addrInput.requestFocus();
            return;
        }
        int port = Protocol.DEFAULT_PORT;
        try {
            String p = portInput.getText().toString().trim();
            if (!p.isEmpty()) port = Integer.parseInt(p);
        } catch (NumberFormatException ignored) {
        }
        startPairing(a, port, a);
    }

    // ------------------------------------------------------------------ pairing

    private void startPairing(final String address, final int port, final String label) {
        cancelPairing();
        pairCancelled = false;
        final int gen = ++pairGen;
        showPairDialog(label);
        final String devName = Prefs.deviceName(this);
        final String devModel = Prefs.deviceModel();
        new Thread(() -> {
            SecureChannel ch = null;
            try {
                Connector.Target t = Connector.parse(address, port);
                ch = Connector.open(Collections.singletonList(address), port, null, 8000, 0);
                pairChannel = ch;
                if (pairCancelled) throw new IOException("cancelled");
                PairedHost h = Pairing.pair(ch, t.port, devName, devModel, code -> main.post(() -> showCode(gen, code)));
                Pairing.finishWithHostInfo(ch, h, devName);
                store.put(h);
                main.post(() -> pairSuccess(gen, h));
            } catch (AuthException e) {
                if (ch != null) ch.close();
                main.post(() -> pairFailed(gen, messageFor(e)));
            } catch (IOException | RuntimeException e) {
                if (ch != null) ch.close();
                final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> pairFailed(gen, getString(R.string.pair_err_network, msg)));
            }
        }, "dl-pair").start();
    }

    private String messageFor(AuthException e) {
        switch (e.status) {
            case Protocol.ST_PAIRING_CLOSED: return getString(R.string.pair_err_closed);
            case Protocol.ST_DENIED: return getString(R.string.pair_err_denied);
            case Protocol.ST_TIMEOUT: return getString(R.string.pair_err_timeout);
            case Protocol.ST_RATE_LIMITED: return getString(R.string.pair_err_rate);
            case Protocol.ST_VERSION: return getString(R.string.pair_err_version);
            case Protocol.ST_BUSY: return getString(R.string.conn_busy);
            default: return getString(R.string.pair_err_generic, e.getMessage());
        }
    }

    /** Pairing: the code to compare with the PC's, in DeskLink's own card. Cancel (or Back) stops it. */
    private void showPairDialog(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(this, 24);
        box.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 4));
        pairSpinner = new ProgressBar(this);
        pairSpinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        box.addView(pairSpinner, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        pairTitle = Ui.text(this, getString(R.string.pair_connecting, label), 18, Ui.color(this, R.color.text), true);
        pairTitle.setGravity(Gravity.CENTER);
        pairTitle.setPadding(0, Ui.dp(this, 14), 0, 0);
        box.addView(pairTitle);
        pairCode = Ui.text(this, "", 44, Ui.color(this, R.color.text), true);
        pairCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pairCode.setLetterSpacing(0.08f);
        pairCode.setGravity(Gravity.CENTER);
        pairCode.setVisibility(View.GONE);
        pairCode.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        box.addView(pairCode);
        pairMsg = Ui.text(this, "", 14, Ui.color(this, R.color.text_dim), false);
        pairMsg.setGravity(Gravity.CENTER);
        pairMsg.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(pairMsg);
        final Sheet s = new Sheet(this, Sheet.CENTER).keepOnOutsideTouch();
        s.body.addView(box);
        pairButton = Ui.button(this, getString(R.string.cancel), false, v -> s.dismiss());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.gravity = Gravity.CENTER_HORIZONTAL;
        bl.topMargin = Ui.dp(this, 16);
        s.body.addView(pairButton, bl);
        s.onDismiss(this::cancelPairing); // closing the card stops the pairing (unless it worked: see pairSuccess)
        pairDialog = s.show();
    }

    private boolean pairShowing() { return pairDialog != null && pairDialog.isShowing(); }

    private void showCode(int gen, String code) {
        if (gen != pairGen || !pairShowing()) return;
        pairTitle.setText(R.string.pair_confirm_title);
        pairCode.setText(code);
        pairCode.setVisibility(View.VISIBLE);
        pairMsg.setText(R.string.pair_waiting);
    }

    private void pairSuccess(int gen, PairedHost h) {
        if (gen != pairGen || isDestroyed()) return;
        if (pairDialog != null) {
            pairDialog.onDismiss(null);
            pairDialog.dismiss();
        }
        Toast.makeText(this, getString(R.string.pair_success, h.name()), Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, SessionActivity.class);
        i.putExtra(SessionActivity.EXTRA_HOST_ID, h.hostId);
        startActivity(i);
        finish();
    }

    private void pairFailed(int gen, String msg) {
        if (gen != pairGen || pairCancelled || isDestroyed()) return;
        if (!pairShowing()) {
            Sheet.message(this, null, msg);
            return;
        }
        pairSpinner.setVisibility(View.GONE);
        pairCode.setVisibility(View.GONE);
        pairTitle.setText(msg);
        pairTitle.setTextSize(16);
        pairMsg.setText("");
        pairButton.setText(R.string.close);
    }

    private void cancelPairing() {
        pairCancelled = true;
        pairGen++;
        SecureChannel c = pairChannel;
        pairChannel = null;
        if (c != null) c.close();
        if (pairShowing()) pairDialog.dismiss();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (!Sheet.dismissTop(this)) super.onBackPressed(); // (Android 13 and later: an open sheet takes Back itself)
    }
}

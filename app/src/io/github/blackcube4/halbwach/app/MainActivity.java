package io.github.blackcube4.halbwach.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import io.github.blackcube4.halbwach.R;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int MAX_WIDTH_DP = 720;

    private int cBg, cCard, cField, cText, cText2, cAccent, cOnAccent, cAccentSoft, cOk, cWarn, cBad;

    private TextView shizukuDot, shizukuText, serviceDot, serviceText, wallDot, wallText;
    private TextView mainButton, permissionButton;
    private TextView idleValue;
    private EditText token;
    private Switch autostart;
    private TextView urlOn, urlOff, urlStatus, urlJpg, urlMjpg;
    private TextView[] camLabels;
    private TextView lastLabel;
    private TextView log, logToggle;
    private boolean logVisible = false;
    private boolean running = false;
    private boolean busy = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 4000);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (code, result) ->
            background(() -> ((App) getApplication()).autostart());
    private final Shizuku.OnBinderReceivedListener binderListener = this::refresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadColors();
        SharedPreferences p = Control.prefs(this);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(12), dp(20), dp(28));

        // Header
        TextView title = text(getString(R.string.app_name), 30, cText, true);
        column.addView(title);
        TextView sub = text(getString(R.string.subtitle), 15, cText2, false);
        sub.setPadding(0, dp(4), 0, dp(16));
        column.addView(sub);

        // Status
        LinearLayout status = card(column);
        status.addView(sectionTitle(getString(R.string.status)));
        TextView[] row = statusRow(status, "Shizuku");
        shizukuDot = row[0];
        shizukuText = row[1];
        row = statusRow(status, getString(R.string.service));
        serviceDot = row[0];
        serviceText = row[1];
        row = statusRow(status, getString(R.string.position));
        wallDot = row[0];
        wallText = row[1];

        mainButton = button(getString(R.string.start), true);
        mainButton.setOnClickListener(v -> toggleService());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(16);
        status.addView(mainButton, lp);

        permissionButton = button(getString(R.string.grant_shizuku), false);
        permissionButton.setOnClickListener(v -> {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(1);
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp2.topMargin = dp(10);
        status.addView(permissionButton, lp2);

        // Settings
        LinearLayout settings = card(column);
        settings.addView(sectionTitle(getString(R.string.settings)));

        LinearLayout idleRow = settingRow(settings, getString(R.string.idle_title), getString(R.string.idle_hint));
        LinearLayout stepper = new LinearLayout(this);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = roundButton("−");
        idleValue = text(String.valueOf(p.getInt("idle", 10)), 18, cText, true);
        idleValue.setGravity(Gravity.CENTER);
        idleValue.setMinWidth(dp(48));
        TextView plus = roundButton("+");
        minus.setOnClickListener(v -> changeIdle(-5));
        plus.setOnClickListener(v -> changeIdle(+5));
        stepper.addView(minus);
        stepper.addView(idleValue);
        stepper.addView(plus);
        idleRow.addView(stepper);

        LinearLayout autoRow = settingRow(settings, getString(R.string.autostart_title), getString(R.string.autostart_hint));
        autostart = new Switch(this);
        autostart.setChecked(p.getBoolean("autostart", true));
        autostart.setThumbTintList(switchTint(true));
        autostart.setTrackTintList(switchTint(false));
        autostart.setOnCheckedChangeListener((b, checked) -> Control.prefs(this).edit().putBoolean("autostart", checked).apply());
        autoRow.addView(autostart);

        LinearLayout pinRow = settingRow(settings, getString(R.string.pin_title), getString(R.string.pin_hint));
        Switch pin = new Switch(this);
        pin.setChecked(p.getBoolean("pin", true));
        pin.setThumbTintList(switchTint(true));
        pin.setTrackTintList(switchTint(false));
        pin.setOnCheckedChangeListener((b, checked) -> {
            Control.prefs(this).edit().putBoolean("pin", checked).apply();
            hintRestart();
        });
        pinRow.addView(pin);

        LinearLayout wallRow = settingRow(settings, getString(R.string.wall_title), getString(R.string.wall_hint));
        Switch wall = new Switch(this);
        wall.setChecked(p.getBoolean("wall", false));
        wall.setThumbTintList(switchTint(true));
        wall.setTrackTintList(switchTint(false));
        LinearLayout tolRow = stepperRow(settings, getString(R.string.tolerance_title), getString(R.string.tolerance_hint), "tolerance", 8, 1, 2, 25);
        LinearLayout waitRow = stepperRow(settings, getString(R.string.settle_title), getString(R.string.settle_hint), "settle", 20, 5, 5, 300);
        tolRow.setVisibility(wall.isChecked() ? View.VISIBLE : View.GONE);
        waitRow.setVisibility(wall.isChecked() ? View.VISIBLE : View.GONE);
        wall.setOnCheckedChangeListener((b, checked) -> {
            Control.prefs(this).edit().putBoolean("wall", checked).apply();
            tolRow.setVisibility(checked ? View.VISIBLE : View.GONE);
            waitRow.setVisibility(checked ? View.VISIBLE : View.GONE);
            hintRestart();
        });
        wallRow.addView(wall);

        TextView tokenLabel = text(getString(R.string.token_title), 16, cText, false);
        tokenLabel.setPadding(0, dp(14), 0, dp(2));
        settings.addView(tokenLabel);
        TextView tokenHint = text(getString(R.string.token_hint), 13, cText2, false);
        settings.addView(tokenHint);
        token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        token.setText(p.getString("token", ""));
        token.setTextColor(cText);
        token.setHintTextColor(cText2);
        token.setHint(R.string.token_empty);
        token.setBackground(rounded(cField, dp(12)));
        token.setPadding(dp(14), dp(12), dp(14), dp(12));
        token.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                Control.prefs(MainActivity.this).edit().putString("token", s.toString().replaceAll("[^A-Za-z0-9_-]", "")).apply();
                updateUrls();
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8);
        settings.addView(token, tlp);

        // Camera
        LinearLayout cam = card(column);
        cam.addView(sectionTitle(getString(R.string.camera)));
        TextView camHint = text(getString(R.string.camera_hint), 13, cText2, false);
        camHint.setPadding(0, 0, 0, dp(10));
        cam.addView(camHint);
        LinearLayout segments = new LinearLayout(this);
        segments.setBackground(rounded(cField, dp(24)));
        segments.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[][] options = {{"off", getString(R.string.camera_off)}, {"front", getString(R.string.camera_front)}, {"back", getString(R.string.camera_back)}};
        for (String[] o : options) {
            TextView seg = text(o[1], 15, cText, true);
            seg.setGravity(Gravity.CENTER);
            seg.setTag(o[0]);
            seg.setOnClickListener(v -> {
                Control.prefs(this).edit().putString("camera", (String) v.getTag()).apply();
                updateSegments(segments);
                updateUrls();
                hintRestart();
            });
            segments.addView(seg, new LinearLayout.LayoutParams(0, dp(40), 1));
        }
        cam.addView(segments, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updateSegments(segments);

        // Home Assistant
        LinearLayout ha = card(column);
        ha.addView(sectionTitle("Home Assistant"));
        TextView haHint = text(getString(R.string.ha_hint), 13, cText2, false);
        haHint.setPadding(0, 0, 0, dp(6));
        ha.addView(haHint);
        urlOn = urlRow(ha, getString(R.string.url_on));
        urlOff = urlRow(ha, getString(R.string.url_off));
        urlStatus = urlRow(ha, getString(R.string.url_status));
        camLabels = new TextView[2];
        urlJpg = urlRow(ha, getString(R.string.url_jpg));
        camLabels[0] = lastLabel;
        urlMjpg = urlRow(ha, getString(R.string.url_mjpg));
        camLabels[1] = lastLabel;

        // Log
        LinearLayout logCard = card(column);
        LinearLayout logHead = new LinearLayout(this);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = sectionTitle(getString(R.string.log));
        logHead.addView(logTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        logToggle = text(getString(R.string.show), 15, cAccent, true);
        logToggle.setPadding(dp(12), dp(6), 0, dp(6));
        logToggle.setOnClickListener(v -> {
            logVisible = !logVisible;
            log.setVisibility(logVisible ? View.VISIBLE : View.GONE);
            logToggle.setText(logVisible ? R.string.hide : R.string.show);
            refresh();
        });
        logHead.addView(logToggle);
        logCard.addView(logHead);
        log = text("", 12, cText2, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setBackground(rounded(cField, dp(12)));
        log.setPadding(dp(12), dp(10), dp(12), dp(10));
        log.setVisibility(View.GONE);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(8);
        logCard.addView(log, llp);

        // Centered, limited width for tablet landscape
        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, dp(MAX_WIDTH_DP)), ViewGroup.LayoutParams.WRAP_CONTENT);
        center.addView(column, clp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        scroll.setFillViewport(true);
        scroll.addView(center);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        scroll.setClipToPadding(false);
        setContentView(scroll);

        updateUrls();
        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderReceivedListener(binderListener);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- Actions

    private void toggleService() {
        if (busy) return;
        busy = true;
        mainButton.setText(running ? R.string.stopping : R.string.starting);
        boolean stop = running;
        background(() -> {
            if (stop) Control.stop();
            else Control.start(this);
        });
    }

    private void changeIdle(int delta) {
        SharedPreferences p = Control.prefs(this);
        int v = Math.max(0, Math.min(600, p.getInt("idle", 10) + delta));
        p.edit().putInt("idle", v).apply();
        idleValue.setText(String.valueOf(v));
        hintRestart();
    }

    private void background(Runnable r) {
        new Thread(() -> {
            r.run();
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
            }
            runOnUiThread(() -> {
                busy = false;
                refresh();
            });
        }).start();
    }

    private void refresh() {
        new Thread(() -> {
            boolean binder = Shizuku.pingBinder();
            boolean granted = binder && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            boolean isRunning = granted && Control.isRunning();
            org.json.JSONObject st = isRunning ? Control.status(this) : null;
            String text = granted && logVisible ? Control.log(40) : null;
            runOnUiThread(() -> {
                running = isRunning;
                if (!binder) {
                    setStatus(shizukuDot, shizukuText, cBad, getString(R.string.shizuku_not_running));
                } else if (!granted) {
                    setStatus(shizukuDot, shizukuText, cWarn, getString(R.string.shizuku_no_permission));
                } else {
                    setStatus(shizukuDot, shizukuText, cOk, getString(R.string.shizuku_ready));
                }
                setStatus(serviceDot, serviceText, isRunning ? cOk : cText2, isRunning ? getString(R.string.running) : getString(R.string.stopped));
                if (st == null || st.isNull("wall")) {
                    setStatus(wallDot, wallText, cText2, st == null ? "–" : getString(R.string.wall_mode_off));
                } else if (st.optBoolean("wall")) {
                    setStatus(wallDot, wallText, cOk, getString(R.string.wall_on));
                } else {
                    setStatus(wallDot, wallText, cWarn, getString(R.string.wall_not, st.optDouble("tilt"), st.optDouble("deviation")));
                }
                permissionButton.setVisibility(binder && !granted ? View.VISIBLE : View.GONE);
                if (!busy) {
                    mainButton.setText(isRunning ? R.string.stop : R.string.start);
                    styleButton(mainButton, !isRunning);
                }
                mainButton.setEnabled(granted);
                mainButton.setAlpha(granted ? 1f : 0.4f);
                if (text != null) log.setText(text.isEmpty() ? getString(R.string.log_empty) : text);
                updateUrls();
            });
        }).start();
    }

    private void updateUrls() {
        SharedPreferences p = Control.prefs(this);
        String ip = ipAddress();
        String base = "http://" + (ip != null ? ip : "<tablet-ip>") + ":" + p.getInt("port", 8765);
        String tok = p.getString("token", "");
        String suffix = tok.isEmpty() ? "" : "?token=" + tok;
        urlOn.setText(base + "/on" + suffix);
        urlOff.setText(base + "/off" + suffix);
        urlStatus.setText(base + "/status" + suffix);
        urlJpg.setText(base + "/camera.jpg" + suffix);
        urlMjpg.setText(base + "/camera.mjpg" + suffix);
        int vis = p.getString("camera", "off").equals("off") ? View.GONE : View.VISIBLE;
        urlJpg.setVisibility(vis);
        urlMjpg.setVisibility(vis);
        for (TextView l : camLabels) l.setVisibility(vis);
    }

    private void updateSegments(LinearLayout segments) {
        String current = Control.prefs(this).getString("camera", "off");
        for (int i = 0; i < segments.getChildCount(); i++) {
            TextView seg = (TextView) segments.getChildAt(i);
            boolean on = current.equals(seg.getTag());
            seg.setBackground(on ? rounded(cAccent, dp(20)) : null);
            seg.setTextColor(on ? cOnAccent : cText2);
        }
    }

    private void hintRestart() {
        if (running) Toast.makeText(this, R.string.restart_hint, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- Building blocks

    private void loadColors() {
        cBg = getColor(R.color.bg);
        cCard = getColor(R.color.card);
        cField = getColor(R.color.field);
        cText = getColor(R.color.text);
        cText2 = getColor(R.color.text2);
        cAccent = getColor(R.color.accent);
        cOnAccent = getColor(R.color.on_accent);
        cAccentSoft = getColor(R.color.accent_soft);
        cOk = getColor(R.color.ok);
        cWarn = getColor(R.color.warn);
        cBad = getColor(R.color.bad);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(cCard, dp(20)));
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s.toUpperCase(), 13, cText2, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView[] statusRow(LinearLayout parent, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        TextView dot = new TextView(this);
        dot.setBackground(oval(cText2));
        r.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView l = text(label, 17, cText, true);
        l.setPadding(dp(12), 0, 0, 0);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = text("…", 16, cText2, false);
        v.setGravity(Gravity.END);
        r.addView(v);
        parent.addView(r);
        return new TextView[] {dot, v};
    }

    private void setStatus(TextView dot, TextView label, int color, String s) {
        dot.setBackground(oval(color));
        label.setText(s);
    }

    private LinearLayout settingRow(LinearLayout parent, String title, String hint) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 16, cText, false));
        texts.addView(text(hint, 13, cText2, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(r);
        return r;
    }

    private LinearLayout stepperRow(LinearLayout parent, String title, String hint, String key, int def, int step, int min, int max) {
        LinearLayout row = settingRow(parent, title, hint);
        TextView minus = roundButton("−");
        TextView value = text(String.valueOf(Control.prefs(this).getInt(key, def)), 18, cText, true);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(48));
        TextView plus = roundButton("+");
        View.OnClickListener change = v -> {
            SharedPreferences p = Control.prefs(this);
            int n = Math.max(min, Math.min(max, p.getInt(key, def) + (v == plus ? step : -step)));
            p.edit().putInt(key, n).apply();
            value.setText(String.valueOf(n));
            hintRestart();
        };
        minus.setOnClickListener(change);
        plus.setOnClickListener(change);
        row.addView(minus);
        row.addView(value);
        row.addView(plus);
        return row;
    }

    private TextView urlRow(LinearLayout parent, String label) {
        TextView l = text(label, 13, cText2, false);
        l.setPadding(0, dp(8), 0, dp(4));
        parent.addView(l);
        lastLabel = l;
        TextView u = text("", 14, cText, false);
        u.setTypeface(Typeface.MONOSPACE);
        u.setBackground(new RippleDrawable(ColorStateList.valueOf(cAccentSoft), rounded(cField, dp(10)), null));
        u.setPadding(dp(12), dp(10), dp(12), dp(10));
        u.setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText(label, u.getText()));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
        parent.addView(u);
        return u;
    }

    private TextView button(String s, boolean primary) {
        TextView b = text(s, 16, primary ? cOnAccent : cAccent, true);
        b.setGravity(Gravity.CENTER);
        styleButton(b, primary);
        return b;
    }

    private void styleButton(TextView b, boolean primary) {
        int bg = primary ? cAccent : cAccentSoft;
        b.setTextColor(primary ? cOnAccent : cAccent);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(bg, dp(26)), null));
    }

    private TextView roundButton(String s) {
        TextView b = text(s, 22, cAccent, true);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(cAccentSoft);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), d, null));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return b;
    }

    private ColorStateList switchTint(boolean thumb) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        int[] colors = thumb ? new int[] {cAccent, cText2} : new int[] {(cAccent & 0x00FFFFFF) | 0x66000000, (cText2 & 0x00FFFFFF) | 0x44000000};
        return new ColorStateList(states, colors);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String ipAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}

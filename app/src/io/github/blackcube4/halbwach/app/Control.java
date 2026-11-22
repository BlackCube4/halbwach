package io.github.blackcube4.halbwach.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/** Starts and stops the panel service through Shizuku (it then runs as the shell user). */
final class Control {

    static final String LOG = "/data/local/tmp/halbwach.log";
    static final String STOP_FILE = "/data/local/tmp/halbwach.stop";
    static final String HA_APP = "io.homeassistant.companion.android";
    static final String MAIN_CLASS = "io.github.blackcube4.halbwach.Main";
    // The brackets keep pgrep from matching its own command line
    private static final String PATTERN = "'[i]o.github.blackcube4.halbwach.Main'";

    private Control() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean shizukuReady() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isRunning() {
        return !exec("pgrep -f " + PATTERN).trim().isEmpty();
    }

    static String start(Context c) {
        SharedPreferences p = prefs(c);
        String args = "idle=" + p.getInt("idle", 10) + " port=" + p.getInt("port", 8765);
        String token = p.getString("token", "").trim();
        if (!token.isEmpty()) args += " token=" + token.replaceAll("[^A-Za-z0-9_-]", "");
        args += " camera=" + p.getString("camera", "off");
        if (p.getBoolean("pin", true)) args += " app=" + HA_APP;
        if (p.getBoolean("wall", false)) {
            args += " wall=1 tolerance=" + p.getInt("tolerance", 8) + " settle=" + p.getInt("settle", 20);
        }
        String apk = c.getApplicationInfo().sourceDir;
        // Stop a running service cleanly first (stop file), hard only if needed
        if (isRunning()) stop();
        exec("CLASSPATH=" + apk + " setsid nohup app_process / " + MAIN_CLASS + " " + args
                + " > " + LOG + " 2>&1 < /dev/null &");
        return args;
    }

    /** Clean stop via the stop file (panel on, timeout restored); kill only after 8 s. */
    static void stop() {
        exec("touch " + STOP_FILE + "; i=0; while pgrep -f " + PATTERN + " >/dev/null && [ $i -lt 32 ]; do sleep 0.25; i=$((i+1)); done; "
                + "pkill -f " + PATTERN + "; rm -f " + STOP_FILE);
    }

    /** Status of the running service from its HTTP interface, or null. */
    static org.json.JSONObject status(Context c) {
        SharedPreferences p = prefs(c);
        String token = p.getString("token", "").trim();
        try {
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + p.getInt("port", 8765) + "/status" + (token.isEmpty() ? "" : "?token=" + token)).openConnection();
            con.setConnectTimeout(1500);
            con.setReadTimeout(1500);
            try (java.io.InputStream in = con.getInputStream()) {
                return new org.json.JSONObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    static String log(int lines) {
        return exec("tail -n " + lines + " " + LOG);
    }

    static String exec(String cmd) {
        try {
            // Shizuku.newProcess is private in the API but still present
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process proc = (Process) m.invoke(null, new String[] {"sh", "-c", cmd}, null, null);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            proc.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "Error: " + cause;
        }
    }
}

package io.github.blackcube4.halbwach.app;

import android.app.Application;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * When the Shizuku service starts (e.g. after a reboot) it sends its binder to every app it
 * has granted. That starts this process, and the panel service can start along with it.
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Shizuku.addBinderReceivedListenerSticky(() -> new Thread(this::autostart, "autostart").start());
    }

    // The binder can arrive several times in a row: never start twice at once
    synchronized void autostart() {
        if (!Control.prefs(this).getBoolean("autostart", true)) return;
        if (!Control.shizukuReady()) return;
        if (Control.isRunning()) return;
        Log.i("Halbwach", "Autostart: " + Control.start(this));
    }
}

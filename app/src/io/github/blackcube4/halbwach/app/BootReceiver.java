package io.github.blackcube4.halbwach.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After boot: if Shizuku is already running (root, or a fork that starts on boot), start the
 * service right away. Otherwise it starts once Shizuku sends its binder later (see {@link App}).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        App app = (App) context.getApplicationContext();
        new Thread(() -> {
            try {
                app.autostart();
            } finally {
                pending.finish();
            }
        }, "boot").start();
    }
}

package io.github.blackcube4.halbwach;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Uses the accelerometer to detect whether the tablet hangs in its wall mount: display
 * vertical and the tablet straight in landscape or portrait, without movement.
 *
 * It counts as on the wall only after that position was stable for a while. It counts as
 * taken off as soon as it is tilted clearly or moved for longer. A short tap does not count.
 */
final class WallMount {

    private static final float ALPHA = 0.15f;          // low-pass filter for gravity
    private static final float MOVE_THRESHOLD = 1.2f;  // m/s^2 deviation = movement
    private static final long LEAVE_AFTER_MS = 1500;   // tilted/moved this long = taken off
    private static final float HYSTERESIS = 5f;        // extra degrees before leaving

    private static volatile boolean enabled = false;
    private static float tolerance = 8f;
    private static long settleMs = 20_000;

    private static final float[] g = new float[3];
    private static boolean hasGravity = false;

    private static volatile boolean onWall = false;
    private static volatile float tilt = 90f;      // display tilt from vertical
    private static volatile float rotation = 45f;  // deviation from landscape/portrait
    private static long goodSince = 0;
    private static long badSince = 0;
    private static long lastLog = 0;

    private WallMount() {
    }

    static boolean enabled() {
        return enabled;
    }

    /** Without wall mode the tablet always counts as "on the wall". */
    static boolean onWall() {
        return !enabled || onWall;
    }

    static String status() {
        if (!enabled) return "\"wall\":null";
        return String.format(Locale.ROOT, "\"wall\":%b,\"tilt\":%.1f,\"deviation\":%.1f", onWall, tilt, rotation);
    }

    static void init(float toleranceDegrees, int settleSeconds) {
        tolerance = toleranceDegrees;
        settleMs = settleSeconds * 1000L;
        HandlerThread thread = new HandlerThread("wallmount");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        CountDownLatch done = new CountDownLatch(1);
        handler.post(() -> {
            try {
                SensorManager sm = (SensorManager) ShellContext.get().getSystemService(Context.SENSOR_SERVICE);
                Sensor accel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (accel == null) throw new IllegalStateException("no accelerometer");
                boolean ok = sm.registerListener(new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent e) {
                        update(e.values[0], e.values[1], e.values[2]);
                    }

                    @Override
                    public void onAccuracyChanged(Sensor s, int accuracy) {
                    }
                }, accel, SensorManager.SENSOR_DELAY_NORMAL, handler);
                if (!ok) throw new IllegalStateException("cannot register the sensor listener");
                enabled = true;
                Main.log(String.format(Locale.ROOT, "Wall mode on: tolerance %.0f degrees, %d s still until active", tolerance, settleMs / 1000));
            } catch (Throwable t) {
                Main.log("Wall mode unavailable: " + (t.getCause() != null ? t.getCause() : t));
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private static void update(float x, float y, float z) {
        if (!hasGravity) {
            g[0] = x;
            g[1] = y;
            g[2] = z;
            hasGravity = true;
        } else {
            g[0] += ALPHA * (x - g[0]);
            g[1] += ALPHA * (y - g[1]);
            g[2] += ALPHA * (z - g[2]);
        }
        float norm = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        if (norm < 1f) return;

        // Display vertical: gravity lies in the display plane, z is almost 0
        tilt = (float) Math.toDegrees(Math.asin(Math.min(1f, Math.abs(g[2]) / norm)));
        // Hung straight: gravity points along a display edge
        double angle = Math.toDegrees(Math.atan2(g[0], g[1]));
        rotation = (float) Math.abs(angle - Math.round(angle / 90.0) * 90.0);

        float dx = x - g[0], dy = y - g[1], dz = z - g[2];
        boolean moving = Math.sqrt(dx * dx + dy * dy + dz * dz) > MOVE_THRESHOLD;

        long now = System.currentTimeMillis();
        boolean good = !moving && tilt <= tolerance && rotation <= tolerance;
        boolean bad = moving || tilt > tolerance + HYSTERESIS || rotation > tolerance + HYSTERESIS;

        if (!onWall) {
            if (good) {
                if (goodSince == 0) goodSince = now;
                if (now - goodSince >= settleMs) {
                    onWall = true;
                    badSince = 0;
                    Main.log(String.format(Locale.ROOT, "On the wall (tilt %.1f, deviation %.1f degrees)", tilt, rotation));
                    Main.onWallChanged(true);
                }
            } else {
                goodSince = 0;
            }
        } else {
            if (bad) {
                if (badSince == 0) badSince = now;
                if (now - badSince >= LEAVE_AFTER_MS) {
                    onWall = false;
                    goodSince = 0;
                    Main.log(String.format(Locale.ROOT, "Taken off the wall (tilt %.1f, deviation %.1f degrees%s)",
                            tilt, rotation, moving ? ", moving" : ""));
                    Main.onWallChanged(false);
                }
            } else {
                badSince = 0;
            }
        }

        // For tuning: log the position at most every 5 minutes
        if (now - lastLog > 300_000) {
            lastLog = now;
            Main.log(String.format(Locale.ROOT, "Position: tilt %.1f, deviation %.1f degrees, %s", tilt, rotation, onWall ? "on the wall" : "not on the wall"));
        }
    }
}

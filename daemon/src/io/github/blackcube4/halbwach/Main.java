package io.github.blackcube4.halbwach;

import android.os.Build;
import android.os.IBinder;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Halbwach: turns off only the display panel while Android stays awake, so a web
 * dashboard keeps running and is up to date the moment the panel comes back on.
 *
 * Runs as the shell user (app_process via adb or Shizuku). Panel control follows
 * scrcpy (Apache 2.0, https://github.com/Genymobile/scrcpy).
 *
 * Arguments (all optional, key=value):
 *   idle=10        seconds without touch before the panel turns off (0 = never automatically)
 *   device=/dev/input/event4
 *   port=8765      HTTP port for Home Assistant (0 = off)
 *   token=...      if set, every request must carry ?token=...
 *   camera=off     front or back: serves /camera.jpg and /camera.mjpg
 *   camera_width=1280, camera_fps=5, rotation=0 (extra image rotation in degrees)
 *   app=...        package name: open and pin this app on start (e.g. after a reboot)
 *   wall=0         1: only active while the tablet hangs straight and still on the wall
 *   tolerance=8, settle=20 (degrees of deviation, seconds of stillness until active)
 */
public final class Main {

    // linux/input-event-codes.h
    private static final int EV_KEY = 0x01;
    private static final int KEY_F1 = 59;
    private static final int BTN_TOUCH = 0x14a;
    // _IOW('E', 0x90, int)
    private static final int EVIOCGRAB = 0x40044590;

    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;
    private static final int TIMEOUT_MAX = Integer.MAX_VALUE;
    private static final long DOUBLE_TAP_MS = 500;

    static final String STOP_FILE = "/data/local/tmp/halbwach.stop";
    static final String TIMEOUT_FILE = "/data/local/tmp/halbwach.timeout";

    private static long idleMs = 10_000;
    private static String device = "/dev/input/event4";
    private static int port = 8765;
    private static String token = null;
    private static String camera = "off";
    private static int cameraWidth = 1280;
    private static int cameraFps = 5;
    private static int rotation = 0;
    private static String app = null;
    private static boolean wall = false;
    private static float tolerance = 8f;
    private static int settle = 20;
    private static volatile boolean armed = false;

    private static final Object lock = new Object();
    private static volatile boolean panelOn = true;
    private static volatile long lastActivity = System.currentTimeMillis();
    private static volatile long panelOffSince = 0;
    private static String originalTimeout = null;
    private static boolean ownTimeout = false;
    @SuppressWarnings("unused")
    private static android.net.LocalServerSocket singleInstance;

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            int i = a.indexOf('=');
            if (i < 0) continue;
            String k = a.substring(0, i), v = a.substring(i + 1);
            switch (k) {
                case "idle": idleMs = Long.parseLong(v) * 1000; break;
                case "device": device = v; break;
                case "port": port = Integer.parseInt(v); break;
                case "token": token = v.isEmpty() ? null : v; break;
                case "camera": camera = v; break;
                case "camera_width": cameraWidth = Integer.parseInt(v); break;
                case "camera_fps": cameraFps = Integer.parseInt(v); break;
                case "rotation": rotation = Integer.parseInt(v); break;
                case "app": app = v.isEmpty() ? null : v; break;
                case "wall": wall = v.equals("1") || v.equals("on"); break;
                case "tolerance": tolerance = Float.parseFloat(v); break;
                case "settle": settle = Integer.parseInt(v); break;
                default: log("Unknown argument: " + a);
            }
        }
        // Single instance: abstract socket names are unique system-wide
        try {
            singleInstance = new android.net.LocalServerSocket("halbwach");
        } catch (java.io.IOException e) {
            log("Already running, this instance exits");
            return;
        }
        log("Start: Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.BRAND + " " + Build.MODEL
                + ", idle=" + idleMs / 1000 + "s, device=" + device + ", port=" + port + (token != null ? ", with token" : "")
                + ", camera=" + camera + ", wall=" + wall);

        // If an earlier run was killed hard, our maximum timeout is still set:
        // restore the user's remembered value first.
        String leftover = sh("cat " + TIMEOUT_FILE + " 2>/dev/null").trim();
        if (!leftover.isEmpty()) {
            sh("settings put system screen_off_timeout " + leftover + "; rm -f " + TIMEOUT_FILE);
            log("Screen timeout restored from an earlier run: " + leftover);
        }

        // ART does not run shutdown hooks on SIGTERM. Stop cleanly via the stop file or
        // HTTP /stop instead; the hook only runs on System.exit.
        Runtime.getRuntime().addShutdownHook(new Thread(Main::cleanUp));
        sh("rm -f " + STOP_FILE);

        if (app != null) pinApp();
        Camera.init(camera, cameraWidth, cameraFps, rotation);
        if (wall) WallMount.init(tolerance, settle);
        // Android must not go to sleep by itself: with the panel off no touches arrive.
        // In wall mode only once the tablet is on the wall.
        if (WallMount.onWall()) takeTimeout();

        Thread input = new Thread(Main::inputLoop, "input");
        input.setDaemon(true);
        input.start();

        if (port > 0) {
            Thread http = new Thread(Main::httpLoop, "http");
            http.setDaemon(true);
            http.start();
        }

        boolean wasInteractive = true;
        long nextArmCheck = 0;
        boolean waitLogged = false;
        while (true) {
            Thread.sleep(250);
            // Arm the gesture (short sleep/wake) only when no lock screen can show up:
            // an app is pinned, or the device is unlocked right now.
            if (!armed && System.currentTimeMillis() > nextArmCheck) {
                nextArmCheck = System.currentTimeMillis() + 3000;
                if (lockTaskActive() || (isInteractive() && !keyguardShowing())) {
                    armGesture();
                    armed = true;
                    wasInteractive = isInteractive();
                } else if (!waitLogged) {
                    log("Waiting to turn the panel off until unlocked or an app is pinned");
                    waitLogged = true;
                }
            }
            if (new java.io.File(STOP_FILE).exists()) {
                sh("rm -f " + STOP_FILE);
                log("Stop file found");
                System.exit(0);
            }
            boolean interactive = isInteractive();
            if (interactive != wasInteractive) {
                log(interactive ? "Android woke up" : "Android went to sleep (e.g. power button)");
                wasInteractive = interactive;
                synchronized (lock) {
                    // After a real sleep/wake Android controls the panel again.
                    panelOn = true;
                    lastActivity = System.currentTimeMillis();
                }
            }
            if (armed && WallMount.onWall() && interactive && idleMs > 0 && panelOn && System.currentTimeMillis() - lastActivity > idleMs) {
                // If pinning was released in the meantime (e.g. to use Shizuku),
                // bring the dashboard app back and pin it before turning the panel off.
                if (app != null && !lockTaskActive()) pinApp();
                setPanel(false, "no touch for " + idleMs / 1000 + " s");
            }
        }
    }

    // ---------------------------------------------------------------- Gesture arming

    /** Honor only arms the double-tap gesture on a real sleep; afterwards it stays armed. */
    private static void armGesture() throws InterruptedException {
        log("Arming the double-tap gesture (short sleep and wake)");
        sh("input keyevent KEYCODE_SLEEP");
        Thread.sleep(1500);
        sh("input keyevent KEYCODE_WAKEUP");
        Thread.sleep(1500);
        lastActivity = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- Wall mode

    /** Called by the wall mount detector: hung on the wall (true) or taken off (false). */
    static void onWallChanged(boolean onWall) {
        new Thread(() -> {
            if (onWall) {
                takeTimeout();
                lastActivity = System.currentTimeMillis();
                log("Halbwach active");
            } else {
                setPanel(true, "taken off the wall");
                restoreTimeout();
                if (app != null && lockTaskActive()) sh("am task lock stop");
                log("Halbwach paused: normal tablet, pinning released");
            }
        }, "wall").start();
    }

    // ---------------------------------------------------------------- App pinning

    /** Opens the dashboard app and pins it (screen pinning) unless something is pinned already. */
    private static void pinApp() {
        if (lockTaskActive()) {
            log("An app is pinned already");
            return;
        }
        // Start through the launcher intent (like tapping the icon). "am start -n" with the
        // activity fails on MagicOS with "Activity class ... does not exist".
        String started = sh("monkey -p " + app + " -c android.intent.category.LAUNCHER 1");
        if (!started.contains("Events injected: 1")) {
            log("Cannot start app " + app + ": " + started.replace('\n', ' ').trim());
            return;
        }
        for (int i = 0; i < 20; i++) {
            sleepQuietly(500);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("taskId=(\\d+): " + java.util.regex.Pattern.quote(app) + "/")
                    .matcher(sh("am stack list"));
            if (m.find()) {
                sh("am task lock " + m.group(1));
                log("Opened and pinned app " + app + " (task " + m.group(1) + ")");
                return;
            }
        }
        log("Started app " + app + " but could not pin it (task not found)");
    }

    private static boolean lockTaskActive() {
        String state = sh("dumpsys activity activities | grep -m1 mLockTaskModeState");
        return state.contains("PINNED") || state.contains("LOCKED");
    }

    private static boolean keyguardShowing() {
        return sh("dumpsys window | grep -m1 isKeyguardShowing").contains("true");
    }

    // ---------------------------------------------------------------- Panel

    private static void setPanel(boolean on, String reason) {
        synchronized (lock) {
            if (panelOn == on) return;
            boolean ok = setDisplayPowerMode(on ? POWER_MODE_NORMAL : POWER_MODE_OFF);
            if (!ok) {
                log("ERROR: turning the panel " + (on ? "on" : "off") + " failed (" + reason + ")");
                return;
            }
            panelOn = on;
            lastActivity = System.currentTimeMillis();
            if (on) {
                long dark = (System.currentTimeMillis() - panelOffSince) / 1000;
                log("Panel ON  (" + reason + ", was dark for " + dark + " s)");
            } else {
                panelOffSince = System.currentTimeMillis();
                log("Panel OFF (" + reason + ")");
            }
            lock.notifyAll();
        }
    }

    private static boolean setDisplayPowerMode(int mode) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Method setMode = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);

            // Like scrcpy: on Honor (Android 14+) only the internal display via getInternalDisplayToken.
            Method internal = null;
            try {
                internal = sc.getMethod("getInternalDisplayToken");
            } catch (NoSuchMethodException ignored) {
            }
            if (internal != null && Build.BRAND.equalsIgnoreCase("honor")) {
                setMode.invoke(null, (IBinder) internal.invoke(null), mode);
                return true;
            }

            // Otherwise all physical displays (through DisplayControl since Android 14)
            Class<?> dc = displayControlClass();
            long[] ids = (long[]) dc.getMethod("getPhysicalDisplayIds").invoke(null);
            Method token = dc.getMethod("getPhysicalDisplayToken", long.class);
            for (long id : ids) {
                setMode.invoke(null, (IBinder) token.invoke(null, id), mode);
            }
            return true;
        } catch (Throwable t) {
            log("setDisplayPowerMode: " + t);
            return false;
        }
    }

    private static Class<?> displayControl;

    private static Class<?> displayControlClass() throws Exception {
        if (displayControl == null) {
            Class<?> f = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method create = f.getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                    ClassLoader.class, int.class, boolean.class, String.class);
            ClassLoader cl = (ClassLoader) create.invoke(null, Os.getenv("SYSTEMSERVERCLASSPATH"), null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null);
            Class<?> c = cl.loadClass("com.android.server.display.DisplayControl");
            Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            load.setAccessible(true);
            load.invoke(Runtime.getRuntime(), c, "android_servers");
            displayControl = c;
        }
        return displayControl;
    }

    private static Object powerManager;

    private static boolean isInteractive() {
        try {
            if (powerManager == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "power");
                powerManager = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            }
            return (Boolean) powerManager.getClass().getMethod("isInteractive").invoke(powerManager);
        } catch (Throwable t) {
            log("isInteractive: " + t);
            return true;
        }
    }

    // ---------------------------------------------------------------- Touchscreen

    /**
     * Reads the touchscreen directly. While the panel is off the device is grabbed
     * exclusively so that Android does not pass blind touches to the dashboard.
     */
    private static void inputLoop() {
        final int size = System.getProperty("os.arch", "").contains("64") ? 24 : 16;
        final byte[] buf = new byte[size * 64];
        long lastTapDown = 0;
        while (true) {
            FileDescriptor fd = null;
            boolean grabbed = false;
            try {
                fd = Os.open(device, OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
                boolean wantGrab = !panelOn;
                if (wantGrab) grabbed = grab(fd);
                StructPollfd p = new StructPollfd();
                p.fd = fd;
                p.events = (short) OsConstants.POLLIN;
                while (panelOn != wantGrab) { // state unchanged -> keep reading
                    p.revents = 0;
                    if (Os.poll(new StructPollfd[] {p}, 200) <= 0) continue;
                    int n;
                    try {
                        n = Os.read(fd, buf, 0, buf.length);
                    } catch (ErrnoException e) {
                        if (e.errno == OsConstants.EAGAIN) continue;
                        throw e;
                    }
                    for (int off = 0; off + size <= n; off += size) {
                        int type = u16(buf, off + size - 8);
                        int code = u16(buf, off + size - 6);
                        int value = s32(buf, off + size - 4);
                        long now = System.currentTimeMillis();
                        if (type == EV_KEY && code == KEY_F1 && value == 1) {
                            log("Double-tap gesture detected" + (panelOn ? " (panel was on already)" : ""));
                            setPanel(true, "double-tap gesture");
                        } else if (type == EV_KEY && code == BTN_TOUCH && value == 1) {
                            if (!panelOn) {
                                // Gesture was not armed: the driver reports normal touches.
                                if (now - lastTapDown < DOUBLE_TAP_MS) {
                                    log("Double tap (normal touches) on the dark panel");
                                    setPanel(true, "double tap without gesture");
                                } else {
                                    log("Touch on the dark panel" + (grabbed ? " (intercepted)" : " (NOT intercepted)"));
                                }
                            }
                            lastTapDown = now;
                        }
                        if (panelOn) lastActivity = now;
                    }
                }
            } catch (Throwable t) {
                log("Touchscreen: " + t);
                sleepQuietly(1000);
            } finally {
                if (fd != null) {
                    try {
                        Os.close(fd); // also releases a grab
                    } catch (ErrnoException ignored) {
                    }
                }
            }
        }
    }

    private static Method ioctlInt;

    private static boolean grab(FileDescriptor fd) {
        try {
            if (ioctlInt == null) ioctlInt = Os.class.getMethod("ioctlInt", FileDescriptor.class, int.class);
            ioctlInt.invoke(null, fd, EVIOCGRAB);
            return true;
        } catch (Throwable t) {
            log("Grabbing the touchscreen failed: " + (t.getCause() != null ? t.getCause() : t));
            return false;
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    private static int s32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | b[o + 3] << 24;
    }

    // ---------------------------------------------------------------- HTTP for Home Assistant

    private static void httpLoop() {
        try (ServerSocket server = new ServerSocket(port, 16, InetAddress.getByName("0.0.0.0"))) {
            log("HTTP listening on port " + port + " (/on, /off, /status" + (Camera.enabled() ? ", /camera.jpg, /camera.mjpg" : "") + ")");
            while (true) {
                Socket s = server.accept();
                // One thread per request so that a running camera stream blocks nothing
                Thread t = new Thread(() -> handle(s), "http-request");
                t.setDaemon(true);
                t.start();
            }
        } catch (Throwable t) {
            log("HTTP server: " + t);
        }
    }

    private static void handle(Socket s) {
        String path = "?";
        try (Socket socket = s) {
            socket.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String target = parts.length > 1 ? parts[1] : "/";
            path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            OutputStream out = socket.getOutputStream();
            if (token != null && !query.contains("token=" + token)) {
                respond(out, 403, "application/json", "{\"error\":403}\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            switch (path) {
                case "/on":
                    setPanel(true, "Home Assistant");
                    break;
                case "/off":
                    setPanel(false, "Home Assistant");
                    break;
                case "/stop":
                    new Thread(() -> {
                        sleepQuietly(300);
                        log("Stop via HTTP");
                        System.exit(0);
                    }).start();
                    break;
                case "/camera.jpg": {
                    if (!Camera.enabled()) {
                        respond(out, 404, "application/json", "{\"error\":\"camera off\"}\n".getBytes(StandardCharsets.US_ASCII));
                        return;
                    }
                    byte[] jpg = Camera.snapshot();
                    if (jpg == null) {
                        respond(out, 503, "application/json", "{\"error\":\"no image\"}\n".getBytes(StandardCharsets.US_ASCII));
                    } else {
                        respond(out, 200, "image/jpeg", jpg);
                    }
                    return;
                }
                case "/camera.mjpg":
                    if (!Camera.enabled()) {
                        respond(out, 404, "application/json", "{\"error\":\"camera off\"}\n".getBytes(StandardCharsets.US_ASCII));
                        return;
                    }
                    log("Camera stream opened by " + socket.getInetAddress().getHostAddress());
                    try {
                        Camera.stream(out);
                    } finally {
                        log("Camera stream closed");
                    }
                    return;
                case "/status":
                    break;
                default:
                    respond(out, 404, "application/json", "{\"error\":404}\n".getBytes(StandardCharsets.US_ASCII));
                    return;
            }
            String body = "{\"panel\":\"" + (panelOn ? "on" : "off") + "\",\"android_awake\":" + isInteractive()
                    + ",\"camera\":\"" + (!Camera.enabled() ? "off" : Camera.active() ? "active" : "ready") + "\","
                    + WallMount.status() + "}\n";
            respond(out, 200, "application/json", body.getBytes(StandardCharsets.US_ASCII));
        } catch (java.net.SocketException e) {
            // The client closed the connection (e.g. a stream ended)
        } catch (Throwable t) {
            log("HTTP request " + path + ": " + t);
        }
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws java.io.IOException {
        String reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Error";
        out.write(("HTTP/1.0 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    // ---------------------------------------------------------------- Helpers

    private static boolean cleanedUp = false;

    private static synchronized void cleanUp() {
        if (cleanedUp) return;
        cleanedUp = true;
        setPanel(true, "exit");
        restoreTimeout();
        log("Stopped");
    }

    /**
     * Remembers the user's screen timeout (including "never") and sets the maximum.
     * The remembered value is also written to a file in case the service is killed hard.
     */
    private static synchronized void takeTimeout() {
        if (ownTimeout) return;
        String current = sh("settings get system screen_off_timeout").trim();
        if (current.isEmpty() || current.equals("null")) current = "15000";
        originalTimeout = current;
        sh("echo " + current + " > " + TIMEOUT_FILE);
        sh("settings put system screen_off_timeout " + TIMEOUT_MAX);
        ownTimeout = true;
        log("Screen timeout was " + current + ", now maximum");
    }

    /** Gives the user's timeout back, but only if Halbwach changed it. */
    private static synchronized void restoreTimeout() {
        if (!ownTimeout) return;
        ownTimeout = false;
        String current = sh("settings get system screen_off_timeout").trim();
        if (!current.equals(String.valueOf(TIMEOUT_MAX))) {
            // Changed in the settings meanwhile: that choice wins
            sh("rm -f " + TIMEOUT_FILE);
            log("Screen timeout was changed to " + current + " meanwhile, keeping it");
            return;
        }
        sh("settings put system screen_off_timeout " + originalTimeout + "; rm -f " + TIMEOUT_FILE);
        log("Screen timeout restored to " + originalTimeout);
    }

    private static String sh(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            log("Command failed: " + cmd + ": " + e);
            return "";
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    static synchronized void log(String msg) {
        System.out.println(TIME.format(new Date()) + "  " + msg);
        System.out.flush();
    }
}

package io.github.blackcube4.halbwach;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Range;
import android.util.Size;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Camera for Home Assistant: snapshot (/camera.jpg) and MJPEG stream (/camera.mjpg).
 * The camera only runs while someone is fetching images and turns off 5 s afterwards.
 */
final class Camera {

    private static final long CLOSE_DELAY_MS = 5000;
    private static final long WARMUP_MS = 1000; // let auto exposure settle

    private static boolean enabled = false;
    private static boolean front = true;
    private static int maxWidth = 1280;
    private static int fps = 5;
    private static int extraRotation = 0;

    private static Handler handler;
    private static Executor executor;
    private static CameraManager manager;
    private static String cameraId;

    private static CameraDevice device;
    private static CameraCaptureSession session;
    private static ImageReader reader;
    private static int users = 0;
    private static long openedAt = 0;

    private static final Object frameLock = new Object();
    private static byte[] frame;
    private static long frameSeq = 0;

    private static final Runnable closeLater = Camera::closeIfUnused;

    private Camera() {
    }

    static boolean enabled() {
        return enabled;
    }

    static synchronized boolean active() {
        return device != null;
    }

    /** facing: front, back or off */
    static void init(String facing, int width, int framesPerSecond, int rotation) {
        if (facing == null || facing.equals("off")) return;
        front = !facing.equals("back");
        maxWidth = width;
        fps = Math.max(1, Math.min(15, framesPerSecond));
        extraRotation = rotation;

        HandlerThread thread = new HandlerThread("camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        executor = handler::post;

        CountDownLatch done = new CountDownLatch(1);
        handler.post(() -> {
            try {
                // The context needs a looper; this thread becomes the main looper
                Field main = android.os.Looper.class.getDeclaredField("sMainLooper");
                main.setAccessible(true);
                if (main.get(null) == null) main.set(null, thread.getLooper());

                Constructor<CameraManager> ctor = CameraManager.class.getDeclaredConstructor(Context.class);
                manager = ctor.newInstance(ShellContext.get());
                for (String id : manager.getCameraIdList()) {
                    Integer lens = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                    boolean isFront = lens != null && lens == CameraCharacteristics.LENS_FACING_FRONT;
                    if (isFront == front) {
                        cameraId = id;
                        break;
                    }
                }
                if (cameraId == null) throw new IllegalStateException("no " + (front ? "front" : "back") + " camera found");
                enabled = true;
                Main.log("Camera ready: " + (front ? "front" : "back") + " (id " + cameraId + "), max. width " + maxWidth + ", " + fps + " fps");
            } catch (Throwable t) {
                Main.log("Camera unavailable: " + (t.getCause() != null ? t.getCause() : t));
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    // ---------------------------------------------------------------- Fetching

    static byte[] snapshot() throws Exception {
        acquire();
        try {
            long seq = seq();
            long deadline = System.currentTimeMillis() + 8000;
            byte[] f = null;
            while (System.currentTimeMillis() < deadline) {
                f = nextFrame(seq, 3000);
                if (f == null) break;
                seq = seq();
                if (System.currentTimeMillis() - openedAt >= WARMUP_MS) break;
            }
            return f;
        } finally {
            release();
        }
    }

    static void stream(OutputStream out) throws Exception {
        acquire();
        try {
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            long interval = 1000 / fps;
            long seq = seq();
            while (true) {
                long started = System.currentTimeMillis();
                byte[] f = nextFrame(seq, 4000);
                if (f == null) throw new IOException("camera delivers no images");
                seq = seq();
                out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(f);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                long wait = interval - (System.currentTimeMillis() - started);
                if (wait > 0) Thread.sleep(wait);
            }
        } finally {
            release();
        }
    }

    private static long seq() {
        synchronized (frameLock) {
            return frameSeq;
        }
    }

    private static byte[] nextFrame(long after, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (frameLock) {
            while (frameSeq <= after) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                frameLock.wait(left);
            }
            return frame;
        }
    }

    // ---------------------------------------------------------------- Camera on/off

    private static synchronized void acquire() throws Exception {
        if (!enabled) throw new IllegalStateException("camera is disabled");
        users++;
        handler.removeCallbacks(closeLater);
        if (device == null) {
            try {
                open();
            } catch (Exception e) {
                users--;
                closeNow();
                throw e;
            }
        }
    }

    private static synchronized void release() {
        users = Math.max(0, users - 1);
        if (users == 0) handler.postDelayed(closeLater, CLOSE_DELAY_MS);
    }

    private static synchronized void closeIfUnused() {
        if (users == 0 && device != null) {
            closeNow();
            Main.log("Camera off");
        }
    }

    private static void closeNow() {
        try {
            if (session != null) session.close();
        } catch (Throwable ignored) {
        }
        try {
            if (device != null) device.close();
        } catch (Throwable ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Throwable ignored) {
        }
        session = null;
        device = null;
        reader = null;
    }

    @SuppressWarnings("MissingPermission")
    private static void open() throws Exception {
        CameraCharacteristics ch = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size size = null;
        for (Size s : map.getOutputSizes(ImageFormat.JPEG)) {
            boolean fits = s.getWidth() <= maxWidth;
            if (size == null
                    || (fits && (size.getWidth() > maxWidth || s.getWidth() * s.getHeight() > size.getWidth() * size.getHeight()))
                    || (!fits && size.getWidth() > maxWidth && s.getWidth() < size.getWidth())) {
                size = s;
            }
        }
        Range<Integer> range = null;
        Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (range == null || r.getUpper() < range.getUpper()
                        || (r.getUpper().equals(range.getUpper()) && r.getLower() < range.getLower())) {
                    range = r;
                }
            }
        }
        int jpegOrientation = jpegOrientation(ch);

        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 3);
        reader.setOnImageAvailableListener(r -> {
            try (Image img = r.acquireLatestImage()) {
                if (img == null) return;
                ByteBuffer b = img.getPlanes()[0].getBuffer();
                byte[] data = new byte[b.remaining()];
                b.get(data);
                synchronized (frameLock) {
                    frame = data;
                    frameSeq++;
                    frameLock.notifyAll();
                }
            } catch (Throwable t) {
                Main.log("Camera image: " + t);
            }
        }, handler);

        CompletableFuture<CameraDevice> opened = new CompletableFuture<>();
        manager.openCamera(cameraId, executor, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                opened.complete(camera);
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                Main.log("Camera disconnected (used by another app?)");
                camera.close();
                synchronized (Camera.class) {
                    if (device == camera) {
                        session = null;
                        device = null;
                    }
                }
                opened.completeExceptionally(new IOException("camera disconnected"));
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                camera.close();
                opened.completeExceptionally(new IOException("camera error " + error));
            }
        });
        device = opened.get(6, TimeUnit.SECONDS);

        CompletableFuture<CameraCaptureSession> configured = new CompletableFuture<>();
        device.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                Collections.singletonList(new OutputConfiguration(reader.getSurface())), executor,
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        configured.complete(s);
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession s) {
                        configured.completeExceptionally(new IOException("camera session failed"));
                    }
                }));
        session = configured.get(6, TimeUnit.SECONDS);

        CaptureRequest.Builder req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        req.addTarget(reader.getSurface());
        req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (range != null) req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
        req.set(CaptureRequest.JPEG_QUALITY, (byte) 80);
        req.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
        session.setRepeatingRequest(req.build(), null, handler);
        openedAt = System.currentTimeMillis();
        Main.log("Camera on: " + size + ", " + range + " fps, rotation " + jpegOrientation + " degrees");
    }

    /** As in the Android docs for JPEG_ORIENTATION, using the current display rotation. */
    private static int jpegOrientation(CameraCharacteristics ch) {
        Integer sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int s = sensor != null ? sensor : 0;
        int device = (360 - displayRotation() * 90) % 360;
        if (front) device = -device;
        return ((s + device + extraRotation) % 360 + 360) % 360;
    }

    private static int displayRotation() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "window");
            Object wm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            return (Integer) wm.getClass().getMethod("getDefaultDisplayRotation").invoke(wm);
        } catch (Throwable t) {
            Main.log("Display rotation unknown: " + t);
            return 0;
        }
    }
}

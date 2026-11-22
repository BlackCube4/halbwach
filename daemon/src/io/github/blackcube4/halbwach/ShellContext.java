package io.github.blackcube4.halbwach;

import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.IContentProvider;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * A Context for the shell process so that framework APIs such as the camera work.
 * Based on scrcpy (FakeContext/Workarounds, Apache 2.0, https://github.com/Genymobile/scrcpy).
 *
 * {@link #get()} must be called on a thread with a Looper.
 */
final class ShellContext extends ContextWrapper {

    static final String PACKAGE_NAME = "com.android.shell";

    private static ShellContext instance;

    static synchronized ShellContext get() throws Exception {
        if (instance == null) {
            if (Looper.myLooper() == null) throw new IllegalStateException("ShellContext needs a Looper");
            // Framework code expects a main looper; the first caller provides it
            Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
            mainLooper.setAccessible(true);
            if (mainLooper.get(null) == null) mainLooper.set(null, Looper.myLooper());
            Object thread = createActivityThread();
            instance = new ShellContext(systemContext(thread));
            fillApp(thread, instance);
        }
        return instance;
    }

    private ShellContext(Context base) {
        super(base);
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACKAGE_NAME).build();
    }

    @SuppressWarnings("unused") // @Override since API 34
    public int getDeviceId() {
        return 0;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Context createPackageContext(String packageName, int flags) {
        return this;
    }

    @Override
    public ContentResolver getContentResolver() {
        return resolver;
    }

    /** Get providers (e.g. settings) through getContentProviderExternal, which the shell process may use. */
    private final ContentResolver resolver = new ContentResolver(this) {
        @SuppressWarnings("unused")
        protected IContentProvider acquireProvider(Context c, String name) {
            return externalProvider(name);
        }

        @SuppressWarnings("unused")
        public boolean releaseProvider(IContentProvider icp) {
            return false;
        }

        @SuppressWarnings("unused")
        protected IContentProvider acquireUnstableProvider(Context c, String name) {
            return null;
        }

        @SuppressWarnings("unused")
        public boolean releaseUnstableProvider(IContentProvider icp) {
            return false;
        }

        @SuppressWarnings("unused")
        public void unstableProviderDied(IContentProvider icp) {
        }
    };

    private static IContentProvider externalProvider(String name) {
        try {
            Object am = Class.forName("android.app.ActivityManagerNative").getDeclaredMethod("getDefault").invoke(null);
            Object holder;
            try {
                holder = am.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                        .invoke(am, name, 0, new Binder(), null);
            } catch (NoSuchMethodException e) {
                holder = am.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class)
                        .invoke(am, name, 0, new Binder());
            }
            if (holder == null) return null;
            Field f = holder.getClass().getDeclaredField("provider");
            f.setAccessible(true);
            return (IContentProvider) f.get(holder);
        } catch (Throwable t) {
            Main.log("ShellContext: provider " + name + " unavailable (" + t + ")");
            return null;
        }
    }

    private static Object createActivityThread() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = at.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        set(at, null, "sCurrentActivityThread", thread);
        Field sys = at.getDeclaredField("mSystemThread");
        sys.setAccessible(true);
        sys.setBoolean(thread, true);
        try {
            Class<?> cc = Class.forName("android.app.ConfigurationController");
            Constructor<?> ccCtor = cc.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
            ccCtor.setAccessible(true);
            set(at, thread, "mConfigurationController", ccCtor.newInstance(thread));
        } catch (Throwable t) {
            Main.log("ShellContext: ConfigurationController not set (" + t + ")");
        }
        try {
            Class<?> abd = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> abdCtor = abd.getDeclaredConstructor();
            abdCtor.setAccessible(true);
            Object data = abdCtor.newInstance();
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = PACKAGE_NAME;
            set(abd, data, "appInfo", info);
            set(at, thread, "mBoundApplication", data);
        } catch (Throwable t) {
            Main.log("ShellContext: AppBindData not set (" + t + ")");
        }
        return thread;
    }

    private static Context systemContext(Object thread) throws Exception {
        Method m = thread.getClass().getDeclaredMethod("getSystemContext");
        m.setAccessible(true);
        return (Context) m.invoke(thread);
    }

    private static void fillApp(Object thread, Context ctx) {
        try {
            Application app = Instrumentation.newApplication(Application.class, ctx);
            set(thread.getClass(), thread, "mInitialApplication", app);
        } catch (Throwable t) {
            Main.log("ShellContext: Application not set (" + t + ")");
        }
    }

    private static void set(Class<?> c, Object target, String field, Object value) throws Exception {
        Field f = c.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}

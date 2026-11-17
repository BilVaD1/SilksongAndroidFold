package dev.silksong.shell;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

// Where a foldable's fold is, and whether it is half open, as the device
// itself reports it.
//
// Every foldable since Android 12L ships the window extensions library
// (androidx.window.extensions) as a system shared library: it is what Jetpack
// WindowManager's FoldingFeature is built on, and it reports the fold in the
// activity's own window coordinates -- across the window or down it, where
// exactly, and the posture (flat or half open). That is the one source that
// is right on every shape of foldable: a book-style Fold whose inner screen is
// naturally portrait, a passport-shaped one whose inner screen is naturally
// landscape, a clamshell Flip, a hinge with a gap in it.
//
// It is used here directly rather than through Jetpack WindowManager, which
// would bring a Kotlin runtime and coroutines into the shell for one callback.
// The library is declared in the manifest as an optional <uses-library>, so
// its classes are on our class path when the device has it; everything is
// reached by reflection, so nothing of it is needed to compile and a device
// without it simply reports nothing (see TabletopMode for the fallback).
//
// Two generations of its listener API are handled: vendor API level 2 and up
// take a Context and the library's own Consumer interface; level 1 takes an
// Activity and java.util.function.Consumer.
final class FoldInfo
{
    private static final String TAG = "SilksongFold";

    /** The fold crossing the window, in window coordinates. */
    static final class Fold
    {
        final Rect bounds;
        final boolean halfOpen;

        Fold(Rect bounds, boolean halfOpen)
        {
            this.bounds = bounds;
            this.halfOpen = halfOpen;
        }

        /** Runs from side to side of the window, as in a laptop held half open. */
        boolean horizontal() { return bounds.width() > bounds.height(); }

        @Override public String toString()
        {
            return (horizontal() ? "horizontal" : "vertical") + " fold " + bounds.toShortString()
                + (halfOpen ? ", half open" : ", flat");
        }
    }

    /** Called on the main thread; null when no fold crosses the window. */
    interface Listener { void onFoldChanged(Fold fold); }

    // FoldingFeature's constants.
    private static final int STATE_HALF_OPENED = 2;

    private static boolean probed;
    private static Object component;   // WindowLayoutComponent
    private static int vendorLevel;

    private final Activity activity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Object token;              // the consumer we registered
    private boolean coreConsumer;

    FoldInfo(Activity activity, Listener listener)
    {
        this.activity = activity;
        this.listener = listener;
    }

    /** True when the device has the window extensions library. */
    static boolean available() { return component() != null; }

    private static synchronized Object component()
    {
        if (probed) return component;
        probed = true;
        try
        {
            Class<?> provider = Class.forName("androidx.window.extensions.WindowExtensionsProvider");
            Class<?> extensions = Class.forName("androidx.window.extensions.WindowExtensions");
            Object ext = provider.getMethod("getWindowExtensions").invoke(null);
            vendorLevel = (Integer) extensions.getMethod("getVendorApiLevel").invoke(ext);
            component = extensions.getMethod("getWindowLayoutComponent").invoke(ext);
            Log.i(TAG, component != null
                ? "Window extensions, vendor API level " + vendorLevel
                : "Window extensions without a layout component");
        }
        catch (Throwable t)
        {
            Log.i(TAG, "No window extensions on this device (" + t + ")");
            component = null;
        }
        return component;
    }

    void start()
    {
        Object c = component();
        if (c == null || token != null) return;
        try
        {
            Class<?> layout = Class.forName("androidx.window.extensions.layout.WindowLayoutComponent");
            if (vendorLevel >= 2)
            {
                try
                {
                    Class<?> consumer = Class.forName("androidx.window.extensions.core.util.function.Consumer");
                    Object proxy = Proxy.newProxyInstance(consumer.getClassLoader(), new Class<?>[] { consumer },
                        (self, method, args) -> {
                            switch (method.getName())
                            {
                                case "accept": deliver(args[0]); return null;
                                case "equals": return self == args[0];
                                case "hashCode": return System.identityHashCode(self);
                                default: return "FoldInfo consumer";
                            }
                        });
                    layout.getMethod("addWindowLayoutInfoListener", Context.class, consumer).invoke(c, activity, proxy);
                    token = proxy;
                    coreConsumer = true;
                    return;
                }
                catch (Throwable t)
                {
                    Log.w(TAG, "Context listener refused; trying the Activity one", t);
                }
            }
            java.util.function.Consumer<Object> jc = this::deliver;
            layout.getMethod("addWindowLayoutInfoListener", Activity.class, java.util.function.Consumer.class)
                .invoke(c, activity, jc);
            token = jc;
            coreConsumer = false;
        }
        catch (Throwable t)
        {
            Log.w(TAG, "Could not listen for the fold", t);
            token = null;
        }
    }

    void stop()
    {
        Object c = component();
        if (c == null || token == null) return;
        try
        {
            Class<?> layout = Class.forName("androidx.window.extensions.layout.WindowLayoutComponent");
            Class<?> consumer = coreConsumer
                ? Class.forName("androidx.window.extensions.core.util.function.Consumer")
                : java.util.function.Consumer.class;
            layout.getMethod("removeWindowLayoutInfoListener", consumer).invoke(c, token);
        }
        catch (Throwable t)
        {
            Log.w(TAG, "Could not stop listening for the fold", t);
        }
        token = null;
    }

    private void deliver(Object info)
    {
        Fold fold = parse(info);
        if (Looper.myLooper() == Looper.getMainLooper()) listener.onFoldChanged(fold);
        else handler.post(() -> listener.onFoldChanged(fold));
    }

    private static Fold parse(Object info)
    {
        try
        {
            Class<?> layoutInfo = Class.forName("androidx.window.extensions.layout.WindowLayoutInfo");
            Class<?> folding = Class.forName("androidx.window.extensions.layout.FoldingFeature");
            Method features = layoutInfo.getMethod("getDisplayFeatures");
            Method bounds = folding.getMethod("getBounds");
            Method state = folding.getMethod("getState");
            for (Object f : (List<?>) features.invoke(info))
            {
                if (!folding.isInstance(f)) continue;
                Rect r = new Rect((Rect) bounds.invoke(f));
                return new Fold(r, (Integer) state.invoke(f) == STATE_HALF_OPENED);
            }
        }
        catch (Throwable t)
        {
            Log.w(TAG, "Unreadable window layout info", t);
        }
        return null;
    }
}

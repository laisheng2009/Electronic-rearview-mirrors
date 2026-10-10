package com.byd.extend;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.provider.Settings;

/** Shell identity for platform calls; app resources and a replicated camera settings snapshot. */
final class CameraRuntimeContext extends ContextWrapper {
    final CameraRuntimePreferences settings = new CameraRuntimePreferences();
    private final CameraRuntimePreferences counters = new CameraRuntimePreferences();
    private final ApplicationInfo application;
    private final Resources resources;
    private final Context resourceContext;
    final java.util.concurrent.Callable<StockAvmPreview.Config> avmConfig;
    private final java.util.function.IntConsumer displayPower;
    private int lastDisplayPower = android.view.Display.STATE_UNKNOWN;
    volatile boolean overlayAllowed;
    volatile boolean uiVisible;

    CameraRuntimeContext(Context system, int appUid,
            java.util.concurrent.Callable<StockAvmPreview.Config> avmConfig,
            java.util.function.IntConsumer displayPower) throws Exception {
        super(TurnSignalShellMain.shellContext(system));
        this.avmConfig = avmConfig;
        this.displayPower = displayPower;
        application = system.getPackageManager().getApplicationInfo(CameraHelperMain.PACKAGE_NAME, 0);
        if (application.uid != appUid) throw new SecurityException("camera owner uid mismatch");
        resources = system.getPackageManager().getResourcesForApplication(application);
        resourceContext = system.createPackageContext(CameraHelperMain.PACKAGE_NAME, 0);
    }

    @Override public Context getApplicationContext() { return this; }
    @Override public ApplicationInfo getApplicationInfo() { return application; }
    @Override public Resources getResources() { return resources; }
    @Override public Context createConfigurationContext(android.content.res.Configuration configuration) {
        Resources localized = resourceContext.createConfigurationContext(configuration).getResources();
        return new ContextWrapper(super.createConfigurationContext(configuration)) {
            @Override public Resources getResources() { return localized; }
        };
    }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        if ("settings".equals(name)) return settings;
        if ("lifetime_counters".equals(name)) return counters;
        throw new IllegalArgumentException("unsupported runtime preferences: " + name);
    }

    static boolean overlayAllowed(Context context) {
        return context instanceof CameraRuntimeContext
                ? ((CameraRuntimeContext) context).overlayAllowed : Settings.canDrawOverlays(context);
    }

    static void reportDisplayPower(Context context, int displayId) {
        if (!(context instanceof CameraRuntimeContext)
                || displayId != android.view.Display.DEFAULT_DISPLAY) return;
        android.hardware.display.DisplayManager manager = (android.hardware.display.DisplayManager)
                context.getSystemService(Context.DISPLAY_SERVICE);
        android.view.Display display = manager == null ? null : manager.getDisplay(displayId);
        if (display == null) return;
        int state = display.getState();
        if (state == android.view.Display.STATE_OFF || state == android.view.Display.STATE_ON) {
            CameraRuntimeContext runtime = (CameraRuntimeContext) context;
            synchronized (runtime) {
                if (runtime.lastDisplayPower == state) return;
                runtime.lastDisplayPower = state;
            }
            runtime.displayPower.accept(
                    state == android.view.Display.STATE_OFF
                            ? CameraRuntimeHost.POWER_SCREEN_OFF : CameraRuntimeHost.POWER_SCREEN_ON);
        }
    }
}

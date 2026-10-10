package com.byd.extend;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = 29, application = Application.class)
public class RuntimeLifecycleBehaviorTest {
    @Test public void recoveryRespectsSessionAndExplicitShutdown() {
        Context context = RuntimeEnvironment.getApplication();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        GuardRecovery.setAutoStartEnabled(context, false);
        GuardRecovery.sessionStarted(context);
        var app = shadowOf(RuntimeEnvironment.getApplication());
        var watchdog = new GuardWatchdogReceiver();
        Intent wake = new Intent(GuardRecovery.ACTION_WATCHDOG);
        watchdog.onReceive(context, wake);
        assertEquals(CameraHelperService.class.getName(), app.getNextStartedService().getComponent().getClassName());
        assertNull(app.getNextStartedService());

        // A new process has no cached boot count, but its persisted active-session marker survives.
        AndroidBehaviorHarness.field(GuardRecovery.class, "processBootCount", null);
        var listener = Robolectric.buildService(RecoveryNotificationListener.class).create();
        assertEquals(CameraHelperService.class.getName(), app.getNextStartedService().getComponent().getClassName());
        listener.destroy();
        GuardRecovery.setUserShutdownActive(context, true);
        watchdog.onReceive(context, wake);
        var stoppedListener = Robolectric.buildService(RecoveryNotificationListener.class).create();
        stoppedListener.get().onListenerConnected();
        assertNull("explicit shutdown must survive every queued recovery entry", app.getNextStartedService());
        stoppedListener.destroy();

        GuardRecovery.setUserShutdownActive(context, false);
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 8);
        AndroidBehaviorHarness.field(GuardRecovery.class, "processBootCount", null);
        watchdog.onReceive(context, wake);
        assertNull("old boot session must not enable autostart", app.getNextStartedService());
        assertFalse(GuardRecovery.isAutoStartEnabled(context));
        GuardRecovery.setAutoStartEnabled(context, true);
        watchdog.onReceive(context, wake);
        assertNotNull(app.getNextStartedService());
    }
}

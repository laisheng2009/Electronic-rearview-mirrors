package com.byd.extend;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import com.byd.extend.ui.CameraHostSlot;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** Android/OEM boundaries only; Activity, UI controller and permission ordering stay real. */
public final class AndroidBehaviorHarness {
    private AndroidBehaviorHarness() {}

    @Implements(CameraProbeActivity.class)
    public static class CameraBoundary extends ShadowActivity {
        @Implementation protected void startAndBindHelperService() { }
        @Implementation protected View obtainProductionCameraHost(CameraHostSlot slot) {
            return new FrameLayout(org.robolectric.RuntimeEnvironment.getApplication());
        }
        @Implementation protected void updateProductionCameraHost(View view, CameraHostSlot slot) { }
        @Implementation protected void releaseProductionCameraHost(View view, CameraHostSlot slot) { }
    }

    @Implements(AvasAudioLibrary.class)
    public static class AudioAssetsBoundary {
        // File decoding is independent of navigation/permissions and has no device in these tests.
        @Implementation protected void ensureBuiltins() { }
    }

    @Implements(android.provider.Settings.Secure.class)
    public static class SecureReadBoundary extends org.robolectric.shadows.ShadowSettings.ShadowSecure {
        public static boolean unavailable;
        @Implementation protected static int getInt(android.content.ContentResolver resolver,
                String name, int fallback) {
            if (unavailable && android.provider.Settings.Secure.ACCESSIBILITY_ENABLED.equals(name))
                throw new SecurityException("settings provider unavailable");
            return org.robolectric.shadows.ShadowSettings.ShadowSecure.getInt(resolver, name, fallback);
        }
    }

    public static Object field(Object target, String name) {
        try {
            Field field = type(target).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target instanceof Class ? null : target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    public static void field(Object target, String name, Object value) {
        try {
            Field field = type(target).getDeclaredField(name);
            field.setAccessible(true);
            field.set(target instanceof Class ? null : target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    public static Object call(Object target, String name, Object... args) {
        try {
            for (Method method : type(target).getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                method.setAccessible(true);
                return method.invoke(target instanceof Class ? null : target, args);
            }
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Class<?> type(Object target) {
        return target instanceof Class ? (Class<?>) target : target.getClass();
    }

    public static void await(String message, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        org.junit.Assert.assertTrue(message, condition.getAsBoolean());
    }

    public static void settings(Context context) {
        context.getSharedPreferences("settings", 0).edit()
                .putString("ui_language", "en")
                .putBoolean("update_auto_check_enabled", false)
                .putBoolean("update_hint_enabled", false)
                .putBoolean("auto_start_enabled", false).commit();
        LocalAdbClient.setSocketFactoryForTest(() -> { throw new java.io.IOException("No device in host tests"); });
    }
}

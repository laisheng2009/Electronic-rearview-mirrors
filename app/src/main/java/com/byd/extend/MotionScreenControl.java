package com.byd.extend;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.function.BiConsumer;

/** One-shot shell-UID screen control guarded by an observed, continuously-OFF session. */
final class MotionScreenControl implements AutoCloseable {
    private static final String POWER_IFACE = "android.os.IPowerManager";
    private static final String DISPLAY_IFACE = "android.hardware.display.IDisplayManager";
    private static final String ANIMATION_IFACE = "android.os.IAccAnimation";
    private static final int POWER_OFF = 0;
    private static final int DISPLAY_OFF = 1;
    private static final int DISPLAY_ON = 2;
    private static final long POLL_MS = 250L;
    private static final long WAIT_MS = 1_500L;

    private final HandlerThread thread = new HandlerThread("motion-screen-control");
    private final Handler handler;
    private final BiConsumer<String, String> stateListener;
    private final Runnable observe = this::observePower;

    private String sessionId;
    private boolean eligible;
    private boolean offContinuous;
    private boolean ownsWake;
    private boolean coverAttempted;
    private boolean coverRemoved;
    private boolean finishAttempted;
    private final MotionScreenCyclePolicy cycle = new MotionScreenCyclePolicy();

    MotionScreenControl(BiConsumer<String, String> stateListener) {
        this.stateListener = stateListener;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    void beginSession(String id) {
        handler.post(() -> {
            handler.removeCallbacks(observe);
            sessionId = id;
            eligible = false;
            offContinuous = false;
            ownsWake = false;
            coverAttempted = false;
            coverRemoved = false;
            finishAttempted = false;
            cycle.beginSession();
            report("session_ready", "screen control awaits eligible camera session");
        });
    }

    void setEligible(String id, boolean next) {
        handler.post(() -> {
            if (!sameSession(id)) return;
            eligible = next;
            if (!next) {
                handler.removeCallbacks(observe);
                report("ineligible", "screen writes disabled");
                return;
            }
            // The app owns the PowerOFF/lock gate and calls this only after its warm-up.
            // Do not run a second continuous vehicle-state monitor before an owned wake.
            offContinuous = true;
        });
    }

    void wakeForWarning(String id) {
        handler.post(() -> {
            if (!sameSession(id) || !cycle.mayStartPreflight(eligible, offContinuous, ownsWake)) {
                report("wake_skipped", "session is not eligible or screen is already worker-owned");
                return;
            }
            boolean rearming = cycle.needsFreshBlackOffBaseline();
            try {
                requireShellUid();
                int powerState = readPower();
                if (powerState != POWER_OFF) {
                    offContinuous = false;
                    cycle.markUncertain();
                    report("wake_skipped", "vehicle Power is not OFF; screen writes remain disabled");
                    return;
                }
                boolean interactive = isInteractive();
                int display = displayState();
                String animation = animationStatus();
                String descriptor = animationDescriptor();
                if (interactive || display != DISPLAY_OFF || !"1".equals(animation)
                        || !ANIMATION_IFACE.equals(descriptor)) {
                    if (!rearming) cycle.markUncertain();
                    report("wake_skipped", rearming
                            ? "user-owned screen has not returned to confirmed black/off state"
                            : "OFF/display/animation preflight was not confirmed");
                    return;
                }
                if (rearming) {
                    cycle.confirmFreshBlackOffBaseline();
                    resetLocalWarningCycle();
                    report("black_baseline_observed", "fresh Power/display/animation preflight confirmed");
                }
                if (!cycle.mayWriteWake()) {
                    report("wake_skipped", "screen cycle remains uncertain");
                    return;
                }
                cycle.beginWakeAttempt(); // No repeat unless a full black/off baseline is observed.
                Object power = service(POWER_IFACE);
                Class<?> api = Class.forName(POWER_IFACE);
                api.getMethod("wakeUp", long.class, int.class, String.class, String.class)
                        .invoke(power, SystemClock.uptimeMillis(), 13,
                                "byd-extend-motion-warning", "com.android.shell");
                long deadline = SystemClock.elapsedRealtime() + WAIT_MS;
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!offContinuous || cycle.userTookOver() || !eligible) break;
                    if (readPower() != POWER_OFF) {
                        offContinuous = false;
                        break;
                    }
                    if (isInteractive() && displayState() == DISPLAY_ON) {
                        ownsWake = true;
                        handler.removeCallbacks(observe);
                        handler.postDelayed(observe, POLL_MS);
                        report("wake_requested", "reason=13; app warning window may now be shown");
                        return;
                    }
                    SystemClock.sleep(50L);
                }
                cycle.markUncertain();
                report("wake_uncertain", "wake returned without a confirmed interactive transition");
            } catch (Throwable error) {
                cycle.markUncertain();
                report("wake_uncertain", summary(error));
            }
        });
    }

    /** Called only after the app-owned fullscreen warning window reports that it is ready. */
    void warningWindowReady(String id) {
        handler.post(() -> {
            if (!sameSession(id) || !eligible || !ownsWake || cycle.userTookOver()
                    || cycle.uncertain() || coverAttempted) {
                report("cover_release_skipped", "screen session is not safely owned or command already attempted");
                return;
            }
            coverAttempted = true;
            try {
                if (!offContinuous || readPower() != POWER_OFF || !isInteractive()
                        || displayState() != DISPLAY_ON || !"1".equals(animationStatus())) {
                    cycle.markUncertain();
                    stopPowerObservation();
                    report("cover_release_skipped", "warning-window/vehicle state is not confirmed");
                    return;
                }
                int result = animationCommand(3);
                if (result != 0 || !"0".equals(animationStatus())) {
                    cycle.markUncertain();
                    stopPowerObservation();
                    report("cover_release_uncertain", "transaction 3 result/state was not confirmed");
                    return;
                }
                coverRemoved = true;
                report("cover_release_requested", "transaction=3; result=0; physical visibility is not inferred");
            } catch (Throwable error) {
                cycle.markUncertain();
                stopPowerObservation();
                report("cover_release_uncertain", summary(error));
            }
        });
    }

    void finishWarning(String id, boolean restoreAllowed) {
        handler.post(() -> {
            if (!sameSession(id) || finishAttempted) {
                report("sleep_skipped", "session mismatch or finish already attempted");
                return;
            }
            finishAttempted = true;
            if (cycle.userTookOver()) {
                ownsWake = false;
                stopPowerObservation();
                report("user_dismissed_no_sleep", "this cycle will not sleep; later wake requires fresh black/off confirmation");
                return;
            }
            if (!restoreAllowed || !eligible || !ownsWake || !offContinuous || cycle.uncertain()) {
                cycle.markUncertain();
                stopPowerObservation();
                report("sleep_skipped", "warning stop is not an owned, continuously-OFF session");
                return;
            }
            if (!coverRemoved) {
                cycle.markUncertain();
                stopPowerObservation();
                report("sleep_skipped", "screen cover was not confirmed released for this warning");
                return;
            }
            try {
                if (readPower() != POWER_OFF || !isInteractive() || displayState() != DISPLAY_ON
                        || !"0".equals(animationStatus())) {
                    cycle.markUncertain();
                    stopPowerObservation();
                    report("sleep_skipped", "current vehicle/display state is not confirmed");
                    return;
                }
                Object power = service(POWER_IFACE);
                Class<?> api = Class.forName(POWER_IFACE);
                api.getMethod("goToSleep", long.class, int.class, int.class)
                        .invoke(power, SystemClock.uptimeMillis(), 13, 1);
                long deadline = SystemClock.elapsedRealtime() + WAIT_MS;
                boolean off = false;
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!offContinuous || cycle.userTookOver() || readPower() != POWER_OFF) break;
                    if (!isInteractive() && displayState() == DISPLAY_OFF) {
                        off = true;
                        break;
                    }
                    SystemClock.sleep(50L);
                }
                if (!off) {
                    cycle.markUncertain();
                    stopPowerObservation();
                    report("sleep_uncertain", "sleep transition was not observed; transaction 2 was not sent");
                    return;
                }
                int result = animationCommand(2);
                if (result != 0) {
                    cycle.markUncertain();
                    stopPowerObservation();
                    report("black_restore_uncertain", "transaction 2 result was not zero");
                    return;
                }
                long restoreDeadline = SystemClock.elapsedRealtime() + WAIT_MS;
                while (SystemClock.elapsedRealtime() < restoreDeadline) {
                    if (!offContinuous || cycle.userTookOver() || readPower() != POWER_OFF) break;
                    if (!isInteractive() && displayState() == DISPLAY_OFF
                            && "1".equals(animationStatus())) {
                        cycle.confirmOwnedBlackRestore();
                        resetLocalWarningCycle();
                        report("black_restore_observed", "transaction=2; argument=0");
                        return;
                    }
                    SystemClock.sleep(50L);
                }
                cycle.markUncertain();
                stopPowerObservation();
                report("black_restore_uncertain", "OFF/animation restore observation timed out");
            } catch (Throwable error) {
                cycle.markUncertain();
                stopPowerObservation();
                report("finish_uncertain", summary(error));
            }
        });
    }

    void markUserTakeover(String id) {
        handler.post(() -> {
            if (!sameSession(id)) return;
            cycle.markUserTakeover();
            ownsWake = false;
            stopPowerObservation();
            report("user_takeover", "this cycle will not sleep; later wake requires fresh black/off confirmation");
        });
    }

    private void observePower() {
        if (!eligible || !ownsWake || sessionId == null || cycle.uncertain() || cycle.userTookOver()) return;
        try {
            if (readPower() != POWER_OFF) offContinuous = false;
        } catch (Throwable ignored) {
            offContinuous = false;
        }
        if (!offContinuous) {
            cycle.markUncertain();
            stopPowerObservation();
            report("off_session_lost", "vehicle Power was nonzero or unreadable");
            return;
        }
        handler.postDelayed(observe, POLL_MS);
    }

    private void stopPowerObservation() {
        handler.removeCallbacks(observe);
    }

    /** Resets one-shot details only after a verified black/off baseline. */
    private void resetLocalWarningCycle() {
        stopPowerObservation();
        ownsWake = false;
        coverAttempted = false;
        coverRemoved = false;
        finishAttempted = false;
        offContinuous = true;
    }

    private boolean sameSession(String id) {
        return sessionId != null && sessionId.equals(id);
    }

    private void report(String state, String detail) {
        if (stateListener != null && sessionId != null)
            stateListener.accept(state, detail == null ? "" : detail);
    }

    private static int readPower() throws Exception {
        IBinder target = binder("autoservice");
        if (target == null) throw new IllegalStateException("autoservice unavailable");
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken(target.getInterfaceDescriptor());
            input.writeInt(1001);
            input.writeInt(315621418);
            if (!target.transact(5, input, output, 0))
                throw new IllegalStateException("Power read transaction unavailable");
            output.readException();
            if (output.dataAvail() != 4) throw new IllegalStateException("Power read reply is ambiguous");
            int value = output.readInt();
            if (value < 0 || value > 4) throw new IllegalStateException("Power value out of range");
            return value;
        } finally {
            input.recycle();
            output.recycle();
        }
    }

    private static boolean isInteractive() throws Exception {
        Object power = service(POWER_IFACE);
        return (Boolean) Class.forName(POWER_IFACE).getMethod("isInteractive").invoke(power);
    }

    private static int displayState() throws Exception {
        Object display = service(DISPLAY_IFACE);
        Object info = Class.forName(DISPLAY_IFACE).getMethod("getDisplayInfo", int.class)
                .invoke(display, 0);
        if (info == null) throw new IllegalStateException("display 0 state unavailable");
        return info.getClass().getField("state").getInt(info);
    }

    private static String animationStatus() throws Exception {
        return (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class).invoke(null, "sys.accanim.status");
    }

    private static String animationDescriptor() throws Exception {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken(ANIMATION_IFACE);
            IBinder target = binder("accanimation");
            if (target == null || !target.transact(IBinder.INTERFACE_TRANSACTION, input, output, 0))
                throw new IllegalStateException("accanimation descriptor unavailable");
            return output.readString();
        } finally {
            input.recycle();
            output.recycle();
        }
    }

    private static int animationCommand(int command) throws Exception {
        if (command != 2 && command != 3) throw new IllegalArgumentException("unsupported accanimation command");
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken(ANIMATION_IFACE);
            if (command == 2) input.writeInt(0);
            IBinder target = binder("accanimation");
            if (target == null) throw new IllegalStateException("accanimation unavailable");
            if (!target.transact(command, input, output, 0) || output.dataSize() != 4)
                throw new IllegalStateException("accanimation reply missing/ambiguous");
            return output.readInt(); // Native reply: raw int32, not an exception header.
        } finally {
            input.recycle();
            output.recycle();
        }
    }

    private static Object service(String iface) throws Exception {
        IBinder target = binder(iface.equals(POWER_IFACE) ? "power" : "display");
        if (target == null) throw new IllegalStateException(iface + " service unavailable");
        return Class.forName(iface + "$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, target);
    }

    private static IBinder binder(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
    }

    private static void requireShellUid() {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("screen controls require shell UID");
    }

    private static String summary(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.lang.reflect.InvocationTargetException) && cause.getCause() != null)
            cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override public void close() {
        handler.removeCallbacks(observe);
        handler.post(() -> {
            eligible = false;
            sessionId = null;
        });
        thread.quitSafely();
    }
}

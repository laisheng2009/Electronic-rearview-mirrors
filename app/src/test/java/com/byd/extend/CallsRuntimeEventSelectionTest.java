package com.byd.extend;

import android.view.accessibility.AccessibilityEvent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CallsRuntimeEventSelectionTest {
    @Test
    public void selectsXCallEventsAndWindowChangesForTargetRescans() {
        assertTrue(CallsRuntime.isXCallTargetEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.byd.xcall"));
        assertTrue(CallsRuntime.isXCallTargetEvent(
                AccessibilityEvent.TYPE_WINDOWS_CHANGED, null));
        assertTrue(CallsRuntime.isXCallTargetEvent(
                AccessibilityEvent.TYPE_WINDOWS_CHANGED, "com.android.systemui"));

        assertFalse(CallsRuntime.isXCallTargetEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.systemui"));
        assertFalse(CallsRuntime.isXCallTargetEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, null));
    }

    @Test
    public void targetSearchExpiresAtItsOriginalDeadline() {
        assertFalse(CallsRuntime.targetSearchExpired(1_000L, 2_999L));
        assertTrue(CallsRuntime.targetSearchExpired(1_000L, 3_000L));
        assertTrue(CallsRuntime.targetSearchExpired(1_000L, 3_001L));
    }

    @Test
    public void hintWindowExpiresAtItsDeadline() {
        assertFalse(CallsRuntime.deadlineReached(1_799L, 1_800L));
        assertTrue(CallsRuntime.deadlineReached(1_800L, 1_800L));
    }
}

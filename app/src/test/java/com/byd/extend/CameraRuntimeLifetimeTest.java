package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraRuntimeLifetimeTest {
    @Test public void shellStartAndUiDetachRetainRuntimeAndWakeBaseline() {
        CameraRuntimeLifetime<Object> lifetime = new CameraRuntimeLifetime<>();
        int[] initialized = {0};
        lifetime.start(() -> initialized[0]++);
        assertTrue(lifetime.started());
        assertNull(lifetime.client());
        assertFalse(lifetime.updateAwakeSession(1, false));
        Object client = new Object();
        assertTrue(lifetime.attach(client, () -> initialized[0]++));
        assertTrue(lifetime.detach(client));
        assertEquals(1, initialized[0]);
        assertTrue(lifetime.started());
        assertFalse(lifetime.stopped());
        assertTrue(lifetime.updateAwakeSession(2, true));
        assertFalse(lifetime.updateAwakeSession(2, true));
    }

    @Test public void explicitShutdownIsTerminal() {
        CameraRuntimeLifetime<Object> lifetime = new CameraRuntimeLifetime<>();
        int[] closures = {0};
        lifetime.start(() -> { });
        lifetime.shutdown(() -> closures[0]++);
        lifetime.shutdown(() -> closures[0]++);
        assertTrue(lifetime.stopped());
        assertEquals(1, closures[0]);
        assertFalse(lifetime.updateAwakeSession(3, true));
        assertThrows(IllegalStateException.class, () -> lifetime.start(() -> { }));
    }
}

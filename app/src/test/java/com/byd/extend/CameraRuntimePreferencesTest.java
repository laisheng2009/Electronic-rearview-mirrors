package com.byd.extend;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraRuntimePreferencesTest {
    @Test public void firstAppSnapshotReplacesShellBootstrapDefaults() {
        CameraRuntimePreferences preferences = new CameraRuntimePreferences();
        preferences.edit().putInt("scale", 100).apply();
        assertTrue(preferences.reconcile(Map.of("scale", 75), true).isEmpty());
        assertEquals(75, preferences.getInt("scale", 0));
    }

    @Test public void laterReattachPreservesActualRuntimeEdits() {
        CameraRuntimePreferences preferences = new CameraRuntimePreferences();
        preferences.reconcile(Map.of("scale", 75), true);
        preferences.edit().putInt("scale", 80).apply();
        assertEquals(80, preferences.reconcile(Map.of("scale", 75), false).get("scale"));
        assertEquals(80, preferences.getInt("scale", 0));
    }
}

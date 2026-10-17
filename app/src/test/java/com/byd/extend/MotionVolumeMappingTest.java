package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Locale;
import java.util.Set;
import org.junit.Test;

public final class MotionVolumeMappingTest {
    @Test public void api29NormalizesRemovableUuidForTheIndexedCollection() {
        String storedId = "ABCD-1234";
        String name = MotionVolumeMapping.legacyName(false, storedId);
        assertEquals("abcd-1234", name);
        assertEquals(name, MotionVolumeMapping.indexedName(name, Set.of("external_primary", name)));
    }

    @Test public void primaryUsesDedicatedCollectionNotMergedExternalVolume() {
        assertEquals("external_primary", MotionVolumeMapping.legacyName(true, null));
        assertNull(MotionVolumeMapping.indexedName("external_primary", Set.of("external")));
    }

    @Test public void unindexedAndMissingVolumesStayUnknown() {
        assertNull(MotionVolumeMapping.legacyName(false, null));
        assertNull(MotionVolumeMapping.indexedName(null, Set.of("external_primary")));
        assertNull(MotionVolumeMapping.indexedName("abcd-1234", Set.of("external_primary")));
    }

    @Test public void frameworkNameIsUsedExactlyAndLocaleDoesNotAffectLegacyName() {
        assertEquals("framework-name", MotionVolumeMapping.indexedName("framework-name", Set.of("framework-name")));
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("abcd-1234", MotionVolumeMapping.legacyName(false, "ABCD-1234"));
        } finally {
            Locale.setDefault(original);
        }
    }
}

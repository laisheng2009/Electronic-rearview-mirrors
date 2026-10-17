package com.byd.extend;

import java.util.Locale;
import java.util.Set;

final class MotionVolumeMapping {
    private MotionVolumeMapping() { }

    // API 29 has no public StorageVolume.getMediaStoreVolumeName().
    static String legacyName(boolean primary, String uuid) {
        return primary ? "external_primary" : uuid == null ? null : uuid.toLowerCase(Locale.ROOT);
    }

    static String indexedName(String candidate, Set<String> indexedVolumes) {
        return candidate != null && indexedVolumes.contains(candidate) ? candidate : null;
    }
}

package com.byd.extend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Shared opaque identity/revision key for worker-persisted Motion covers. */
public final class MotionThumbnailKey {
    private static final String KEY_PATTERN = "[0-9a-f]{64}-[0-9a-f]{64}";

    private MotionThumbnailKey() { }

    public static String forEntry(MotionMediaLibrary.Entry entry) {
        return key(identity(entry.volumeId, entry.contentUri.toString(), entry.mediaStoreId, entry.segmentId),
                revision(entry.sizeBytes, entry.durationMs, entry.recordedAtMillis));
    }

    static String forRecord(MotionMediaIndex.Record record, long mediaStoreId) {
        return key(identity(record.volumeId, record.uri, mediaStoreId, record.segmentId),
                revision(record.sizeBytes, record.durationMs, record.recordedAtMs));
    }

    static String identity(String volumeId, String uri, long mediaStoreId, String segmentId) {
        return volumeId + "|" + uri + "|" + mediaStoreId + "|" + segmentId;
    }

    static String revision(long sizeBytes, long durationMs, long recordedAtMillis) {
        return sizeBytes + "|" + durationMs + "|" + recordedAtMillis;
    }

    public static String key(String identity, String revision) {
        byte[] identityBytes = identity.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String identityHash = hex(digest.digest(identityBytes));
            digest.update(identityBytes);
            digest.update((byte) 0);
            digest.update(revision.getBytes(StandardCharsets.UTF_8));
            return identityHash + "-" + hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static boolean isValid(String key) {
        return key != null && key.matches(KEY_PATTERN);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}

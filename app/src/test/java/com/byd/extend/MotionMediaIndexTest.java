package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.List;

public final class MotionMediaIndexTest {
    @Test public void finishedClipAndDetectionMarkersSurviveReopen() throws Exception {
        File directory = Files.createTempDirectory("motion-index-").toFile();
        File indexFile = new File(directory, "library.json");
        MotionMediaIndex index = new MotionMediaIndex(indexFile);
        MotionMediaIndex.Record record = new MotionMediaIndex.Record(
                "content://media/external_primary/video/media/42", "external_primary",
                "motion_20261017_101112.mp4", "segment-42", 1_797_000_000_000L,
                57_000L, 1920, 1080, MotionMediaIndex.RECORDING);
        index.put(record);
        assertTrue(index.addMarker(record.uri, new MotionMediaIndex.Marker(58_234L, 1_797_000_001_234L, 1)));
        assertTrue(index.addMarker(record.uri, new MotionMediaIndex.Marker(61_500L, 1_797_000_004_500L, 2)));
        assertTrue(index.update(record.uri, MotionMediaIndex.COMPLETE, 120_000L, 8_200_000L));

        MotionMediaIndex reopened = new MotionMediaIndex(indexFile);
        List<MotionMediaIndex.Record> records = reopened.records();
        assertEquals(1, records.size());
        MotionMediaIndex.Record saved = records.get(0);
        assertEquals(MotionMediaIndex.COMPLETE, saved.status);
        assertEquals(120_000L, saved.durationMs);
        assertEquals(8_200_000L, saved.sizeBytes);
        assertEquals(2, saved.markers.size());
        assertEquals(58_234L, saved.markers.get(0).elapsedRealtimeMs);
        assertEquals(1, saved.markers.get(0).level);
        assertEquals(2, saved.markers.get(1).level);
        assertNull(reopened.find("content://media/other/9"));
    }

    @Test public void duplicateMarkerIsIdempotentAndBadLevelsCannotEnterIndex() throws Exception {
        File directory = Files.createTempDirectory("motion-index-").toFile();
        MotionMediaIndex index = new MotionMediaIndex(new File(directory, "library.json"));
        MotionMediaIndex.Record record = new MotionMediaIndex.Record(
                "content://media/external_primary/video/media/43", "external_primary",
                "motion_20261017_101112.mp4", "segment-43", 1_797_000_000_000L,
                57_000L, 1920, 1080, MotionMediaIndex.RECORDING);
        index.put(record);

        assertTrue(index.addMarker(record.uri, new MotionMediaIndex.Marker(58_234L, 10L, 2)));
        assertTrue(index.addMarker(record.uri, new MotionMediaIndex.Marker(58_234L, 11L, 2)));
        assertEquals(1, index.find(record.uri).markers.size());
        try {
            new MotionMediaIndex.Marker(60_000L, 12L, -2);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
            return;
        }
        throw new AssertionError("invalid detection error level was accepted");
    }

    @Test public void sustainedEpisodeUpdatesOneIntervalAndKeepsSeparateLevelEvents() throws Exception {
        File directory = Files.createTempDirectory("motion-index-").toFile();
        File indexFile = new File(directory, "library.json");
        MotionMediaIndex index = new MotionMediaIndex(indexFile);
        MotionMediaIndex.Record record = new MotionMediaIndex.Record(
                "content://media/external_primary/video/media/44", "external_primary",
                "motion_20261017_101112.mp4", "segment-44", 1_797_000_000_000L,
                1_000L, 1920, 1080, MotionMediaIndex.RECORDING);
        index.put(record);

        index.upsertMarker(record.uri, new MotionMediaIndex.Marker(
                "yellow-episode", 1_100L, 5_100L, 2_100L, 6_100L, 1));
        index.upsertMarker(record.uri, new MotionMediaIndex.Marker(
                "yellow-episode", 1_100L, 5_100L, 4_100L, 8_100L, 1));
        index.upsertMarker(record.uri, new MotionMediaIndex.Marker(
                "red-episode", 2_000L, 6_000L, 2_500L, 6_500L, 2));

        MotionMediaIndex.Record persisted = new MotionMediaIndex(indexFile).find(record.uri);
        assertEquals(2, persisted.markers.size());
        assertEquals("yellow-episode", persisted.markers.get(0).eventId);
        assertEquals(4_100L, persisted.markers.get(0).endElapsedRealtimeMs);
        assertEquals(2, persisted.markers.get(1).level);
    }

    @Test public void legacySnapshotMergeKeepsSourceAndWorkerStateAcrossReopen() throws Exception {
        File directory = Files.createTempDirectory("motion-index-migration-").toFile();
        File sourceFile = new File(directory, "app-media-index.json");
        File targetFile = new File(directory, "shell-media-index.json");
        MotionMediaIndex source = new MotionMediaIndex(sourceFile);

        MotionMediaIndex.Record shared = new MotionMediaIndex.Record(
                "content://media/external_primary/video/media/51", "primary",
                "Motion_legacy_shared.mp4", "legacy-shared", 1_797_000_000_001L,
                12_000L, 1920, 1080, MotionMediaIndex.INTERRUPTED);
        shared.durationMs = 9_000L;
        shared.sizeBytes = 123_456L;
        shared.protectedFile = true;
        shared.markers.add(new MotionMediaIndex.Marker(
                "event-legacy", 14_000L, 1_797_000_000_003L,
                16_000L, 1_797_000_000_005L, 2));
        source.put(shared);

        MotionMediaIndex.Record legacyOnly = new MotionMediaIndex.Record(
                "content://media/external_primary/video/media/52", "primary",
                "Motion_legacy_only.mp4", "legacy-only", 1_797_000_000_006L,
                18_000L, 1280, 720, MotionMediaIndex.COMPLETE);
        legacyOnly.durationMs = 7_000L;
        legacyOnly.sizeBytes = 98_765L;
        legacyOnly.markers.add(new MotionMediaIndex.Marker(19_000L, 1_797_000_000_007L, 1));
        source.put(legacyOnly);

        MotionMediaIndex worker = new MotionMediaIndex(targetFile);
        MotionMediaIndex.Record current = new MotionMediaIndex.Record(shared.uri, "primary",
                "Motion_worker_name.mp4", "worker-current", 1_797_000_000_100L,
                30_000L, 1920, 1080, MotionMediaIndex.COMPLETE);
        current.durationMs = 20_000L;
        current.sizeBytes = 400_000L;
        worker.put(current);
        assertTrue(worker.addMarker(shared.uri, new MotionMediaIndex.Marker("event-worker", 35_000L,
                1_797_000_000_200L, 38_000L, 1_797_000_000_203L, 1)));

        List<MotionMediaIndex.Record> imported;
        try (FileInputStream input = new FileInputStream(sourceFile)) {
            imported = MotionMediaIndex.parseLegacySnapshot(input, 1024 * 1024);
        }
        worker.mergeLegacyRecords(imported, "legacy-snapshot-sha256", true);
        MotionMediaIndex reopened = new MotionMediaIndex(targetFile);
        assertEquals(2, reopened.records().size());
        MotionMediaIndex.Record merged = reopened.find(shared.uri);
        assertEquals(MotionMediaIndex.COMPLETE, merged.status);
        assertEquals("worker-current", merged.segmentId);
        assertEquals(20_000L, merged.durationMs);
        assertEquals(400_000L, merged.sizeBytes);
        assertTrue(merged.protectedFile);
        assertEquals(2, merged.markers.size());
        assertEquals("event-legacy", merged.markers.get(0).eventId);
        assertEquals("event-worker", merged.markers.get(1).eventId);

        worker = new MotionMediaIndex(targetFile);
        worker.mergeLegacyRecords(imported, "legacy-snapshot-sha256", true);
        MotionMediaIndex afterRepeat = new MotionMediaIndex(targetFile);
        assertEquals(2, afterRepeat.records().size());
        assertEquals(2, afterRepeat.find(shared.uri).markers.size());
        assertTrue(afterRepeat.hasImportedLegacyDigest("legacy-snapshot-sha256"));
        assertTrue(sourceFile.isFile());
        assertEquals(2, new MotionMediaIndex(sourceFile).records().size());
        assertEquals("legacy-only", afterRepeat.find(legacyOnly.uri).segmentId);
        assertEquals(1, afterRepeat.find(legacyOnly.uri).markers.size());
    }

    @Test public void incompleteLegacyMergePersistsRowsWithoutClaimingMigrationComplete() throws Exception {
        File directory = Files.createTempDirectory("motion-index-partial-migration-").toFile();
        File indexFile = new File(directory, "shell-media-index.json");
        MotionMediaIndex.Record record = new MotionMediaIndex.Record(
                "content://media/external_primary/video/media/61", "primary",
                "Motion_legacy_unresolved.mp4", "legacy-unresolved", 1_797_000_000_010L,
                21_000L, 1920, 1080, MotionMediaIndex.INTERRUPTED);
        record.markers.add(new MotionMediaIndex.Marker("event-preserved", 22_000L,
                1_797_000_000_011L, 24_000L, 1_797_000_000_013L, 2));

        MotionMediaIndex index = new MotionMediaIndex(indexFile);
        index.mergeLegacyRecords(java.util.Collections.singletonList(record), "partial-digest", false);

        MotionMediaIndex reopened = new MotionMediaIndex(indexFile);
        assertFalse(reopened.hasImportedLegacyDigest("partial-digest"));
        MotionMediaIndex.Record saved = reopened.find(record.uri);
        assertNotNull(saved);
        assertEquals("legacy-unresolved", saved.segmentId);
        assertEquals(1, saved.markers.size());
        assertEquals("event-preserved", saved.markers.get(0).eventId);
    }
}

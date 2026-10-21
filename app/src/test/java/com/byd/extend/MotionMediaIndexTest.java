package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.List;

public final class MotionMediaIndexTest {
    @Test public void interruptedClipKeepsOnlyCapturedMarkerIntervalsAcrossReopen() throws Exception {
        File directory = Files.createTempDirectory("motion-index-interrupted-").toFile();
        File file = new File(directory, "library.json");
        MotionMediaIndex index = new MotionMediaIndex(file);
        MotionMediaIndex.Record record = new MotionMediaIndex.Record(
                "content://media/external_primary/video/media/45", "external_primary",
                "Motion_interrupted.mp4", "segment-45", 5_000L,
                1_000L, 1920, 1080, MotionMediaIndex.RECORDING);
        index.put(record);
        index.upsertMarker(record.uri, new MotionMediaIndex.Marker(
                "yellow", 500L, 4_500L, 3_500L, 7_500L, 1));
        index.upsertMarker(record.uri, new MotionMediaIndex.Marker(
                "red", 2_000L, 6_000L, 4_000L, 8_000L, 2));
        index.upsertMarker(record.uri, new MotionMediaIndex.Marker(
                "uncaptured", 4_000L, 8_000L, 5_000L, 9_000L, 1));
        assertTrue(index.finishRecording(record.uri, MotionMediaIndex.INTERRUPTED, 2_000L, 100_000L));

        MotionMediaIndex.Record saved = new MotionMediaIndex(file).find(record.uri);
        assertEquals(MotionMediaIndex.INTERRUPTED, saved.status);
        assertEquals(2, saved.markers.size());
        assertEquals(1_000L, saved.markers.get(0).elapsedRealtimeMs);
        assertEquals(5_000L, saved.markers.get(0).wallTimeMs);
        assertEquals(3_000L, saved.markers.get(0).endElapsedRealtimeMs);
        assertEquals(7_000L, saved.markers.get(0).endWallTimeMs);
        assertEquals(2, saved.markers.get(1).level);
        assertEquals(2_000L, saved.markers.get(1).elapsedRealtimeMs);
        assertEquals(3_000L, saved.markers.get(1).endElapsedRealtimeMs);
    }

    @Test public void finishedClipAndDetectionMarkersSurviveReopen() throws Exception {
        File directory = Files.createTempDirectory("motion-index-").toFile();
        File indexFile = new File(directory, "library.json");
        MotionMediaIndex index = new MotionMediaIndex(indexFile);
        MotionMediaIndex.Record record = new MotionMediaIndex.Record(
                "content://media/external_primary/video/media/42", "external_primary",
                "motion_20261017_101112.mp4", "segment-42", 1_797_000_000_000L,
                57_000L, 1920, 1080, MotionMediaIndex.RECORDING, new int[] {2, 0, 3, 1});
        index.put(record);
        record.cameraOrder[0] = 1;
        assertTrue(index.addMarker(record.uri, new MotionMediaIndex.Marker(58_234L, 1_797_000_001_234L, 1)));
        assertTrue(index.addMarker(record.uri, new MotionMediaIndex.Marker(61_500L, 1_797_000_004_500L, 2)));
        assertTrue(index.finishRecording(record.uri, MotionMediaIndex.COMPLETE, 120_000L, 8_200_000L));

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
        assertArrayEquals(new int[] {2, 0, 3, 1}, saved.cameraOrder);
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
                12_000L, 1920, 1080, MotionMediaIndex.INTERRUPTED, new int[] {1, 3, 0, 2});
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
                18_000L, 1280, 720, MotionMediaIndex.COMPLETE, new int[] {3, 2, 1, 0});
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
        assertArrayEquals(new int[] {3, 2, 1, 0}, afterRepeat.find(legacyOnly.uri).cameraOrder);
        assertArrayEquals(new int[] {1, 3, 0, 2}, afterRepeat.find(shared.uri).cameraOrder);
    }

    @Test public void oldOrMalformedCameraOrderKeepsClipWithoutInventingViews() throws Exception {
        File file = new File(Files.createTempDirectory("motion-index-order-").toFile(), "library.json");
        MotionMediaIndex index = new MotionMediaIndex(file);
        MotionMediaIndex.Record record = new MotionMediaIndex.Record(
                "content://media/external_primary/video/media/62", "primary", "Motion_old.mp4",
                "old", 5_000L, 1_000L, 1920, 1080, MotionMediaIndex.COMPLETE);
        index.put(record);
        assertNull(new MotionMediaIndex(file).find(record.uri).cameraOrder);
        JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        JSONObject row = root.getJSONArray("records").getJSONObject(0);
        for (String malformed : new String[] {"[0,0,2,3]", "[0,1,2]", "[0,1,2,4]",
                "[0,1,2,2.5]", "[0,1,2,\"3\"]"}) {
            row.put("cameraOrder", new JSONArray(malformed));
            Files.write(file.toPath(), root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            MotionMediaIndex.Record reopened = new MotionMediaIndex(file).find(record.uri);
            assertNotNull(reopened);
            assertEquals("Motion_old.mp4", reopened.name);
            assertNull(reopened.cameraOrder);
        }
    }

    @Test public void volumeAndMediaIdLookupReturnsOnlyAnIndependentMatchingRecord() throws Exception {
        File file = new File(Files.createTempDirectory("motion-index-media-id-").toFile(), "library.json");
        MotionMediaIndex index = new MotionMediaIndex(file);
        MotionMediaIndex.Record sameIdOtherVolume = new MotionMediaIndex.Record(
                "content://media/external_primary/video/media/73", "primary", "primary.mp4",
                "primary-73", 1_000L, 10L, 1920, 1080, MotionMediaIndex.COMPLETE);
        MotionMediaIndex.Record target = new MotionMediaIndex.Record(
                "content://media/ABCD-1234/video/media/73", "ABCD-1234", "sd.mp4",
                "sd-73", 2_000L, 20L, 1280, 720, MotionMediaIndex.INTERRUPTED,
                new int[] {2, 0, 3, 1});
        target.markers.add(new MotionMediaIndex.Marker("event", 25L, 2_005L, 30L, 2_010L, 1));
        index.put(sameIdOtherVolume);
        index.put(target);

        MotionMediaIndex reopened = new MotionMediaIndex(file);
        MotionMediaIndex.Record found = reopened.find("ABCD-1234", 73L);
        assertNotNull(found);
        assertEquals(target.uri, found.uri);
        assertEquals("sd-73", found.segmentId);
        assertEquals(1, found.markers.size());
        assertNull(reopened.find("primary", 99L));
        assertNull(reopened.find("missing-volume", 73L));

        found.cameraOrder[0] = 0;
        found.markers.clear();
        MotionMediaIndex.Record unchanged = reopened.find("ABCD-1234", 73L);
        assertArrayEquals(new int[] {2, 0, 3, 1}, unchanged.cameraOrder);
        assertEquals(1, unchanged.markers.size());
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

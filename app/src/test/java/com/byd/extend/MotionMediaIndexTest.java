package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
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
}

package com.byd.extend;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class MotionMediaPagesTest {
    @Test public void continuationsKeepOriginalRowsAndStatsAcrossAnOverlappingRead() throws Exception {
        MotionMediaPages pages = new MotionMediaPages();
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < 70; i++) rows.add(new JSONObject().put("mediaStoreId", i + 1));
        JSONObject stats = new JSONObject().put("ownBytes", 700);
        JSONObject first = new JSONObject(pages.start("all", rows, stats));
        String continuation = first.getString("nextPageToken");
        rows.get(32).put("mediaStoreId", 999);
        rows.clear();
        stats.put("ownBytes", 999);
        pages.start("other", java.util.Collections.singletonList(new JSONObject().put("mediaStoreId", 8)), stats);

        JSONObject second = new JSONObject(pages.next("all", continuation));
        assertEquals(33, second.getJSONArray("entries").getJSONObject(0).getInt("mediaStoreId"));
        assertEquals(700, second.getJSONObject("stats").getInt("ownBytes"));
        JSONObject last = new JSONObject(pages.next("all", second.getString("nextPageToken")));
        assertEquals(6, last.getJSONArray("entries").length());
        assertEquals(70, last.getJSONArray("entries").getJSONObject(5).getInt("mediaStoreId"));
        assertTrue(last.isNull("nextPageToken"));
        assertThrows(IllegalArgumentException.class, () -> pages.next("all", continuation));
    }

    @Test public void emptyReadsAndExpiredOrWrongQueryTokensNeverBecomeAnotherSnapshot() throws Exception {
        MotionMediaPages pages = new MotionMediaPages();
        JSONObject empty = new JSONObject(pages.start("all", new ArrayList<>(), new JSONObject()));
        assertEquals(0, empty.getJSONArray("entries").length());
        assertTrue(empty.isNull("nextPageToken"));
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < 33; i++) rows.add(new JSONObject().put("id", i));
        String token = new JSONObject(pages.start("all", rows, new JSONObject())).getString("nextPageToken");
        assertThrows(IllegalArgumentException.class, () -> pages.next("sd", token));
        for (int i = 0; i < 4; i++) pages.start("other", rows, new JSONObject());
        assertThrows(IllegalArgumentException.class, () -> pages.next("all", token));
    }

    @Test public void largeJsonSnapshotsRespectTheWholePageCharacterLimitAndReassembleInOrder() throws Exception {
        MotionMediaPages pages = new MotionMediaPages();
        char[] body = new char[9_000];
        Arrays.fill(body, 'x');
        String payload = new String(body);
        char[] statsText = new char[12_000];
        Arrays.fill(statsText, 's');
        JSONObject stats = new JSONObject().put("description", new String(statsText));
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 1; i <= 70; i++) {
            JSONArray markers = new JSONArray().put(new JSONObject()
                    .put("eventId", "event-" + i).put("level", i % 2 + 1)
                    .put("elapsedRealtimeMs", i * 100L));
            rows.add(new JSONObject().put("mediaStoreId", i).put("payload", payload)
                    .put("markers", markers));
        }

        String encoded = pages.start("large", rows, stats);
        rows.get(40).put("mediaStoreId", 999);
        rows.clear();
        stats.put("description", "changed");

        List<Integer> ids = new ArrayList<>();
        boolean firstPage = true;
        while (true) {
            JSONObject page = new JSONObject(encoded);
            assertTrue(encoded.length() <= 256 * 1024);
            assertTrue(page.getJSONArray("entries").length() <= 32);
            assertEquals(12_000, page.getJSONObject("stats").getString("description").length());
            if (firstPage) assertTrue(page.getJSONArray("entries").length() < 32);
            for (int i = 0; i < page.getJSONArray("entries").length(); i++) {
                JSONObject entry = page.getJSONArray("entries").getJSONObject(i);
                int mediaStoreId = entry.getInt("mediaStoreId");
                ids.add(mediaStoreId);
                JSONArray markers = entry.getJSONArray("markers");
                assertEquals(1, markers.length());
                JSONObject marker = markers.getJSONObject(0);
                assertEquals("event-" + mediaStoreId, marker.getString("eventId"));
                assertEquals(mediaStoreId % 2 + 1, marker.getInt("level"));
                assertEquals(mediaStoreId * 100L, marker.getLong("elapsedRealtimeMs"));
            }
            firstPage = false;
            if (page.isNull("nextPageToken")) break;
            encoded = pages.next("large", page.getString("nextPageToken"));
        }

        assertEquals(70, ids.size());
        for (int i = 0; i < ids.size(); i++) assertEquals(i + 1, (int) ids.get(i));
    }

    @Test public void oversizedSingleEntryOrMandatoryStatsFailsWithoutReturningAPartialPage() throws Exception {
        char[] oversized = new char[256 * 1024];
        Arrays.fill(oversized, 'x');
        JSONObject row = new JSONObject().put("payload", new String(oversized));
        IllegalArgumentException entryFailure = assertThrows(IllegalArgumentException.class,
                () -> new MotionMediaPages().start("entry", java.util.Collections.singletonList(row),
                        new JSONObject()));
        assertTrue(entryFailure.getMessage().contains("256 Ki characters"));

        JSONObject stats = new JSONObject().put("description", new String(oversized));
        IllegalArgumentException statsFailure = assertThrows(IllegalArgumentException.class,
                () -> new MotionMediaPages().start("stats", new ArrayList<>(), stats));
        assertTrue(statsFailure.getMessage().contains("256 Ki characters"));
    }

    @Test public void oversizedLaterEntryKeepsItsContinuationAvailableForRetry() throws Exception {
        char[] oversized = new char[256 * 1024];
        Arrays.fill(oversized, 'x');
        List<JSONObject> rows = Arrays.asList(new JSONObject().put("mediaStoreId", 1),
                new JSONObject().put("payload", new String(oversized)));
        MotionMediaPages pages = new MotionMediaPages();
        JSONObject first = new JSONObject(pages.start("later-oversized", rows, new JSONObject()));
        String token = first.getString("nextPageToken");

        IllegalArgumentException firstFailure = assertThrows(IllegalArgumentException.class,
                () -> pages.next("later-oversized", token));
        assertTrue(firstFailure.getMessage().contains("256 Ki characters"));
        IllegalArgumentException retryFailure = assertThrows(IllegalArgumentException.class,
                () -> pages.next("later-oversized", token));
        assertTrue(retryFailure.getMessage().contains("256 Ki characters"));
    }
}

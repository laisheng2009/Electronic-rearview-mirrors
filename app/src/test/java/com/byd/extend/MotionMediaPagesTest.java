package com.byd.extend;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
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
}

package com.byd.extend;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Metadata-only continuations; real read/delete authorization never uses this cache. */
final class MotionMediaPages {
    private static final int PAGE_SIZE = 32;
    private static final int MAX_PAGE_CHARS = 256 * 1024;
    private final LinkedHashMap<String, Page> pending = new LinkedHashMap<>();
    private long token = System.nanoTime() & Long.MAX_VALUE;

    private static final class Page {
        final String query, stats;
        final List<String> entries;
        final int offset;

        Page(String query, List<String> entries, String stats, int offset) {
            this.query = query;
            this.entries = entries;
            this.stats = stats;
            this.offset = offset;
        }
    }

    synchronized String start(String query, List<JSONObject> entries, JSONObject stats) throws JSONException {
        List<String> snapshot = new ArrayList<>(entries.size());
        for (JSONObject entry : entries) snapshot.add(entry.toString());
        return encode(new Page(query, snapshot, stats.toString(), 0));
    }

    synchronized String next(String query, String continuation) throws JSONException {
        Page page = pending.get(continuation);
        if (page == null || !page.query.equals(query))
            throw new IllegalArgumentException("Expired or mismatched Motion page token");
        String encoded = encode(page);
        pending.remove(continuation);
        return encoded;
    }

    private String encode(Page page) throws JSONException {
        int maxEnd = Math.min(page.entries.size(), page.offset + PAGE_SIZE);
        int end = page.offset;
        String encoded = null;
        long nextToken = token == Long.MAX_VALUE ? 1 : token + 1;
        while (end < maxEnd) {
            int candidateEnd = end + 1;
            String candidateNext = candidateEnd < page.entries.size() ? Long.toString(nextToken) : null;
            String candidate = encode(page, candidateEnd, candidateNext);
            if (candidate.length() > MAX_PAGE_CHARS) {
                if (end == page.offset) throw oversizedPage();
                break;
            }
            end = candidateEnd;
            encoded = candidate;
        }
        if (page.offset == page.entries.size()) {
            encoded = encode(page, end, null);
            if (encoded.length() > MAX_PAGE_CHARS) throw oversizedPage();
        }
        if (encoded == null) throw oversizedPage();
        if (end < page.entries.size()) {
            token = nextToken;
            String next = Long.toString(token);
            pending.put(next, new Page(page.query, page.entries, page.stats, end));
            // Metadata-only queries may abandon a continuation; bound retained snapshots.
            while (pending.size() > 4) pending.remove(pending.keySet().iterator().next());
        }
        return encoded;
    }

    private String encode(Page page, int end, String next) throws JSONException {
        JSONArray entries = new JSONArray();
        for (int i = page.offset; i < end; i++) entries.put(new JSONObject(page.entries.get(i)));
        return new JSONObject().put("version", 1).put("entries", entries)
                .put("stats", new JSONObject(page.stats))
                .put("nextPageToken", next == null ? JSONObject.NULL : next).toString();
    }

    private static IllegalArgumentException oversizedPage() {
        return new IllegalArgumentException("Motion media page exceeds 256 Ki characters");
    }
}

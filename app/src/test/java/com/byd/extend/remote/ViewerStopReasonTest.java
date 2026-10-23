package com.byd.extend.remote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ViewerStopReasonTest {
    @Test public void retainsCodesWithoutLoggingBrowserMessagesUrlsOrCredentials() throws Exception {
        JSONObject cause = new JSONObject().put("origin", "playback_error").put("category", "hls")
                .put("mediaCode", 3).put("exception", "NotSupportedError")
                .put("hlsType", "mediaError").put("hlsDetails", "bufferAppendError")
                .put("fragment", 7).put("httpStatus", 404)
                .put("url", "https://private.example/?password=secret").put("message", "secret");
        assertEquals("origin=playback_error category=hls exception=NotSupportedError hlsType=mediaError"
                + " hlsDetails=bufferAppendError mediaCode=3 fragment=7 httpStatus=404", ViewerStopReason.describe(cause));
        cause.put("exception", "secret\r\nforged_log").put("fragment", -1).put("httpStatus", "token");
        assertFalse(ViewerStopReason.describe(cause).contains("secret"));
        assertFalse(ViewerStopReason.describe(cause).contains("fragment="));
        assertFalse(ViewerStopReason.describe(cause).contains("httpStatus="));
        assertEquals("origin=pagehide", ViewerStopReason.describe(new JSONObject().put("origin", "pagehide")));
    }

    @Test public void classifiesLegacyCurrentAndStaleMediaWithoutExposingIdentifiers() {
        FragmentedVideoWindow current = new FragmentedVideoWindow();
        FragmentedVideoWindow retired = new FragmentedVideoWindow();
        String playlist = current.getPlaylistPath();
        String serverRevision = " serverRevision=" + ViewerStopReason.WEB_REVISION;
        assertEquals("resource=playlist generation=legacy clientRevision=unknown" + serverRevision,
                ViewerStopReason.mediaRequest("/live.m3u8", playlist, null));
        assertEquals("resource=playlist generation=current clientRevision=2" + serverRevision,
                ViewerStopReason.mediaRequest(playlist, playlist, "2"));
        assertEquals("resource=playlist generation=stale clientRevision=1" + serverRevision,
                ViewerStopReason.mediaRequest(retired.getPlaylistPath(), playlist, "1"));
        assertEquals("resource=init generation=current clientRevision=2" + serverRevision,
                ViewerStopReason.mediaRequest(playlist.replace("live.m3u8", "init.mp4"), playlist, "2"));
        assertEquals("resource=segment generation=current clientRevision=2" + serverRevision,
                ViewerStopReason.mediaRequest(playlist.replace("live.m3u8", "segment/7.m4s"), playlist, "2"));
        assertEquals("resource=playlist generation=none clientRevision=2" + serverRevision,
                ViewerStopReason.mediaRequest(playlist, null, "2"));
        assertEquals("resource=other generation=invalid clientRevision=unknown" + serverRevision,
                ViewerStopReason.mediaRequest("/media/secret/private?password=secret", playlist, "secret\r\nforged"));
    }

    @Test public void browserRevisionIsNumericAndBounded() throws Exception {
        JSONObject cause = new JSONObject().put("origin", "playback_error").put("clientRevision", 2);
        assertEquals("origin=playback_error clientRevision=2", ViewerStopReason.describe(cause));
        for (Object invalid : new Object[] {-1, 2.5, 1_000_001, "secret"}) {
            cause.put("clientRevision", invalid);
            assertEquals("origin=playback_error", ViewerStopReason.describe(cause));
        }
    }
}

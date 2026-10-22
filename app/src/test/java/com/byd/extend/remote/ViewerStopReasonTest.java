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
}

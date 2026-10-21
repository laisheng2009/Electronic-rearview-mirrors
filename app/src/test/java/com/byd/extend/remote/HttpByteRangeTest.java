package com.byd.extend.remote;

import org.junit.Test;
import static org.junit.Assert.*;

public class HttpByteRangeTest {
    @Test public void browserSeekAndSuffixUseActualFileBoundaries() {
        HttpByteRange seek = HttpByteRange.parse("bytes=4096-8191", 10000);
        assertEquals(4096, seek.start);
        assertEquals(4096, seek.length());
        assertEquals("bytes 4096-8191/10000", seek.contentRange());
        assertEquals(5904, HttpByteRange.parse("bytes=4096-", 10000).length());
        assertEquals(9999, HttpByteRange.parse("bytes=4096-999999", 10000).end);
        assertEquals(9900, HttpByteRange.parse("bytes=-100", 10000).start);
        assertEquals(0, HttpByteRange.parse("bytes=-999999", 10000).start);
        assertFalse(HttpByteRange.parse(null, 10000).partial);
    }

    @Test public void rejectsInvalidOrOutOfBoundsRequests() {
        for (String header : new String[]{"bytes=10000-", "bytes=2-1", "bytes=-0", "bytes=-",
                "bytes=1-2,4-5", "bytes=999999999999999999999-", "items=1-2"})
            assertThrows(IllegalArgumentException.class, () -> HttpByteRange.parse(header, 10000));
    }
}

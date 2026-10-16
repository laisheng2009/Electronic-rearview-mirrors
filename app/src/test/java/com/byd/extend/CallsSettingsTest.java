package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class CallsSettingsTest {
    @Test
    public void normalizeNumberRemovesAllowedFormattingAndKeepsInternationalPrefix() {
        assertEquals("+12025550123", CallsSettings.normalizeNumber(" +1 (202) 555-0123 "));
    }

    @Test
    public void normalizeNumberAcceptsShortCodesAndNumbersBeyondTheFormerLimit() {
        assertEquals("112", CallsSettings.normalizeNumber("112"));
        assertEquals("911", CallsSettings.normalizeNumber("911"));
        assertEquals("1", CallsSettings.normalizeNumber("1"));
        assertEquals("1234567890123456", CallsSettings.normalizeNumber("1234567890123456"));
    }

    @Test
    public void normalizeNumberRejectsEmptyAndMalformedNumbers() {
        assertNull(CallsSettings.normalizeNumber(null));
        assertNull(CallsSettings.normalizeNumber(""));
        assertNull(CallsSettings.normalizeNumber(" ()- "));
        assertNull(CallsSettings.normalizeNumber("+"));
        assertNull(CallsSettings.normalizeNumber("112x"));
        assertNull(CallsSettings.normalizeNumber("+1+2025550123"));
        assertNull(CallsSettings.normalizeNumber("2025550123,123"));
        assertNull(CallsSettings.normalizeNumber("１２３４５６"));
    }
}

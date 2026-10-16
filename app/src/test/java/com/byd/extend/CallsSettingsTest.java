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
    public void normalizeNumberRejectsInvalidOrOutOfRangeNumbers() {
        assertNull(CallsSettings.normalizeNumber("911"));
        assertNull(CallsSettings.normalizeNumber("1234567890123456"));
        assertNull(CallsSettings.normalizeNumber("+1+2025550123"));
        assertNull(CallsSettings.normalizeNumber("2025550123,123"));
        assertNull(CallsSettings.normalizeNumber("１２３４５６"));
    }
}

package com.byd.extend;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CameraFrameRateTest {
    @Test public void selectionMapsToTheApprovedRates() {
        assertEquals(15, CameraFrameRate.fromSelection(0));
        assertEquals(25, CameraFrameRate.fromSelection(1));
        assertEquals(30, CameraFrameRate.fromSelection(2));
    }

    @Test public void legacyValueNormalizesWithoutChangingCurrentValues() {
        assertEquals(15, CameraFrameRate.normalize(20));
        assertEquals(15, CameraFrameRate.normalize(15));
        assertEquals(25, CameraFrameRate.normalize(25));
        assertEquals(30, CameraFrameRate.normalize(30));
        assertEquals(CameraFrameRate.DEFAULT, CameraFrameRate.normalize(24));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidSelectionIsRejected() {
        CameraFrameRate.fromSelection(3);
    }
}

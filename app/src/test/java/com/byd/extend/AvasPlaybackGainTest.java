package com.byd.extend;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AvasPlaybackGainTest {
    @Test public void exteriorGainHas34DbCeilingWithoutChangingInterior() {
        assertEquals(3400, AvasPlaybackPlan.exteriorTargetGainMb(100));
        assertEquals(2798, AvasPlaybackPlan.exteriorTargetGainMb(50));
        assertEquals(0, AvasPlaybackPlan.exteriorTargetGainMb(1));
        assertEquals(.501187f, AvasPlaybackPlan.exteriorPlayerVolume(1, true), .00001f);
        assertEquals(1f, AvasPlaybackPlan.exteriorPlayerVolume(50, true), 0f);
        assertEquals(.5f, AvasPlaybackPlan.exteriorPlayerVolume(50, false), 0f);
        assertEquals(0f, AvasPlaybackPlan.exteriorPlayerVolume(0, true), 0f);
        assertEquals(2f, AvasPlaybackPlan.interiorPcmGain(100), 0f);
    }
}

package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AvasEngineMotorMappingTest {

    @Test public void calibrationMedianTracksNewRevisionsAndResets() {
        AvasEngineMotorMapping.Scale scale = new AvasEngineMotorMapping.Scale();
        scale.observe(20, true, 100, 1, 1_000, true, 100, 1);
        scale.observe(20, true, 200, 2, 1_800, true, 200, 2);
        scale.observe(20, true, 300, 3, 2_000, true, 300, 3);
        assertEquals(3, scale.sampleCount());
        assertEquals(90, scale.rawPerKph(), 0.001f);

        scale.observe(20, true, 400, 4, 1_600, true, 300, 3);
        assertEquals(85, scale.rawPerKph(), 0.001f);
        scale.observe(20, true, 400, 4, 4_000, true, 300, 3);
        assertEquals(4, scale.sampleCount());
        assertEquals(85, scale.rawPerKph(), 0.001f);
        scale.observe(20, true, 500, 5, 1_400, true, 400, 4);
        assertEquals(5, scale.sampleCount());
        assertEquals(80, scale.rawPerKph(), 0.001f);
        scale.observe(20, true, 600, 6, 2_400, true, 500, 5);
        assertEquals(5, scale.sampleCount());
        assertEquals(90, scale.rawPerKph(), 0.001f);

        scale.reset();
        assertEquals(0, scale.sampleCount());
        assertEquals(AvasEngineMotorMapping.NOMINAL_AUDIO_SCALE, scale.rawPerKph(), 0f);
        scale.observe(20, true, 500, 4, 1_200, true, 300, 3);
        scale.observe(20, true, 600, 5, 1_600, true, 400, 4);
        scale.observe(20, true, 700, 6, 2_000, true, 500, 5);
        assertEquals(3, scale.sampleCount());
        assertEquals(80, scale.rawPerKph(), 0.001f);
    }

    @Test public void invalidRoadSpeedCannotFreezeMovingOrCoastingEngineAtIdle() {
        AvasEngineMotorMapping.Scale front = new AvasEngineMotorMapping.Scale();
        AvasEngineMotorMapping.Scale rear = new AvasEngineMotorMapping.Scale();
        AvasEngineMotorMapping.Motion motion = AvasEngineMotorMapping.select(
                32, false, 1_200, true, front, -2_400, true, rear);
        assertEquals("motor_rear", motion.source);
        assertEquals(32, motion.speedKph, 0.001f);
        AvasEngineModel model = new AvasEngineModel(900, 6000);
        AvasEngineModel.State state = model.update(0, motion.speedKph, true,
                0, true, 0, true, 4, true);
        assertTrue(state.valid);
        assertEquals(900.0f, state.rpm, 0.01f);
        assertEquals(0, state.load, 0.001f);
        motion = AvasEngineMotorMapping.select(Float.NaN, false, Integer.MIN_VALUE,
                false, front, -750, true, rear);
        assertEquals(10, motion.speedKph, 0.001f);
        assertEquals("motor_rear", motion.source);
        state = model.update(20, motion.speedKph, true,
                0, true, 0, true, 4, true);
        assertTrue(state.rpm > 900.0f);
        float coastingRpm = state.rpm;
        state = model.update(40, motion.speedKph, true,
                0, true, 0, true, 4, true);
        assertTrue(state.rpm > coastingRpm);
        motion = AvasEngineMotorMapping.select(20, true, Integer.MIN_VALUE, false,
                front, -2_147_482_648, true, rear);
        assertEquals("speed", motion.source);
    }

}

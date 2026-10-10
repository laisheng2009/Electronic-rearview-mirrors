package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AvasEngineMotorMappingTest {

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

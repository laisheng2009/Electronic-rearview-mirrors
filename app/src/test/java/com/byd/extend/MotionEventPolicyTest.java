package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionEventPolicyTest {
    @Test public void warmsFor2500msAndEmitsBothNativeLevels() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 1_000L);

        assertTrue(policy.observe(-3, 3_499L).suppressed);
        MotionEventPolicy.Decision levelOne = policy.observe(-3, 3_500L);
        assertEquals(MotionEventPolicy.LEVEL_ONE, levelOne.level);
        assertEquals(1_000, levelOne.elapsedMs - 2_500);
        assertEquals(13_500L, levelOne.warningUntilMs);

        MotionEventPolicy.Decision levelTwo = policy.observe(4, 8_000L);
        assertEquals(MotionEventPolicy.LEVEL_TWO, levelTwo.level);
        assertEquals(18_000L, levelTwo.warningUntilMs);
        assertTrue(policy.warningActive(17_999L));
        assertFalse(policy.warningActive(18_000L));
    }

    @Test public void zeroDoesNotRenewAndMinusTwoIsError() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 0L);
        assertEquals(MotionEventPolicy.LEVEL_ONE, policy.observe(-1, 2_500L).level);
        assertEquals(MotionEventPolicy.LEVEL_NONE, policy.observe(0, 11_000L).level);
        assertEquals(12_500L, policy.warningUntilMs());

        MotionEventPolicy.Decision error = policy.observe(-2, 11_100L);
        assertTrue(error.error);
        assertEquals(-2, error.nativeResult);
        assertTrue(policy.warningActive(11_100L));
        assertFalse(policy.warningActive(12_500L));
    }

    @Test public void eligibilityResetAndExplicitStopClearWarning() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 5L);
        policy.observe(2, 2_505L);
        policy.setEligible(false, 2_600L);
        assertFalse(policy.eligible());
        assertFalse(policy.warningActive(2_600L));

        policy.setEligible(true, 3_000L);
        assertTrue(policy.observe(2, 5_499L).suppressed);
        assertEquals(MotionEventPolicy.LEVEL_TWO, policy.observe(2, 5_500L).level);
        policy.stop(5_600L);
        assertFalse(policy.warningActive(5_600L));
    }

    @Test public void visibleDeadlineStartsAfterCoverAndRenewsOnActualDetection() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 0L);
        policy.observe(1, 2_500L);
        policy.beginVisibleWarning(6_000L);
        assertEquals(16_000L, policy.warningUntilMs());

        policy.observe(2, 15_500L);
        assertEquals(25_500L, policy.warningUntilMs());
        policy.observe(0, 16_000L);
        assertTrue(policy.warningActive(25_499L));
        assertFalse(policy.warningActive(25_500L));
    }

    @Test public void lateWindowDrawCannotRearmAnIneligibleSession() {
        MotionEventPolicy policy = new MotionEventPolicy();
        policy.setEligible(true, 0L);
        policy.observe(1, 2_500L);
        policy.setEligible(false, 3_000L);
        policy.beginVisibleWarning(4_000L);
        assertEquals(0L, policy.warningUntilMs());
        assertFalse(policy.warningActive(4_000L));
    }
}

package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class TurnSignalCenterReturnTrackerTest {
    private static final int LEFT = 2;
    private static final int RIGHT = 4;

    @Test public void outwardArmedSessionSeedCompletesWhenNextSampleSkipsSmallCenterBand() {
        TurnSignalGuardRuntime.CenterReturnTracker left = tracker(LEFT, 0.3f);
        assertTrue(left.observe(LEFT, true, 0.1f, -0.2f));

        TurnSignalGuardRuntime.CenterReturnTracker right = tracker(RIGHT, -0.3f);
        assertTrue(right.observe(RIGHT, true, 0.1f, 0.2f));
    }

    private static TurnSignalGuardRuntime.CenterReturnTracker tracker(
            int direction, float initialAngle) {
        TurnSignalGuardRuntime.CenterReturnTracker tracker =
                new TurnSignalGuardRuntime.CenterReturnTracker();
        tracker.seed(initialAngle);
        return tracker;
    }
}

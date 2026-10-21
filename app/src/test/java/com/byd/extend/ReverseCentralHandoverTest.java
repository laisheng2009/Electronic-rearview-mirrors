package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class ReverseCentralHandoverTest {
    @Test public void switchesOnlyWhenTheRequestedSourceIsFreshInEitherDirection() {
        boolean front = ReverseCameraCompositionView.selectCentralFront(true, false, false, true);
        assertFalse(front);
        front = ReverseCameraCompositionView.selectCentralFront(true, front, true, true);
        assertTrue(front);
        front = ReverseCameraCompositionView.selectCentralFront(false, front, true, false);
        assertTrue(front);
        front = ReverseCameraCompositionView.selectCentralFront(false, front, true, true);
        assertFalse(front);
    }

    @Test public void rapidReversalKeepsTheLiveSourceAndLossDoesNotKeepStaleFront() {
        boolean front = ReverseCameraCompositionView.selectCentralFront(false, true, true, false);
        assertTrue(ReverseCameraCompositionView.selectCentralFront(true, front, true, false));
        assertFalse(ReverseCameraCompositionView.selectCentralFront(true, front, false, false));
        assertFalse(ReverseCameraCompositionView.selectCentralFront(false, false, true, false));
    }
}

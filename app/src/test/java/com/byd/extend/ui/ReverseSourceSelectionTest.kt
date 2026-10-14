package com.byd.extend.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReverseSourceSelectionTest {
    @Test fun sourceRemainsFrontAcrossIntegratedCamerasAndFallsBackOnlyWhenUnsupported() {
        val state = ReverseUiState(frontIntegration = mapOf(
            ReverseElement.RearLeft to true,
            ReverseElement.RearRight to true,
            ReverseElement.Rear to false,
        ))
        var source = ReverseSource.Front
        for (element in listOf(ReverseElement.RearRight, ReverseElement.RearLeft)) {
            source = state.sourceFor(element, source)
            assertEquals(ReverseSource.Front, source)
        }
        assertEquals(ReverseSource.Rear, state.sourceFor(ReverseElement.Rear, source))
        for (element in ReverseElement.entries) {
            assertEquals(ReverseSource.Rear, state.sourceFor(element, ReverseSource.Rear))
            if (element != ReverseElement.RearLeft && element != ReverseElement.RearRight) {
                assertEquals(ReverseSource.Rear, state.sourceFor(element, source))
            }
        }
    }
}

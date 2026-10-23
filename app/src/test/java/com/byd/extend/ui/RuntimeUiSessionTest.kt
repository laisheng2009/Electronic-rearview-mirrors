package com.byd.extend.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeUiSessionTest {
    @Test fun motionCameraTabSelectionIsRetainedInSessionModelAcrossRootTabChanges() {
        val selections = RuntimeUiSelections(
            activeTab = RootTab.Signals,
            signalsCategory = SignalsCategory.Motion,
            blindSection = CameraSection.Parameters,
            blindGroup = CameraGroup.Rear,
            blindSide = CameraSide.Left,
            parkingSection = CameraSection.Parameters,
            parkingView = ParkingView.Front,
            reverseSection = CameraSection.Parameters,
            reverseElement = ReverseElement.Rear,
            reverseSource = ReverseSource.Rear,
            mirrorSection = CameraSection.Parameters,
            settingsCategory = SettingsCategory.Permissions,
            diagnosticMode = DiagnosticMode.Signals,
            directSelection = null,
            avmSelection = null,
            avmOrientation = AvmOrientation.Horizontal,
        )
        val session = RuntimeUiSession.Session(selections)

        session.select(selections.copy(activeTab = RootTab.Settings))

        for (tab in 0..2) {
            session.selectMotionCameraTab(tab)
            assertEquals(tab, session.motionCameraTab())
        }
        assertThrows(IllegalArgumentException::class.java) { session.selectMotionCameraTab(3) }
    }
}

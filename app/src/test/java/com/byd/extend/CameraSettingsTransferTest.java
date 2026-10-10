package com.byd.extend;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class CameraSettingsTransferTest {
    private static final CameraSettingsTransfer.GeometryResolver GEOMETRY = target ->
            target == CameraDisplayTarget.CLUSTER
                    ? new CameraSettingsTransfer.DisplayGeometry(1920, 720, 0, 0, 0)
                    : new CameraSettingsTransfer.DisplayGeometry(1280, 800, 12, 30, 70);

    @Test
    public void v4ExportsBothDisplayPlacementsForMirrorAndEveryBlindProfile() {
        TestSharedPreferences source = new TestSharedPreferences();
        for (CameraProfile profile : CameraProfile.values()) {
            seedBlindPlacement(source, profile, CameraDisplayTarget.TABLET,
                    CameraPlacement.of(0.10f, 0.11f, 0.20f, 0.21f));
            seedBlindPlacement(source, profile, CameraDisplayTarget.CLUSTER,
                    CameraPlacement.of(0.50f, 0.51f, 0.22f, 0.23f));
        }
        seedMirrorPlacement(source, CameraDisplayTarget.TABLET,
                CameraPlacement.of(0.12f, 0.13f, 0.24f, 0.25f));
        seedMirrorPlacement(source, CameraDisplayTarget.CLUSTER,
                CameraPlacement.of(0.52f, 0.53f, 0.26f, 0.27f));

        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(source, GEOMETRY));
        @SuppressWarnings("unchecked") Map<String, Object> values =
                (Map<String, Object>) parsed.get("settings");
        for (CameraProfile profile : CameraProfile.values()) {
            assertEquals(0.10f, number(values, BlindSpotOverlayController.placementKey(
                    profile, CameraDisplayTarget.TABLET,
                    BlindSpotOverlayController.PLACEMENT_X)), 0.0f);
            assertEquals(0.50f, number(values, BlindSpotOverlayController.placementKey(
                    profile, CameraDisplayTarget.CLUSTER,
                    BlindSpotOverlayController.PLACEMENT_X)), 0.0f);
        }
        assertEquals(12.0f, number(values, RearviewMirrorSettings.placementKey(
                CameraDisplayTarget.TABLET, RearviewMirrorSettings.PLACEMENT_X)), 0.0f);
        assertEquals(52.0f, number(values, RearviewMirrorSettings.placementKey(
                CameraDisplayTarget.CLUSTER, RearviewMirrorSettings.PLACEMENT_X)), 0.0f);

        TestSharedPreferences destination = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(destination, parsed, GEOMETRY);
        for (CameraProfile profile : CameraProfile.values()) {
            assertPlacement(CameraPlacement.of(0.10f, 0.11f, 0.20f, 0.21f),
                    BlindSpotOverlayController.readPlacement(destination, profile,
                            CameraDisplayTarget.TABLET, 1280, 800, 12, 30, 70));
            assertPlacement(CameraPlacement.of(0.50f, 0.51f, 0.22f, 0.23f),
                    BlindSpotOverlayController.readPlacement(destination, profile,
                            CameraDisplayTarget.CLUSTER, 1920, 720, 0, 0, 0));
        }
        assertPlacement(CameraPlacement.of(0.12f, 0.13f, 0.24f, 0.25f),
                RearviewMirrorSettings.placement(destination, CameraDisplayTarget.TABLET));
        assertPlacement(CameraPlacement.of(0.52f, 0.53f, 0.26f, 0.27f),
                RearviewMirrorSettings.placement(destination, CameraDisplayTarget.CLUSTER));
        assertEquals(1, destination.transactions);
    }

    @Test
    public void v4RoundTripsIndependentBordersAndRejectsInvalidPairsBeforeWrite() {
        TestSharedPreferences source = new TestSharedPreferences();
        CameraBorderSettings.writeBlind(source, CameraProfile.FRONT_LEFT,
                new CameraBorderSettings.Border(3, 0xFF112233));
        CameraBorderSettings.writeParking(source, ParkingCameraProfile.RIGHT,
                new CameraBorderSettings.Border(4, 0xFF223344));
        CameraBorderSettings.writeReverse(source, 2, false,
                new CameraBorderSettings.Border(5, 0xFF334455));
        CameraBorderSettings.writeReverse(source, 2, true,
                new CameraBorderSettings.Border(6, 0xFF445566));
        CameraBorderSettings.writeReverseElement(source,
                ReverseCameraLayout.BACKGROUND_PANE_ID,
                new CameraBorderSettings.Border(7, 0xFF556677));
        CameraBorderSettings.writeReverseElement(source,
                ReverseCameraLayout.WIDGET_PANE_ID,
                new CameraBorderSettings.Border(8, 0xFF667788));
        CameraBorderSettings.writeMirror(source, false,
                new CameraBorderSettings.Border(9, 0xFF778899));
        CameraBorderSettings.writeMirror(source, true,
                new CameraBorderSettings.Border(10, 0xFF8899AA));

        Map<String, Object> parsed = CameraSettingsTransfer.parseCameraPreset(
                CameraSettingsTransfer.exportCameraPreset(source, GEOMETRY));
        TestSharedPreferences target = new TestSharedPreferences();
        CameraSettingsTransfer.applyCameraPreset(target, parsed, GEOMETRY);
        assertEquals(3, CameraBorderSettings.forBlind(target, CameraProfile.FRONT_LEFT).borderDp);
        assertEquals(4, CameraBorderSettings.forParking(target, ParkingCameraProfile.RIGHT).borderDp);
        assertEquals(5, CameraBorderSettings.forReverse(target, 2, false).borderDp);
        assertEquals(6, CameraBorderSettings.forReverse(target, 2, true).borderDp);
        assertEquals(7, CameraBorderSettings.forReverseElement(target,
                ReverseCameraLayout.BACKGROUND_PANE_ID).borderDp);
        assertEquals(8, CameraBorderSettings.forReverseElement(target,
                ReverseCameraLayout.WIDGET_PANE_ID).borderDp);
        assertEquals(9, CameraBorderSettings.forMirror(target, false).borderDp);
        assertEquals(10, CameraBorderSettings.forMirror(target, true).borderDp);

        @SuppressWarnings("unchecked") Map<String, Object> values =
                (Map<String, Object>) parsed.get("settings");
        String colorKey = CameraBorderSettings.colorKey(
                CameraBorderSettings.blindPrefix(CameraProfile.of(CameraProfile.REAR_LEFT)));
        values.remove(colorKey);
        TestSharedPreferences unchanged = new TestSharedPreferences();
        unchanged.putString("unrelated", "keep");
        Map<String, ?> before = unchanged.getAll();
        assertThrows(IllegalArgumentException.class,
                () -> CameraSettingsTransfer.applyCameraPreset(unchanged, parsed, GEOMETRY));
        assertEquals(before, unchanged.getAll());
        assertEquals(0, unchanged.transactions);
    }

    private static void seedBlindPlacement(
            TestSharedPreferences preferences, CameraProfile profile,
            int target, CameraPlacement placement) {
        preferences.putFloat(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_X), placement.x);
        preferences.putFloat(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_Y), placement.y);
        preferences.putFloat(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_WIDTH), placement.width);
        preferences.putFloat(BlindSpotOverlayController.placementKey(
                profile, target, BlindSpotOverlayController.PLACEMENT_HEIGHT), placement.height);
    }

    private static void seedMirrorPlacement(
            TestSharedPreferences preferences, int target, CameraPlacement placement) {
        preferences.putFloat(RearviewMirrorSettings.placementKey(
                target, RearviewMirrorSettings.PLACEMENT_X), placement.x * 100.0f);
        preferences.putFloat(RearviewMirrorSettings.placementKey(
                target, RearviewMirrorSettings.PLACEMENT_Y), placement.y * 100.0f);
        preferences.putFloat(RearviewMirrorSettings.placementKey(
                target, RearviewMirrorSettings.PLACEMENT_WIDTH), placement.width * 100.0f);
        preferences.putFloat(RearviewMirrorSettings.placementKey(
                target, RearviewMirrorSettings.PLACEMENT_HEIGHT), placement.height * 100.0f);
    }

    private static float number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).floatValue();
    }

    private static void assertPlacement(CameraPlacement expected, CameraPlacement actual) {
        assertEquals(expected.x, actual.x, 0.000001f);
        assertEquals(expected.y, actual.y, 0.000001f);
        assertEquals(expected.width, actual.width, 0.000001f);
        assertEquals(expected.height, actual.height, 0.000001f);
    }
}

package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BydStockAvmTest {
    @Test public void mapsRequiredRearBackgroundAndNormalDiagnosticViews() {
        assertEquals("LANDSCAPE_REARLR", BydStockAvm.nativeViewName(StockAvmPreview.VIEW_REAR_DUAL, true));
        assertEquals("PORTRAIT_REAR_LR", BydStockAvm.nativeViewName(StockAvmPreview.VIEW_REAR_DUAL, false));
        assertEquals("PORTRAIT_LEFT_REARLR", BydStockAvm.nativeViewName(StockAvmPreview.VIEW_REAR_LEFT, true));
        assertEquals("PORTRAIT_RIGHT_REARLR", BydStockAvm.nativeViewName(StockAvmPreview.VIEW_REAR_RIGHT, true));
        assertEquals("LANDSCAPE_FRONT", BydStockAvm.nativeViewName(StockAvmPreview.horizontalViewpoint(2), true));
        assertEquals("LANDSCAPE_REAR", BydStockAvm.nativeViewName(StockAvmPreview.horizontalViewpoint(3), true));
        assertThrows(IllegalArgumentException.class, () -> BydStockAvm.nativeViewName(999, true));
        assertThrows(IllegalArgumentException.class,
                () -> BydStockAvm.nativeViewName(StockAvmPreview.horizontalViewpoint(44), true));
    }

    @Test public void calibrationDirectoryMatchesBydResolutionContract() {
        assertEquals("1300P", BydStockAvm.parameterDirectory(1, 1300));
        assertEquals("1080P", BydStockAvm.parameterDirectory(1, 1080));
        assertEquals("1200P", BydStockAvm.parameterDirectory(1, 1200));
        assertEquals("960P", BydStockAvm.parameterDirectory(4, 960));
    }

    @Test public void dedicatedBydBranchPrecedesTsMutationsAndOwnsOnlyItsSurfaceWrapper() throws Exception {
        String source = CameraRuntimeContinuityTest.source("StockAvmPreview");
        assertTrue(source.indexOf("\"set_screen_density\"") < source.indexOf("byd.open("));
        assertTrue(source.indexOf("byd.open(") < source.indexOf("currentStage = \"configure_stock_dewarp\""));
        String byd = CameraRuntimeContinuityTest.source("BydStockAvm");
        assertTrue(byd.contains("getCameraSurfaceTexture"));
        assertTrue(byd.contains("input = new Surface(texture)"));
        assertFalse(byd.contains("texture.release()"));
        assertFalse(byd.contains("/collect2/"));
        assertFalse(byd.contains("resource_ts"));
        assertTrue(byd.contains("config.intrinsic"));
        assertTrue(source.contains("parcel.writeString(intrinsic)"));
        assertTrue(source.contains("config.intrinsic = parcel.readString()"));
        assertEquals(5, StockAvmShellProtocol.VERSION);
    }

    @Test public void rejectsOversizedCalibrationAtIpcBoundary() {
        StockAvmPreview.Config config = new StockAvmPreview.Config(1, 123, 1920, 1300,
                "ocean", "SL06", "SL06");
        config.intrinsic = "x".repeat(65537);
        assertThrows(IllegalArgumentException.class, config::validate);
        config.intrinsic = null;
        config.validate();
    }
}

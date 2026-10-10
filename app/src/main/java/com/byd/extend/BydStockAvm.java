package com.byd.extend;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.util.Map;
import java.util.function.BiConsumer;
import static com.byd.extend.StockAvmPreview.*;

/** The BYD renderer contract from SL06 AutoVideo, separate from TS JSON/layout APIs. */
final class BydStockAvm implements AutoCloseable {
    private final BiConsumer<String, String> stage;
    private Object api;
    private Surface input;

    BydStockAvm(BiConsumer<String, String> stage) { this.stage = stage; }

    void open(Context context, Config config, ReverseSteeringModelConfig.Selection selection,
            Surface display, int viewpoint, boolean horizontal) throws Exception {
        ClassLoader loader = context.getClassLoader();
        Class<?> type = Class.forName("com.byd.carvis.AvmAPI", true, loader);
        java.lang.reflect.Method initialize = type.getMethod("initialize", String.class,
                int.class, int.class, int.class, int.class, String.class, Context.class);
        String root = "/data/local/tmp/bydextend_avm/byd/" + config.carType
                + selection.resourceSuffix + "/" + config.panoHeight + "/" + selection.packageLastUpdateTime;
        String model = root + "/model", parameters = root + "/parameters", pano = root + "/pano";
        copyAssetDirectory(loader, context,
                "resource_byd/" + config.carType + "/CarModel" + selection.resourceSuffix, model);
        copyAssetDirectory(loader, context, "resource_byd/" + config.carType + "/"
                + parameterDirectory(config.panoramaState, config.panoHeight), parameters);
        boolean mode3d = (Boolean) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarStatus", "is3DMode",
                new Class<?>[]{Context.class}, context);
        copyAssetDirectory(loader, context,
                mode3d ? "pano_3d_resources/" + config.carType : "pano_resources", pano);
        int width = ((Number) invokeStatic(loader,
                "com.byd.avc.util.devicestates.DeviceStates$ScreenStatus", "getOriginalScreenWidth")).intValue();
        int height = ((Number) invokeStatic(loader,
                "com.byd.avc.util.devicestates.DeviceStates$ScreenStatus", "getOriginalScreenHeight")).intValue();
        if (width < 320 || width > 4096 || height < 240 || height > 4096)
            throw new IllegalStateException("Invalid OEM display size: " + width + "x" + height);
        boolean board7325L = (Boolean) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarStatus", "isBootBoard7325L");
        String supplier = (String) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarSetting", "getCameraSupplier");
        Map<?, ?> suppliers = (Map<?, ?>) getStaticField(loader,
                "com.byd.avc.autostudy.utils.CameraAutoStudyMap", "FUNCTION_DOMAIN_CONTROLLER_SUPPLIER_MAP");
        boolean rearLeftRightFront = board7325L || supplier != null && !supplier.isEmpty()
                && suppliers.containsValue(supplier);
        int cameraType = ((Number) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarSetting", "getPanoCameraType")).intValue();
        invokeStatic(loader, "com.byd.avc.util.CarBodyColorController", "getCarBodyColor");
        String color = (String) invokeStatic(loader,
                "com.byd.avc.util.CarBodyColorController", "getCurrentColor");
        api = type.getConstructor().newInstance();
        invoke(api, "setCameraIndex", new Class<?>[]{int.class, int.class, int.class, int.class},
                0, 1, rearLeftRightFront ? 2 : 3, rearLeftRightFront ? 3 : 2);
        initialize.invoke(api, model, config.panoWidth, config.panoHeight, width, height, parameters, context);
        invoke(api, "setCameraType", new Class<?>[]{int.class, int.class}, cameraType, 0);
        invoke(api, "setAutomaticCalibrationStr", new Class<?>[]{String.class}, config.intrinsic);
        invoke(api, "setCarResources", new Class<?>[]{String.class, String.class, String.class, String.class},
                config.carType, config.subCarType, color == null ? "" : color, pano);
        stage.accept("byd_initialize", "model=" + model + ";parameters=" + parameters
                + ";camera_order=" + (rearLeftRightFront ? "rear_left_right_front" : "rear_left_front_right"));
        if (!Boolean.TRUE.equals(invoke(api, "initDisplay", new Class<?>[]{Context.class, Surface.class},
                null, display))) throw new IllegalStateException("BYD initDisplay rejected surface");
        setViewpoint(viewpoint, horizontal);
        long deadline = System.nanoTime() + 3_000_000_000L;
        do {
            SurfaceTexture texture = (SurfaceTexture) invoke(api, "getCameraSurfaceTexture", new Class<?>[0]);
            if (texture != null) {
                input = new Surface(texture);
                if (!input.isValid()) throw new IllegalStateException("Invalid BYD input Surface");
                stage.accept("byd_input_surface", "ready");
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("BYD input SurfaceTexture unavailable");
    }

    void setViewpoint(int viewpoint, boolean horizontal) throws Exception {
        String name = nativeViewName(viewpoint, horizontal);
        int nativeView = api.getClass().getField(name).getInt(null);
        invoke(api, "setViewpoint", new Class<?>[]{int.class}, nativeView);
        stage.accept("byd_viewpoint", "requested=" + viewpoint + ";native=" + name + ":" + nativeView);
    }

    static String nativeViewName(int view, boolean horizontal) {
        if (view == VIEW_REAR_DUAL) return horizontal ? "LANDSCAPE_REARLR" : "PORTRAIT_REAR_LR";
        if (view == VIEW_REAR_LEFT) return "PORTRAIT_LEFT_REARLR";
        if (view == VIEW_REAR_RIGHT) return "PORTRAIT_RIGHT_REARLR";
        // TS-only clairvoyance layouts must not silently show an unrelated BYD view.
        String layout = layoutName(view);
        String prefix = horizontal ? "LANDSCAPE_" : "PORTRAIT_";
        switch (layout) {
            case "VIEW_2D_FRONT": return horizontal ? "LANDSCAPE_FRONT" : "PORTRAIT_LEFT_FRONT";
            case "VIEW_2D_REAR": return horizontal ? "LANDSCAPE_REAR" : "PORTRAIT_LEFT_REAR";
            case "VIEW_2D_FRONT_FULL": return prefix + "WIDE_FRONT";
            case "VIEW_2D_REAR_FULL": return prefix + "WIDE_REAR";
            case "VIEW_2D_LEFT_FRONT": return "PORTRAIT_LEFT_FRONT";
            case "VIEW_2D_RIGHT_FRONT": return "PORTRAIT_RIGHT_FRONT";
            case "VIEW_2D_LEFT_REAR": return "PORTRAIT_LEFT_REAR";
            case "VIEW_2D_RIGHT_REAR": return "PORTRAIT_RIGHT_REAR";
            case "VIEW_2D_FRONT_WHEELS":
            case "VIEW_2D_FRONT_LEFT_AND_RIGHT": return horizontal ? "LANDSCAPE_FRONTLR" : "PORTRAIT_FRONT_LR";
            case "VIEW_2D_REAR_WHEELS":
            case "VIEW_2D_REAR_LEFT_AND_RIGHT": return horizontal ? "LANDSCAPE_REARLR" : "PORTRAIT_REAR_LR";
            case "VIEW_2D_TOP":
            case "VIEW_2D_TOP_FULL": return prefix + "PARKING_2D";
            case "VIEW_3D_FRONT": return horizontal ? "LANDSCAPE_3D_FRONT" : "PORTRAIT_LEFT_3D_FRONT";
            case "VIEW_3D_REAR": return horizontal ? "LANDSCAPE_3D_REAR" : "PORTRAIT_LEFT_3D_REAR";
            case "VIEW_3D_TOP": return horizontal ? "LANDSCAPE_3D_TOP" : "PORTRAIT_LEFT_3D_TOP";
            default: throw new IllegalArgumentException("No equivalent BYD layout: " + layout);
        }
    }

    Surface input() { return input; }

    static String parameterDirectory(int panoramaState, int height) {
        if (panoramaState != 1) return "960P";
        return height == 1300 ? "1300P" : height == 1080 ? "1080P" : "1200P";
    }

    @Override public void close() throws Exception {
        Throwable failure = terminate(api);
        api = null;
        // Release our producer wrapper, not the SurfaceTexture owned by the OEM renderer.
        if (input != null) { input.release(); input = null; }
        if (failure != null) throw new Exception(failure);
    }
}

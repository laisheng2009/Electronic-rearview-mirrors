package com.byd.extend;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Active OEM model steering bounds used only by Reverse crop shifting. */
final class ReverseSteeringModelConfig {
    private static final String STOCK_PACKAGE = "com.byd.avc";
    private static final String CAR_STATUS = "com.byd.avc.util.devicestates.CarStatus";
    private static final String DEVICE_STATES = "com.byd.avc.util.devicestates.DeviceStates";
    private static final String RENDER_SDK_HELPER = "com.byd.avc.sdk.RenderSdkHelper";
    private static final String PANO_RESOURCE_CONTROLLER =
            "com.byd.avc.util.PanoResourceController";
    private static final int MAX_CONFIG_BYTES = 1_048_576;

    final float minimumDegrees;
    final float maximumDegrees;
    final String model;

    ReverseSteeringModelConfig(
            float minimumDegrees, float maximumDegrees, String model) {
        if (!ReverseSteeringShift.hasValidBounds(minimumDegrees, maximumDegrees)) {
            throw new IllegalArgumentException("invalid active-model steering bounds");
        }
        if (model == null || model.trim().isEmpty() || model.length() > 256) {
            throw new IllegalArgumentException("active model identity unavailable");
        }
        this.minimumDegrees = minimumDegrees;
        this.maximumDegrees = maximumDegrees;
        this.model = model;
    }

    /** Resolve OEM selection on the app Looper; callers read the selected asset on a worker. */
    static Selection prepareLoad(Context context) throws Exception {
        // CarStatus creates a Handler during class initialization.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("OEM steering model requires the app main Looper");
        }
        if (context == null) throw new IllegalArgumentException("app context is missing");

        Context stockContext = context.createPackageContext(STOCK_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        ClassLoader loader = stockContext.getClassLoader();
        invokeStatic(loader, DEVICE_STATES, "setAVCContext",
                new Class<?>[]{Context.class}, stockContext);
        invokeStatic(loader, CAR_STATUS, "initBootBoard", new Class<?>[0]);
        invokeStatic(loader, CAR_STATUS, "initCarBodyTypeValue",
                new Class<?>[]{Context.class}, stockContext);

        String carType = (String) invokeStatic(loader, CAR_STATUS, "getCarBodyType",
                new Class<?>[]{Context.class}, stockContext);
        String subCarType = (String) invokeStatic(loader, CAR_STATUS, "getSubCarType",
                new Class<?>[]{Context.class}, stockContext);
        String series = "";
        try {
            Object value = invokeStatic(loader, CAR_STATUS, "getCarSeries", new Class<?>[0]);
            if (value instanceof String) series = (String) value;
        } catch (Exception ignored) {
            // Series is diagnostic only; stock model resources are selected by body and subtype.
        }
        requirePathPart("car type", carType);
        requirePathPart("sub-car type", subCarType);

        Object renderSdkHelper = invokeStatic(loader, RENDER_SDK_HELPER,
                "getInstance", new Class<?>[0]);
        String renderer = (String) invoke(renderSdkHelper, "getSDKTypeName", new Class<?>[0]);
        if (!"TS".equals(renderer) && !"BYD".equals(renderer)) {
            throw new IllegalArgumentException("active OEM renderer unavailable");
        }
        String resourceSuffix;
        String assetPath;
        String externalPath = "";
        String format;
        if ("TS".equals(renderer)) {
            // Preserve the stock TS resource_ts/<type>/CarModel/<sub-type> layout.
            resourceSuffix = "/" + subCarType;
            assetPath = "resource_ts/" + carType + "/CarModel/" + subCarType
                    + "/Vehicle_Configuration.json";
            format = "json";
        } else {
            String bydSuffix = (String) invokeStatic(loader, PANO_RESOURCE_CONTROLLER,
                    "getCarParamPath", new Class<?>[]{Context.class}, stockContext);
            requireResourceSuffix(bydSuffix);
            resourceSuffix = bydSuffix;
            assetPath = "resource_byd/" + carType + "/CarModel" + resourceSuffix
                    + "/configs/vehicle_param.yaml";
            externalPath = "/collect2/autovideo/resource_byd/" + carType
                    + "/CarModel" + resourceSuffix + "/resource/configs/vehicle_param.yaml";
            format = "yaml";
        }

        PackageInfo packageInfo = stockContext.getPackageManager()
                .getPackageInfo(STOCK_PACKAGE, 0);
        String model = (series == null || series.isEmpty() ? "" : series + "/")
                + carType + "/" + subCarType;
        return new Selection(stockContext, STOCK_PACKAGE,
                packageInfo.versionName == null ? "" : packageInfo.versionName,
                packageInfo.versionCode, packageInfo.lastUpdateTime,
                stockContext.getApplicationInfo().sourceDir, model, renderer,
                resourceSuffix, assetPath, externalPath, format);
    }

    boolean sameModel(ReverseSteeringModelConfig other) {
        return other != null && model.equals(other.model)
                && minimumDegrees == other.minimumDegrees && maximumDegrees == other.maximumDegrees;
    }

    /** A selected asset failure is reusable only while that exact selection stays active. */
    static boolean shouldLoadAfterCachedFailure(
            Selection selection, Selection failedSelection) {
        return selection == null || failedSelection == null
                || !failedSelection.sameIdentity(selection);
    }

    static final class Selection {
        final String packageName;
        final String packageVersionName;
        final int packageVersionCode;
        final long packageLastUpdateTime;
        final String packageSourceDir;
        final String model;
        final String renderer;
        final String resourceSuffix;
        final String configurationPath;
        final String externalConfigurationPath;
        final String configurationFormat;
        private final Context stockContext;

        Selection(Context stockContext, String packageName, String packageVersionName,
                int packageVersionCode, long packageLastUpdateTime, String packageSourceDir,
                String model, String renderer, String resourceSuffix, String configurationPath,
                String externalConfigurationPath, String configurationFormat) {
            this.stockContext = stockContext;
            this.packageName = packageName;
            this.packageVersionName = packageVersionName;
            this.packageVersionCode = packageVersionCode;
            this.packageLastUpdateTime = packageLastUpdateTime;
            this.packageSourceDir = packageSourceDir;
            this.model = model;
            this.renderer = renderer;
            this.resourceSuffix = resourceSuffix;
            this.configurationPath = configurationPath;
            this.externalConfigurationPath = externalConfigurationPath;
            this.configurationFormat = configurationFormat;
        }

        boolean sameIdentity(Selection other) {
            return other != null && packageName.equals(other.packageName)
                    && packageVersionName.equals(other.packageVersionName)
                    && packageVersionCode == other.packageVersionCode
                    && packageLastUpdateTime == other.packageLastUpdateTime
                    && packageSourceDir.equals(other.packageSourceDir)
                    && model.equals(other.model) && renderer.equals(other.renderer)
                    && resourceSuffix.equals(other.resourceSuffix)
                    && configurationPath.equals(other.configurationPath)
                    && externalConfigurationPath.equals(other.externalConfigurationPath);
        }

        /** Read and parse only the already-selected asset; this method must run on a worker. */
        Capture load() throws Exception {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                throw new IllegalStateException("OEM steering asset read requires a worker");
            }
            final String raw;
            try (InputStream input = stockContext.getAssets().open(configurationPath)) {
                raw = readUtf8(input);
            } catch (FileNotFoundException missing) {
                return capture("", message(missing));
            }
            return capture(raw, "");
        }

        Capture capture(String rawConfiguration) {
            return capture(rawConfiguration, "");
        }

        private Capture capture(String rawConfiguration, String initialError) {
            Map<String, String> rawFields = new LinkedHashMap<>();
            float minimum = Float.NaN;
            float maximum = Float.NaN;
            boolean available = false;
            String parseError = initialError;
            if (parseError.isEmpty()) {
                try {
                    Bounds bounds = "TS".equals(renderer)
                            ? parseJson(rawConfiguration, rawFields)
                            : parseYaml(rawConfiguration, rawFields);
                    minimum = bounds.minimumDegrees;
                    maximum = bounds.maximumDegrees;
                    available = true;
                } catch (Exception invalid) {
                    parseError = message(invalid);
                }
            }
            return new Capture(this, rawConfiguration, rawFields, minimum, maximum,
                    available, "BYD".equals(renderer), parseError);
        }
    }

    static final class Capture {
        final String packageName;
        final String packageVersionName;
        final int packageVersionCode;
        final long packageLastUpdateTime;
        final String packageSourceDir;
        final String model;
        final String renderer;
        final String resourceSuffix;
        final String configurationPath;
        final String externalConfigurationPath;
        final String configurationFormat;
        final String rawConfiguration;
        final Map<String, String> rawSteeringParameters;
        final float minimumDegrees;
        final float maximumDegrees;
        final boolean boundsAvailable;
        final boolean provisionalBounds;
        final String parseError;

        private Capture(Selection selection, String rawConfiguration,
                Map<String, String> rawSteeringParameters, float minimumDegrees,
                float maximumDegrees, boolean boundsAvailable, boolean provisionalBounds,
                String parseError) {
            packageName = selection.packageName;
            packageVersionName = selection.packageVersionName;
            packageVersionCode = selection.packageVersionCode;
            packageLastUpdateTime = selection.packageLastUpdateTime;
            packageSourceDir = selection.packageSourceDir;
            model = selection.model;
            renderer = selection.renderer;
            resourceSuffix = selection.resourceSuffix;
            configurationPath = selection.configurationPath;
            externalConfigurationPath = selection.externalConfigurationPath;
            configurationFormat = selection.configurationFormat;
            this.rawConfiguration = rawConfiguration;
            this.rawSteeringParameters = Collections.unmodifiableMap(
                    new LinkedHashMap<>(rawSteeringParameters));
            this.minimumDegrees = minimumDegrees;
            this.maximumDegrees = maximumDegrees;
            this.boundsAvailable = boundsAvailable;
            this.provisionalBounds = provisionalBounds;
            this.parseError = parseError;
        }

        ReverseSteeringModelConfig toConfig() {
            if (!boundsAvailable) {
                throw new IllegalStateException(parseError.isEmpty()
                        ? "active model steering bounds unavailable" : parseError);
            }
            return new ReverseSteeringModelConfig(minimumDegrees, maximumDegrees, model);
        }
    }

    static ReverseSteeringModelConfig fromJson(String model, String json) throws Exception {
        Bounds bounds = parseJson(json, new LinkedHashMap<>());
        return new ReverseSteeringModelConfig(
                bounds.minimumDegrees, bounds.maximumDegrees, model);
    }

    static ReverseSteeringModelConfig fromYaml(String model, String yaml) throws Exception {
        Bounds bounds = parseYaml(yaml, new LinkedHashMap<>());
        return new ReverseSteeringModelConfig(
                bounds.minimumDegrees, bounds.maximumDegrees, model);
    }

    /** Mirrors the stock VehicleConfiguration line cleanup before reading the JSON object. */
    static String normalizeOemJson(String source) {
        if (source == null) throw new IllegalArgumentException("model configuration is missing");
        StringBuilder normalized = new StringBuilder(source.length());
        boolean checkLine = false;
        for (String sourceLine : source.split("\\r?\\n")) {
            String line = sourceLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            if (checkLine && !line.contains("}") && !line.contains("]")) {
                normalized.append(',');
            }
            checkLine = false;
            if (line.endsWith("}") || line.endsWith("},")) {
                if (normalized.length() > 0 && !isCorrectEnd(normalized)) {
                    normalized.deleteCharAt(normalized.length() - 1);
                }
            } else if (!line.endsWith(",") && !line.contains("{") && !line.contains("[")) {
                checkLine = true;
            }
            normalized.append(line);
        }
        if (normalized.length() == 0) {
            throw new IllegalArgumentException("model configuration is empty");
        }
        return normalized.toString();
    }

    private static Bounds parseJson(String json, Map<String, String> rawFields) throws Exception {
        JSONObject root = new JSONObject(normalizeOemJson(json));
        JSONObject project = root.optJSONObject("PROJECT_CONFIG");
        if (project == null) {
            throw new IllegalArgumentException("explicit PROJECT_CONFIG steering bounds missing");
        }
        Object minimumValue = project.opt("AngleMin");
        Object maximumValue = project.opt("AngleMax");
        if (minimumValue != null && minimumValue != JSONObject.NULL) {
            rawFields.put("AngleMin", String.valueOf(minimumValue));
        }
        if (maximumValue != null && maximumValue != JSONObject.NULL) {
            rawFields.put("AngleMax", String.valueOf(maximumValue));
        }
        if (minimumValue == null || minimumValue == JSONObject.NULL
                || maximumValue == null || maximumValue == JSONObject.NULL) {
            throw new IllegalArgumentException("explicit PROJECT_CONFIG steering bounds missing");
        }
        int minimumMilliDegrees = exactInt(minimumValue, "AngleMin");
        int maximumMilliDegrees = exactInt(maximumValue, "AngleMax");
        return checkedBounds(minimumMilliDegrees / 1000.0f, maximumMilliDegrees / 1000.0f);
    }

    /** Narrow reader for the stock BYD vehicle_body_param YAML section. */
    private static Bounds parseYaml(String yaml, Map<String, String> rawFields) {
        if (yaml == null) throw new IllegalArgumentException("model configuration is missing");
        boolean inBody = false;
        int bodySections = 0;
        int bodyChildIndent = -1;
        for (String sourceLine : yaml.split("\\r?\\n")) {
            int indent = 0;
            while (indent < sourceLine.length() && sourceLine.charAt(indent) == ' ') indent++;
            boolean tabIndent = indent < sourceLine.length()
                    && sourceLine.charAt(indent) == '\t';
            String line = sourceLine.substring(indent).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            if (tabIndent && (inBody || line.equals("vehicle_body_param:"))) {
                throw new IllegalArgumentException("tab indentation in vehicle_body_param");
            }
            if (indent == 0) {
                if (line.equals("vehicle_body_param:")) bodySections++;
                inBody = line.equals("vehicle_body_param:");
                bodyChildIndent = -1;
                if (bodySections > 1) {
                    throw new IllegalArgumentException("ambiguous vehicle_body_param section");
                }
                continue;
            }
            if (!inBody) continue;
            if (bodyChildIndent < 0) bodyChildIndent = indent;
            if (indent != bodyChildIndent) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            if (!"steering_angle_max".equals(key)
                    && !"steering_angle_normalize_ratio".equals(key)) continue;
            String value = yamlScalar(line.substring(colon + 1).trim());
            if (rawFields.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate " + key + " in vehicle_body_param");
            }
        }
        String maximumValue = rawFields.get("steering_angle_max");
        if (bodySections != 1 || maximumValue == null || maximumValue.isEmpty()) {
            throw new IllegalArgumentException("vehicle_body_param steering_angle_max missing");
        }
        double maximum = exactFiniteNumber(maximumValue, "steering_angle_max");
        if (maximum <= 0.0d || maximum > Float.MAX_VALUE) {
            throw new IllegalArgumentException("steering_angle_max is not positive");
        }
        float degrees = (float) maximum;
        return checkedBounds(-degrees, degrees);
    }

    private static Bounds checkedBounds(float minimum, float maximum) {
        if (!ReverseSteeringShift.hasValidBounds(minimum, maximum)) {
            throw new IllegalArgumentException("invalid active-model steering bounds");
        }
        return new Bounds(minimum, maximum);
    }

    private static String yamlScalar(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static boolean isCorrectEnd(StringBuilder value) {
        return value.toString().endsWith("}") || value.toString().endsWith("]")
                || value.charAt(value.length() - 1) != ',';
    }

    private static int exactInt(Object value, String key) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(key + " is not an integer");
        }
        Number number = (Number) value;
        double numeric = number.doubleValue();
        int result = number.intValue();
        if (!Double.isFinite(numeric) || numeric != result) {
            throw new IllegalArgumentException(key + " is not an integer");
        }
        return result;
    }

    private static double exactFiniteNumber(String value, String key) {
        try {
            double result = Double.parseDouble(value);
            if (Double.isFinite(result)) return result;
        } catch (NumberFormatException ignored) {
            // Replaced below with the selected field name for diagnostics.
        }
        throw new IllegalArgumentException(key + " is not numeric");
    }

    private static void requirePathPart(String label, String value) {
        if (value == null || value.isEmpty() || value.length() > 128 || value.contains("/")
                || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException("active " + label + " unavailable");
        }
    }

    private static void requireResourceSuffix(String suffix) {
        if (suffix == null || suffix.length() > 128 || suffix.contains("\\")
                || suffix.contains("..") || suffix.contains("//")
                || (!suffix.isEmpty() && !suffix.matches("/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))) {
            throw new IllegalArgumentException("active OEM resource suffix unavailable");
        }
    }

    private static Object invokeStatic(
            ClassLoader loader, String className, String methodName,
            Class<?>[] parameterTypes, Object... values) throws Exception {
        Class<?> type = loader.loadClass(className);
        Method method = type.getMethod(methodName, parameterTypes);
        return method.invoke(null, values);
    }

    private static Object invoke(
            Object target, String methodName, Class<?>[] parameterTypes) throws Exception {
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        return method.invoke(target);
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    private static String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_CONFIG_BYTES) {
                throw new IllegalArgumentException("active model configuration is too large");
            }
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class Bounds {
        final float minimumDegrees;
        final float maximumDegrees;

        Bounds(float minimumDegrees, float maximumDegrees) {
            this.minimumDegrees = minimumDegrees;
            this.maximumDegrees = maximumDegrees;
        }
    }
}

package com.byd.extend;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Active OEM model steering bounds used only by Reverse crop shifting. */
final class ReverseSteeringModelConfig {
    private static final String STOCK_PACKAGE = "com.byd.avc";
    private static final String CAR_STATUS = "com.byd.avc.util.devicestates.CarStatus";
    private static final String DEVICE_STATES = "com.byd.avc.util.devicestates.DeviceStates";
    private static final String OEM_RENDERER_FILE = "/collect2/autovideo/sdk/pano_sdk.txt";
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

    /** Read model identity on the app Looper; resource selection and I/O run on a worker. */
    static PreparedModel prepareLoad(Context context) throws Exception {
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

        PackageInfo packageInfo = stockContext.getPackageManager()
                .getPackageInfo(STOCK_PACKAGE, 0);
        String model = (series == null || series.isEmpty() ? "" : series + "/")
                + carType + "/" + subCarType;
        return new PreparedModel(stockContext, packageInfo, model, carType, subCarType);
    }

    static final class PreparedModel {
        private final Context stockContext;
        private final PackageInfo packageInfo;
        private final String model;
        private final String carType;
        private final String subCarType;

        PreparedModel(Context stockContext, PackageInfo packageInfo, String model,
                String carType, String subCarType) {
            this.stockContext = stockContext;
            this.packageInfo = packageInfo;
            this.model = model;
            this.carType = carType;
            this.subCarType = subCarType;
        }

        Selection resolve() throws Exception {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                throw new IllegalStateException("OEM steering resource selection requires a worker");
            }
            return resolveResources();
        }

        // The dedicated stock-renderer shell has a Looper but does not host application UI.
        Selection resolveResources() throws Exception {
            List<String> paths = new ArrayList<>();
            addIfPresent(paths, "resource_ts/" + carType + "/CarModel/" + subCarType
                    + "/Vehicle_Configuration.json");
            String bydRoot = "resource_byd/" + carType + "/CarModel";
            addIfPresent(paths, bydRoot + "/configs/vehicle_param.yaml");
            String[] variants = stockContext.getAssets().list(bydRoot);
            if (variants != null) {
                for (String variant : variants) {
                    requirePathPart("resource variant", variant);
                    addIfPresent(paths, bydRoot + "/" + variant + "/configs/vehicle_param.yaml");
                }
            }
            String rendererHint = "";
            // Read the marker written by AutoVideo, never initialize its ADAS-backed renderer.
            try (InputStream input = new FileInputStream(OEM_RENDERER_FILE)) {
                rendererHint = readUtf8(input).trim();
            } catch (IOException | SecurityException unavailable) {
                // Without a readable marker, only a single model configuration is safe.
            }
            String path = selectConfigurationPath(carType, subCarType, paths, rendererHint);
            boolean ts = path.startsWith("resource_ts/");
            String root = ts ? "resource_ts/" + carType + "/CarModel" : bydRoot;
            String ending = ts ? "/Vehicle_Configuration.json" : "/configs/vehicle_param.yaml";
            String suffix = path.substring(root.length(), path.length() - ending.length());
            return new Selection(stockContext, STOCK_PACKAGE,
                    packageInfo.versionName == null ? "" : packageInfo.versionName,
                    packageInfo.versionCode, packageInfo.lastUpdateTime,
                    stockContext.getApplicationInfo().sourceDir, model, ts ? "TS" : "BYD",
                    suffix, path, ts ? "" : "/collect2/autovideo/" + root + suffix
                            + "/resource/configs/vehicle_param.yaml", ts ? "json" : "yaml");
        }

        private void addIfPresent(List<String> paths, String path) throws IOException {
            int slash = path.lastIndexOf('/');
            String[] files = stockContext.getAssets().list(path.substring(0, slash));
            if (files == null) return;
            for (String file : files) {
                if (file.equals(path.substring(slash + 1))) {
                    paths.add(path);
                    return;
                }
            }
        }
    }

    static String selectConfigurationPath(String carType, String subCarType,
            List<String> paths, String rendererHint) {
        requirePathPart("car type", carType);
        requirePathPart("sub-car type", subCarType);
        if (!rendererHint.isEmpty() && !"TS".equals(rendererHint) && !"BYD".equals(rendererHint)) {
            throw new IllegalArgumentException("unsupported OEM renderer: " + rendererHint);
        }
        String tsPath = "resource_ts/" + carType + "/CarModel/" + subCarType
                + "/Vehicle_Configuration.json";
        String bydRoot = "resource_byd/" + carType + "/CarModel";
        String bydEnding = "/configs/vehicle_param.yaml";
        String selected = null;
        for (String path : paths) {
            boolean ts = path.equals(tsPath);
            boolean byd = path.startsWith(bydRoot + "/") && path.endsWith(bydEnding);
            if (byd) {
                requireResourceSuffix(path.substring(bydRoot.length(), path.length() - bydEnding.length()));
            }
            if ((!ts && !byd) || ("TS".equals(rendererHint) && !ts)
                    || ("BYD".equals(rendererHint) && !byd)) continue;
            // Multiple renderer/trim resources are not evidence of which one is active.
            if (selected != null && !selected.equals(path)) {
                throw new IllegalArgumentException("ambiguous active-model steering configuration");
            }
            selected = path;
        }
        if (selected == null) {
            throw new IllegalArgumentException("active-model steering configuration unavailable");
        }
        return selected;
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

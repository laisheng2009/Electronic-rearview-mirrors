package com.byd.extend;

/** Native paths verified in Sea Lion 07 EV's libavmapi; not a universal TS layout catalog. */
public final class BydAvmDisplayContract {
    private BydAvmDisplayContract() {}

    public static String nativeView(String layout, boolean horizontal) {
        switch (layout) {
            case "VIEW_2D_TOP_FULL": return "LANDSCAPE_PIP_2D";
            case "VIEW_2D_FRONT": return horizontal ? "LANDSCAPE_FRONT" : "PORTRAIT_LEFT_FRONT";
            case "VIEW_2D_REAR": return horizontal ? "LANDSCAPE_REAR" : "PORTRAIT_LEFT_REAR";
            case "VIEW_2D_FRONT_FULL": return horizontal ? "LANDSCAPE_WIDE_FRONT" : null;
            case "VIEW_2D_REAR_FULL": return horizontal ? "LANDSCAPE_WIDE_REAR" : null;
            case "VIEW_2D_FRONT_WHEELS": return horizontal ? "LANDSCAPE_FRONTLR" : null;
            case "VIEW_2D_REAR_WHEELS": return horizontal ? "LANDSCAPE_REARLR" : null;
            case "VIEW_3D_FRONT": return horizontal ? "LANDSCAPE_3D_FRONT" : "PORTRAIT_LEFT_3D_FRONT";
            case "VIEW_3D_REAR": return horizontal ? "LANDSCAPE_3D_REAR" : "PORTRAIT_LEFT_3D_REAR";
            default: return null;
        }
    }

    static int[] canvas(int width, int height, boolean horizontal) {
        if (width < 240 || height < 240 || width > 4096 || height > 4096)
            throw new IllegalArgumentException("Invalid OEM canvas: " + width + "x" + height);
        // Preserve OEM square displays. Rectangular H/V surfaces select the native draw branch.
        return horizontal ? new int[]{Math.max(width, height), Math.min(width, height)}
                : new int[]{Math.min(width, height), Math.max(width, height)};
    }

    /** TextureView initially stretches its buffer to the view; undo only that stretch. */
    static float[] fitScale(int canvasWidth, int canvasHeight, int viewWidth, int viewHeight) {
        if (canvasWidth <= 0 || canvasHeight <= 0 || viewWidth <= 0 || viewHeight <= 0)
            throw new IllegalArgumentException("Positive canvas and viewport required");
        float scale = Math.min((float) viewWidth / canvasWidth, (float) viewHeight / canvasHeight);
        return new float[]{canvasWidth * scale / viewWidth, canvasHeight * scale / viewHeight};
    }
}

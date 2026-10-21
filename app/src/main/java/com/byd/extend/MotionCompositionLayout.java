package com.byd.extend;

/** Pixel geometry of the four camera images in a Motion recording. */
public final class MotionCompositionLayout {
    public static final int VIDEO_WIDTH = 1920;
    public static final int VIDEO_HEIGHT = 1080;
    private static final int CAMERA_WIDTH = 1920;
    private static final int CAMERA_HEIGHT = 1300;

    public static final class Rect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        private Rect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    private MotionCompositionLayout() { }

    public static Rect singleCameraRect(int width, int height) {
        if (width < 2 || height < 2) return null;
        int imageWidth = (int) Math.min(width, (long) height * CAMERA_WIDTH / CAMERA_HEIGHT);
        int imageHeight = (int) Math.min(height, (long) width * CAMERA_HEIGHT / CAMERA_WIDTH);
        int left = (width - imageWidth) / 2, top = (height - imageHeight) / 2;
        return new Rect(left, top, left + imageWidth, top + imageHeight);
    }

    public static Rect cameraRectForCell(int cell) {
        return cameraRectForCell(cell, VIDEO_WIDTH, VIDEO_HEIGHT);
    }

    public static Rect cameraRectForCell(int cell, int outputWidth, int outputHeight) {
        if (cell < 0 || cell >= 4 || outputWidth < 2 || outputHeight < 2) return null;
        int cellWidth = outputWidth / 2;
        int cellHeight = outputHeight / 2;
        int imageWidth;
        int imageHeight;
        if ((long) cellWidth * CAMERA_HEIGHT <= (long) cellHeight * CAMERA_WIDTH) {
            imageWidth = cellWidth;
            imageHeight = (int) ((long) cellWidth * CAMERA_HEIGHT / CAMERA_WIDTH);
        } else {
            imageHeight = cellHeight;
            imageWidth = (int) ((long) cellHeight * CAMERA_WIDTH / CAMERA_HEIGHT);
        }
        if (imageWidth == 0 || imageHeight == 0) return null;

        int left = (outputWidth - imageWidth * 2) / 2 + cell % 2 * imageWidth;
        int top = (outputHeight - imageHeight * 2) / 2 + cell / 2 * imageHeight;
        return new Rect(left, top, left + imageWidth, top + imageHeight);
    }

    public static Rect cameraRectForDirection(int direction, int[] cameraOrder) {
        return cameraRectForDirection(direction, cameraOrder, VIDEO_WIDTH, VIDEO_HEIGHT);
    }

    public static Rect cameraRectForDirection(int direction, int[] cameraOrder,
            int outputWidth, int outputHeight) {
        if (direction < 0 || direction >= 4 || !validOrder(cameraOrder)) return null;
        for (int cell = 0; cell < cameraOrder.length; cell++) {
            if (cameraOrder[cell] == direction)
                return cameraRectForCell(cell, outputWidth, outputHeight);
        }
        return null;
    }

    private static boolean validOrder(int[] order) {
        if (order == null || order.length != 4) return false;
        boolean[] seen = new boolean[4];
        for (int direction : order) {
            if (direction < 0 || direction >= seen.length || seen[direction]) return false;
            seen[direction] = true;
        }
        return true;
    }
}

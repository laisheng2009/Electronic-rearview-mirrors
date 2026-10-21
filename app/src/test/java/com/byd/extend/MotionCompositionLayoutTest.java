package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MotionCompositionLayoutTest {
    @Test public void selectedLiveCameraUsesFullOutputWithoutStretchOrCrop() {
        assertRect(MotionCompositionLayout.singleCameraRect(1280, 720), 108, 0, 1171, 720);
        assertRect(MotionCompositionLayout.singleCameraRect(720, 1280), 0, 396, 720, 883);
        assertNull(MotionCompositionLayout.singleCameraRect(0, 720));
    }

    @Test public void canonicalCellsAreCenteredAdjacentAndPreserveCameraAspect() {
        MotionCompositionLayout.Rect topLeft = MotionCompositionLayout.cameraRectForCell(0);
        MotionCompositionLayout.Rect topRight = MotionCompositionLayout.cameraRectForCell(1);
        MotionCompositionLayout.Rect bottomLeft = MotionCompositionLayout.cameraRectForCell(2);
        MotionCompositionLayout.Rect bottomRight = MotionCompositionLayout.cameraRectForCell(3);

        assertRect(topLeft, 163, 0, 960, 540);
        assertRect(topRight, 960, 0, 1757, 540);
        assertRect(bottomLeft, 163, 540, 960, 1080);
        assertRect(bottomRight, 960, 540, 1757, 1080);
        assertEquals(1920.0 / 1300.0, (double) topLeft.width() / topLeft.height(), 0.002);
    }

    @Test public void qualitySizesUseActualEncodedPixelDimensions() {
        MotionCompositionLayout.Rect p720 = MotionCompositionLayout.cameraRectForCell(0, 1280, 720);
        MotionCompositionLayout.Rect p1440 = MotionCompositionLayout.cameraRectForCell(0, 2560, 1440);

        assertRect(p720, 109, 0, 640, 360);
        assertRect(p1440, 217, 0, 1280, 720);
        assertEquals(1920.0 / 1300.0, (double) p720.width() / p720.height(), 0.002);
        assertEquals(1920.0 / 1300.0, (double) p1440.width() / p1440.height(), 0.002);
    }

    @Test public void narrowerOutputCentersTwoAdjacentCameraImagesVertically() {
        MotionCompositionLayout.Rect first = MotionCompositionLayout.cameraRectForCell(0, 1920, 1440);
        MotionCompositionLayout.Rect second = MotionCompositionLayout.cameraRectForCell(1, 1920, 1440);

        assertRect(first, 0, 70, 960, 720);
        assertRect(second, 960, 70, 1920, 720);
        assertEquals(1920.0 / 1300.0, (double) first.width() / first.height(), 0.0001);
    }

    @Test public void directionUsesSavedCellOrderAndRejectsUnknownOrder() {
        int[] order = {2, 0, 3, 1};

        assertRect(MotionCompositionLayout.cameraRectForDirection(0, order), 960, 0, 1757, 540);
        assertRect(MotionCompositionLayout.cameraRectForDirection(3, order), 163, 540, 960, 1080);
        assertNull(MotionCompositionLayout.cameraRectForDirection(0, null));
        assertNull(MotionCompositionLayout.cameraRectForDirection(0, new int[]{0, 1, 1, 3}));
        assertNull(MotionCompositionLayout.cameraRectForCell(4));
    }

    private static void assertRect(MotionCompositionLayout.Rect rect,
            int left, int top, int right, int bottom) {
        assertEquals(left, rect.left);
        assertEquals(top, rect.top);
        assertEquals(right, rect.right);
        assertEquals(bottom, rect.bottom);
    }
}

package com.byd.extend;

import java.nio.ByteBuffer;

/** Packs real YUV_420_888 plane views into the native library's NV12 byte layout. */
final class MotionYuvPacker {
    private static volatile boolean nativeLoaded;
    private MotionYuvPacker() {}

    static synchronized void loadNative(String libraryPath) {
        if (nativeLoaded) return;
        System.load(libraryPath);
        nativeLoaded = true;
    }

    static int outputSize(int width, int height) {
        requireEvenDimensions(width, height);
        long bytes = (long) width * height * 3L / 2L;
        if (bytes > Integer.MAX_VALUE) throw new IllegalArgumentException("frame too large");
        return (int) bytes;
    }

    static void pack(Plane y, Plane u, Plane v, int width, int height, byte[] output) {
        int expected = outputSize(width, height);
        if (output == null || output.length != expected)
            throw new IllegalArgumentException("NV12 output length mismatch");
        validate(y, width, height);
        validate(u, width / 2, height / 2);
        validate(v, width / 2, height / 2);

        if (nativeLoaded && y.buffer.isDirect() && u.buffer.isDirect() && v.buffer.isDirect()) {
            packDirect(y.buffer, y.buffer.position(), y.rowStride, y.pixelStride,
                    u.buffer, u.buffer.position(), u.rowStride, u.pixelStride,
                    v.buffer, v.buffer.position(), v.rowStride, v.pixelStride,
                    width, height, output);
            return;
        }
        copyPlane(y, width, height, output, 0, 1);
        int chromaOffset = width * height;
        for (int row = 0; row < height / 2; row++) {
            int out = chromaOffset + row * width;
            for (int col = 0; col < width / 2; col++) {
                output[out + col * 2] = sample(u, row, col);
                output[out + col * 2 + 1] = sample(v, row, col);
            }
        }
    }

    private static native void packDirect(
            ByteBuffer y, int yOffset, int yRow, int yPixel,
            ByteBuffer u, int uOffset, int uRow, int uPixel,
            ByteBuffer v, int vOffset, int vRow, int vPixel,
            int width, int height, byte[] output);

    private static void copyPlane(Plane plane, int width, int height, byte[] output,
            int outputOffset, int outputPixelStride) {
        if (plane.pixelStride == 1 && outputPixelStride == 1) {
            ByteBuffer source = plane.buffer.duplicate();
            if (plane.rowStride == width) {
                source.get(output, outputOffset, width * height);
            } else {
                int start = source.position();
                for (int row = 0; row < height; row++) {
                    source.position(start + row * plane.rowStride);
                    source.get(output, outputOffset + row * width, width);
                }
            }
            return;
        }
        for (int row = 0; row < height; row++) {
            int out = outputOffset + row * width * outputPixelStride;
            for (int col = 0; col < width; col++)
                output[out + col * outputPixelStride] = sample(plane, row, col);
        }
    }

    private static byte sample(Plane plane, int row, int col) {
        int offset = plane.buffer.position() + row * plane.rowStride + col * plane.pixelStride;
        return plane.buffer.get(offset);
    }

    private static void validate(Plane plane, int width, int height) {
        if (plane == null || plane.buffer == null || plane.rowStride <= 0 || plane.pixelStride <= 0)
            throw new IllegalArgumentException("missing YUV plane or invalid stride");
        long rowBytes = (long) (width - 1) * plane.pixelStride + 1L;
        long required = (long) (height - 1) * plane.rowStride + rowBytes;
        if (rowBytes > plane.rowStride || required > plane.buffer.remaining())
            throw new IllegalArgumentException("YUV plane is shorter than its dimensions/strides");
    }

    private static void requireEvenDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0)
            throw new IllegalArgumentException("YUV420 requires positive even dimensions");
    }

    static final class Plane {
        final ByteBuffer buffer;
        final int rowStride;
        final int pixelStride;

        Plane(ByteBuffer buffer, int rowStride, int pixelStride) {
            this.buffer = buffer;
            this.rowStride = rowStride;
            this.pixelStride = pixelStride;
        }
    }
}

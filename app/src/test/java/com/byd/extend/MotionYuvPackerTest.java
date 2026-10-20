package com.byd.extend;

import org.junit.Test;
import org.junit.BeforeClass;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MotionYuvPackerTest {
    @BeforeClass public static void loadRequestedNativeLibrary() {
        String library = System.getProperty("byd.motion.native");
        if (library != null) MotionYuvPacker.loadNative(library);
    }

    @Test public void packsNativeSizedIndependentPlanesWithExactFinalLimits() {
        for (int chromaStride : new int[]{1, 2, 3}) {
            int width = 3840, height = 2600, yRow = width + 8;
            int uvRow = (width / 2 - 1) * chromaStride + 5;
            ByteBuffer y = ByteBuffer.allocateDirect((height - 1) * yRow + width + 2);
            ByteBuffer u = ByteBuffer.allocateDirect((height / 2 - 1) * uvRow
                    + (width / 2 - 1) * chromaStride + 3);
            ByteBuffer v = ByteBuffer.allocateDirect(u.capacity());
            y.position(2); u.position(2); v.position(2);
            for (int row = 0; row < height; row++)
                for (int col = 0; col < width; col++) y.put(2 + row * yRow + col, (byte) (row + col));
            for (int row = 0; row < height / 2; row++) for (int col = 0; col < width / 2; col++) {
                u.put(2 + row * uvRow + col * chromaStride, (byte) (row + col * 3));
                v.put(2 + row * uvRow + col * chromaStride, (byte) (row * 7 - col));
            }
            byte[] packed = new byte[MotionYuvPacker.outputSize(width, height)];
            MotionYuvPacker.pack(new MotionYuvPacker.Plane(y.asReadOnlyBuffer(), yRow, 1),
                    new MotionYuvPacker.Plane(u.asReadOnlyBuffer(), uvRow, chromaStride),
                    new MotionYuvPacker.Plane(v.asReadOnlyBuffer(), uvRow, chromaStride),
                    width, height, packed);
            for (int row = 0; row < height; row++) for (int col = 0; col < width; col++)
                assertEquals((byte) (row + col), packed[row * width + col]);
            for (int row = 0; row < height / 2; row++) for (int col = 0; col < width / 2; col++) {
                assertEquals((byte) (row + col * 3), packed[width * height + row * width + col * 2]);
                assertEquals((byte) (row * 7 - col), packed[width * height + row * width + col * 2 + 1]);
            }
        }
    }

    @Test public void packsPaddedPlanesAndInterleavesUvNotVu() {
        // 4x2 luma uses row padding; chroma uses two-byte pixel spacing and its own row padding.
        ByteBuffer y = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 99, 5, 6, 7, 8});
        ByteBuffer u = ByteBuffer.wrap(new byte[]{10, 90, 11, 90, 99});
        ByteBuffer v = ByteBuffer.wrap(new byte[]{20, 90, 21, 90, 99});
        byte[] packed = new byte[MotionYuvPacker.outputSize(4, 2)];

        MotionYuvPacker.pack(new MotionYuvPacker.Plane(y, 5, 1),
                new MotionYuvPacker.Plane(u, 5, 2),
                new MotionYuvPacker.Plane(v, 5, 2), 4, 2, packed);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 10, 20, 11, 21}, packed);
        assertEquals(0, y.position());
        assertEquals(0, u.position());
        assertEquals(0, v.position());
    }

    @Test public void honorsPlaneBufferPositionAndRemaining() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{90, 1, 2, 3, 4, 90});
        y.position(1);
        y.limit(5);
        ByteBuffer u = ByteBuffer.wrap(new byte[]{90, 10, 90, 90});
        u.position(1);
        u.limit(3);
        ByteBuffer v = ByteBuffer.wrap(new byte[]{90, 20, 90, 90});
        v.position(1);
        v.limit(3);
        byte[] packed = new byte[6];

        MotionYuvPacker.pack(new MotionYuvPacker.Plane(y, 2, 1),
                new MotionYuvPacker.Plane(u, 1, 1),
                new MotionYuvPacker.Plane(v, 1, 1), 2, 2, packed);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 10, 20}, packed);
    }

    @Test public void packsDirectReadOnlySlicesWithoutChangingPositionsLimitsOrMarks() {
        for (boolean padded : new boolean[]{false, true}) {
            byte[] luma = padded ? new byte[]{1, 2, 3, 4, 99, 5, 6, 7, 8}
                    : new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
            ByteBuffer y = readOnlyDirectSlice(luma);
            ByteBuffer u = readOnlyDirectSlice(new byte[]{10, 90, 11});
            ByteBuffer v = readOnlyDirectSlice(new byte[]{20, 90, 21});
            int[] limits = {y.limit(), u.limit(), v.limit()};
            byte[] packed = new byte[12];

            MotionYuvPacker.pack(new MotionYuvPacker.Plane(y, padded ? 5 : 4, 1),
                    new MotionYuvPacker.Plane(u, 3, 2),
                    new MotionYuvPacker.Plane(v, 3, 2), 4, 2, packed);

            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 10, 20, 11, 21}, packed);
            ByteBuffer[] sources = {y, u, v};
            for (int i = 0; i < sources.length; i++) {
                assertEquals(2, sources[i].position());
                assertEquals(limits[i], sources[i].limit());
                sources[i].reset();
                assertEquals(1, sources[i].position());
            }
        }
    }

    @Test public void preservesStridedLumaAndIndependentChromaPlanes() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{
                1, 90, 2, 90, 3, 90, 4, 99, 99, 5, 90, 6, 90, 7, 90, 8});
        byte[] packed = new byte[12];
        MotionYuvPacker.pack(new MotionYuvPacker.Plane(y, 9, 2),
                new MotionYuvPacker.Plane(ByteBuffer.wrap(new byte[]{10, 11}), 2, 1),
                new MotionYuvPacker.Plane(ByteBuffer.wrap(new byte[]{20, 21}), 2, 1),
                4, 2, packed);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 10, 20, 11, 21}, packed);
    }

    @Test public void rejectsInvalidPlanesAndOutputBeforeWritingAnyBytes() {
        for (int invalid = 0; invalid < 4; invalid++) {
            ByteBuffer y = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
            ByteBuffer u = ByteBuffer.wrap(new byte[]{10});
            ByteBuffer v = ByteBuffer.wrap(new byte[]{20});
            if (invalid == 0) y.limit(3);
            if (invalid == 1) u.limit(0);
            if (invalid == 2) v.limit(0);
            byte[] packed = new byte[invalid == 3 ? 7 : 6];
            Arrays.fill(packed, (byte) 99);
            byte[] untouched = packed.clone();
            try {
                MotionYuvPacker.pack(new MotionYuvPacker.Plane(y, 2, 1),
                        new MotionYuvPacker.Plane(u, 1, 1),
                        new MotionYuvPacker.Plane(v, 1, 1), 2, 2, packed);
                fail("invalid input was accepted: " + invalid);
            } catch (IllegalArgumentException expected) {
                assertArrayEquals(untouched, packed);
            }
        }
    }

    private static ByteBuffer readOnlyDirectSlice(byte[] pixels) {
        ByteBuffer backing = ByteBuffer.allocateDirect(pixels.length + 8);
        backing.position(3);
        ByteBuffer slice = backing.slice();
        slice.position(2);
        slice.put(pixels);
        slice.flip();
        ByteBuffer source = slice.asReadOnlyBuffer();
        source.position(1);
        source.mark();
        source.position(2);
        return source;
    }
}

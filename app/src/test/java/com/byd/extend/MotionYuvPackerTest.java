package com.byd.extend;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MotionYuvPackerTest {
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

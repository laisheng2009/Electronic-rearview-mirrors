package com.byd.extend;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class MotionYuvPackerTest {
    @Test public void packsPaddedPlanesAndInterleavesUvNotVu() {
        // 4x2 luma uses row padding; chroma uses two-byte pixel spacing and its own row padding.
        ByteBuffer y = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 99, 5, 6, 7, 8, 99});
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

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortPlaneRatherThanReadingPastIt() {
        MotionYuvPacker.pack(new MotionYuvPacker.Plane(ByteBuffer.wrap(new byte[]{1}), 2, 1),
                new MotionYuvPacker.Plane(ByteBuffer.wrap(new byte[]{2}), 1, 1),
                new MotionYuvPacker.Plane(ByteBuffer.wrap(new byte[]{3}), 1, 1), 2, 2,
                new byte[6]);
    }
}

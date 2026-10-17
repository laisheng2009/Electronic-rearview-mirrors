package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class MotionDirectionIconTest {
    @Test
    public void mapsUiOrderToOemAssetsAndPanoPreviewIndexes() {
        int[] icons = {
                R.drawable.motion_ic_front_view,
                R.drawable.motion_ic_behind_view,
                R.drawable.motion_ic_left_view,
                R.drawable.motion_ic_right_view
        };
        int[] sourceIndexes = {4, 1, 2, 3};
        for (int direction = 0; direction < 4; direction++) {
            assertEquals(icons[direction], MotionDirectionIcon.drawableFor(direction));
            assertEquals(sourceIndexes[direction], MotionDirectionIcon.sourceIndexFor(direction));
        }
    }

    @Test
    public void rejectsDirectionsOutsideUiOrder() {
        assertThrows(IllegalArgumentException.class,
                () -> MotionDirectionIcon.drawableFor(-1));
        assertThrows(IllegalArgumentException.class,
                () -> MotionDirectionIcon.sourceIndexFor(4));
    }
}

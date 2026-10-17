package com.byd.extend;

import androidx.annotation.DrawableRes;

/** Shared direction-to-OEM-asset mapping for the editor and encoded Motion composition. */
public final class MotionDirectionIcon {
    private MotionDirectionIcon() {}

    @DrawableRes
    public static int drawableFor(int direction) {
        switch (direction) {
            case 0: return R.drawable.motion_ic_front_view;
            case 1: return R.drawable.motion_ic_behind_view;
            case 2: return R.drawable.motion_ic_left_view;
            case 3: return R.drawable.motion_ic_right_view;
            default: throw new IllegalArgumentException("Motion direction must be in range 0..3");
        }
    }

    /** Maps UI/recording order front, rear, left, right to pano_h preview indexes. */
    public static int sourceIndexFor(int direction) {
        switch (direction) {
            case 0: return 4;
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            default: throw new IllegalArgumentException("Motion direction must be in range 0..3");
        }
    }
}

package com.byd.extend;

/** Small, JVM-testable policy for preserving the shared OEM NAV stream. */
final class AvasNavVolumePolicy {
    interface IntRead { int get() throws Exception; }
    interface BoolRead { boolean get() throws Exception; }
    interface IntWrite { void set(int value) throws Exception; }
    interface BoolWrite { void set(boolean value) throws Exception; }
    interface Action { void run() throws Exception; }

    private AvasNavVolumePolicy() {}

    static int captureAndCap(BoolRead mute, IntRead lastAudible, IntRead current,
            IntWrite saveMute, IntWrite saveVolume, IntWrite volume) throws Exception {
        boolean muted = mute.get();
        int saved;
        try {
            saved = lastAudible.get();
            if (saved < 0) throw new IllegalStateException("Invalid last-audible NAV volume");
        } catch (Exception unavailable) {
            if (muted) throw new IllegalStateException("Cannot preserve muted NAV volume", unavailable);
            saved = current.get();
            if (saved < 0) throw new IllegalStateException("Invalid NAV volume");
        }
        // A volume write can unmute, so persist both original values before it.
        saveMute.set(muted ? 1 : 0);
        saveVolume.set(saved);
        cap(volume, current);
        return saved;
    }

    static void cap(IntWrite volume, IntRead current) throws Exception {
        volume.set(1);
        if (current.get() != 1) throw new IllegalStateException("Exterior NAV cap was not applied");
    }

    static void restoreVolume(int saved, IntWrite write, Action clear) throws Exception {
        if (saved < 0) return;
        write.set(saved);
        clear.run();
    }

    static void restoreMute(int saved, BoolRead current, BoolWrite write, Action clear)
            throws Exception {
        if (saved < 0) return;
        boolean expected = saved == 1;
        if (current.get() != expected) write.set(expected);
        clear.run();
    }

    static void restore(int savedVolume, int savedMute,
            IntWrite volumeWrite, Action clearVolume,
            BoolRead currentMute, BoolWrite muteWrite, Action clearMute) throws Exception {
        // setStreamVolume may unmute, so never consume the mute marker first.
        restoreVolume(savedVolume, volumeWrite, clearVolume);
        restoreMute(savedMute, currentMute, muteWrite, clearMute);
    }
}

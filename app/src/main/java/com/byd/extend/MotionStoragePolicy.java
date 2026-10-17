package com.byd.extend;

final class MotionStoragePolicy {
    static final long GB = 1_000_000_000L;

    static final class Parts {
        final long other;
        final long own;
        final long allowance;
        final long unallocated;
        final long oem;
        final long oemAllowance;

        Parts(long other, long own, long allowance, long unallocated, long oem, long oemAllowance) {
            this.other = other;
            this.own = own;
            this.allowance = allowance;
            this.unallocated = unallocated;
            this.oem = oem;
            this.oemAllowance = oemAllowance;
        }

        long free() { return allowance + oemAllowance + unallocated; }
        long total() { return other + own + oem + free(); }
    }

    private MotionStoragePolicy() { }

    static Parts calculate(long total, long available, long ownArchive, long ownLimit,
            boolean recordingHere, Long keepFree, long oemArchive, Long oemLimit) {
        long capacity = Math.max(0, total);
        long free = clamp(available, 0, capacity);
        long own = clamp(ownArchive, 0, capacity - free);
        long oem = clamp(oemArchive, 0, capacity - free - own);
        long writable = Math.max(0, free - clamp(keepFree == null ? 0 : keepFree, 0, capacity));
        long ownRemaining = recordingHere ? Math.max(0, clamp(ownLimit, 0, capacity) - own) : 0;
        long oemRemaining = oemLimit == null ? 0 : Math.max(0, clamp(oemLimit, 0, capacity) - oem);
        boolean shared = ownRemaining > writable - oemRemaining;
        long allowance = ownRemaining;
        long oemAllowance = oemRemaining;
        if (shared) {
            long combined = ownRemaining + oemRemaining;
            allowance = combined == 0 ? 0 : (long) (writable * (ownRemaining / (double) combined));
            allowance = clamp(allowance, Math.max(0, writable - oemRemaining), Math.min(ownRemaining, writable));
            oemAllowance = writable - allowance;
        }
        return new Parts(capacity - free - own - oem, own, allowance,
                free - allowance - oemAllowance, oem, oemAllowance);
    }

    private static long clamp(long value, long low, long high) {
        return Math.max(low, Math.min(high, value));
    }
}

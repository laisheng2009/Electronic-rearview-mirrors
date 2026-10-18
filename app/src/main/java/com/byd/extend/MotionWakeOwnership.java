package com.byd.extend;

/** A single Motion wake may preserve only the same live OFF-state camera producer. */
final class MotionWakeOwnership {
    private String session;
    private int producerEpoch;
    private long expiresAt;
    private int retainedProducerEpoch;

    void arm(String session, int producerEpoch, long nowMs) {
        if (session == null || producerEpoch <= 0) throw new IllegalArgumentException("wake owner missing");
        this.session = session;
        this.producerEpoch = producerEpoch;
        expiresAt = nowMs + 10_000L;
    }

    boolean consume(String session, int producerEpoch, boolean powerOff,
            boolean quickboot, long nowMs) {
        boolean matches = this.session != null && this.session.equals(session)
                && this.producerEpoch == producerEpoch && powerOff && !quickboot
                && nowMs <= expiresAt;
        clearPending();
        retainedProducerEpoch = matches ? producerEpoch : 0;
        return matches;
    }

    boolean takeRetainedForPowerOn(int producerEpoch) {
        boolean matches = retainedProducerEpoch > 0 && retainedProducerEpoch == producerEpoch;
        clear();
        return matches;
    }

    void clearPending() { session = null; expiresAt = 0; }
    void clear() { clearPending(); retainedProducerEpoch = 0; }
}

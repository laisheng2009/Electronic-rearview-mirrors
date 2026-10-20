package com.byd.extend;

/** A single Motion wake may preserve only the same live OFF-state camera producer. */
final class MotionWakeOwnership {
    private String session;
    private int producerEpoch;
    private long expiresAt;
    private String retainedSession;
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
        retainedSession = matches ? session : null;
        retainedProducerEpoch = matches ? producerEpoch : 0;
        return matches;
    }

    /** Rebinds only this session's live wake identity; the original deadline is unchanged. */
    boolean rebindProducerEpoch(String session, int oldEpoch, int newEpoch) {
        if (session == null || oldEpoch <= 0 || newEpoch <= 0) return false;
        boolean rebound = false;
        if (this.session != null && this.session.equals(session)
                && producerEpoch == oldEpoch) {
            producerEpoch = newEpoch;
            rebound = true;
        }
        if (retainedSession != null && retainedSession.equals(session)
                && retainedProducerEpoch == oldEpoch) {
            retainedProducerEpoch = newEpoch;
            rebound = true;
        }
        return rebound;
    }

    boolean takeRetainedForPowerOn(int producerEpoch) {
        boolean matches = retainedProducerEpoch > 0 && retainedProducerEpoch == producerEpoch;
        clear();
        return matches;
    }

    void clearPending() { session = null; producerEpoch = 0; expiresAt = 0; }
    void clear() { clearPending(); retainedSession = null; retainedProducerEpoch = 0; }
}

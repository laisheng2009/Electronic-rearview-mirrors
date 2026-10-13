package com.byd.extend;

final class TurnSignalListenerRecovery {
    static final int STALK = 1;
    static final int BLINK = 2;

    private int invalidSignals;
    private boolean validPollRequired;
    private boolean neutralRequired;

    boolean invalidate(int signal) {
        boolean newlyInvalid = (invalidSignals & signal) == 0;
        invalidSignals |= signal;
        validPollRequired = true;
        neutralRequired = true;
        return newlyInvalid;
    }

    void validCallback(int signal) {
        invalidSignals &= ~signal;
    }

    boolean ready() {
        return invalidSignals == 0 && !validPollRequired;
    }

    boolean acceptStalkObservation() {
        return !neutralRequired;
    }

    void reset() {
        invalidSignals = 0;
        validPollRequired = false;
        neutralRequired = false;
    }
}

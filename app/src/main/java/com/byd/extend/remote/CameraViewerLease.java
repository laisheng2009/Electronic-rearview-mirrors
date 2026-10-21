package com.byd.extend.remote;

/** One camera consumer across all authorized transports, held through source cleanup. */
public final class CameraViewerLease {
    private Object owner;
    public synchronized boolean acquire(Object candidate) {
        if (candidate == null) throw new IllegalArgumentException("owner required");
        if (owner != null && owner != candidate) return false;
        owner = candidate;
        return true;
    }
    public synchronized void release(Object candidate) { if (owner == candidate) owner = null; }
}

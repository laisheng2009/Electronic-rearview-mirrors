package com.byd.extend.remote;

/** Global authenticated viewer ownership; resource handover is serialized by the runtime. */
public final class CameraViewerLease {
    private Object owner;
    public synchronized boolean acquire(Object candidate) {
        if (candidate == null) throw new IllegalArgumentException("owner required");
        if (owner != null && owner != candidate) return false;
        owner = candidate;
        return true;
    }
    public synchronized void release(Object candidate) { if (owner == candidate) owner = null; }
    public synchronized Object replace(Object authenticated) {
        if (authenticated == null) throw new IllegalArgumentException("owner required");
        Object previous = owner;
        owner = authenticated;
        return previous;
    }
    public synchronized boolean owns(Object candidate) { return candidate != null && owner == candidate; }
}

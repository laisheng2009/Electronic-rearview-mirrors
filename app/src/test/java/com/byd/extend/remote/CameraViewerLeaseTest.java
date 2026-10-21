package com.byd.extend.remote;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CameraViewerLeaseTest {
    @Test public void replacementKeepsNewOwnerWhenOldCleanupArrives() {
        CameraViewerLease lease = new CameraViewerLease();
        Object previous = new Object(), next = new Object();
        lease.replace(previous);
        org.junit.Assert.assertSame(previous, lease.replace(next));
        lease.release(previous);
        assertTrue(lease.owns(next));
        assertFalse(lease.owns(previous));
        lease.release(next);
        assertFalse(lease.owns(next));
    }

    @Test
    public void leaseIsExclusiveAndWrongOwnerCannotReleaseIt() {
        CameraViewerLease lease = new CameraViewerLease();
        Object first = new Object();
        Object wrongOwner = new Object();
        Object nextOwner = new Object();

        assertTrue(lease.acquire(first));
        assertTrue(lease.acquire(first));
        assertFalse(lease.acquire(wrongOwner));

        lease.release(wrongOwner);
        assertFalse(lease.acquire(nextOwner));

        lease.release(first);
        assertTrue(lease.acquire(nextOwner));
    }
}

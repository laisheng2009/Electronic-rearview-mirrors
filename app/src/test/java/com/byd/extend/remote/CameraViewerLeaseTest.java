package com.byd.extend.remote;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CameraViewerLeaseTest {
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

package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class MotionVideoRecorderSurfaceLeaseTest {
    @Test(timeout = 3_000)
    public void retainRacingFinalizationIsEitherLeasedOrRejected() throws Exception {
        MotionVideoRecorder.InputSurfaceLease lease = new MotionVideoRecorder.InputSurfaceLease();
        lease.resetForNextRecording();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicBoolean retained = new AtomicBoolean();
        AtomicBoolean releaseRequired = new AtomicBoolean();
        Thread acquirer = new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                lease.retain();
                retained.set(true);
            } catch (IllegalStateException releaseAlreadyStarted) {
                retained.set(false);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        acquirer.setDaemon(true);
        Thread finalizer = new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                releaseRequired.set(lease.beginRelease());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        finalizer.setDaemon(true);
        try {
            acquirer.start();
            finalizer.start();
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertTrue("successful retain must be visible to finalization",
                    retained.get() == releaseRequired.get());
        } finally {
            start.countDown();
            lease.acknowledgeRelease();
            joinWorker(acquirer);
            joinWorker(finalizer);
        }

        MotionVideoRecorder.InputSurfaceLease unretained = new MotionVideoRecorder.InputSurfaceLease();
        assertFalse(unretained.beginRelease());
        unretained.awaitReleaseAcknowledged();
    }

    @Test(timeout = 3_000)
    public void interruptCannotReleaseSurfaceBeforeOwnerAcknowledges() throws Exception {
        MotionVideoRecorder.InputSurfaceLease lease = new MotionVideoRecorder.InputSurfaceLease();
        lease.resetForNextRecording();
        lease.retain();
        assertTrue(lease.beginRelease());
        try {
            lease.retain();
            fail("release-started lease must reject acquisition");
        } catch (IllegalStateException expected) { }

        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread finalizer = new Thread(() -> {
            waiting.countDown();
            lease.awaitReleaseAcknowledged();
            interruptRestored.set(Thread.currentThread().isInterrupted());
            complete.countDown();
        });
        finalizer.setDaemon(true);
        try {
            finalizer.start();
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            finalizer.interrupt();
            assertFalse(complete.await(100, TimeUnit.MILLISECONDS));
            assertTrue(finalizer.isAlive());

            lease.acknowledgeRelease();
            assertTrue(complete.await(1, TimeUnit.SECONDS));
            assertTrue(interruptRestored.get());
        } finally {
            lease.acknowledgeRelease();
            joinWorker(finalizer);
        }
    }

    private static void joinWorker(Thread worker) {
        try { worker.join(1_000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}

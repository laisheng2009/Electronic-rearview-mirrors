package com.byd.extend;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ReverseCameraControllerCleanupTest {
    @Test
    public void asyncFailureRetriesSameRequestAndFinishesExactlyOnce() {
        Harness harness = new Harness(false, true);

        harness.coordinator.startCameraThenWindow();
        assertEquals(Arrays.asList(47), harness.closeRequestIds);
        Consumer<Boolean> oldWindowCallback = harness.windowCallbacks.get(0);

        oldWindowCallback.accept(false);
        assertEquals(1, harness.retryEvents);
        Runnable scheduledRetry = harness.retry;
        assertTrue(scheduledRetry != null);

        scheduledRetry.run();
        harness.windowCallbacks.get(1).accept(true);

        assertEquals(Arrays.asList(47, 47), harness.closeRequestIds);
        assertEquals(2, harness.coordinator.closeAttempts());
        assertTrue(harness.coordinator.cameraClosed());
        assertEquals(1, harness.priorityReleases);
        assertEquals(1, harness.stoppedEvents);
        assertNull(harness.active[0]);

        oldWindowCallback.accept(true);
        scheduledRetry.run();
        assertEquals(1, harness.priorityReleases);
        assertEquals(1, harness.stoppedEvents);
        assertEquals(Arrays.asList(47, 47), harness.closeRequestIds);
    }

    private static final class Harness {
        final ReverseCameraController.CleanupCloseCoordinator[] active =
                new ReverseCameraController.CleanupCloseCoordinator[1];
        final List<Integer> closeRequestIds = new ArrayList<>();
        final List<Consumer<Boolean>> windowCallbacks = new ArrayList<>();
        final List<Object> cameraResults;
        final ReverseCameraController.CleanupCloseCoordinator coordinator;
        Runnable retry;
        int retryEvents;
        int priorityReleases;
        int stoppedEvents;

        Harness(Object... cameraResults) {
            this.cameraResults = new ArrayList<>(Arrays.asList(cameraResults));
            coordinator = new ReverseCameraController.CleanupCloseCoordinator(
                    47,
                    candidate -> active[0] == candidate,
                    requestId -> {
                        closeRequestIds.add(requestId);
                        Object result = this.cameraResults.remove(0);
                        if (result instanceof Throwable) throw (Throwable) result;
                        return (Boolean) result;
                    },
                    windowCallbacks::add,
                    task -> retry = task,
                    () -> retry = null,
                    ignored -> retryEvents++,
                    ignored -> {},
                    ignored -> {},
                    state -> {
                        assertSame(active[0], state);
                        active[0] = null;
                        priorityReleases++;
                        stoppedEvents++;
                    });
            active[0] = coordinator;
        }
    }
}

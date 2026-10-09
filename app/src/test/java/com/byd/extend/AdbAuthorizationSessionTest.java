package com.byd.extend;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class AdbAuthorizationSessionTest {
    @Test public void timeoutOrDenialKeepsBudgetUntilNewProcessButManualCanRetry() {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        try (AdbAuthorizationSession.Attempt ignored = process.begin()) {
            try (AdbAuthorizationSession.PromptReservation first =
                         process.reservePrompt("key", false)) {
                assertNotNull(first);
                assertTrue(first.markWriteStarted());
            }
        }
        try (AdbAuthorizationSession.Attempt recreatedActivity = process.begin()) {
            assertNull(process.reservePrompt("key", false));
            try (AdbAuthorizationSession.PromptReservation manual =
                         process.reservePrompt("key", true)) {
                assertNotNull(manual);
                assertTrue(manual.markWriteStarted());
            }
            assertNull(process.reservePrompt("key", false));
        }
        try (AdbAuthorizationSession.PromptReservation freshProcess =
                     new AdbAuthorizationSession().reservePrompt("key", false)) {
            assertNotNull(freshProcess);
        }
    }

    @Test public void unsentReservationRollsBackButWriteStartConsumesBudget() {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        AdbAuthorizationSession.PromptReservation unsent = process.reservePrompt("key", false);
        assertNotNull(unsent);
        assertNull(process.reservePrompt("key", false));
        unsent.close();
        try (AdbAuthorizationSession.PromptReservation retry =
                     process.reservePrompt("key", false)) {
            assertNotNull(retry);
            assertTrue(retry.markWriteStarted());
            assertFalse(retry.markWriteStarted());
        }
        assertNull(process.reservePrompt("key", false));
        try (AdbAuthorizationSession.PromptReservation force =
                     process.reservePrompt("key", true)) {
            assertNotNull(force);
            assertTrue(force.markWriteStarted());
        }
        assertNull(process.reservePrompt("key", false));
    }

    @Test public void foregroundRequestCoalescesAndSnapshotTracksLifecycle() throws Exception {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        AdbAuthorizationSession.Submission first = process.requestForeground(
                LocalAdbClient.PromptMode.AUTO_ONCE, null, (requestId, mode, token) -> {
                    started.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return LocalAdbClient.Result.ok("done", 0, "key", false);
                });
        assertEquals(AdbAuthorizationSession.Disposition.ACCEPTED, first.disposition);
        assertEquals(AdbAuthorizationSession.RequestState.QUEUED, first.snapshot.state);
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertEquals(AdbAuthorizationSession.RequestState.RUNNING,
                process.foregroundSnapshot().state);
        process.addForegroundListener(() -> {
            AdbAuthorizationSession.RequestSnapshot snapshot = process.foregroundSnapshot();
            if (snapshot != null && snapshot.state == AdbAuthorizationSession.RequestState.TERMINAL) {
                terminal.countDown();
            }
        });

        AdbAuthorizationSession.Submission duplicate = process.requestForeground(
                LocalAdbClient.PromptMode.AUTO_ONCE, null,
                (requestId, mode, token) -> { fail("coalesced work must not run"); return null; });
        assertEquals(AdbAuthorizationSession.Disposition.COALESCED, duplicate.disposition);
        assertEquals(first.snapshot.requestId, duplicate.snapshot.requestId);
        assertTrue(process.pending());

        release.countDown();
        assertTrue(terminal.await(10, TimeUnit.SECONDS));
        AdbAuthorizationSession.RequestSnapshot completed = process.foregroundSnapshot();
        assertEquals(AdbAuthorizationSession.RequestState.TERMINAL, completed.state);
        assertEquals("done", completed.result.output);
        assertNull(completed.error);
        assertEquals(AdbAuthorizationSession.RequestState.QUEUED, first.snapshot.state);
        assertFalse(process.pending());
        try (AdbAuthorizationSession.RecoveryOperation recovery = process.beginRecovery()) {
            assertNotNull(recovery);
        }
    }

    @Test public void forceSupersedesAutoWithoutStaleCompletionOrPriorityGap() throws Exception {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        CountDownLatch autoStarted = new CountDownLatch(1);
        CountDownLatch releaseAuto = new CountDownLatch(1);
        CountDownLatch forceStarted = new CountDownLatch(1);
        CountDownLatch releaseForce = new CountDownLatch(1);
        CountDownLatch forceTerminal = new CountDownLatch(1);
        AtomicBoolean cancelledAuto = new AtomicBoolean();
        AtomicReference<Long> autoToken = new AtomicReference<>();
        process.addForegroundListener(() -> {
            AdbAuthorizationSession.RequestSnapshot snapshot = process.foregroundSnapshot();
            if (snapshot != null && snapshot.mode == LocalAdbClient.PromptMode.FORCE
                    && snapshot.state == AdbAuthorizationSession.RequestState.TERMINAL) {
                forceTerminal.countDown();
            }
        });

        AdbAuthorizationSession.Submission automatic = process.requestForeground(
                LocalAdbClient.PromptMode.AUTO_ONCE, LocalAdbClient::cancelPendingAuthorization,
                (requestId, mode, token) -> {
                    autoToken.set(token);
                    autoStarted.countDown();
                    assertTrue(releaseAuto.await(10, TimeUnit.SECONDS));
                    return LocalAdbClient.Result.failed("stale-auto", "", -1, "key");
                });
        assertTrue(autoStarted.await(10, TimeUnit.SECONDS));

        AdbAuthorizationSession.Submission forced = process.requestForeground(
                LocalAdbClient.PromptMode.FORCE, LocalAdbClient::cancelPendingAuthorization,
                (requestId, mode, token) -> {
                    assertNotEquals(autoToken.get().longValue(), token);
                    assertTrue(LocalAdbClient.isCancellationTokenCurrent(token));
                    forceStarted.countDown();
                    assertTrue(releaseForce.await(10, TimeUnit.SECONDS));
                    return LocalAdbClient.Result.ok("force", 0, "key", false);
                });
        cancelledAuto.set(!LocalAdbClient.isCancellationTokenCurrent(autoToken.get()));
        releaseAuto.countDown();
        assertEquals(AdbAuthorizationSession.Disposition.REPLACED_AUTO, forced.disposition);
        assertTrue(cancelledAuto.get());
        assertTrue(forceStarted.await(10, TimeUnit.SECONDS));
        assertTrue(process.pending());

        AdbAuthorizationSession.Submission duplicateForce = process.requestForeground(
                LocalAdbClient.PromptMode.FORCE, LocalAdbClient::cancelPendingAuthorization,
                (requestId, mode, token) -> { fail("coalesced work must not run"); return null; });
        assertEquals(AdbAuthorizationSession.Disposition.COALESCED, duplicateForce.disposition);
        assertEquals(forced.snapshot.requestId, duplicateForce.snapshot.requestId);
        AdbAuthorizationSession.Submission lowerPriority = process.requestForeground(
                LocalAdbClient.PromptMode.AUTO_ONCE, null,
                (requestId, mode, token) -> { fail("AUTO must coalesce with FORCE"); return null; });
        assertEquals(AdbAuthorizationSession.Disposition.COALESCED, lowerPriority.disposition);

        releaseForce.countDown();
        assertTrue(forceTerminal.await(10, TimeUnit.SECONDS));
        AdbAuthorizationSession.RequestSnapshot finalSnapshot = process.foregroundSnapshot();
        assertEquals(forced.snapshot.requestId, finalSnapshot.requestId);
        assertEquals("force", finalSnapshot.result.output);
        assertFalse(process.pending());
        assertTrue(automatic.snapshot.requestId < forced.snapshot.requestId);
    }

    @Test public void explicitForegroundCancellationAllowsReopenWithoutStaleCompletion() throws Exception {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch noLongerPending = new CountDownLatch(1);
        CountDownLatch reopenedTerminal = new CountDownLatch(1);
        AtomicReference<Long> token = new AtomicReference<>();
        process.addListener(() -> {
            if (!process.pending()) noLongerPending.countDown();
        });
        process.addForegroundListener(() -> {
            AdbAuthorizationSession.RequestSnapshot snapshot = process.foregroundSnapshot();
            if (snapshot != null && snapshot.state == AdbAuthorizationSession.RequestState.TERMINAL
                    && snapshot.result != null && "reopened".equals(snapshot.result.output)) {
                reopenedTerminal.countDown();
            }
        });

        AdbAuthorizationSession.Submission cancelledRequest = process.requestForeground(
                LocalAdbClient.PromptMode.AUTO_ONCE,
                LocalAdbClient::cancelPendingAuthorization, (requestId, mode, requestToken) -> {
                    token.set(requestToken);
                    started.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return LocalAdbClient.Result.ok("late", 0, "key", false);
                });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        process.cancelForeground();
        assertNull(process.foregroundSnapshot());
        assertFalse(LocalAdbClient.isCancellationTokenCurrent(token.get()));
        assertTrue(process.pending());

        AdbAuthorizationSession.Submission reopened = process.requestForeground(
                LocalAdbClient.PromptMode.AUTO_ONCE, LocalAdbClient::cancelPendingAuthorization,
                (requestId, mode, requestToken) -> {
                    assertTrue(LocalAdbClient.isCancellationTokenCurrent(requestToken));
                    return LocalAdbClient.Result.ok("reopened", 0, "key", false);
                });
        assertEquals(AdbAuthorizationSession.Disposition.ACCEPTED, reopened.disposition);
        assertTrue(cancelledRequest.snapshot.requestId < reopened.snapshot.requestId);
        assertTrue(process.pending());
        release.countDown();
        assertTrue(reopenedTerminal.await(10, TimeUnit.SECONDS));
        assertTrue(noLongerPending.await(10, TimeUnit.SECONDS));
        assertEquals(reopened.snapshot.requestId, process.foregroundSnapshot().requestId);
        assertEquals("reopened", process.foregroundSnapshot().result.output);
        assertFalse(process.pending());
    }

    @Test public void rejectedWorkerAdmissionDoesNotConsumeStartupOrPriority() throws Exception {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        java.lang.reflect.Field field = AdbAuthorizationSession.class.getDeclaredField("foregroundExecutor");
        field.setAccessible(true);
        ((ExecutorService) field.get(process)).shutdown();
        try {
            process.requestForeground(LocalAdbClient.PromptMode.AUTO_ONCE, null,
                    (requestId, mode, token) -> { throw new AssertionError("not admitted"); });
            fail("stopped executor accepted work");
        } catch (java.util.concurrent.RejectedExecutionException expected) {
            assertNull(process.foregroundSnapshot());
            assertFalse(process.pending());
        }
    }

    @Test public void manualFirstConsumesAutomaticBudgetAndNestedRequestsStayPending() {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        AdbAuthorizationSession.Attempt queued = process.begin();
        AdbAuthorizationSession.Attempt executing = process.begin();
        try (AdbAuthorizationSession.PromptReservation manual =
                     process.reservePrompt("key", true)) {
            assertNotNull(manual);
            assertTrue(manual.markWriteStarted());
        }
        assertNull(process.reservePrompt("key", false));
        queued.close(); queued.close();
        assertTrue(process.pending());
        assertNull(process.beginRecovery());
        executing.close();
        assertFalse(process.pending());
        try (AdbAuthorizationSession.RecoveryOperation recovery = process.beginRecovery()) {
            assertNotNull(recovery);
        }
    }

    @Test public void acceptedRsaCancelsExistingRecoveryAndRejectsQueuedWrites() throws Exception {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean written = new AtomicBoolean();
        try (AdbAuthorizationSession.RecoveryOperation recovery = process.beginRecovery()) {
            recovery.register(() -> closed.set(true));
            try (AdbAuthorizationSession.Attempt rsa = process.begin()) {
                assertTrue(closed.get());
                assertTrue(recovery.cancelled());
                try { recovery.write(() -> written.set(true)); fail(); }
                catch (IOException expected) { }
                AtomicBoolean lateClosed = new AtomicBoolean();
                try { recovery.register(() -> lateClosed.set(true)); fail(); }
                catch (IOException expected) { }
                assertTrue(lateClosed.get());
            }
            try { recovery.write(() -> written.set(true)); fail(); }
            catch (IOException expected) { }
            assertFalse(written.get());
        }
    }

    @Test public void cancelledRecoveryCannotSendTcpipEvenAfterRsaCompletes() throws Exception {
        AdbAuthorizationSession process = new AdbAuthorizationSession();
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        try (AdbAuthorizationSession.RecoveryOperation recovery = process.beginRecovery()) {
            process.begin().close();
            try {
                LocalAdbTlsClient.requestTcpip5555(new ByteArrayInputStream(new byte[0]), sent, recovery);
                fail();
            } catch (IOException expected) { }
            assertEquals(0, sent.size());
        }
    }

    @Test public void concurrentKeyCallersReceiveOnePersistedIdentity() throws Exception {
        Path directory = Files.createTempDirectory("byd-adb-keys-");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<KeyPair>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return LocalAdbClient.loadOrCreateKeys(directory.toFile());
            }));
            start.countDown();
            KeyPair first = results.get(0).get(20, TimeUnit.SECONDS);
            for (Future<KeyPair> future : results) {
                KeyPair pair = future.get(20, TimeUnit.SECONDS);
                assertArrayEquals(first.getPrivate().getEncoded(), pair.getPrivate().getEncoded());
                assertArrayEquals(first.getPublic().getEncoded(), pair.getPublic().getEncoded());
            }
            assertArrayEquals(first.getPrivate().getEncoded(), Files.readAllBytes(directory.resolve("adb_key.priv")));
            assertArrayEquals(first.getPublic().getEncoded(), Files.readAllBytes(directory.resolve("adb_key.pub")));
            KeyPair existing = LocalAdbClient.loadOrCreateKeys(directory.toFile());
            assertArrayEquals(first.getPrivate().getEncoded(), existing.getPrivate().getEncoded());
            Files.write(directory.resolve("adb_key.priv"), new byte[]{1, 2, 3});
            try { LocalAdbClient.loadOrCreateKeys(directory.toFile()); fail(); }
            catch (IOException expected) { }
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(directory.resolve("adb_key.priv")));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            Files.deleteIfExists(directory.resolve("adb_key.pub"));
            Files.deleteIfExists(directory.resolve("adb_key.priv"));
            Files.delete(directory);
        }
    }
}

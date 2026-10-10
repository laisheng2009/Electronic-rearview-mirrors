package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Operation-local cancellation must not cancel unrelated ADB requests. */
public final class LocalAdbStreamingTest {
    @Test
    public void exportCancellationClosesOnlyItsRegisteredSocket() throws Exception {
        CompatibilityBundleExporter.ExportControl control =
                new CompatibilityBundleExporter.ExportControl();
        final boolean[] closed = {false};
        Socket socket = new Socket() {
            @Override public synchronized void close() throws IOException {
                closed[0] = true;
            }
        };
        long generation = LocalAdbClient.cancellationToken();
        control.registerActiveSocket(socket);
        control.cancel();
        assertTrue(closed[0]);
        assertTrue(LocalAdbClient.isCancellationTokenCurrent(generation));
    }

    @Test
    public void blockedExportSinkDoesNotBlockManualAuthorization() throws Exception {
        Path filesDir = Files.createTempDirectory("byd-adb-stream-auth-");
        ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        ExecutorService peers = Executors.newCachedThreadPool();
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        CountDownLatch publicKeyReceived = new CountDownLatch(1);
        AtomicBoolean publicKeyEvent = new AtomicBoolean();
        LocalAdbClient.setStorageForTest(filesDir.toFile(), new TestSharedPreferences());
        LocalAdbClient.setSocketFactoryForTest(() -> new Socket(
                InetAddress.getLoopbackAddress(), server.getLocalPort()));
        try {
            Future<?> streamPeer = peers.submit(() -> { serveBlockedStream(server); return null; });
            Future<LocalAdbClient.Result> stream = peers.submit(() ->
                    LocalAdbClient.executeAuthorizedStreaming(null, "true",
                            blockedOutput(sinkEntered, releaseSink), 16_384,
                            5_000, null, null));
            assertTrue("export did not reach its output sink",
                    sinkEntered.await(5, TimeUnit.SECONDS));

            Future<?> authorizationPeer = peers.submit(() -> {
                serveAuthorization(server, publicKeyReceived, new CountDownLatch(0)); return null;
            });
            Future<LocalAdbClient.Result> authorization = peers.submit(() ->
                    LocalAdbClient.authorize(null, LocalAdbClient.PromptMode.FORCE,
                            (event, fields) -> {
                                if (fields.length > 1 && "stage".equals(fields[0])) {
                                    String stage = String.valueOf(fields[1]);
                                    if ("public_key_sent".equals(stage)) {
                                        publicKeyEvent.set(true);
                                    }
                                }
                            }));

            LocalAdbClient.Result authorizationResult = authorization.get(5, TimeUnit.SECONDS);
            assertTrue(authorizationResult.error, authorizationResult.ok);
            assertTrue(authorizationResult.publicKeySent);
            assertTrue(publicKeyReceived.await(1, TimeUnit.SECONDS));
            assertTrue(publicKeyEvent.get());
            assertEquals(0L, sinkEntered.getCount());

            releaseSink.countDown();
            assertFalse(stream.get(5, TimeUnit.SECONDS).ok);
            streamPeer.get(5, TimeUnit.SECONDS);
            authorizationPeer.get(5, TimeUnit.SECONDS);
        } finally {
            releaseSink.countDown();
            server.close();
            peers.shutdownNow();
            boolean stopped = peers.awaitTermination(5, TimeUnit.SECONDS);
            LocalAdbClient.setSocketFactoryForTest(null);
            LocalAdbClient.setStorageForTest(null, null);
            deleteTestFiles(filesDir);
            assertTrue(stopped);
        }
    }

    @Test
    public void lateOlderAuthorizationFailureDoesNotReplaceNewerCnxnEvidence() throws Exception {
        Path filesDir = Files.createTempDirectory("byd-adb-state-generation-");
        ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        ExecutorService peers = Executors.newCachedThreadPool();
        CountDownLatch olderConnected = new CountDownLatch(1);
        CountDownLatch releaseOlder = new CountDownLatch(1);
        LocalAdbClient.setStorageForTest(filesDir.toFile(), new TestSharedPreferences());
        LocalAdbClient.setSocketFactoryForTest(() -> new Socket(
                InetAddress.getLoopbackAddress(), server.getLocalPort()));
        try {
            Future<?> olderPeer = peers.submit(() -> {
                serveDelayedAuthorizationFailure(server, olderConnected, releaseOlder); return null;
            });
            Future<LocalAdbClient.Result> older = peers.submit(() ->
                    LocalAdbClient.executeAuthorizedStreaming(null, "true",
                            new ByteArrayOutputStream(), 16_384, 5_000, null, null));
            assertTrue("older operation did not connect", olderConnected.await(5, TimeUnit.SECONDS));

            Future<?> newerPeer = peers.submit(() -> { serveAcceptedConnection(server); return null; });
            Future<LocalAdbClient.Result> newer = peers.submit(() ->
                    LocalAdbClient.executeAuthorizedStreaming(null, "true",
                            new ByteArrayOutputStream(), 16_384, 5_000, null, null));
            newer.get(5, TimeUnit.SECONDS);
            assertEquals(LocalAdbClient.AccessState.Status.OK,
                    LocalAdbClient.readAccessState(null).status);

            releaseOlder.countDown();
            LocalAdbClient.Result olderResult = older.get(5, TimeUnit.SECONDS);
            assertTrue(olderResult.authorizationRequired);
            assertFalse(olderResult.publicKeySent);
            olderPeer.get(5, TimeUnit.SECONDS);
            newerPeer.get(5, TimeUnit.SECONDS);
            assertEquals(LocalAdbClient.AccessState.Status.OK,
                    LocalAdbClient.readAccessState(null).status);
        } finally {
            releaseOlder.countDown();
            server.close();
            peers.shutdownNow();
            boolean stopped = peers.awaitTermination(5, TimeUnit.SECONDS);
            LocalAdbClient.setSocketFactoryForTest(null);
            LocalAdbClient.setStorageForTest(null, null);
            deleteTestFiles(filesDir);
            assertTrue(stopped);
        }
    }

    @Test
    public void activeForegroundPromptDefersNewBackgroundProbe() throws Exception {
        Path filesDir = Files.createTempDirectory("byd-adb-prompt-proof-");
        ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        ExecutorService peers = Executors.newCachedThreadPool();
        CountDownLatch publicKeyReceived = new CountDownLatch(1);
        CountDownLatch acceptPrompt = new CountDownLatch(1);
        LocalAdbClient.setStorageForTest(filesDir.toFile(), new TestSharedPreferences());
        LocalAdbClient.setSocketFactoryForTest(() -> new Socket(
                InetAddress.getLoopbackAddress(), server.getLocalPort()));
        try {
            Future<?> promptPeer = peers.submit(() -> {
                serveAuthorization(server, publicKeyReceived, acceptPrompt); return null;
            });
            Future<LocalAdbClient.Result> prompt = peers.submit(() -> LocalAdbClient.authorize(
                    null, LocalAdbClient.PromptMode.FORCE, null));
            assertTrue(publicKeyReceived.await(5, TimeUnit.SECONDS));
            LocalAdbClient.Result probe = LocalAdbClient.executeAuthorizedStreaming(null, "true",
                    new ByteArrayOutputStream(), 16_384, 5_000, null, null);
            assertTrue(probe.superseded);
            assertFalse(probe.publicKeySent);
            assertEquals(LocalAdbClient.AccessState.Status.UNKNOWN, LocalAdbClient.readAccessState(null).status);

            acceptPrompt.countDown();
            LocalAdbClient.Result accepted = prompt.get(5, TimeUnit.SECONDS);
            assertTrue(accepted.error, accepted.ok);
            assertTrue(LocalAdbClient.hasAuthorizedIdentity(null));
            assertEquals(LocalAdbClient.AccessState.Status.OK, LocalAdbClient.readAccessState(null).status);
            promptPeer.get(5, TimeUnit.SECONDS);
            server.setSoTimeout(100);
            org.junit.Assert.assertThrows(java.net.SocketTimeoutException.class, server::accept);
        } finally {
            acceptPrompt.countDown();
            server.close();
            peers.shutdownNow();
            boolean stopped = peers.awaitTermination(5, TimeUnit.SECONDS);
            LocalAdbClient.setSocketFactoryForTest(null);
            LocalAdbClient.setStorageForTest(null, null);
            deleteTestFiles(filesDir);
            assertTrue(stopped);
        }
    }

    @Test
    public void disconnectAfterPublicKeyKeepsSendEvidenceAndFingerprint() throws Exception {
        Path filesDir = Files.createTempDirectory("byd-adb-sent-disconnect-");
        ExecutorService peers = Executors.newSingleThreadExecutor();
        LocalAdbClient.setStorageForTest(filesDir.toFile(), new TestSharedPreferences());
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            LocalAdbClient.setSocketFactoryForTest(() -> new Socket(
                    InetAddress.getLoopbackAddress(), server.getLocalPort()));
            Future<?> peer = peers.submit(() -> {
                try (Socket socket = server.accept()) {
                    AdbPacket.read(socket.getInputStream());
                    writeToken(socket);
                    assertEquals(AdbPacket.AUTH_SIGNATURE, AdbPacket.read(socket.getInputStream()).arg0);
                    writeToken(socket);
                    assertEquals(AdbPacket.AUTH_RSAPUBLICKEY, AdbPacket.read(socket.getInputStream()).arg0);
                }
                return null;
            });
            LocalAdbClient.Result result = LocalAdbClient.authorize(
                    null, LocalAdbClient.PromptMode.FORCE, null);
            peer.get(5, TimeUnit.SECONDS);
            assertFalse(result.ok);
            assertTrue(result.publicKeySent);
            assertTrue(result.publicKeyWriteStarted);
            assertEquals(LocalAdbClient.readAccessState(null).fingerprint, result.fingerprint);
            assertFalse("unavailable".equals(result.fingerprint));
        } finally {
            peers.shutdownNow();
            LocalAdbClient.setSocketFactoryForTest(null);
            LocalAdbClient.setStorageForTest(null, null);
            deleteTestFiles(filesDir);
        }
    }

    @Test
    public void cancellationClosesSocketBeforeFirstAuthToken() throws Exception {
        Path filesDir = Files.createTempDirectory("byd-adb-early-cancel-");
        ExecutorService peers = Executors.newCachedThreadPool();
        CountDownLatch connected = new CountDownLatch(1);
        LocalAdbClient.setStorageForTest(filesDir.toFile(), new TestSharedPreferences());
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            LocalAdbClient.setSocketFactoryForTest(() -> new Socket(
                    InetAddress.getLoopbackAddress(), server.getLocalPort()));
            Future<Integer> peer = peers.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    AdbPacket.read(socket.getInputStream());
                    connected.countDown();
                    return socket.getInputStream().read();
                }
            });
            Future<LocalAdbClient.Result> result = peers.submit(() -> LocalAdbClient.authorize(
                    null, LocalAdbClient.PromptMode.FORCE, null));
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertTrue(LocalAdbClient.cancelPendingAuthorization());
            assertTrue(result.get(3, TimeUnit.SECONDS).superseded);
            assertEquals(-1, peer.get(3, TimeUnit.SECONDS).intValue());
        } finally {
            peers.shutdownNow();
            LocalAdbClient.setSocketFactoryForTest(null);
            LocalAdbClient.setStorageForTest(null, null);
            deleteTestFiles(filesDir);
        }
    }

    private static void serveBlockedStream(ServerSocket server) throws Exception {
        try (Socket socket = server.accept()) {
            assertCommand(AdbPacket.A_CNXN, AdbPacket.read(socket.getInputStream()).command);
            writeCnxn(socket);
            AdbPacket open = AdbPacket.read(socket.getInputStream());
            assertCommand(AdbPacket.A_OPEN, open.command);
            int localId = open.arg0;
            AdbPacket.write(socket.getOutputStream(), AdbPacket.A_OKAY, 2, localId, null);
            byte[] data = new byte[4096];
            AdbPacket.write(socket.getOutputStream(), AdbPacket.A_WRTE, 2, localId, data);
            // Foreground authorization preempts this blocked background stream immediately.
            socket.setSoTimeout(3000);
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    private static void serveAuthorization(ServerSocket server,
            CountDownLatch publicKeyReceived, CountDownLatch acceptPrompt) throws Exception {
        try (Socket socket = server.accept()) {
            assertCommand(AdbPacket.A_CNXN, AdbPacket.read(socket.getInputStream()).command);
            writeToken(socket);
            AdbPacket signature = AdbPacket.read(socket.getInputStream());
            assertEquals(AdbPacket.A_AUTH, signature.command);
            assertEquals(AdbPacket.AUTH_SIGNATURE, signature.arg0);
            writeToken(socket);
            AdbPacket publicKey = AdbPacket.read(socket.getInputStream());
            assertEquals(AdbPacket.A_AUTH, publicKey.command);
            assertEquals(AdbPacket.AUTH_RSAPUBLICKEY, publicKey.arg0);
            publicKeyReceived.countDown();
            assertTrue(acceptPrompt.await(5, TimeUnit.SECONDS));
            writeCnxn(socket);
        }
    }

    private static void serveDelayedAuthorizationFailure(ServerSocket server,
            CountDownLatch connected, CountDownLatch release) throws Exception {
        try (Socket socket = server.accept()) {
            assertCommand(AdbPacket.A_CNXN, AdbPacket.read(socket.getInputStream()).command);
            connected.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            writeToken(socket);
            AdbPacket signature = AdbPacket.read(socket.getInputStream());
            assertEquals(AdbPacket.AUTH_SIGNATURE, signature.arg0);
            writeToken(socket);
        }
    }

    private static void serveAcceptedConnection(ServerSocket server) throws Exception {
        try (Socket socket = server.accept()) {
            assertCommand(AdbPacket.A_CNXN, AdbPacket.read(socket.getInputStream()).command);
            writeCnxn(socket);
            AdbPacket open = AdbPacket.read(socket.getInputStream());
            assertCommand(AdbPacket.A_OPEN, open.command);
            AdbPacket.write(socket.getOutputStream(), AdbPacket.A_OKAY, 2, open.arg0, null);
            AdbPacket.write(socket.getOutputStream(), AdbPacket.A_CLSE, 2, open.arg0, null);
            assertCommand(AdbPacket.A_CLSE, AdbPacket.read(socket.getInputStream()).command);
        }
    }

    private static OutputStream blockedOutput(CountDownLatch entered, CountDownLatch release) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException {
                block(entered, release);
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                block(entered, release);
            }
        };
    }

    private static void block(CountDownLatch entered, CountDownLatch release) throws IOException {
        entered.countDown();
        try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("sink timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("sink interrupted", interrupted);
        }
    }

    private static void writeToken(Socket socket) throws IOException {
        AdbPacket.write(socket.getOutputStream(), AdbPacket.A_AUTH,
                AdbPacket.AUTH_TOKEN, 0, new byte[20]);
    }

    private static void writeCnxn(Socket socket) throws IOException {
        AdbPacket.write(socket.getOutputStream(), AdbPacket.A_CNXN,
                AdbPacket.VERSION, AdbPacket.MAX_DATA, new byte[]{'d', 'e', 'v', 'i', 'c', 'e', 0});
    }

    private static void assertCommand(int expected, int actual) {
        assertEquals(expected, actual);
    }

    private static void deleteTestFiles(Path filesDir) throws IOException {
        Path keys = filesDir.resolve("adb_keys");
        for (String name : new String[]{
                "adb_key.priv", "adb_key.priv.new", "adb_key.priv.bak",
                "adb_key.pub", "adb_key.pub.new", "adb_key.pub.bak"}) {
            Files.deleteIfExists(keys.resolve(name));
        }
        Files.deleteIfExists(keys);
        Files.deleteIfExists(filesDir);
    }
}

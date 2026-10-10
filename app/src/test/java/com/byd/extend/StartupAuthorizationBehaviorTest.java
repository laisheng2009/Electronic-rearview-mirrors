package com.byd.extend;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import com.byd.extend.ui.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowSettings;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, qualifiers = "w1600dp-h1000dp-mdpi", shadows = {
        AndroidBehaviorHarness.CameraBoundary.class, AndroidBehaviorHarness.AudioAssetsBoundary.class,
        StartupAuthorizationBehaviorTest.FeatureBootstrapBoundary.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class StartupAuthorizationBehaviorTest {
    private static Looper serviceLooper;
    @Test public void startupOrdersWindowsAndManualRetryOwnsRsaAndReadback() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AndroidBehaviorHarness.settings(app);
        ShadowSettings.setCanDrawOverlays(false);
        android.content.pm.ResolveInfo overlayTarget = new android.content.pm.ResolveInfo();
        overlayTarget.activityInfo = new android.content.pm.ActivityInfo();
        overlayTarget.activityInfo.packageName = "com.android.settings";
        overlayTarget.activityInfo.name = "OverlayPermissionActivity";
        Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + app.getPackageName())), overlayTarget);
        String foreign = "other.app/other.app.Accessibility";
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, foreign);
        PeerFactory peers = new PeerFactory(app);
        LocalAdbClient.setSocketFactoryForTest(peers::open);
        var service = Robolectric.buildService(CameraHelperService.class).create();
        serviceLooper = ((Handler) AndroidBehaviorHarness.field(service.get(), "runtimeHandler")).getLooper();
        var screen = Robolectric.buildActivity(CameraProbeActivity.class).setup().visible();
        List<org.robolectric.android.controller.ActivityController<CameraProbeActivity>> screens = new ArrayList<>();
        screens.add(screen);
        CameraProbeActivity activity = screen.get();
        ProductionUiController ui = (ProductionUiController) AndroidBehaviorHarness.field(activity, "productionUi");
        try {
            screen.windowFocusChanged(true);
            tick(700);
            assertEquals(DialogKind.Background, ui.getState().getDialog().getKind());
            assertTrue("no RSA request behind the initial modal", peers.all.isEmpty());
            assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
            ui.dispatch(new BydExtendUiAction.Run(CommandId.ConfirmDialog));
            assertEquals("com.byd.appstartmanagement",
                    Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getPackageName());
            screen.windowFocusChanged(false).pause();
            tick(1000);
            assertTrue("system settings must not hide RSA", peers.all.isEmpty());
            screen.resume().windowFocusChanged(true);
            tick(600);
            awaitPrompt(peers, 1);
            Peer abandoned = peers.all.get(0);
            assertFalse(AppPermissionProvisioner.hasWriteSecureSettings(app));
            assertTrue(peers.shell.isEmpty());
            assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());

            activity.requestAdbAuthorization("test_manual", "test", false);
            awaitPrompt(peers, 2);
            assertTrue("manual retry must close the previous socket", abandoned.closed);
            Peer current = peers.all.get(1);
            long requestId = LocalAdbClient.AUTHORIZATION.foregroundSnapshot().requestId;
            current.approve();
            pumpUntil(() -> peers.grantStarted.getCount() == 0);
            assertFalse(AppPermissionProvisioner.hasWriteSecureSettings(app));
            Intent premature = Shadows.shadowOf(activity).peekNextStartedActivity();
            boolean overlayOvertookReadback = premature != null
                    && Settings.ACTION_MANAGE_OVERLAY_PERMISSION.equals(premature.getAction());
            peers.allowGrants.countDown();
            pumpUntil(() -> AppPermissionProvisioner.hasWriteSecureSettings(app)
                    && AppPermissionProvisioner.hasAccessibilityAccess(app) && AvasNotificationAccess.isGranted(app));
            assertEquals(requestId, LocalAdbClient.AUTHORIZATION.foregroundSnapshot().requestId);
            assertTrue(LocalAdbClient.AUTHORIZATION.foregroundSnapshot().result.ok);
            assertTrue(LocalAdbClient.hasAuthorizedIdentity(app));
            assertTrue(Settings.Secure.getString(app.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(foreign));
            assertEquals(0L, Settings.Global.getLong(app.getContentResolver(), "adb_allowed_connection_time", -1));
            assertTrue(peers.shell.stream().anyMatch(s -> s.contains("WRITE_SECURE_SETTINGS")));
            Intent overlay = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("overlay is the final startup settings screen", overlay);
            assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.getAction());
            int prompts = peers.promptCount();
            byte[] identity = LocalAdbClient.loadOrCreateKeys(app).getPublic().getEncoded();
            LocalAdbClient.Result repeat = LocalAdbClient.authorize(app, LocalAdbClient.PromptMode.FORCE, null);
            assertTrue(repeat.error, repeat.ok);
            assertEquals(prompts, peers.promptCount());
            assertArrayEquals(identity, LocalAdbClient.loadOrCreateKeys(app).getPublic().getEncoded());

            screen.windowFocusChanged(false).pause().stop();
            for (boolean timeout : new boolean[]{true, false}) {
                peers.trusted = false;
                peers.timeout = timeout;
                peers.reject = !timeout;
                int grants = peers.shell.size();
                var failed = Robolectric.buildActivity(CameraProbeActivity.class).create().start();
                screens.add(failed);
                // A manual request queued while the window is inactive must resume on focus.
                assertTrue(failed.get().requestAdbAuthorization("test_manual", "test_failure", false));
                failed.resume().visible().windowFocusChanged(true);
                pumpUntil(() -> LocalAdbClient.AUTHORIZATION.foregroundSnapshot() != null
                        && !LocalAdbClient.AUTHORIZATION.foregroundSnapshot().isActive());
                LocalAdbClient.Result result = LocalAdbClient.AUTHORIZATION.foregroundSnapshot().result;
                assertNotNull(result);
                assertFalse(result.ok);
                assertTrue(result.publicKeySent);
                assertFalse(LocalAdbClient.hasAuthorizedIdentity(app));
                assertEquals("failed RSA must not provision permissions", grants, peers.shell.size());
                Intent next = Shadows.shadowOf(failed.get()).getNextStartedActivity();
                assertNotNull("failure must still release the overlay step", next);
                assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, next.getAction());
                failed.windowFocusChanged(false).pause().stop();
            }
            // Known production race, deliberately active until the separately deferred fix lands.
            assertFalse("overlay must not overtake permission readback", overlayOvertookReadback);
        } finally {
            peers.allowGrants.countDown();
            LocalAdbClient.AUTHORIZATION.cancelForeground();
            peers.all.forEach(Peer::close);
            for (var owner : screens) owner.destroy();
            service.destroy();
            LocalAdbClient.setSocketFactoryForTest(null);
        }
    }

    private static void tick(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
    private static void pumpUntil(java.util.function.BooleanSupplier done) throws Exception {
        AndroidBehaviorHarness.await("authorization/readback did not finish", () -> {
            Shadows.shadowOf(serviceLooper).idle();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            return done.getAsBoolean();
        });
        tick(20);
    }
    private static void awaitPrompt(PeerFactory peers, int count) throws Exception {
        pumpUntil(() -> peers.promptCount() >= count);
    }

    // A successful authorization may start cameras/weather; those unrelated OEM features are excluded.
    @Implements(CameraHelperService.class)
    public static class FeatureBootstrapBoundary extends org.robolectric.shadows.ShadowService {
        @Implementation protected void handleStartCommand(ServiceRuntimeCommand command) { }
        @Implementation protected void startForegroundRuntime() { }
    }

    static final class PeerFactory {
        final Context app;
        final List<Peer> all = new CopyOnWriteArrayList<>();
        final List<String> shell = new CopyOnWriteArrayList<>();
        final CountDownLatch grantStarted = new CountDownLatch(1), allowGrants = new CountDownLatch(1);
        volatile boolean trusted, timeout, reject;
        PeerFactory(Context app) { this.app = app; }
        Socket open() throws IOException {
            try {
                Peer peer = new Peer(this, LocalAdbClient.loadOrCreateKeys(app));
                all.add(peer);
                return peer;
            } catch (Exception error) { throw new IOException(error); }
        }
        int promptCount() { return (int) all.stream().filter(p -> p.prompted).count(); }
    }

    // No TCP endpoint: real persisted RSA identity, handshake and shell protocol over in-memory bytes.
    static final class Peer extends Socket {
        final PeerFactory owner;
        final KeyPair keys;
        final byte[] token = new byte[20];
        final ArrayDeque<Integer> input = new ArrayDeque<>();
        volatile boolean prompted, closed;
        Peer(PeerFactory owner, KeyPair keys) { this.owner = owner; this.keys = keys; Arrays.fill(token, (byte) 42); }
        @Override public boolean isConnected() { return true; }
        @Override public void setSoTimeout(int timeout) { }
        @Override public void setTcpNoDelay(boolean enabled) { }
        @Override public synchronized void close() { closed = true; notifyAll(); }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    synchronized (Peer.this) {
                        while (input.isEmpty() && !closed) {
                            if (prompted && owner.timeout) throw new SocketTimeoutException("unaccepted RSA");
                            try { Peer.this.wait(5000); }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                        }
                        return input.isEmpty() ? -1 : input.remove();
                    }
                }
                @Override public int read(byte[] dst, int offset, int length) throws IOException {
                    int value = read();
                    if (value < 0) return -1;
                    dst[offset] = (byte) value;
                    int count = 1;
                    synchronized (Peer.this) {
                        while (count < length && !input.isEmpty()) dst[offset + count++] = (byte) (int) input.remove();
                    }
                    return count;
                }
            };
        }
        @Override public OutputStream getOutputStream() {
            return new ByteArrayOutputStream() {
                @Override public void flush() throws IOException {
                    AdbPacket packet = AdbPacket.read(new ByteArrayInputStream(toByteArray()));
                    reset();
                    try { receive(packet); } catch (Exception error) { throw new IOException(error); }
                }
            };
        }
        synchronized void send(int command, int arg0, int arg1, byte[] bytes) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AdbPacket.write(out, command, arg0, arg1, bytes);
            for (byte b : out.toByteArray()) input.add(b & 255);
            notifyAll();
        }
        void approve() throws IOException {
            owner.trusted = true;
            send(AdbPacket.A_CNXN, AdbPacket.VERSION, AdbPacket.MAX_DATA, new byte[0]);
        }
        void receive(AdbPacket p) throws Exception {
            if (p.command == AdbPacket.A_CNXN) send(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token);
            else if (p.command == AdbPacket.A_AUTH && p.arg0 == AdbPacket.AUTH_SIGNATURE) {
                Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsa.init(Cipher.DECRYPT_MODE, keys.getPublic());
                byte[] signed = rsa.doFinal(p.payload);
                assertArrayEquals(token, Arrays.copyOfRange(signed, signed.length - token.length, signed.length));
                if (owner.trusted) approve();
                else send(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token);
            } else if (p.command == AdbPacket.A_AUTH && p.arg0 == AdbPacket.AUTH_RSAPUBLICKEY) {
                assertEquals(AdbKeyFormatter.formatPublicKey((RSAPublicKey) keys.getPublic()),
                        new String(p.payload, StandardCharsets.UTF_8).replace("\0", ""));
                prompted = true;
                if (owner.reject) send(AdbPacket.A_AUTH, AdbPacket.AUTH_TOKEN, 0, token);
            } else if (p.command == AdbPacket.A_OPEN) {
                assertTrue("permission writes must follow accepted RSA", owner.trusted);
                String wire = new String(p.payload, StandardCharsets.UTF_8).replace("\0", "");
                String command = wire.substring(6, wire.indexOf("\necho "));
                owner.shell.add(command);
                execute(command);
                String marker = wire.substring(wire.indexOf("\necho ") + 6).replace("$?", "0");
                send(AdbPacket.A_OKAY, 1, p.arg0, new byte[0]);
                send(AdbPacket.A_WRTE, 1, p.arg0, ("\n" + marker + "\n").getBytes(StandardCharsets.UTF_8));
                send(AdbPacket.A_CLSE, 1, p.arg0, new byte[0]);
            }
        }
        void execute(String command) throws InterruptedException {
            Context app = owner.app;
            String[] words = command.replace("'", "").split(" ");
            if (command.startsWith("pm grant --user ")) {
                owner.grantStarted.countDown();
                assertTrue("test did not release permission writer", owner.allowGrants.await(5, TimeUnit.SECONDS));
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(words[words.length - 1]);
            } else if (command.startsWith("cmd notification allow_listener "))
                Shadows.shadowOf(app.getSystemService(NotificationManager.class))
                        .setNotificationListenerAccessGranted(ComponentName.unflattenFromString(words[3]), true);
            else if (command.startsWith("settings --user ") && command.contains(" put secure ")) {
                if (words[5].equals(Settings.Secure.ACCESSIBILITY_ENABLED))
                    Settings.Secure.putInt(app.getContentResolver(), words[5], Integer.parseInt(words[6]));
                else Settings.Secure.putString(app.getContentResolver(), words[5], words[6]);
            }
            else throw new AssertionError("Unexpected permission command: " + command);
        }
    }
}

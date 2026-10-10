package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.media.session.PlaybackState;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class MusicMediaKeyPolicyTest {
    @Test public void playerSelectionSurvivesNonPlayerWindowsAndChangesOnlyForCapableApp() {
        MusicMediaKeyPolicy.Selection selection = new MusicMediaKeyPolicy.Selection();
        selection.opened("com.example.radio", true);
        assertFalse(selection.needsSessionRefresh(true));
        assertTrue(selection.needsSessionRefresh(false));
        selection.opened("com.example.newplayer", false); // its session has not reached our cache yet
        assertTrue(selection.needsSessionRefresh(true)); // the OLD paused session cannot suppress refresh
        selection.opened("com.example.navigation", false);
        assertEquals("com.example.radio", selection.player);
        assertTrue(selection.mayUseReceiver("com.example.radio"));
        selection.opened("com.example.systemdialog", false);
        assertEquals("com.example.radio", selection.player);
        selection.opened("com.example.newplayer", true);
        assertEquals("com.example.newplayer", selection.player);
        assertTrue(selection.mayUseReceiver("com.example.newplayer"));
        selection.opened("com.example.navigation", false);
        assertTrue(selection.mayUseReceiver("com.example.newplayer"));
        assertFalse(selection.mayUseReceiver("com.example.radio"));
        selection.clear();
        assertEquals("", selection.player);
    }

    @Test public void transportCapabilityNotFocusQualifiesAndEachCommandMustBeSupported() {
        assertFalse(MusicMediaKeyPolicy.isPlayer(0));
        assertFalse(MusicMediaKeyPolicy.isPlayer(PlaybackState.ACTION_STOP));
        assertTrue(MusicMediaKeyPolicy.isPlayer(PlaybackState.ACTION_PLAY));
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_SKIP_TO_NEXT;
        assertTrue(MusicMediaKeyPolicy.supports(actions, 353));
        assertTrue(MusicMediaKeyPolicy.supports(actions, KeyEvent.KEYCODE_MEDIA_NEXT));
        assertFalse(MusicMediaKeyPolicy.supports(actions, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertFalse(MusicMediaKeyPolicy.supports(actions, KeyEvent.KEYCODE_MEDIA_RECORD));
    }
    @Test
    public void routesOnlyEnabledUnassignedInitialMediaDowns() {
        assertTrue(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, false, false, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 0));
        assertTrue(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, false, false, MusicMediaKeyPolicy.OEM_WHEEL_KEY,
                KeyEvent.ACTION_DOWN, 0));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                false, true, false, false, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 0));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, false, false, false, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 0));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, true, false, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 0));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, false, true, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 0));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, false, false, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.ACTION_DOWN, 1));
        assertFalse(MusicMediaKeyPolicy.shouldRouteInitialDown(
                true, true, false, false, KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.ACTION_DOWN, 0));
    }

    @Test
    public void wheelPressMapsToStandardPlayPauseAndShellPayloadIsBounded() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                MusicMediaKeyPolicy.standardKeyCode(MusicMediaKeyPolicy.OEM_WHEEL_KEY));
        assertTrue(MusicMediaKeyPolicy.supportsReceiverFallback(
                MusicMediaKeyPolicy.standardKeyCode(MusicMediaKeyPolicy.OEM_WHEEL_KEY)));
        assertFalse(MusicMediaKeyPolicy.supportsReceiverFallback(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertFalse(MusicMediaKeyPolicy.supportsReceiverFallback(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertFalse(MusicMediaKeyPolicy.supportsReceiverFallback(KeyEvent.KEYCODE_MEDIA_PLAY));
        assertFalse(MusicMediaKeyPolicy.supportsReceiverFallback(KeyEvent.KEYCODE_MEDIA_PAUSE));
        assertTrue(MusicMediaKeyPolicy.isValidCommand(
                "com.example.player", MusicMediaKeyPolicy.OEM_WHEEL_KEY,
                KeyEvent.ACTION_DOWN, 0, 100, 100));
        assertTrue(MusicMediaKeyPolicy.isValidCommand(
                "", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.ACTION_UP, 0, 100, 140));
        assertFalse(MusicMediaKeyPolicy.isValidCommand(
                "not a package", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.ACTION_DOWN, 0, 100, 100));
        assertFalse(MusicMediaKeyPolicy.isValidCommand(
                "com.example.player", KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.ACTION_DOWN, 0, 100, 100));
        assertFalse(MusicMediaKeyPolicy.isValidCommand(
                "com.example.player", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.ACTION_MULTIPLE, 0, 100, 100));
        assertFalse(MusicMediaKeyPolicy.isValidCommand(
                "com.example.player", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.ACTION_DOWN, 0, 100, 100 + MusicMediaKeyPolicy.MAX_KEY_HOLD_MS + 1));
    }

    @Test
    public void consumedDownOwnsOnlyItsMatchingUp() {
        MusicMediaKeyPolicy.RoutedKeys keys = new MusicMediaKeyPolicy.RoutedKeys();
        keys.begin(MusicMediaKeyPolicy.OEM_WHEEL_KEY, 42);

        assertTrue(keys.owns(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 42));
        assertFalse(keys.finish(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 43));
        assertTrue(keys.owns(MusicMediaKeyPolicy.OEM_WHEEL_KEY, 42));
        assertTrue(keys.finish(MusicMediaKeyPolicy.OEM_WHEEL_KEY, 42));
        assertFalse(keys.owns(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 42));
    }

    @Test
    public void receiverFallbackWaitsForOriginalUpAndSendsAtMostOnceAfterTimeout() {
        MusicMediaKeyPolicy.ReceiverFallback fallback = new MusicMediaKeyPolicy.ReceiverFallback(
                1_000, new HashMap<>());

        assertFalse(fallback.shouldDispatch(3_000));
        assertFalse(fallback.originalUp(1_500));
        assertFalse(fallback.shouldDispatch(2_999));
        assertTrue(fallback.markDispatched(3_000));
        assertFalse(fallback.markDispatched(3_001));
    }

    @Test
    public void receiverFallbackResponseJustBeforeTimeoutCancels() {
        Map<Object, Integer> baseline = new HashMap<>();
        baseline.put("session", 2);
        MusicMediaKeyPolicy.ReceiverFallback fallback =
                new MusicMediaKeyPolicy.ReceiverFallback(1_000, baseline);
        assertFalse(fallback.originalUp(1_500));

        assertFalse(fallback.shouldDispatch(2_999));
        assertTrue(fallback.observePlaybackState("session", 3));
        assertEquals("playback_state_change", fallback.cancelReason());
        assertFalse(fallback.shouldDispatch(3_000));
    }

    @Test
    public void receiverFallbackCancelsForLifecycleAndHeldKeyEvents() {
        for (String reason : new String[]{
                "new_media_press", "target_changed", "shutdown", "observer_error", "key_repeat"}) {
            MusicMediaKeyPolicy.ReceiverFallback fallback =
                    new MusicMediaKeyPolicy.ReceiverFallback(1_000, new HashMap<>());
            assertFalse(fallback.originalUp(1_500));

            assertTrue(reason, fallback.cancel(reason));
            assertEquals(reason, fallback.cancelReason());
            assertFalse(reason, fallback.shouldDispatch(3_000));
        }
        MusicMediaKeyPolicy.ReceiverFallback held =
                new MusicMediaKeyPolicy.ReceiverFallback(1_000, new HashMap<>());
        assertTrue(held.originalUp(3_000));
        assertEquals("key_held", held.cancelReason());
        assertFalse(held.shouldDispatch(3_001));
    }

    @Test
    public void receiverFallbackSessionAppearanceAndDestructionAreResponses() {
        Map<Object, Integer> baseline = new HashMap<>();
        baseline.put("existing", 2);
        MusicMediaKeyPolicy.ReceiverFallback appeared =
                new MusicMediaKeyPolicy.ReceiverFallback(1_000, baseline);
        Map<Object, Integer> withNewSession = new HashMap<>(baseline);
        withNewSession.put("new", 0);
        assertTrue(appeared.observeSessions(withNewSession));
        assertEquals("session_appeared", appeared.cancelReason());

        MusicMediaKeyPolicy.ReceiverFallback destroyed =
                new MusicMediaKeyPolicy.ReceiverFallback(1_000, baseline);
        assertTrue(destroyed.observeSessions(new HashMap<>()));
        assertEquals("session_destroyed", destroyed.cancelReason());
    }

    @Test
    public void receiverFallbackKeepsLivePlayerGateAheadOfExplicitReceiverLookup() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/MusicMetadataRuntime.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/MusicMetadataRuntime.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int start = text.indexOf("private boolean dispatchMediaKeyOnHandler(");
        int end = text.indexOf("private boolean mediaKeyDecision(", start);
        assertTrue("media-key dispatch method found", start >= 0 && end > start);
        String dispatch = text.substring(start, end);
        int liveGate = dispatch.indexOf("if (!isLiveMediaProcess(packageName, false))");
        int receiverLookup = dispatch.indexOf("ComponentName receiver = null;");
        int sessionPriorityGate = dispatch.indexOf("controlSession(packageName, 0) == null");
        int selectedPlayerGate = dispatch.indexOf("mediaSelection.mayUseReceiver(packageName)");
        assertTrue("closed/crashed selected player must be rejected before fallback",
                liveGate >= 0 && receiverLookup > liveGate);
        assertTrue("session-less explicit receiver fallback remains selected-player-only",
                sessionPriorityGate > receiverLookup && selectedPlayerGate > sessionPriorityGate);
    }

    @Test
    public void runtimeObservesCallbacksBeforeDebounceAndDispatchesOneHeadsetFallbackPair()
            throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/MusicMetadataRuntime.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/MusicMetadataRuntime.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int observation = text.indexOf("observeReceiverFallbackSessions(controllers);");
        int debounce = text.indexOf("eventReconciler.accept(\"sessions_callback\"");
        int fallback = text.indexOf("private void onReceiverFallbackTimeout()");
        int finalRefresh = text.indexOf("sessionManager.getActiveSessions(null)", fallback);
        int keyDown = text.indexOf("fallback, KeyEvent.ACTION_DOWN", finalRefresh);
        int keyUp = text.indexOf("fallback, KeyEvent.ACTION_UP", keyDown);
        assertTrue("only the normalized wheel play/pause key can start this fallback",
                text.contains("MusicMediaKeyPolicy.supportsReceiverFallback(keyCode)"));
        assertTrue("session response is observed before reconciliation can debounce",
                observation >= 0 && debounce > observation);
        assertTrue("fallback does a final active-session refresh before one 79 down/up pair",
                fallback >= 0 && finalRefresh > fallback && keyDown > finalRefresh && keyUp > keyDown
                        && text.contains("KeyEvent.KEYCODE_HEADSETHOOK"));
        assertTrue("broadcast dispatch is logged as enqueue only, without acknowledgement",
                text.contains("broadcast_enqueued_only") && text.contains("\"acknowledged\", false"));
    }
}

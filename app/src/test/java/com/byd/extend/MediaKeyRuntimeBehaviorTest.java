package com.byd.extend;

import android.app.ActivityManager;
import android.app.Application;
import android.content.*;
import android.content.pm.*;
import android.media.AudioManager;
import android.media.session.*;
import android.os.*;
import android.view.KeyEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = 29, application = Application.class, shadows = MediaKeyRuntimeBehaviorTest.FrameworkBoundary.class)
public class MediaKeyRuntimeBehaviorTest {
    @Implements(MusicMetadataRuntime.class)
    public static class FrameworkBoundary {
        // DiLink framework bootstrap is absent from the host; routing/observation remain real.
        @Implementation protected static void ensureMediaFrameworkInitialized() { }
    }

    @Test public void livePlayerFallbackIsCancelledBySessionResponse() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String player = "test.music.player";
        PackageInfo info = new PackageInfo();
        info.packageName = player;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = player;
        info.applicationInfo.enabled = true;
        shadowOf(app.getPackageManager()).installPackage(info);
        ActivityInfo receiver = new ActivityInfo();
        receiver.packageName = player;
        receiver.name = player + ".MediaReceiver";
        receiver.applicationInfo = info.applicationInfo;
        receiver.exported = receiver.enabled = true;
        ResolveInfo resolve = new ResolveInfo();
        resolve.activityInfo = receiver;
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(player), resolve);
        ActivityManager processes = app.getSystemService(ActivityManager.class);
        var running = new ActivityManager.RunningAppProcessInfo(player, 1234, new String[]{player});
        running.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        shadowOf(processes).setProcesses(List.of(running));
        MediaSessionManager sessions = app.getSystemService(MediaSessionManager.class);
        List<String> events = new ArrayList<>();
        MusicMetadataRuntime runtime = new MusicMetadataRuntime(app, new Handler(Looper.getMainLooper()),
                (kind, fields) -> events.add(kind + Arrays.toString(fields)),
                (reason, action) -> action.run(), oemAudio(), processes);
        AndroidBehaviorHarness.field(runtime, "mediaContext", app);
        try {
            runtime.configure(true);
            runtime.powerStateChanged(true, "test_wake");
            runtime.noteForegroundPackage(player);
            press(runtime, player, true);
            assertEquals(List.of(85, 85), keys(app));

            // A real framework session appears before the receiver fallback deadline.
            Context playerContext = app.createPackageContext(player, 0);
            MediaSession session = new MediaSession(playerContext, "test-player");
            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE).build());
            shadowOf(session.getController()).setPackageName(player);
            shadowOf(session.getController()).setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE).build());
            shadowOf(sessions).addController(session.getController());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            assertEquals("session response must cancel the 79 fallback", List.of(85, 85), keys(app));
            shadowOf(sessions).clearControllers();
            session.release();
            runtime.refreshSessions("session_closed");

            press(runtime, player, true);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            assertEquals(List.of(85, 85, 85, 85, 79, 79), keys(app));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            assertEquals("fallback is one complete key cycle", 6, keys(app).size());
            shadowOf(processes).setProcesses(Collections.emptyList());
            press(runtime, player, false);
            assertEquals(6, keys(app).size());
            assertTrue(events.toString(), events.stream().anyMatch(e -> e.contains("observed_response")));
        } catch (PackageManager.NameNotFoundException failure) {
            throw new AssertionError(failure);
        } finally { runtime.stop(); }
    }

    private static AudioManager oemAudio() throws Exception {
        // The SDK stub omits AudioManager(Context); this object supplies only the OEM focus getter.
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (AudioManager) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(field.get(null), android.media.BehaviorAudioManager.class);
    }

    private static void press(MusicMetadataRuntime runtime, String player, boolean accepted) {
        long down = SystemClock.uptimeMillis();
        assertEquals(accepted, runtime.dispatchMediaKey(player, 353, KeyEvent.ACTION_DOWN, 0, down, down));
        assertEquals(accepted, runtime.dispatchMediaKey(player, 353, KeyEvent.ACTION_UP, 0, down, down + 20));
    }

    private static List<Integer> keys(Application app) {
        List<Integer> result = new ArrayList<>();
        for (Intent intent : shadowOf(app).getBroadcastIntents()) {
            if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
                assertEquals(new ComponentName("test.music.player", "test.music.player.MediaReceiver"),
                        intent.getComponent());
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                assertEquals("each command must be a complete DOWN/UP cycle",
                        result.size() % 2 == 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, event.getAction());
                result.add(event.getKeyCode());
            }
        }
        return result;
    }
}

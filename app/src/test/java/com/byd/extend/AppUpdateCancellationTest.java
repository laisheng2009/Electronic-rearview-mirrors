package com.byd.extend;

import android.app.Application;
import android.app.DownloadManager;
import android.database.Cursor;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.OperationCanceledException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = 29, application = Application.class)
public final class AppUpdateCancellationTest {
    @Test public void cancelRemovesActualDownloadAndItsPartialFiles() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        CancellationSignal cancellation = new CancellationSignal();
        File source = new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "byd-extend-v9.0.0.apk");
        File staged = new File(app.getFilesDir(), "updates/byd-extend-v9.0.0.apk");
        staged.getParentFile().mkdirs();
        AppUpdateManager.UpdateInfo info = new AppUpdateManager.UpdateInfo("9.0.0",
                "https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/download/v9.0.0/byd-extend-v9.0.0.apk", "notes");
        assertThrows(OperationCanceledException.class, () -> new AppUpdateManager().downloadAndVerify(
                app, info, progress -> {
                    try {
                        Files.write(source.toPath(), new byte[]{1});
                        Files.write(staged.toPath(), new byte[]{2});
                    } catch (Exception error) { throw new AssertionError(error); }
                    cancellation.cancel();
                }, cancellation));
        assertFalse(source.exists());
        assertFalse(staged.exists());
        DownloadManager manager = app.getSystemService(DownloadManager.class);
        try (Cursor cursor = manager.query(new DownloadManager.Query())) {
            assertEquals(0, cursor.getCount());
        }
    }
}

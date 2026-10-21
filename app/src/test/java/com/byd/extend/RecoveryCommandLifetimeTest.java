package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class RecoveryCommandLifetimeTest {
    @Test public void terminatesAndReapsAnActualWaitingProcess() throws Exception {
        String executable = new File(System.getProperty("java.home"),
                "bin/java" + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""))
                .getAbsolutePath();
        String classes = new File(WaitingProcess.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getAbsolutePath();
        Process process = new ProcessBuilder(executable, "-cp", classes,
                WaitingProcess.class.getName()).redirectErrorStream(true).start();
        try {
            assertFalse(process.waitFor(250, TimeUnit.MILLISECONDS));
            assertTrue(AvasRecoveryShellMain.stopCommand(process));
            assertFalse(process.isAlive());
            assertTrue(AvasRecoveryShellMain.stopCommand(process));
        } finally {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    /** Real OS process lifetime only; does not substitute an Android broadcast/receiver outcome. */
    public static final class WaitingProcess {
        public static void main(String[] args) throws Exception {
            new java.util.concurrent.CountDownLatch(1).await();
        }
    }
}

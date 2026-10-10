package com.byd.extend;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ContinuousLogcatRecorderTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void restartsDeadReaderAppendsHistoryAndClearJoinsBeforeStartingFresh() throws Exception {
        File directory = temporary.newFolder();
        File log = new File(directory, "logcat.txt");
        java.util.concurrent.BlockingQueue<FakeProcess> children = new java.util.concurrent.LinkedBlockingQueue<>();
        List<List<String>> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
        ContinuousLogcatRecorder recorder = new ContinuousLogcatRecorder(directory, "boot-a", "pid=test", null,
                command -> {
                    commands.add(command);
                    FakeProcess child = new FakeProcess();
                    children.add(child);
                    return child;
                });
        try {
            recorder.start();
            FakeProcess first = children.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(first);
            first.send("before_restart\n");
            awaitText(log, "before_restart");
            first.destroy();
            FakeProcess second = children.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(second);
            second.send("after_restart\n");
            awaitText(log, "after_restart");
            assertTrue(readText(log).contains("before_restart"));
            assertEquals(1, recorder.clear());
            assertFalse(second.isAlive());
            FakeProcess third = children.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(third);
            third.send("after_clear\n");
            awaitText(log, "after_clear");
            String fresh = readText(log);
            assertFalse(fresh.contains("before_restart"));
            assertFalse(fresh.contains("after_restart"));
            assertFalse(commands.get(0).contains("-T"));
            assertTrue(commands.get(2).contains("-T"));
            recorder.close();
            assertFalse(third.isAlive());
        } finally { recorder.close(); }
    }

    private static void awaitText(File file, String expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (file.isFile() && readText(file).contains(expected)) return;
            Thread.sleep(10L);
        }
        fail("Recorder did not retain " + expected);
    }

    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final class FakeProcess extends java.lang.Process {
        final java.io.PipedInputStream input;
        final java.io.PipedOutputStream output;
        volatile boolean alive = true;
        FakeProcess() {
            try {
                input = new java.io.PipedInputStream();
                output = new java.io.PipedOutputStream(input);
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        void send(String value) throws IOException { output.write(value.getBytes(StandardCharsets.UTF_8)); }
        @Override public java.io.InputStream getInputStream() { return input; }
        @Override public java.io.InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        @Override public boolean isAlive() { return alive; }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public int waitFor() throws InterruptedException { while (alive) Thread.sleep(10L); return 0; }
        @Override public void destroy() {
            alive = false;
            try { output.close(); } catch (IOException ignored) {}
        }
    }
}

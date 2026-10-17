package com.byd.extend;

import android.content.Context;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Cross-process active-writer marker used to distinguish a live segment from crash recovery. */
final class MotionMediaLease implements Closeable {
    private final RandomAccessFile file;
    private final FileChannel channel;
    private final FileLock lock;

    private MotionMediaLease(RandomAccessFile file, FileChannel channel, FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    static MotionMediaLease acquire(Context context, String uri) throws IOException {
        File path = lockPath(context, uri);
        File parent = path.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()))
            throw new IOException("Cannot create Motion recording lease directory");
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        FileChannel channel = file.getChannel();
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Motion segment is already active");
            return new MotionMediaLease(file, channel, lock);
        } catch (OverlappingFileLockException active) {
            try { channel.close(); } catch (IOException ignored) { }
            try { file.close(); } catch (IOException ignored) { }
            throw new IOException("Motion segment is already active", active);
        } catch (IOException failure) {
            try { channel.close(); } catch (IOException ignored) { }
            try { file.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    static boolean isHeld(Context context, String uri) {
        File path = lockPath(context, uri);
        if (!path.exists()) return false;
        try (RandomAccessFile file = new RandomAccessFile(path, "rw"); FileChannel channel = file.getChannel()) {
            try {
                FileLock probe = channel.tryLock();
                if (probe == null) return true;
                probe.release();
                return false;
            } catch (OverlappingFileLockException active) {
                return true;
            }
        } catch (IOException unavailable) {
            // A lease that cannot be checked is not considered safe to recover or publish.
            return true;
        }
    }

    private static File lockPath(Context context, String uri) {
        String hash;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(uri.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            hash = value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return new File(new File(context.getFilesDir(), "motion/leases"), hash + ".lock");
    }

    @Override public void close() throws IOException {
        try { lock.release(); }
        finally {
            try { channel.close(); }
            finally { file.close(); }
        }
    }
}

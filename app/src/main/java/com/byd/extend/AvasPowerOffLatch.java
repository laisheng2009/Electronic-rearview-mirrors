package com.byd.extend;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** One helper-owned event, never a playback history or an app preference. */
final class AvasPowerOffLatch {
    private final File file;
    private final int ownerUid;
    private boolean pending;
    private IOException loadError;

    AvasPowerOffLatch(File file, int ownerUid) {
        this.file = file;
        this.ownerUid = ownerUid;
        if (file.isFile()) try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != 1) throw new IOException("Unknown power-off latch version");
            int storedOwner = in.readInt();
            boolean storedPending = in.readBoolean();
            pending = storedOwner == ownerUid && storedPending;
        } catch (IOException failure) { loadError = failure; }
    }

    boolean pending() { return pending; }
    IOException loadError() { return loadError; }

    void set(boolean value) {
        if (pending == value && loadError == null) return;
        File temporary = new File(file.getPath() + ".tmp");
        try {
            Files.createDirectories(file.getParentFile().toPath());
            try (FileOutputStream bytes = new FileOutputStream(temporary);
                    DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(1);
                out.writeInt(ownerUid);
                out.writeBoolean(value);
                out.flush();
                bytes.getFD().sync();
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            pending = value;
            loadError = null;
        } catch (IOException failure) {
            throw new IllegalStateException("Power-off latch persistence failed", failure);
        }
    }
}

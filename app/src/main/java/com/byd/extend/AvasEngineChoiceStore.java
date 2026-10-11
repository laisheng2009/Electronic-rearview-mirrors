package com.byd.extend;

import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Helper-owned live choice: app configuration replay must not overwrite steering-button actions. */
final class AvasEngineChoiceStore {
    private final AtomicFile file;
    private final int ownerUid;

    AvasEngineChoiceStore(File file, int ownerUid) {
        this.file = new AtomicFile(file);
        this.ownerUid = ownerUid;
    }

    Boolean load() throws Exception {
        byte[] bytes;
        try { bytes = file.readFully(); }
        catch (java.io.FileNotFoundException missing) {
            if (!file.getBaseFile().exists()) return null;
            throw missing;
        }
        JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (value.getInt("ownerUid") != ownerUid) return null;
        Object enabled = value.get("enabled");
        if (!(enabled instanceof Boolean)) throw new IllegalArgumentException("invalid engine choice");
        return (Boolean) enabled;
    }

    void save(boolean enabled) throws Exception {
        byte[] bytes = new JSONObject().put("ownerUid", ownerUid).put("enabled", enabled)
                .toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (Exception failure) {
            file.failWrite(output);
            throw failure;
        }
    }
}

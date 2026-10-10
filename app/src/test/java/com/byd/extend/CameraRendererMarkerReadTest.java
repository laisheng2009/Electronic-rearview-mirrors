package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class CameraRendererMarkerReadTest {
    @Test public void readsTrimmedMarker() throws Exception {
        assertEquals("TS", TurnSignalShellMain.readCameraRendererMarkerText(
                new ByteArrayInputStream(" TS\n".getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void rejectsOversizedMarker() throws Exception {
        try {
            TurnSignalShellMain.readCameraRendererMarkerText(new ByteArrayInputStream(new byte[4097]));
            fail("must reject oversized OEM marker");
        } catch (java.io.IOException expected) {
            assertEquals("renderer marker too large", expected.getMessage());
        }
    }

    @Test public void fixedReadCannotBecomeAnArbitraryFileGateway() throws Exception {
        String shell = new String(Files.readAllBytes(Path.of("src/main/java/com/byd/extend/TurnSignalShellMain.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        String branch = shell.substring(shell.indexOf("if (code == TurnSignalShellProtocol.TX_READ_CAMERA_RENDERER_MARKER)"),
                shell.indexOf("if (code == TurnSignalShellProtocol.TX_CAMERA_RUNTIME)"));
        assertTrue(branch.contains("data.dataAvail() != 0"));
        assertFalse(branch.contains("data.readString()"));
        assertTrue(shell.indexOf("isCallerAllowed(Binder.getCallingUid(), appUid)") < shell.indexOf(branch));
    }
}

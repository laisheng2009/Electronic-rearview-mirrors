package com.byd.extend;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import java.util.IdentityHashMap;
import java.util.Map;

/** Keeps a TextureView consumer alive until the stock renderer acknowledges display detach. */
final class StockAvmOutput {
    private static final Map<SurfaceTexture, RetainedTexture> TEXTURES = new IdentityHashMap<>();
    private static final class RetainedTexture {
        int users;
        boolean destroyed;
    }

    final Surface surface;
    private final SurfaceTexture texture;
    private final CameraGpuOutput lease;
    private boolean retired;

    StockAvmOutput(SurfaceTexture texture, int width, int height) {
        this.texture = texture;
        surface = new Surface(texture);
        lease = new CameraGpuOutput(width, height,
                CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_REAR), 0, event -> {}, true);
        synchronized (TEXTURES) {
            TEXTURES.computeIfAbsent(texture, ignored -> new RetainedTexture()).users++;
        }
        CameraGpuOutput.register(surface, lease);
    }

    void retire() {
        if (retired) return;
        retired = true;
        CameraGpuOutput.unregister(surface);
        lease.release(() -> {
            surface.release();
            synchronized (TEXTURES) {
                RetainedTexture state = TEXTURES.get(texture);
                if (--state.users == 0) {
                    TEXTURES.remove(texture);
                    if (state.destroyed) texture.release();
                }
            }
        });
    }

    static boolean retainOnDestroy(SurfaceTexture texture) {
        synchronized (TEXTURES) {
            RetainedTexture state = TEXTURES.get(texture);
            if (state == null) return false;
            state.destroyed = true;
            return true;
        }
    }
}

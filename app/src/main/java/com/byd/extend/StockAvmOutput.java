package com.byd.extend;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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
    private TextureView view;
    private int canvasWidth;
    private int canvasHeight;

    StockAvmOutput(TextureView view, int width, int height) {
        this(view.getSurfaceTexture(), width, height);
        this.view = view;
        lease.setStockCanvasHandler(this::configureCanvas);
    }

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

    private void configureCanvas(int width, int height) {
        Handler main = new Handler(Looper.getMainLooper());
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (retired || view.getSurfaceTexture() != texture)
                throw new IllegalStateException("Stock canvas retired");
            canvasWidth = width;
            canvasHeight = height;
            refreshGeometry();
            android.util.Log.i("StockAvmOutput", "canvas=" + width + "x" + height
                    + ";host=" + view.getWidth() + "x" + view.getHeight());
            return null;
        });
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) task.run();
            else if (!main.post(task)) throw new IllegalStateException("Stock canvas handler stopped");
            task.get(3, TimeUnit.SECONDS);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Stock canvas resize failed", error);
        } finally { main.removeCallbacks(task); task.cancel(false); }
    }

    boolean refreshGeometry() {
        if (canvasWidth == 0 || retired) return false;
        texture.setDefaultBufferSize(canvasWidth, canvasHeight);
        int width = view.getWidth(), height = view.getHeight();
        if (width > 0 && height > 0) {
            float[] scale = BydAvmDisplayContract.fitScale(canvasWidth, canvasHeight, width, height);
            Matrix transform = new Matrix();
            transform.setScale(scale[0], scale[1], width / 2f, height / 2f);
            view.setTransform(transform);
        }
        return true;
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

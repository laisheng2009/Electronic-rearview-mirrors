package com.byd.extend;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewParent;
import java.lang.reflect.Method;

/** View-owned composition layer; its buffer queue survives view removal until the producer detaches. */
final class DirectCameraSurface extends SurfaceView implements SurfaceHolder.Callback {
    interface Listener {
        void available();
        void resized();
        void destroyed();
    }

    static final class Output {
        final SurfaceControl layer;
        final Surface surface;
        final int width, height;
        Output(SurfaceControl layer, Surface surface, int width, int height) {
            this.layer = layer; this.surface = surface; this.width = width; this.height = height;
        }
        void hide() {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setVisibility(layer, false).reparent(layer, null).apply();
            }
        }
        void release() { surface.release(); layer.release(); }
    }

    private final Listener listener;
    private final Matrix transform = new Matrix();
    private final float[] matrixValues = new float[9];
    private Output current;
    private boolean available;
    private Method setMatrix;
    private final android.view.ViewTreeObserver.OnPreDrawListener sync = () -> {
        updateLayer(); return true;
    };

    DirectCameraSurface(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        getHolder().setFormat(PixelFormat.TRANSPARENT);
        getHolder().addCallback(this);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    boolean available() { return available; }

    Output createOutput(int width, int height) {
        if (!available || current != null) throw new IllegalStateException("camera layer unavailable");
        SurfaceControl layer = null;
        Surface surface = null;
        try {
            SurfaceControl parent = (SurfaceControl) SurfaceView.class
                    .getMethod("getSurfaceControl").invoke(this);
            layer = new SurfaceControl.Builder().setName("byd-camera-direct")
                    .setParent(parent).setBufferSize(width, height)
                    .setFormat(PixelFormat.RGBA_8888).setOpaque(true).setHidden(false).build();
            surface = Surface.class.getConstructor().newInstance();
            Surface.class.getMethod("copyFrom", SurfaceControl.class).invoke(surface, layer);
            setMatrix = SurfaceControl.Transaction.class.getMethod("setMatrix",
                    SurfaceControl.class, float.class, float.class, float.class, float.class);
            current = new Output(layer, surface, width, height);
            updateLayer();
            return current;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            current = null;
            if (surface != null) surface.release();
            if (layer != null) layer.release();
            throw new IllegalStateException("direct camera layer creation failed", failure);
        }
    }

    Output retireOutput() {
        Output previous = current;
        current = null;
        if (previous != null) previous.hide();
        return previous;
    }

    void transform(Matrix value) {
        transform.set(value);
        updateLayer();
    }

    private void updateLayer() {
        Output output = current;
        if (output == null || !output.layer.isValid() || getWidth() == 0 || getHeight() == 0) return;
        Matrix bufferToView = new Matrix();
        bufferToView.setScale((float) getWidth() / output.width, (float) getHeight() / output.height);
        bufferToView.postConcat(transform);
        bufferToView.getValues(matrixValues);
        float alpha = 1f;
        for (View view = this; view != null;) {
            alpha *= view.getAlpha();
            if (view.getVisibility() != VISIBLE) alpha = 0f;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            setMatrix.invoke(transaction, output.layer, matrixValues[Matrix.MSCALE_X],
                    matrixValues[Matrix.MSKEW_Y], matrixValues[Matrix.MSKEW_X], matrixValues[Matrix.MSCALE_Y]);
            transaction.setPosition(output.layer, matrixValues[Matrix.MTRANS_X], matrixValues[Matrix.MTRANS_Y])
                    .setLayer(output.layer, 1).setAlpha(output.layer, alpha).apply();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("direct camera transform failed", failure);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(sync);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(sync);
        super.onDetachedFromWindow();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        available = true; listener.available();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        updateLayer(); listener.resized();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        available = false; listener.destroyed();
    }
}

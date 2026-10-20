package com.byd.extend;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
    private final Outline outline = new Outline();
    private final Rect clip = new Rect();
    private final int[] surfaceLocation = new int[2];
    private final int[] clipLocation = new int[2];
    private Output current;
    private SurfaceControl viewport;
    private boolean available;
    private Method setMatrix;
    private Method setWindowCrop;
    private Method setCornerRadius;
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
            setWindowCrop = SurfaceControl.Transaction.class.getMethod("setWindowCrop",
                    SurfaceControl.class, Rect.class);
            setCornerRadius = SurfaceControl.Transaction.class.getMethod("setCornerRadius",
                    SurfaceControl.class, float.class);
            viewport = parent;
            current = new Output(layer, surface, width, height);
            updateLayer();
            return current;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            current = null;
            viewport = null;
            if (surface != null) surface.release();
            if (layer != null) layer.release();
            throw new IllegalStateException("direct camera layer creation failed", failure);
        }
    }

    Output retireOutput() {
        Output previous = current;
        current = null;
        viewport = null;
        if (previous != null) previous.hide();
        return previous;
    }

    private void updateLayer() {
        Output output = current;
        if (output == null || !output.layer.isValid() || viewport == null || !viewport.isValid()
                || getWidth() == 0 || getHeight() == 0) return;
        float alpha = 1f;
        for (View view = this; view != null;) {
            alpha *= view.getAlpha();
            if (view.getVisibility() != VISIBLE) alpha = 0f;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            // Arbitrary rotation belongs in GLES: app-UID SurfaceFlinger rejects it, and
            // shell-UID rotated child layers cannot be clipped to a rectangular ancestor.
            setMatrix.invoke(transaction, output.layer, (float) getWidth() / output.width,
                    0f, 0f, (float) getHeight() / output.height);
            float radius = viewportClip();
            setWindowCrop.invoke(transaction, viewport, clip);
            setCornerRadius.invoke(transaction, viewport, radius);
            transaction.setPosition(output.layer, 0f, 0f)
                    .setLayer(output.layer, 1).setAlpha(output.layer, alpha).apply();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("direct camera transform failed", failure);
        }
    }

    /** Native children do not inherit View outlines; mirror the enclosing pane's rounded bounds. */
    private float viewportClip() {
        clip.set(0, 0, getWidth(), getHeight());
        getLocationInWindow(surfaceLocation);
        for (ViewParent parent = getParent(); parent instanceof View;) {
            View view = (View) parent;
            if (view.getClipToOutline() && view.getOutlineProvider() != null) {
                outline.setEmpty();
                view.getOutlineProvider().getOutline(view, outline);
                if (outline.getRect(clip)) {
                    view.getLocationInWindow(clipLocation);
                    clip.offset(clipLocation[0] - surfaceLocation[0],
                            clipLocation[1] - surfaceLocation[1]);
                    return Math.max(0f, outline.getRadius());
                }
            }
            parent = view.getParent();
        }
        clip.set(0, 0, getWidth(), getHeight());
        return 0f;
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

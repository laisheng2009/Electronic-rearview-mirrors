package com.byd.extend;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.TimeZone;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Four GPU fan-out surfaces to one encoder surface; no CPU video-frame copies. */
final class MotionGpuComposer implements AutoCloseable {
    private static final String TAG = "MotionGpuComposer";
    private static final long STARTUP_INPUT_TIMEOUT_MS = 10_000L;
    private static final long GL_TASK_TIMEOUT_MS = 5_000L;
    private final HandlerThread thread = new HandlerThread("motion-video-gpu");
    private final Handler handler;
    private final Object lifecycleLock = new Object();
    private final SurfaceTexture[] textures = new SurfaceTexture[4];
    private final Surface[] inputs = new Surface[4];
    private final boolean[] pending = new boolean[4], seen = new boolean[4];
    private final float[][] transforms = new float[4][16];
    private final int[] names = new int[4];
    private final int[] iconTextures = new int[4];
    private final int[] timestampTexture = new int[1];
    private final MotionCompositionLayout.Rect[] cameraRects = new MotionCompositionLayout.Rect[4];
    private final int width, height;
    private final int[] order;
    private final MotionFrameTimestamp frameTimestamp;
    private final Consumer<String> errors;
    private final Context appContext;
    private final String sessionId;
    private final boolean shellWorker;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private boolean eglInitialized;
    private int program, iconProgram, position, uv, matrix, sampler;
    private int iconPosition, iconUv, iconSampler;
    private String renderedTimestamp;
    private long timestampSecond = Long.MIN_VALUE;
    private int timestampWidth, timestampHeight, timestampInset;
    private boolean queued, closed;
    private boolean initializationRequested;
    private FutureTask<Void> cleanupTask;
    private Runnable closeCallback;
    private boolean cleanupResultPublished;
    private boolean cleanupSucceeded;
    private boolean closeCallbackDelivered;
    private boolean startupMissingReported;
    private boolean firstCompositionLogged;
    private final boolean[] firstInputLogged = new boolean[4];
    private final long[] firstInputTimestampNs = new long[4];
    private long lastFrameMs;
    private final FloatBuffer vertices = floats(-1,-1, 1,-1, -1,1, 1,1);
    private final FloatBuffer coordinates = floats(0,0, 1,0, 0,1, 1,1);
    private final FloatBuffer iconVertices = floats(-1,-1, 1,-1, -1,1, 1,1);
    // Android bitmaps upload top row at GL v=0; flip v to keep the OEM PNG upright.
    private final FloatBuffer iconCoordinates = floats(0,1, 1,1, 0,0, 1,0);
    private final Runnable draw = this::render;

    MotionGpuComposer(Context context, int width, int height, int[] order,
            Consumer<String> errors) throws Exception {
        this(context.getApplicationContext(), width, height, order, "app", false,
                MotionFrameTimestamp.capture(), errors);
    }

    MotionGpuComposer(MotionShellMediaContext shellMediaContext, int width,
            int height, int[] order, String sessionId, MotionFrameTimestamp frameTimestamp,
            Consumer<String> errors) throws Exception {
        this(shellMediaContext.ownerResourcesContext(), width, height, order,
                sessionId == null || sessionId.isEmpty() ? "unknown" : sessionId,
                true, frameTimestamp, errors);
    }

    private MotionGpuComposer(Context resourcesContext, int width, int height,
            int[] order, String sessionId, boolean shellWorker,
            MotionFrameTimestamp frameTimestamp, Consumer<String> errors) throws Exception {
        this.appContext = resourcesContext;
        this.width = width; this.height = height; this.order = order.clone();
        if (frameTimestamp == null) throw new IllegalArgumentException("Motion frame clock is required");
        this.frameTimestamp = frameTimestamp;
        for (int cell = 0; cell < cameraRects.length; cell++) {
            cameraRects[cell] = MotionCompositionLayout.cameraRectForCell(cell, width, height);
            if (cameraRects[cell] == null)
                throw new IllegalArgumentException("Motion output geometry is invalid");
        }
        this.errors = errors == null ? ignored -> { } : errors;
        this.sessionId = sessionId;
        this.shellWorker = shellWorker;
        thread.start();
        Looper looper = thread.getLooper();
        if (looper == null) {
            thread.quitSafely();
            throw new IllegalStateException("GPU worker looper unavailable");
        }
        handler = new Handler(looper);
    }

    Surface[] inputs() { return inputs.clone(); }

    /** Initializes EGL only after the owner has retained this composer for teardown. */
    void initialize(Surface encoder) throws Exception {
        if (encoder == null) throw new IllegalArgumentException("Motion encoder surface is required");
        final FutureTask<Void> task;
        synchronized (lifecycleLock) {
            if (closed || cleanupTask != null)
                throw new IllegalStateException("GPU composer is closing");
            if (initializationRequested)
                throw new IllegalStateException("GPU composer is already initialized or initializing");
            initializationRequested = true;
            task = new FutureTask<>(() -> {
                initializeOnGlThread(encoder);
                return null;
            });
            if (!handler.post(task))
                throw new IllegalStateException("GPU worker rejected initialization");
        }
        await(task, "GPU initialization");
        if (!closed) handler.postDelayed(this::reportMissingInputs, STARTUP_INPUT_TIMEOUT_MS);
    }

    private void initializeOnGlThread(Surface encoder) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] values = new int[2];
        require(EGL14.eglInitialize(display, values, 0, values, 1), "EGL initialize");
        eglInitialized = true;
        EGLConfig[] configs = new EGLConfig[1];
        require(EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,0x3142,1,EGL14.EGL_NONE},
                0, configs, 0, 1, values, 0) && values[0] > 0, "recordable EGL config");
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        window = EGL14.eglCreateWindowSurface(display, configs[0], encoder,
                new int[]{EGL14.EGL_NONE},0);
        require(context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE, "encoder EGL surface");
        require(EGL14.eglMakeCurrent(display,window,window,context), "EGL current");
        program = link("attribute vec2 p;attribute vec2 t;uniform mat4 m;varying vec2 uv;"
                + "void main(){gl_Position=vec4(p,0.,1.);uv=(m*vec4(t,0.,1.)).xy;}",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;"
                + "uniform samplerExternalOES s;varying vec2 uv;void main(){gl_FragColor=texture2D(s,uv);}");
        iconProgram = link("attribute vec2 p;attribute vec2 t;varying vec2 uv;"
                + "void main(){gl_Position=vec4(p,0.,1.);uv=t;}",
                "precision mediump float;uniform sampler2D s;varying vec2 uv;"
                + "void main(){gl_FragColor=texture2D(s,uv);}");
        position=GLES20.glGetAttribLocation(program,"p"); uv=GLES20.glGetAttribLocation(program,"t");
        matrix=GLES20.glGetUniformLocation(program,"m"); sampler=GLES20.glGetUniformLocation(program,"s");
        iconPosition=GLES20.glGetAttribLocation(iconProgram,"p");
        iconUv=GLES20.glGetAttribLocation(iconProgram,"t");
        iconSampler=GLES20.glGetUniformLocation(iconProgram,"s");
        loadDirectionIcons();
        GLES20.glGenTextures(4,names,0);
        for(int i=0;i<4;i++) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,names[i]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            textures[i]=new SurfaceTexture(names[i]);
            textures[i].setDefaultBufferSize(cameraRects[0].width(), cameraRects[0].height());
            inputs[i]=new Surface(textures[i]);
            final int index=i;
            textures[i].setOnFrameAvailableListener(ignored -> frame(index),handler);
        }
        GLES20.glGenTextures(timestampTexture.length, timestampTexture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timestampTexture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private void loadDirectionIcons() {
        GLES20.glGenTextures(iconTextures.length, iconTextures, 0);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        for (int direction = 0; direction < iconTextures.length; direction++) {
            Bitmap bitmap = BitmapFactory.decodeResource(
                    appContext.getResources(), MotionDirectionIcon.drawableFor(direction), options);
            if (bitmap == null) throw new IllegalStateException(
                    "Motion OEM direction icon unavailable: " + direction);
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iconTextures[direction]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            } finally {
                bitmap.recycle();
            }
        }
    }

    private void frame(int index) {
        if(closed) return;
        if (!firstInputLogged[index]) {
            firstInputLogged[index] = true;
            logDiagnostic("first_input_callback", "direction=" + directionName(index));
        }
        pending[index]=true;
        if(!queued) {
            queued=true;
            handler.postDelayed(draw,Math.max(0,40-(SystemClock.elapsedRealtime()-lastFrameMs)));
        }
    }

    private void render() {
        queued=false;
        if(closed) return;
        try {
            require(EGL14.eglMakeCurrent(display,window,window,context),"EGL render current");
            for(int i=0;i<4;i++) if(pending[i]) {
                pending[i]=false; textures[i].updateTexImage();
                textures[i].getTransformMatrix(transforms[i]); seen[i]=true;
                long sourceTimestamp = textures[i].getTimestamp();
                if (firstInputTimestampNs[i] == 0) {
                    firstInputTimestampNs[i] = sourceTimestamp;
                    logDiagnostic("first_input_frame", "direction=" + directionName(i)
                            + " source_timestamp_ns=" + sourceTimestamp);
                }
            }
            // Start only once every direction has a real frame, not a synthetic black quadrant.
            for(boolean available:seen) if(!available) return;
            long presentationTimeNs=SystemClock.elapsedRealtimeNanos();
            updateTimestampTexture(presentationTimeNs/1_000_000L);
            GLES20.glClearColor(0,0,0,1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            for(int cell=0;cell<4;cell++) {
                int direction=order[cell];
                MotionCompositionLayout.Rect camera=cameraRects[cell];
                GLES20.glViewport(camera.left,height-camera.bottom,
                        camera.width(),camera.height());
                GLES20.glUseProgram(program);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,names[direction]);
                GLES20.glUniform1i(sampler,0);
                GLES20.glUniformMatrix4fv(matrix,1,false,transforms[direction],0);
                vertices.position(0); coordinates.position(0);
                GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv);
                GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices);
                GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,0,coordinates);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(uv);
                int size=Math.max(1,Math.round(camera.width()*.05f));
                int inset=Math.max(0,Math.round(camera.width()*.0083f));
                drawOverlay(iconTextures[direction],camera.left+inset,camera.top+inset,size,size);
                drawOverlay(timestampTexture[0],camera.left+timestampInset,
                        camera.bottom-timestampInset-timestampHeight,
                        timestampWidth,timestampHeight);
            }
            EGLExt.eglPresentationTimeANDROID(display,window,presentationTimeNs);
            require(EGL14.eglSwapBuffers(display,window),"encoder swap");
            lastFrameMs=presentationTimeNs/1_000_000;
            if (!firstCompositionLogged) {
                firstCompositionLogged = true;
                logDiagnostic("first_composed_frame", "presentation_timestamp_ns=" + presentationTimeNs);
            }
        } catch(RuntimeException failure) {
            closed=true;
            logDiagnostic("render_failed", failure.toString());
            errors.accept(failure.toString());
        }
    }

    private void updateTimestampTexture(long elapsedMs) {
        long second = frameTimestamp.wallTimeMillis(elapsedMs) / 1_000L;
        if (second == timestampSecond) return;
        timestampSecond = second;
        TimeZone zone = TimeZone.getDefault();
        String next = frameTimestamp.format(elapsedMs, zone);
        if (next.equals(renderedTimestamp)) return;

        Bitmap bitmap = timestampBitmap(next);
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timestampTexture[0]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            renderedTimestamp = next;
        } finally {
            bitmap.recycle();
        }
    }

    private Bitmap timestampBitmap(String value) {
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(Math.max(1f, cameraRects[0].width() * .025f));
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        Paint.FontMetrics metrics = text.getFontMetrics();
        float padding = text.getTextSize() * .3f;
        float widest = text.measureText("00.00.0000 00:00:00+05:30");
        timestampWidth = (int) Math.ceil(widest + padding * 2);
        timestampHeight = (int) Math.ceil(metrics.descent - metrics.ascent + padding * 2);
        timestampInset = Math.max(0, Math.round(cameraRects[0].width() * .0083f));

        Bitmap bitmap = Bitmap.createBitmap(timestampWidth, timestampHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(Color.argb(160, 0, 0, 0));
        canvas.drawRect(0, 0, timestampWidth, timestampHeight, background);
        canvas.drawText(value, padding, padding - metrics.ascent, text);
        return bitmap;
    }

    private void drawOverlay(int texture, int left, int top, int viewWidth, int viewHeight) {
        GLES20.glViewport(left, height - top - viewHeight, viewWidth, viewHeight);
        GLES20.glUseProgram(iconProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(iconSampler, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        iconVertices.position(0); iconCoordinates.position(0);
        GLES20.glEnableVertexAttribArray(iconPosition);
        GLES20.glEnableVertexAttribArray(iconUv);
        GLES20.glVertexAttribPointer(iconPosition, 2, GLES20.GL_FLOAT, false, 0, iconVertices);
        GLES20.glVertexAttribPointer(iconUv, 2, GLES20.GL_FLOAT, false, 0, iconCoordinates);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(iconPosition);
        GLES20.glDisableVertexAttribArray(iconUv);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void reportMissingInputs() {
        if (closed || startupMissingReported) return;
        StringBuilder missing = new StringBuilder();
        for (int direction = 0; direction < seen.length; direction++) {
            if (seen[direction]) continue;
            if (missing.length() > 0) missing.append(',');
            missing.append(directionName(direction));
        }
        if (missing.length() == 0) return;
        startupMissingReported = true;
        logDiagnostic("startup_inputs_missing", "directions=" + missing
                + " timeout_ms=" + STARTUP_INPUT_TIMEOUT_MS);
        errors.accept("motion_input_frames_missing:" + missing);
    }

    private void logDiagnostic(String event, String details) {
        String message = "session=" + sessionId + " pid=" + Process.myPid()
                + " elapsed_ms=" + SystemClock.elapsedRealtime() + " event=" + event
                + (details == null || details.isEmpty() ? "" : " " + details);
        Log.i(TAG, message);
        if (shellWorker) {
            System.out.println("MOTION " + message);
            System.out.flush();
        }
    }

    private static String directionName(int direction) {
        switch (direction) {
            case 0: return "front";
            case 1: return "rear";
            case 2: return "left";
            case 3: return "right";
            default: return "unknown-" + direction;
        }
    }

    /**
     * Schedules one retained cleanup task. A timeout means cleanup is still pending, not complete;
     * the callback fires later only if GL cleanup finishes successfully.
     */
    void close(Runnable onClosed) {
        final FutureTask<Void> task;
        Runnable callbackNow = null;
        synchronized (lifecycleLock) {
            closed = true;
            if (onClosed != null && closeCallback == null && !closeCallbackDelivered
                    && (!cleanupResultPublished || cleanupSucceeded)) {
                closeCallback = onClosed;
                if (cleanupSucceeded) {
                    closeCallbackDelivered = true;
                    callbackNow = onClosed;
                }
            }
            if (cleanupTask == null) {
                cleanupTask = new FutureTask<Void>(() -> {
                    cleanupOnGlThread();
                    publishCleanupSuccess();
                    return null;
                }) {
                    @Override protected void done() {
                        publishCleanupFailure(this);
                    }
                };
                if (!handler.post(cleanupTask)) cleanupTask.cancel(false);
            }
            task = cleanupTask;
        }
        if (callbackNow != null) runCloseCallback(callbackNow);
        // A close invoked from this looper can safely run the retained task inline; otherwise it
        // would wait on itself. A previously queued copy becomes a harmless FutureTask no-op.
        if (Looper.myLooper() == thread.getLooper() && !task.isDone()) task.run();
        try {
            await(task, "GPU cleanup");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("GPU cleanup did not complete", failure);
        }
    }

    @Override public void close() { close(null); }

    private void cleanupOnGlThread() {
        handler.removeCallbacksAndMessages(null);
        boolean hasEgl = eglInitialized;
        boolean hasContext = context != EGL14.EGL_NO_CONTEXT;
        boolean hasWindow = window != EGL14.EGL_NO_SURFACE;
        if (hasEgl && hasContext && hasWindow) {
            require(EGL14.eglMakeCurrent(display, window, window, context), "EGL cleanup current");
            GLES20.glFinish(); // Teardown-only completion barrier before releasing encoder surfaces.
            GLES20.glDeleteTextures(names.length, names, 0);
            GLES20.glDeleteTextures(iconTextures.length, iconTextures, 0);
            GLES20.glDeleteTextures(timestampTexture.length, timestampTexture, 0);
            if (program != 0) GLES20.glDeleteProgram(program);
            if (iconProgram != 0) GLES20.glDeleteProgram(iconProgram);
        }
        for (int i = 0; i < 4; i++) {
            if (textures[i] != null) {
                textures[i].setOnFrameAvailableListener(null);
                textures[i].release();
            }
            if (inputs[i] != null) inputs[i].release();
        }
        if (hasEgl) {
            if (hasContext && hasWindow)
                require(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT), "EGL cleanup unbind");
            if (hasWindow)
                require(EGL14.eglDestroySurface(display, window), "EGL destroy encoder surface");
            if (hasContext)
                require(EGL14.eglDestroyContext(display, context), "EGL destroy context");
            require(EGL14.eglTerminate(display), "EGL terminate");
        }
    }

    private void publishCleanupSuccess() {
        thread.quitSafely();
        Runnable callback;
        synchronized (lifecycleLock) {
            cleanupResultPublished = true;
            cleanupSucceeded = true;
            callback = closeCallbackDelivered ? null : closeCallback;
            if (callback != null) closeCallbackDelivered = true;
        }
        if (callback != null) runCloseCallback(callback);
    }

    private void publishCleanupFailure(FutureTask<Void> task) {
        Throwable failure = null;
        if (task.isCancelled()) {
            failure = new CancellationException("GPU cleanup task was not accepted");
        } else {
            try { task.get(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
            } catch (ExecutionException execution) {
                failure = execution.getCause() == null ? execution : execution.getCause();
            } catch (CancellationException cancelled) {
                failure = cancelled;
            }
        }
        if (failure == null) return;
        synchronized (lifecycleLock) {
            if (cleanupResultPublished) return;
            cleanupResultPublished = true;
            cleanupSucceeded = false;
            closeCallback = null;
        }
        logDiagnostic("gpu_cleanup_failed", failure.toString());
        try { errors.accept("GPU cleanup: " + failure); }
        catch (RuntimeException callbackFailure) {
            Log.w(TAG, "gpu_cleanup_error_callback_failed", callbackFailure);
        }
    }

    private void runCloseCallback(Runnable callback) {
        try { callback.run(); }
        catch (RuntimeException failure) { Log.w(TAG, "gpu_close_callback_failed", failure); }
    }

    private static void await(FutureTask<Void> task, String operation) throws Exception {
        try {
            task.get(GL_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
            throw new IllegalStateException(operation + " remains pending", pending);
        } catch (ExecutionException execution) {
            Throwable failure = execution.getCause() == null ? execution : execution.getCause();
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException(operation + " failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (CancellationException cancelled) {
            throw new IllegalStateException(operation + " was cancelled", cancelled);
        }
    }
    private static FloatBuffer floats(float... values) {
        FloatBuffer buffer=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0); return buffer;
    }
    private static int link(String vertex,String fragment) {
        int v=shader(GLES20.GL_VERTEX_SHADER,vertex), f=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
        int p=GLES20.glCreateProgram();
        GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f);
        int[] status=new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,status,0);
        require(status[0]!=0,GLES20.glGetProgramInfoLog(p)); return p;
    }
    private static int shader(int type,String source) {
        int shader=GLES20.glCreateShader(type); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader);
        int[] status=new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0);
        require(status[0]!=0,GLES20.glGetShaderInfoLog(shader)); return shader;
    }
    private static void require(boolean condition,String stage) {
        if(!condition) throw new IllegalStateException(stage+" EGL="+EGL14.eglGetError());
    }
}

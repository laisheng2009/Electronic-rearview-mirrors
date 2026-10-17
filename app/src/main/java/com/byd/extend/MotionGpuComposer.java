package com.byd.extend;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
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
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Four GPU fan-out surfaces to one encoder surface; no CPU video-frame copies. */
final class MotionGpuComposer implements AutoCloseable {
    private final HandlerThread thread = new HandlerThread("motion-video-gpu");
    private final Handler handler;
    private final SurfaceTexture[] textures = new SurfaceTexture[4];
    private final Surface[] inputs = new Surface[4];
    private final boolean[] pending = new boolean[4], seen = new boolean[4];
    private final float[][] transforms = new float[4][16];
    private final int[] names = new int[4];
    private final int[] iconTextures = new int[4];
    private final int width, height;
    private final int[] order;
    private final Consumer<String> errors;
    private final Context appContext;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int program, iconProgram, position, uv, matrix, sampler;
    private int iconPosition, iconUv, iconSampler;
    private boolean queued, closed;
    private long lastFrameMs;
    private final FloatBuffer vertices = floats(-1,-1, 1,-1, -1,1, 1,1);
    private final FloatBuffer coordinates = floats(0,0, 1,0, 0,1, 1,1);
    private final FloatBuffer iconVertices = floats(-1,-1, 1,-1, -1,1, 1,1);
    // Android bitmaps upload top row at GL v=0; flip v to keep the OEM PNG upright.
    private final FloatBuffer iconCoordinates = floats(0,1, 1,1, 0,0, 1,0);
    private final Runnable draw = this::render;

    MotionGpuComposer(Context context, Surface encoder, int width, int height, int[] order,
            Consumer<String> errors) throws Exception {
        this.appContext = context.getApplicationContext();
        this.width = width; this.height = height; this.order = order.clone(); this.errors = errors;
        thread.start(); handler = new Handler(thread.getLooper());
        try { call(() -> initialize(encoder)); }
        catch (Exception failure) { close(); throw failure; }
    }

    Surface[] inputs() { return inputs.clone(); }

    private void initialize(Surface encoder) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] values = new int[2];
        require(EGL14.eglInitialize(display, values, 0, values, 1), "EGL initialize");
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
            textures[i].setDefaultBufferSize(Math.min(width/2,(height/2)*1920/1300),height/2);
            inputs[i]=new Surface(textures[i]);
            final int index=i;
            textures[i].setOnFrameAvailableListener(ignored -> frame(index),handler);
        }
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
            }
            // Start only once every direction has a real frame, not a synthetic black quadrant.
            for(boolean available:seen) if(!available) return;
            GLES20.glClearColor(0,0,0,1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            for(int cell=0;cell<4;cell++) {
                int direction=order[cell], x=(cell%2)*width/2, y=(1-cell/2)*height/2;
                int viewWidth=Math.min(width/2,(height/2)*1920/1300);
                GLES20.glViewport(x+(width/2-viewWidth)/2,y,viewWidth,height/2);
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
                int cellWidth=width/2, cellHeight=height/2;
                int size=Math.max(1,Math.round(cellWidth*.05f));
                int inset=Math.max(0,Math.round(cellWidth*.0083f));
                GLES20.glViewport(x+inset,y+cellHeight-size-inset,size,size);
                GLES20.glUseProgram(iconProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,iconTextures[direction]);
                GLES20.glUniform1i(iconSampler,0);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA);
                iconVertices.position(0); iconCoordinates.position(0);
                GLES20.glEnableVertexAttribArray(iconPosition);
                GLES20.glEnableVertexAttribArray(iconUv);
                GLES20.glVertexAttribPointer(iconPosition,2,GLES20.GL_FLOAT,false,0,iconVertices);
                GLES20.glVertexAttribPointer(iconUv,2,GLES20.GL_FLOAT,false,0,iconCoordinates);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                GLES20.glDisableVertexAttribArray(iconPosition);
                GLES20.glDisableVertexAttribArray(iconUv);
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            long now=SystemClock.elapsedRealtimeNanos();
            EGLExt.eglPresentationTimeANDROID(display,window,now);
            require(EGL14.eglSwapBuffers(display,window),"encoder swap");
            lastFrameMs=now/1_000_000;
        } catch(RuntimeException failure) {
            closed=true;
            errors.accept(failure.toString());
        }
    }

    @Override public void close() {
        try { call(() -> {
            closed=true; handler.removeCallbacksAndMessages(null);
            if(display!=EGL14.EGL_NO_DISPLAY && context!=EGL14.EGL_NO_CONTEXT
                    && window!=EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display,window,window,context);
                GLES20.glDeleteTextures(names.length,names,0);
                GLES20.glDeleteTextures(iconTextures.length,iconTextures,0);
                if(program!=0) GLES20.glDeleteProgram(program);
                if(iconProgram!=0) GLES20.glDeleteProgram(iconProgram);
            }
            for(int i=0;i<4;i++) {
                if(textures[i]!=null) { textures[i].setOnFrameAvailableListener(null); textures[i].release(); }
                if(inputs[i]!=null) inputs[i].release();
            }
            if(display!=EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                if(window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window);
                if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
                EGL14.eglTerminate(display);
            }
        }); } catch(Exception failure) { errors.accept("GPU cleanup: "+failure); }
        finally { thread.quitSafely(); }
    }

    private void call(Runnable action) throws Exception {
        FutureTask<Void> task=new FutureTask<>(action,null);
        if(!handler.post(task)) throw new IllegalStateException("GPU worker closed");
        task.get(5,TimeUnit.SECONDS);
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

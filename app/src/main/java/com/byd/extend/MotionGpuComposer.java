package com.byd.extend;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
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
    private final int width, height;
    private final int[] order;
    private final Consumer<String> errors;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int program, iconProgram, position, uv, matrix, sampler;
    private boolean queued, closed;
    private long lastFrameMs;
    private final FloatBuffer vertices = floats(-1,-1, 1,-1, -1,1, 1,1);
    private final FloatBuffer coordinates = floats(0,0, 1,0, 0,1, 1,1);
    private final FloatBuffer icon = floats(0,.9f, -.65f,.1f, -.25f,.1f,
            0,.9f, -.25f,.1f, .25f,.1f, 0,.9f, .25f,.1f, .65f,.1f,
            -.25f,.1f, -.25f,-.9f, .25f,-.9f, -.25f,.1f, .25f,-.9f, .25f,.1f);
    private final Runnable draw = this::render;

    MotionGpuComposer(Surface encoder, int width, int height, int[] order, Consumer<String> errors)
            throws Exception {
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
        iconProgram = link("attribute vec2 p;uniform float a;void main(){float c=cos(a);float s=sin(a);"
                + "gl_Position=vec4(c*p.x-s*p.y,s*p.x+c*p.y,0.,1.);}",
                "precision mediump float;void main(){gl_FragColor=vec4(1.,.85,.15,1.);}");
        position=GLES20.glGetAttribLocation(program,"p"); uv=GLES20.glGetAttribLocation(program,"t");
        matrix=GLES20.glGetUniformLocation(program,"m"); sampler=GLES20.glGetUniformLocation(program,"s");
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
                int size=Math.max(20,width/40);
                GLES20.glViewport(x+8,y+height/2-size-8,size,size);
                GLES20.glUseProgram(iconProgram);
                int p=GLES20.glGetAttribLocation(iconProgram,"p");
                float angle=direction==0?0:direction==1?(float)Math.PI:
                        direction==2?(float)Math.PI/2:-(float)Math.PI/2;
                GLES20.glUniform1f(GLES20.glGetUniformLocation(iconProgram,"a"),angle);
                icon.position(0); GLES20.glEnableVertexAttribArray(p);
                GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,0,icon);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,15); GLES20.glDisableVertexAttribArray(p);
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

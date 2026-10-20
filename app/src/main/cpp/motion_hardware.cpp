#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <dlfcn.h>
#include "oem_log_filter.h"

static bool verbose = false;
static __android_logger_function previousLogger = nullptr;
static void filteredLogger(const __android_log_message* message) {
    if (!__atomic_load_n(&verbose, __ATOMIC_RELAXED)
            && oemFrameNoise(message->priority, message->tag, message->message)) return;
    previousLogger(message);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_byd_extend_OemLogPolicy_install(
        JNIEnv*, jclass) {
    auto set = reinterpret_cast<void(*)(__android_logger_function)>(dlsym(RTLD_DEFAULT, "__android_log_set_logger"));
    // These shell processes use Android's default device logger; there is no public logger getter.
    previousLogger = reinterpret_cast<__android_logger_function>(
        dlsym(RTLD_DEFAULT, "__android_log_logd_logger"));
    if (!set || !previousLogger) return false;
    set(filteredLogger);
    return true;
}
extern "C" JNIEXPORT void JNICALL Java_com_byd_extend_OemLogPolicy_setVerbose(
        JNIEnv*, jclass, jboolean enabled) {
    __atomic_store_n(&verbose, enabled, __ATOMIC_RELAXED);
}

extern "C" JNIEXPORT jlong JNICALL Java_com_byd_extend_MotionHardwareBuffer_bind(
        JNIEnv* env, jclass, jobject object, jint texture) {
    auto client = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    auto create = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    auto destroy = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    auto bind = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, object);
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    if (buffer && client && create && destroy && bind) {
        const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
        image = create(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client(buffer), attrs);
        if (image != EGL_NO_IMAGE_KHR) {
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
            bind(GL_TEXTURE_EXTERNAL_OES, image);
            if (glGetError() == GL_NO_ERROR) return reinterpret_cast<jlong>(image);
            destroy(eglGetCurrentDisplay(), image);
        }
    }
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "OEM YUV GPU import failed");
    return 0;
}
extern "C" JNIEXPORT void JNICALL Java_com_byd_extend_MotionHardwareBuffer_release(
        JNIEnv*, jclass, jlong image) {
    auto destroy = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    if (destroy && image) destroy(eglGetCurrentDisplay(), reinterpret_cast<EGLImageKHR>(image));
}

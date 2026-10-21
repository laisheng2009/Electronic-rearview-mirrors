#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "jni_bridge.h"

extern char *camera_tunnel_call(char *operation, char *request_json);
extern void camera_tunnel_free(char *value);
extern int camera_tunnel_set_runtime_dir(char *path);
extern void camera_tunnel_network_changed(void);
extern void camera_tunnel_cancel(char *provider, char *instance_id);

static JavaVM *g_vm;
static jobject g_secret_store;
static jmethodID g_get_secret;
static jmethodID g_put_secret;
static jmethodID g_remove_secret;
static jmethodID g_remove_secret_prefix;
static pthread_mutex_t g_store_mutex = PTHREAD_MUTEX_INITIALIZER;

static void wipe(void *value, size_t length) {
    volatile unsigned char *bytes = (volatile unsigned char *)value;
    while (length-- > 0) *bytes++ = 0;
}

static JNIEnv *get_env(int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if (g_vm == NULL) return NULL;
    jint status = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_OK) return env;
    if (status != JNI_EDETACHED) return NULL;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
    *attached = 1;
    return env;
}

static void release_env(int attached) {
    if (attached && g_vm != NULL) (*g_vm)->DetachCurrentThread(g_vm);
}

static jstring new_string(JNIEnv *env, const char *value) {
    return (*env)->NewStringUTF(env, value == NULL ? "" : value);
}

int camera_secret_get(const char *key, char **out_value, size_t *out_length) {
    if (out_value == NULL || out_length == NULL) return -1;
    *out_value = NULL;
    *out_length = 0;
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env == NULL) return -1;

    pthread_mutex_lock(&g_store_mutex);
    jobject store = g_secret_store;
    jmethodID method = g_get_secret;
    jobject local_store = store == NULL ? NULL : (*env)->NewLocalRef(env, store);
    pthread_mutex_unlock(&g_store_mutex);
    if (local_store == NULL || method == NULL) {
        if (local_store != NULL) (*env)->DeleteLocalRef(env, local_store);
        release_env(attached);
        return -1;
    }

    jstring key_string = new_string(env, key);
    jbyteArray result = (jbyteArray)(*env)->CallObjectMethod(env, local_store, method, key_string);
    char *copy = NULL;
    int result_status = 0;
    if (!(*env)->ExceptionCheck(env) && result != NULL) {
        jsize length = (*env)->GetArrayLength(env, result);
        if (length >= 0) {
            copy = (char *)malloc((size_t)length == 0 ? 1 : (size_t)length);
            if (copy != NULL) {
                (*env)->GetByteArrayRegion(env, result, 0, length, (jbyte *)copy);
                if ((*env)->ExceptionCheck(env)) {
                    wipe(copy, (size_t)length);
                    free(copy);
                    copy = NULL;
                    result_status = -1;
                } else {
                    *out_length = (size_t)length;
                    result_status = 1;
                }
            } else {
                result_status = -1;
            }
        } else {
            result_status = -1;
        }
    } else if ((*env)->ExceptionCheck(env)) {
        result_status = -1;
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (result != NULL) {
        jsize result_length = (*env)->GetArrayLength(env, result);
        if (result_length > 0 && !(*env)->ExceptionCheck(env)) {
            jbyte *secret_bytes = (*env)->GetByteArrayElements(env, result, NULL);
            if (secret_bytes != NULL) {
                wipe(secret_bytes, (size_t)result_length);
                (*env)->ReleaseByteArrayElements(env, result, secret_bytes, 0);
            }
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, result);
    }
    (*env)->DeleteLocalRef(env, key_string);
    (*env)->DeleteLocalRef(env, local_store);
    release_env(attached);
    *out_value = copy;
    return result_status;
}

int camera_secret_put(const char *key, const char *value, size_t length) {
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env == NULL || length > (size_t)INT32_MAX) return 0;

    pthread_mutex_lock(&g_store_mutex);
    jobject store = g_secret_store;
    jmethodID method = g_put_secret;
    jobject local_store = store == NULL ? NULL : (*env)->NewLocalRef(env, store);
    pthread_mutex_unlock(&g_store_mutex);
    if (local_store == NULL || method == NULL) {
        if (local_store != NULL) (*env)->DeleteLocalRef(env, local_store);
        release_env(attached);
        return 0;
    }

    jstring key_string = new_string(env, key);
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (bytes != NULL && length != 0) {
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)value);
    }
    if (bytes != NULL && !(*env)->ExceptionCheck(env)) {
        (*env)->CallVoidMethod(env, local_store, method, key_string, bytes);
    }
    int success = !(*env)->ExceptionCheck(env);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (bytes != NULL) {
        if (length != 0) {
            jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
            if (data != NULL) {
                wipe(data, length);
                (*env)->ReleaseByteArrayElements(env, bytes, data, 0);
            }
        }
        (*env)->DeleteLocalRef(env, bytes);
    }
    (*env)->DeleteLocalRef(env, key_string);
    (*env)->DeleteLocalRef(env, local_store);
    release_env(attached);
    return success;
}

int camera_secret_remove(const char *key) {
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env == NULL) return 0;

    pthread_mutex_lock(&g_store_mutex);
    jobject store = g_secret_store;
    jmethodID method = g_remove_secret;
    jobject local_store = store == NULL ? NULL : (*env)->NewLocalRef(env, store);
    pthread_mutex_unlock(&g_store_mutex);
    if (local_store == NULL || method == NULL) {
        if (local_store != NULL) (*env)->DeleteLocalRef(env, local_store);
        release_env(attached);
        return 0;
    }

    jstring key_string = new_string(env, key);
    (*env)->CallVoidMethod(env, local_store, method, key_string);
    int success = !(*env)->ExceptionCheck(env);
    if (!success) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, key_string);
    (*env)->DeleteLocalRef(env, local_store);
    release_env(attached);
    return success;
}

int camera_secret_remove_prefix(const char *prefix) {
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env == NULL) return 0;

    pthread_mutex_lock(&g_store_mutex);
    jobject store = g_secret_store;
    jmethodID method = g_remove_secret_prefix;
    jobject local_store = store == NULL ? NULL : (*env)->NewLocalRef(env, store);
    pthread_mutex_unlock(&g_store_mutex);
    if (local_store == NULL || method == NULL) {
        if (local_store != NULL) (*env)->DeleteLocalRef(env, local_store);
        release_env(attached);
        return 0;
    }

    jstring prefix_string = new_string(env, prefix);
    (*env)->CallVoidMethod(env, local_store, method, prefix_string);
    int success = !(*env)->ExceptionCheck(env);
    if (!success) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, prefix_string);
    (*env)->DeleteLocalRef(env, local_store);
    release_env(attached);
    return success;
}

void camera_secret_clear(void *value, size_t length) {
    if (value != NULL) wipe(value, length);
}

void camera_tunnel_log_panic(const char *operation, const char *stage, const char *location, const char *detail) {
    __android_log_print(ANDROID_LOG_ERROR, "CameraTunnel",
            "native_panic operation=%s stage=%s at=%s",
            operation == NULL ? "unknown" : operation,
            stage == NULL ? "unknown" : stage,
            location == NULL ? "unknown" : location);
    if (detail == NULL) return;
    const size_t chunk_size = 900;
    size_t length = strlen(detail);
    for (size_t offset = 0, chunk = 0; offset < length; offset += chunk_size, ++chunk) {
        size_t remaining = length - offset;
        int count = (int)(remaining < chunk_size ? remaining : chunk_size);
        __android_log_print(ANDROID_LOG_ERROR, "CameraTunnel",
                "native_panic_detail operation=%s stage=%s part=%zu: %.*s",
                operation == NULL ? "unknown" : operation,
                stage == NULL ? "unknown" : stage, chunk, count, detail + offset);
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_byd_extend_remote_tunnel_NativeTunnelBridge_nativeInitialize(
    JNIEnv *env, jclass clazz, jobject secret_store, jstring runtime_directory) {
    (void)clazz;
    if (secret_store == NULL || runtime_directory == NULL) return JNI_FALSE;
    jclass store_class = (*env)->GetObjectClass(env, secret_store);
    if (store_class == NULL) return JNI_FALSE;
    jmethodID get_secret = (*env)->GetMethodID(env, store_class, "getNative", "(Ljava/lang/String;)[B");
    jmethodID put_secret = (*env)->GetMethodID(env, store_class, "putNative", "(Ljava/lang/String;[B)V");
    jmethodID remove_secret = (*env)->GetMethodID(env, store_class, "removeNative", "(Ljava/lang/String;)V");
    jmethodID remove_secret_prefix = (*env)->GetMethodID(env, store_class, "removePrefixNative", "(Ljava/lang/String;)V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, store_class);
    if (get_secret == NULL || put_secret == NULL || remove_secret == NULL || remove_secret_prefix == NULL) return JNI_FALSE;

    jstring runtime_path = (jstring)(*env)->NewLocalRef(env, runtime_directory);
    const char *path = (*env)->GetStringUTFChars(env, runtime_path, NULL);
    if (path == NULL) {
        (*env)->DeleteLocalRef(env, runtime_path);
        return JNI_FALSE;
    }
    char *path_copy = strdup(path);
    (*env)->ReleaseStringUTFChars(env, runtime_path, path);
    (*env)->DeleteLocalRef(env, runtime_path);
    if (path_copy == NULL) return JNI_FALSE;
    int runtime_ready = camera_tunnel_set_runtime_dir(path_copy);
    free(path_copy);
    if (runtime_ready == 0) return JNI_FALSE;

    jobject global_store = (*env)->NewGlobalRef(env, secret_store);
    if (global_store == NULL) return JNI_FALSE;
    pthread_mutex_lock(&g_store_mutex);
    jobject old_store = g_secret_store;
    g_secret_store = global_store;
    g_get_secret = get_secret;
    g_put_secret = put_secret;
    g_remove_secret = remove_secret;
    g_remove_secret_prefix = remove_secret_prefix;
    pthread_mutex_unlock(&g_store_mutex);
    if (old_store != NULL) (*env)->DeleteGlobalRef(env, old_store);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_byd_extend_remote_tunnel_NativeTunnelBridge_nativeNetworkChanged(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    camera_tunnel_network_changed();
}

JNIEXPORT void JNICALL
Java_com_byd_extend_remote_tunnel_NativeTunnelBridge_nativeCancel(
    JNIEnv *env, jclass clazz, jstring provider, jstring instance_id) {
    (void)clazz;
    if (provider == NULL || instance_id == NULL) return;
    const char *provider_text = (*env)->GetStringUTFChars(env, provider, NULL);
    const char *instance_text = (*env)->GetStringUTFChars(env, instance_id, NULL);
    if (provider_text != NULL && instance_text != NULL) {
        camera_tunnel_cancel((char *)provider_text, (char *)instance_text);
    }
    if (instance_text != NULL) (*env)->ReleaseStringUTFChars(env, instance_id, instance_text);
    if (provider_text != NULL) (*env)->ReleaseStringUTFChars(env, provider, provider_text);
}

JNIEXPORT jstring JNICALL
Java_com_byd_extend_remote_tunnel_NativeTunnelBridge_nativeCall(
    JNIEnv *env, jclass clazz, jstring operation, jstring request_json) {
    (void)clazz;
    if (operation == NULL || request_json == NULL) return new_string(env, "{\"state\":\"error\",\"errorCode\":\"invalid_request\",\"errorDetail\":\"The tunnel request is invalid.\"}");
    const char *op = (*env)->GetStringUTFChars(env, operation, NULL);
    const char *request = (*env)->GetStringUTFChars(env, request_json, NULL);
    if (op == NULL || request == NULL) {
        if (op != NULL) (*env)->ReleaseStringUTFChars(env, operation, op);
        if (request != NULL) (*env)->ReleaseStringUTFChars(env, request_json, request);
        return new_string(env, "{\"state\":\"error\",\"errorCode\":\"native_unavailable\",\"errorDetail\":\"The tunnel runtime could not start.\"}");
    }
    char *result = camera_tunnel_call((char *)op, (char *)request);
    (*env)->ReleaseStringUTFChars(env, operation, op);
    (*env)->ReleaseStringUTFChars(env, request_json, request);
    jstring output = new_string(env, result);
    if (result != NULL) camera_tunnel_free(result);
    return output;
}


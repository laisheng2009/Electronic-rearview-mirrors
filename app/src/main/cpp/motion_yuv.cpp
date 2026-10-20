#include <jni.h>
#include <stdint.h>
#include <string.h>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

extern "C" JNIEXPORT void JNICALL
Java_com_byd_extend_MotionYuvPacker_packDirect(
        JNIEnv* env, jclass, jobject yBuffer, jint yOffset, jint yRow, jint yPixel,
        jobject uBuffer, jint uOffset, jint uRow, jint uPixel,
        jobject vBuffer, jint vOffset, jint vRow, jint vPixel,
        jint width, jint height, jbyteArray output) {
    auto* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    // Java validates all limits/strides before entering this private direct-buffer path.
    if (!y || !u || !v) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "YUV direct buffer unavailable");
        return;
    }
    y += yOffset; u += uOffset; v += vOffset;
    auto* bytes = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(output, nullptr));
    if (!bytes) return;
    if (yPixel == 1 && yRow == width) {
        memcpy(bytes, y, static_cast<size_t>(width) * height);
    } else {
        for (int row = 0; row < height; ++row) {
            auto* dst = bytes + static_cast<size_t>(row) * width;
            auto* src = y + static_cast<size_t>(row) * yRow;
            if (yPixel == 1) memcpy(dst, src, width);
            else for (int col = 0; col < width; ++col) dst[col] = src[col * yPixel];
        }
    }
    for (int row = 0; row < height / 2; ++row) {
        auto* dst = bytes + static_cast<size_t>(width) * height + static_cast<size_t>(row) * width;
        auto* ur = u + static_cast<size_t>(row) * uRow;
        auto* vr = v + static_cast<size_t>(row) * vRow;
        int col = 0;
#if defined(__ARM_NEON)
        if (uPixel == 1 && vPixel == 1) {
            for (; col + 16 <= width / 2; col += 16) {
                uint8x16x2_t uv = {{vld1q_u8(ur + col), vld1q_u8(vr + col)}};
                vst2q_u8(dst + col * 2, uv);
            }
        } else if (uPixel == 2 && vPixel == 2) {
            // The final plane byte need not have padding after it: do not load its missing partner.
            for (; col + 16 < width / 2; col += 16) {
                uint8x16x2_t uv = {{vld2q_u8(ur + col * 2).val[0], vld2q_u8(vr + col * 2).val[0]}};
                vst2q_u8(dst + col * 2, uv);
            }
        }
#endif
        for (; col < width / 2; ++col) {
            dst[col * 2] = ur[col * uPixel];
            dst[col * 2 + 1] = vr[col * vPixel];
        }
    }
    env->ReleasePrimitiveArrayCritical(output, bytes, 0);
}

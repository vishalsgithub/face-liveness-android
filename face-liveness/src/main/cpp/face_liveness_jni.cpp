// JNI bridge from com.xpertnest.liveness.NativeEngine to the C API in cpp/face_liveness.h.

#include <jni.h>

#include <vector>

#include "face_liveness.h"

namespace {

constexpr int kMaxFaces = 16;

// Result layout: [count or negative error code, then 6 floats per face:
//                 x1, y1, x2, y2, detection confidence, liveness].
jfloatArray ToResult(JNIEnv* env, int32_t count, const FlFace* faces) {
    const int n = count > 0 ? count : 0;
    std::vector<jfloat> values(1 + n * 6);
    values[0] = static_cast<jfloat>(count);
    for (int i = 0; i < n; ++i) {
        jfloat* v = &values[1 + i * 6];
        v[0] = faces[i].x1;
        v[1] = faces[i].y1;
        v[2] = faces[i].x2;
        v[3] = faces[i].y2;
        v[4] = faces[i].detection_confidence;
        v[5] = faces[i].liveness;
    }
    jfloatArray array = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (array) env->SetFloatArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    return array;
}

void* Handle(jlong handle) { return reinterpret_cast<void*>(static_cast<intptr_t>(handle)); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_xpertnest_liveness_NativeEngine_nativeCreate(JNIEnv*, jclass) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(fl_create()));
}

JNIEXPORT void JNICALL Java_com_xpertnest_liveness_NativeEngine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    fl_destroy(Handle(handle));
}

JNIEXPORT jint JNICALL Java_com_xpertnest_liveness_NativeEngine_nativeLoad(JNIEnv* env, jclass, jlong handle,
                                                                     jstring model_dir) {
    const char* dir = env->GetStringUTFChars(model_dir, nullptr);
    const jint ret = fl_load(Handle(handle), dir);
    env->ReleaseStringUTFChars(model_dir, dir);
    return ret;
}

JNIEXPORT jfloatArray JNICALL Java_com_xpertnest_liveness_NativeEngine_nativeDetectYuv420(
    JNIEnv* env, jclass, jlong handle, jobject y, jobject u, jobject v, jint width, jint height,
    jint y_row_stride, jint uv_row_stride, jint uv_pixel_stride, jint orientation, jboolean all_faces) {
    auto* y_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(y));
    auto* u_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(u));
    auto* v_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(v));
    FlFace faces[kMaxFaces];
    const int32_t count =
        fl_detect_yuv420(Handle(handle), y_ptr, u_ptr, v_ptr, width, height, y_row_stride,
                         uv_row_stride, uv_pixel_stride, orientation, all_faces ? 1 : 0, faces, kMaxFaces);
    return ToResult(env, count, faces);
}

JNIEXPORT jfloatArray JNICALL Java_com_xpertnest_liveness_NativeEngine_nativeDetectRgba(
    JNIEnv* env, jclass, jlong handle, jobject pixels, jint width, jint height, jint bytes_per_row,
    jint orientation, jboolean all_faces) {
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(pixels));
    FlFace faces[kMaxFaces];
    const int32_t count = fl_detect(Handle(handle), data, width, height, bytes_per_row, FL_FORMAT_RGBA,
                                    orientation, all_faces ? 1 : 0, faces, kMaxFaces);
    return ToResult(env, count, faces);
}

}  // extern "C"

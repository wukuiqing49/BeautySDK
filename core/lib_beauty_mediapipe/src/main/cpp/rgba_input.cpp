#include <jni.h>
#include <cstdint>

// 与 Kotlin 参考实现一致：最近邻缩小 → 顺时针旋转 → 水平镜像 → 指定行步长的RGB。
// 输入/输出均由宿主持有，本函数不分配内存，不保存指针，不调用模型或 GL。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_beautysdk_mediapipe_NativeRgbaConverter_convert(
    JNIEnv* env, jobject, jobject source, jint source_width, jint source_height,
    jint stride, jint rotation, jboolean mirrored, jobject destination, jint width, jint height, jint destination_stride) {
    const auto* input = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    auto* output = static_cast<uint8_t*>(env->GetDirectBufferAddress(destination));
    const int64_t input_size = env->GetDirectBufferCapacity(source);
    const int64_t output_size = env->GetDirectBufferCapacity(destination);
    if (!input || !output || source_width <= 0 || source_height <= 0 || width <= 0 || height <= 0 ||
        stride < int64_t(source_width) * 4 ||
        input_size < int64_t(source_height - 1) * stride + int64_t(source_width) * 4 ||
        destination_stride < int64_t(width) * 3 ||
        int64_t(destination_stride) * height > output_size ||
        (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) return JNI_FALSE;
    const bool swap = rotation == 90 || rotation == 270;
    const int w = swap ? height : width;
    const int h = swap ? width : height;
    for (int dy = 0; dy < height; ++dy) {
        auto* row = output + int64_t(dy) * destination_stride;
        for (int dx = 0; dx < width; ++dx) {
            const int rx = mirrored ? width - 1 - dx : dx;
            int x, y;
            switch (rotation) {
                case 90: x = dy; y = h - 1 - rx; break;
                case 180: x = w - 1 - rx; y = h - 1 - dy; break;
                case 270: x = w - 1 - dy; y = rx; break;
                default: x = rx; y = dy; break;
            }
            const int64_t sx = int64_t(x) * source_width / w;
            const int64_t sy = int64_t(y) * source_height / h;
            const auto* pixel = input + sy * stride + sx * 4;
            *row++ = pixel[0]; *row++ = pixel[1]; *row++ = pixel[2];
        }
        // 最多3字节的行尾padding清零，避免复用不同尺寸缓冲时残留旧像素。
        for (int64_t offset = int64_t(width) * 3; offset < destination_stride; ++offset) *row++ = 0;
    }
    return JNI_TRUE;
}

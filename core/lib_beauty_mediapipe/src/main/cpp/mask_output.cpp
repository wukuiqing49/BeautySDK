#include <jni.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>

// 同步借用模型浮点缓冲和worker输出缓冲；不分配、不持有JNI引用、不读取外部帧。
// 输出为原始采样空间的R8，语义与旧Kotlin量化完全一致（非有限值为背景）。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_beautysdk_mediapipe_NativeMaskConverter_convert(
    JNIEnv* env, jobject, jobject source, jint width, jint height, jint rotation,
    jboolean mirrored, jobject destination) {
    const auto* input=static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    auto* output=static_cast<uint8_t*>(env->GetDirectBufferAddress(destination));
    if (!input || !output || width<=0 || height<=0 || width>2048 || height>2048 ||
        env->GetDirectBufferCapacity(source)<int64_t(width)*height*4 ||
        env->GetDirectBufferCapacity(destination)<int64_t(width)*height ||
        (rotation!=0 && rotation!=90 && rotation!=180 && rotation!=270)) return JNI_FALSE;
    const bool swap=rotation==90 || rotation==270;
    const int w=swap?height:width;
    const int h=swap?width:height;
    for (int y=0;y<h;++y) {
        for (int x=0;x<w;++x) {
            int rx,ry;
            switch(rotation) {
                case 90: rx=h-1-y;ry=x;break;
                case 180: rx=w-1-x;ry=h-1-y;break;
                case 270: rx=y;ry=w-1-x;break;
                default: rx=x;ry=y;break;
            }
            const int dx=mirrored?width-1-rx:rx;
            float value;
            std::memcpy(&value,input+(int64_t(ry)*width+dx)*4,sizeof(value));
            output[int64_t(y)*w+x]=!std::isfinite(value)?0:
                static_cast<uint8_t>((value<0.f?0.f:value>1.f?1.f:value)*255.f+.5f);
        }
    }
    return JNI_TRUE;
}


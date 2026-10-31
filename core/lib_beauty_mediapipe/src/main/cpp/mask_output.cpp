#include <jni.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>

// 同步借用模型浮点缓冲和worker输出缓冲；不分配、不持有JNI引用、不读取外部帧。
// 输出为原始采样空间的R8，语义与旧Kotlin量化完全一致（非有限值为背景）。
namespace {
jboolean convertMask(JNIEnv* env,jobject source,int width,int height,int rotation,bool mirrored,jobject destination,
    const uint8_t* rgb=nullptr,int rgbWidth=0,int rgbHeight=0,int stride=0,uint8_t* history=nullptr,bool useHistory=false) {
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
            int alpha=!std::isfinite(value)?0:static_cast<int>(std::clamp(value,0.f,1.f)*255.f+.5f);
            if(rgb) {
                const auto* color=rgb+int64_t(ry)*rgbHeight/height*stride+int64_t(dx)*rgbWidth/width*3;
                auto* previous=history+(int64_t(ry)*width+dx)*4;
                if(useHistory && alpha>4 && alpha<251) {
                    const int colorDelta=std::max({std::abs(int(color[0])-previous[0]),
                        std::abs(int(color[1])-previous[1]),std::abs(int(color[2])-previous[2])});
                    const int maskDelta=std::abs(alpha-int(previous[3]));
                    // 只压制相同画面中的轻微波动；运动/遮挡/进出画面直接采用当前观测。
                    const float stableColor=1.f-std::clamp((colorDelta-3)/9.f,0.f,1.f);
                    const float stableMask=1.f-std::clamp((maskDelta-8)/40.f,0.f,1.f);
                    const int correction=int(std::lround((int(previous[3])-alpha)*.65f*stableColor*stableMask));
                    alpha+=std::clamp(correction,-8,8); // 限制相对当前真实结果的最大偏差。
                }
                previous[0]=color[0];previous[1]=color[1];previous[2]=color[2];previous[3]=uint8_t(alpha);
            }
            output[int64_t(y)*w+x]=uint8_t(alpha);
        }
    }
    return JNI_TRUE;
}


}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_beautysdk_mediapipe_NativeMaskConverter_convert(JNIEnv* env,jobject,jobject source,jint width,jint height,
    jint rotation,jboolean mirrored,jobject destination) {
    return convertMask(env,source,width,height,rotation,mirrored,destination);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_beautysdk_mediapipe_NativeMaskConverter_convertStable(JNIEnv* env,jobject,jobject source,jint width,jint height,
    jint rotation,jboolean mirrored,jobject destination,jobject rgb,jint rw,jint rh,jint stride,jobject history,jboolean useHistory) {
    if(!rgb||!history||rw<=0||rh<=0||stride<int64_t(rw)*3||width<=0||height<=0||width>2048||height>2048||
        env->GetDirectBufferCapacity(rgb)<int64_t(rh-1)*stride+int64_t(rw)*3||
        env->GetDirectBufferCapacity(history)<int64_t(width)*height*4)return JNI_FALSE;
    const auto* pixels=static_cast<const uint8_t*>(env->GetDirectBufferAddress(rgb));
    auto* state=static_cast<uint8_t*>(env->GetDirectBufferAddress(history));
    if(!pixels||!state)return JNI_FALSE;
    return convertMask(env,source,width,height,rotation,mirrored,destination,pixels,rw,rh,stride,state,useHistory);
}

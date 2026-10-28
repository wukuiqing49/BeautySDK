#include <jni.h>
#include <cstdint>
#include <cmath>
#include <array>
#include <algorithm>

// 小灰度图上的稀疏零均值块匹配：低纹理剔除、前后向一致性、相似变换一致性。
// 无全图光流、无线程、无堆图像缓存；参考图来自最近模型检测，不进行无限链式积分。
namespace {
constexpr int R=3, SEARCH=12, N=16;
struct Point { float x,y; };
struct Match { Point p,q; };
bool inside(int x,int y,int w,int h) { return x>=R && y>=R && x<w-R && y<h-R; }
bool locate(const uint8_t* from,const uint8_t* to,int w,int h,Point p,Point& out) {
    const int px=int(std::lround(p.x)),py=int(std::lround(p.y));
    if(!inside(px,py,w,h)) return false;
    constexpr int count=(2*R+1)*(2*R+1);
    float sum=0,sq=0;
    for(int y=-R;y<=R;++y) for(int x=-R;x<=R;++x) {float v=from[(py+y)*w+px+x];sum+=v;sq+=v*v;}
    const float variance=sq-sum*sum/count;
    if(variance<count*36.f) return false;
    float best=10.f;int bx=px,by=py;
    for(int dy=-SEARCH;dy<=SEARCH;++dy) for(int dx=-SEARCH;dx<=SEARCH;++dx) {
        const int qx=px+dx,qy=py+dy;
        if(!inside(qx,qy,w,h)) continue;
        float qs=0,qv=0,cross=0;
        for(int y=-R;y<=R;++y) for(int x=-R;x<=R;++x) {
            float a=from[(py+y)*w+px+x],b=to[(qy+y)*w+qx+x];qs+=b;qv+=b*b;cross+=a*b;
        }
        const float v=qv-qs*qs/count;
        if(v<count*36.f) continue;
        const float score=(variance+v-2*(cross-sum*qs/count))/(variance+v);
        if(score<best) { best=score;bx=qx;by=qy; }
    }
    if(best>.20f || std::abs(bx-px)>=SEARCH || std::abs(by-py)>=SEARCH) return false;
    out={float(bx),float(by)};return true;
}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_beautysdk_mediapipe_NativeFaceMotion_gray(JNIEnv* env,jobject,jobject source,jint sw,jint sh,jint stride,jobject dest,jint w,jint h) {
    const auto* src=static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    auto* dst=static_cast<uint8_t*>(env->GetDirectBufferAddress(dest));
    if(!src || !dst || sw<=0 || sh<=0 || w<=0 || h<=0 || w>160 || h>160 || stride<int64_t(sw)*4 ||
       env->GetDirectBufferCapacity(source)<int64_t(sh-1)*stride+int64_t(sw)*4 || env->GetDirectBufferCapacity(dest)<int64_t(w)*h) return JNI_FALSE;
    for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
        const auto* p=src+int64_t(y)*sh/h*stride+int64_t(x)*sw/w*4;
        dst[y*w+x]=uint8_t((77*p[0]+150*p[1]+29*p[2])>>8);
    }
    return JNI_TRUE;
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_beautysdk_mediapipe_NativeFaceMotion_match(JNIEnv* env,jobject,jobject reference,jobject current,jint w,jint h,jfloatArray points) {
    const auto* a=static_cast<const uint8_t*>(env->GetDirectBufferAddress(reference));
    const auto* b=static_cast<const uint8_t*>(env->GetDirectBufferAddress(current));
    if(!a || !b || !points || w<16 || h<16 || w>160 || h>160 ||
       env->GetDirectBufferCapacity(reference)<int64_t(w)*h || env->GetDirectBufferCapacity(current)<int64_t(w)*h) return nullptr;
    const int n=env->GetArrayLength(points)/2;if(n<6 || n>N) return nullptr;
    std::array<float,N*2> coords{};env->GetFloatArrayRegion(points,0,n*2,coords.data());
    if(env->ExceptionCheck()) return nullptr;
    std::array<Match,N> matches{};int count=0;
    for(int i=0;i<n;++i) {
        if(!std::isfinite(coords[i*2]) || !std::isfinite(coords[i*2+1])) return nullptr;
        Point p{coords[i*2]*w,coords[i*2+1]*h},q{},back{};
        if(p.x<0 || p.x>=w || p.y<0 || p.y>=h) continue;
        if(locate(a,b,w,h,p,q) && locate(b,a,w,h,q,back) && std::hypot(back.x-p.x,back.y-p.y)<1.6f)
            matches[count++]={p,q};
    }
    if(count<6) return nullptr;
    float bestA=1,bestB=0,bestX=0,bestY=0;int bestCount=0;
    // 穷举小点集的两点假设，拒绝遮挡/局部形变产生的不一致运动。
    for(int i=0;i<count;++i) for(int j=i+1;j<count;++j) {
        float x=matches[j].p.x-matches[i].p.x,y=matches[j].p.y-matches[i].p.y;
        float u=matches[j].q.x-matches[i].q.x,v=matches[j].q.y-matches[i].q.y,den=x*x+y*y;
        if(den<64) continue;
        float ca=(u*x+v*y)/den,cb=(v*x-u*y)/den;
        float scale=std::hypot(ca,cb);
        if(scale<.85f || scale>1.18f || std::abs(std::atan2(cb,ca))>.35f) continue;
        float tx=matches[i].q.x-ca*matches[i].p.x+cb*matches[i].p.y;
        float ty=matches[i].q.y-cb*matches[i].p.x-ca*matches[i].p.y;
        int good=0;
        for(int k=0;k<count;++k) if(std::hypot(ca*matches[k].p.x-cb*matches[k].p.y+tx-matches[k].q.x,
            cb*matches[k].p.x+ca*matches[k].p.y+ty-matches[k].q.y)<1.6f) ++good;
        if(good>bestCount) {bestCount=good;bestA=ca;bestB=cb;bestX=tx;bestY=ty;}
    }
    if(bestCount<6 || bestCount<count*.7f || bestCount<n*.5f) return nullptr;
    // 用一致点最小二乘细化，避免仅由一对整数匹配点决定姿态而产生抖动。
    float px=0,py=0,qx=0,qy=0;int used=0;std::array<bool,N> inlier{};
    for(int k=0;k<count;++k) {
        const auto& m=matches[k];
        if(std::hypot(bestA*m.p.x-bestB*m.p.y+bestX-m.q.x,bestB*m.p.x+bestA*m.p.y+bestY-m.q.y)<1.6f) {
            inlier[k]=true;++used;px+=m.p.x;py+=m.p.y;qx+=m.q.x;qy+=m.q.y;
        }
    }
    px/=used;py/=used;qx/=used;qy/=used;
    float den=0,dot=0,cross=0;
    for(int k=0;k<count;++k) if(inlier[k]) {
        float x=matches[k].p.x-px,y=matches[k].p.y-py,u=matches[k].q.x-qx,v=matches[k].q.y-qy;
        den+=x*x+y*y;dot+=x*u+y*v;cross+=x*v-y*u;
    }
    if(den<64) return nullptr;
    bestA=dot/den;bestB=cross/den;bestX=qx-bestA*px+bestB*py;bestY=qy-bestB*px-bestA*py;
    if(std::hypot(bestA,bestB)<.85f || std::hypot(bestA,bestB)>1.18f || std::abs(std::atan2(bestB,bestA))>.35f) return nullptr;
    const float result[]={bestA,bestB,bestX,bestY,float(bestCount)/n};
    auto out=env->NewFloatArray(5);if(out) env->SetFloatArrayRegion(out,0,5,result);return out;
}

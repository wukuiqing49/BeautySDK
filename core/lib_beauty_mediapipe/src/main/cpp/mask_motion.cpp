#include <jni.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

// 小图前景稀疏块匹配。固定栈内存，前后向校验及多数一致性失败则拒绝整体平移。
namespace {
constexpr int SIDE=48,R=3,SEARCH=9,MAX=36,PATCH=(2*R+1)*(2*R+1);
using Gray=std::array<uint8_t,SIDE*SIDE>;
struct Point {int x,y;};
struct Shift {float x,y;};
bool inside(int x,int y,int w,int h) {return x>=R && y>=R && x<w-R && y<h-R;}
float variance(const Gray& a,int x,int y,int w) {
    float sum=0,sq=0;
    for(int dy=-R;dy<=R;++dy)for(int dx=-R;dx<=R;++dx){float v=a[(y+dy)*w+x+dx];sum+=v;sq+=v*v;}
    return sq-sum*sum/float(PATCH);
}
bool locate(const Gray& a,const Gray& b,int w,int h,Point p,Point& q) {
    if(!inside(p.x,p.y,w,h))return false;
    float sum=0,sq=0;
    for(int y=-R;y<=R;++y)for(int x=-R;x<=R;++x){float v=a[(p.y+y)*w+p.x+x];sum+=v;sq+=v*v;}
    const float va=sq-sum*sum/float(PATCH);
    if(va<PATCH*36.f)return false;
    float best=1.f;int bx=p.x,by=p.y;
    auto evaluate=[&](int cx,int cy) {
        if(!inside(cx,cy,w,h))return;
        float bs=0,bq=0,cross=0;
        for(int y=-R;y<=R;++y)for(int x=-R;x<=R;++x){
            float av=a[(p.y+y)*w+p.x+x],bv=b[(cy+y)*w+cx+x];bs+=bv;bq+=bv*bv;cross+=av*bv;
        }
        float vb=bq-bs*bs/float(PATCH);
        if(vb<PATCH*36.f)return;
        float score=(va+vb-2*(cross-sum*bs/float(PATCH)))/(va+vb);
        if(score<best){best=score;bx=cx;by=cy;}
    };
    // 有界全搜索保留奇数位移，面积降采样抑制混叠；零位移优先。
    evaluate(p.x,p.y);
    for(int dy=-SEARCH;dy<=SEARCH;++dy)for(int dx=-SEARCH;dx<=SEARCH;++dx)evaluate(p.x+dx,p.y+dy);
    if(best>.16f || std::abs(bx-p.x)>=SEARCH || std::abs(by-p.y)>=SEARCH)return false;
    q={bx,by};return true;
}
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_beautysdk_mediapipe_NativeMaskMotion_estimate(JNIEnv* env,jobject,jobject reference,jobject current,
    jobject confidence,jint width,jint height,jint stride) {
    const auto* ref=static_cast<const uint8_t*>(env->GetDirectBufferAddress(reference));
    const auto* cur=static_cast<const uint8_t*>(env->GetDirectBufferAddress(current));
    const auto* mask=static_cast<const uint8_t*>(env->GetDirectBufferAddress(confidence));
    if(!ref || !cur || !mask || width<24 || height<24 || width>256 || height>256 || stride<int64_t(width)*3 ||
        env->GetDirectBufferCapacity(reference)<int64_t(height-1)*stride+width*3 ||
        env->GetDirectBufferCapacity(current)<int64_t(height-1)*stride+width*3 ||
        env->GetDirectBufferCapacity(confidence)<int64_t(width)*height*4)return nullptr;
    const float scale=float(SIDE)/std::max(width,height);
    const int w=std::max(16,int(std::lround(width*scale))),h=std::max(16,int(std::lround(height*scale)));
    Gray a{},b{};
    for(int y=0;y<h;++y)for(int x=0;x<w;++x){
        const int sx=x*width/w,sy=y*height/h;
        const int ex=std::max(sx+1,(x+1)*width/w),ey=std::max(sy+1,(y+1)*height/h);
        int sumA=0,sumB=0,n=0;
        // 面积降采样抑制奇数位移的混叠，避免最近邻小图把同一纹理变成不相关块。
        for(int yy=sy;yy<ey;++yy)for(int xx=sx;xx<ex;++xx){
            const auto* p=ref+yy*stride+xx*3;const auto* q=cur+yy*stride+xx*3;
            sumA+=(77*p[0]+150*p[1]+29*p[2])>>8;sumB+=(77*q[0]+150*q[1]+29*q[2])>>8;++n;
        }
        a[y*w+x]=uint8_t(sumA/n);b[y*w+x]=uint8_t(sumB/n);
    }
    std::array<Shift,MAX> shifts{};int count=0,selected=0;
    for(int gy=0;gy<6;++gy)for(int gx=0;gx<6;++gx){
        if((gx+gy)%2!=0)continue; // 交错18格，限制每次校正的搜索成本。
        Point p{};float strength=PATCH*36.f;
        // 每格选择有纹理且置信度足够的前景，避免背景主导相机/头部运动估计。
        for(int iy=0;iy<3;++iy)for(int ix=0;ix<3;++ix){
            const int x=R+(gx*3+ix)*(w-2*R-1)/18,y=R+(gy*3+iy)*(h-2*R-1)/18;
            float alpha;const int index=(y*height/h)*width+x*width/w;
            std::memcpy(&alpha,mask+int64_t(index)*4,4);
            if(!std::isfinite(alpha)||alpha<.7f)continue;
            float value=variance(a,x,y,w);if(value>strength){strength=value;p={x,y};}
        }
        if(strength<=PATCH*36.f)continue;
        ++selected;Point q{},back{};
        if(locate(a,b,w,h,p,q)&&locate(b,a,w,h,q,back)&&std::hypot(float(back.x-p.x),float(back.y-p.y))<=1.5f)
            shifts[count++]={float(q.x-p.x),float(q.y-p.y)};
    }
    if(count<6)return nullptr;
    std::array<float,MAX> xs{},ys{};
    for(int i=0;i<count;++i){xs[i]=shifts[i].x;ys[i]=shifts[i].y;}
    std::sort(xs.begin(),xs.begin()+count);std::sort(ys.begin(),ys.begin()+count);
    const float mx=xs[count/2],my=ys[count/2];float dx=0,dy=0;int good=0;
    for(int i=0;i<count;++i)if(std::hypot(shifts[i].x-mx,shifts[i].y-my)<=1.75f){dx+=shifts[i].x;dy+=shifts[i].y;++good;}
    if(good<6 || good<count*.75f || good<selected*.35f)return nullptr;
    const float result[]={dx/good*width/w,dy/good*height/h,float(good)/selected};
    if(std::abs(result[0])>width*.24f || std::abs(result[1])>height*.24f)return nullptr;
    auto out=env->NewFloatArray(3);if(out)env->SetFloatArrayRegion(out,0,3,result);return out;
}

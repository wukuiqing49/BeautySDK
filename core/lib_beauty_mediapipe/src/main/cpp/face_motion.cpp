#include <jni.h>
#include <cstdint>
#include <cmath>
#include <array>
#include <algorithm>

// 小灰度图上的稀疏零均值块匹配：低纹理剔除、前后向一致性、相似变换一致性。
// 无全图光流、无线程、无堆图像缓存；参考图来自运行时有效分析/相邻跟踪帧，模型校正年龄仍由运行时限制。
namespace {
constexpr int R=3, N=16;
struct Point { float x,y; };
struct Match { Point p,q; };
bool inside(int x,int y,int w,int h) { return x>=R && y>=R && x<w-R && y<h-R; }
// 同一个参考块在整个搜索中不变：只提取一次，避免每个候选重复读图/求均值与方差。
struct ReferencePatch {
    std::array<uint8_t,49> values{};
    float sum=0,variance=0;
    bool load(const uint8_t* image,int w,int h,Point p) {
        const int px=int(std::lround(p.x)),py=int(std::lround(p.y));
        if(!inside(px,py,w,h))return false;
        int total=0,squares=0,k=0;
        for(int y=-R;y<=R;++y)for(int x=-R;x<=R;++x) {
            const int v=image[(py+y)*w+px+x];values[k++]=uint8_t(v);total+=v;squares+=v*v;
        }
        sum=float(total);variance=float(squares)-sum*sum/49;
        return variance>=49*36.f;
    }
    float score(const uint8_t* image,int w,int h,int qx,int qy) const {
        if(!inside(qx,qy,w,h))return 10.f;
        int total=0,squares=0,cross=0,k=0;
        for(int y=-R;y<=R;++y)for(int x=-R;x<=R;++x) {
            const int b=image[(qy+y)*w+qx+x];
            total+=b;squares+=b*b;cross+=int(values[k++])*b;
        }
        const float qs=float(total),v=float(squares)-qs*qs/49;
        if(v<49*36.f)return 10.f;
        return (variance+v-2*(float(cross)-sum*qs/49))/(variance+v);
    }
};
// 三层以内的小图金字塔；全部栈内有界存储，不增加模型、运行库或异步队列。
struct GrayLevel { const uint8_t* data;int w,h; };
struct Pyramid {
    std::array<std::array<uint8_t,80*80>,2> storage{};
    std::array<GrayLevel,3> levels{};int count=1;
    Pyramid(const uint8_t* data,int w,int h) {
        levels[0]={data,w,h};
        while(count<3) {
            const auto old=levels[count-1];const int nw=(old.w+1)/2,nh=(old.h+1)/2;
            if(std::min(nw,nh)<12)break;
            auto* dst=storage[count-1].data();
            for(int y=0;y<nh;++y)for(int x=0;x<nw;++x) {
                const int x0=x*2,x1=std::min(x0+1,old.w-1),y0=y*2,y1=std::min(y0+1,old.h-1);
                dst[y*nw+x]=uint8_t((int(old.data[y0*old.w+x0])+old.data[y0*old.w+x1]+
                    old.data[y1*old.w+x0]+old.data[y1*old.w+x1]+2)/4);
            }
            levels[count++]={dst,nw,nh};
        }
    }
};
float sample(GrayLevel image,float x,float y) {
    const int ix=int(std::floor(x)),iy=int(std::floor(y));const float fx=x-ix,fy=y-iy;
    const auto* row=image.data+iy*image.w+ix;
    return (row[0]*(1-fx)+row[1]*fx)*(1-fy)+(row[image.w]*(1-fx)+row[image.w+1]*fx)*fy;
}
bool patchInside(GrayLevel image,Point p,int margin) {
    return p.x>=margin&&p.y>=margin&&p.x<image.w-margin-1&&p.y<image.h-margin-1;
}
bool locate(const Pyramid& from,const Pyramid& to,Point p,Point& out) {
    int top=from.count-1;Point q{};bool captured=false;
    // 常见的小位移先在原层捕获，避免粗层降采样丢失细节；失败才进入大位移金字塔。
    // 两条路径共享最终亚像素光流与残差校验，不放宽质量门槛。
    const auto base=from.levels[0];ReferencePatch fine;
    if(fine.load(base.data,base.w,base.h,p)) {
        float best=10.f;int bx=0,by=0;
        for(int y=-5;y<=5;++y)for(int x=-5;x<=5;++x) {
            const int px=int(std::lround(p.x))+x,py=int(std::lround(p.y))+y;
            const float score=fine.score(to.levels[0].data,base.w,base.h,px,py);
            if(score<best){best=score;bx=x;by=y;q={float(px),float(py)};}
        }
        if(best<=.20f&&std::abs(bx)<5&&std::abs(by)<5){captured=true;top=0;}
    }
    for(int level=top;level>=0;--level) {
        const auto a=from.levels[level],b=to.levels[level];
        const float factor=float(1<<level);const Point ref{p.x/factor,p.y/factor};
        if(!patchInside(a,ref,R+1))return false;
        if(level==top && !captured) {
            // 最粗层只做一次有界捕获，不在每个高清点上扩大穷举窗口。
            ReferencePatch patch;if(!patch.load(a.data,a.w,a.h,ref))return false;
            float best=10.f;
            for(int dy=-6;dy<=6;++dy)for(int dx=-6;dx<=6;++dx) {
                const int x=int(std::lround(ref.x))+dx,y=int(std::lround(ref.y))+dy;
                const float score=patch.score(b.data,b.w,b.h,x,y);
                if(score<best){best=score;q={float(x),float(y)};}
            }
            if(best>.6f)return false;
        } else if(level!=top) {q.x*=2;q.y*=2;}
        std::array<float,49> values{},gx{},gy{};int k=0;
        float mean=0,mx=0,my=0;
        for(int y=-R;y<=R;++y)for(int x=-R;x<=R;++x) {
            const float px=ref.x+x,py=ref.y+y;
            values[k]=sample(a,px,py);
            gx[k]=.5f*(sample(a,px+1,py)-sample(a,px-1,py));
            gy[k]=.5f*(sample(a,px,py+1)-sample(a,px,py-1));
            mean+=values[k];mx+=gx[k];my+=gy[k];++k;
        }
        mean/=49;mx/=49;my/=49;float xx=0,xy=0,yy=0,variance=0;
        for(int i=0;i<49;++i){values[i]-=mean;gx[i]-=mx;gy[i]-=my;
            xx+=gx[i]*gx[i];xy+=gx[i]*gy[i];yy+=gy[i]*gy[i];variance+=values[i]*values[i];}
        const float determinant=xx*yy-xy*xy;
        if(variance<49*36.f||determinant<=1e-4f||determinant/(xx+yy)<1.f)return false;
        for(int iteration=0;iteration<12;++iteration) {
            if(!patchInside(b,q,R))return false;
            std::array<float,49> target{};float average=0;k=0;
            for(int y=-R;y<=R;++y)for(int x=-R;x<=R;++x){target[k]=sample(b,q.x+x,q.y+y);average+=target[k++];}
            average/=49;float ex=0,ey=0;
            for(int i=0;i<49;++i){const float error=target[i]-average-values[i];ex+=gx[i]*error;ey+=gy[i]*error;}
            const float dx=(xy*ey-yy*ex)/determinant,dy=(xy*ex-xx*ey)/determinant;
            if(!std::isfinite(dx)||!std::isfinite(dy)||std::abs(dx)>4||std::abs(dy)>4)return false;
            q.x+=dx;q.y+=dy;
            if(dx*dx+dy*dy<.0001f)break;
        }
        if(!patchInside(b,q,R))return false;
        if(level==0) {
            float sum=0,squares=0,cross=0;k=0;
            for(int y=-R;y<=R;++y)for(int x=-R;x<=R;++x){const float v=sample(b,q.x+x,q.y+y);sum+=v;squares+=v*v;cross+=values[k++]*v;}
            const float v=squares-sum*sum/49;
            if(v<49*36.f||(variance+v-2*cross)/(variance+v)>.20f)return false;
        }
    }
    out=q;return true;
}
}
namespace {
bool validGraySource(JNIEnv* env,jobject source,int sw,int sh,int stride) {
    return source && sw>0 && sh>0 && stride>=int64_t(sw)*4 &&
        env->GetDirectBufferAddress(source) &&
        env->GetDirectBufferCapacity(source)>=int64_t(sh-1)*stride+int64_t(sw)*4;
}
bool validGrayDestination(JNIEnv* env,jobject destination,int w,int h) {
    return destination && w>0 && h>0 && w<=320 && h<=320 &&
        env->GetDirectBufferAddress(destination) && env->GetDirectBufferCapacity(destination)>=int64_t(w)*h;
}
void fillGray(const uint8_t* src,int sw,int sh,int stride,uint8_t* dst,int w,int h) {
    for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
        const auto* p=src+int64_t(y)*sh/h*stride+int64_t(x)*sw/w*4;
        dst[y*w+x]=uint8_t((77*p[0]+150*p[1]+29*p[2])>>8);
    }
}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_beautysdk_mediapipe_NativeFaceMotion_gray(JNIEnv* env,jobject,jobject source,jint sw,jint sh,jint stride,jobject dest,jint w,jint h) {
    if(!validGraySource(env,source,sw,sh,stride)||!validGrayDestination(env,dest,w,h))return JNI_FALSE;
    const auto* src=static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    auto* dst=static_cast<uint8_t*>(env->GetDirectBufferAddress(dest));
    fillGray(src,sw,sh,stride,dst,w,h);
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_beautysdk_mediapipe_NativeFaceMotion_grayPair(JNIEnv* env,jobject,jobject source,jint sw,jint sh,jint stride,
    jobject dest,jint w,jint h,jobject detail,jint dw,jint dh) {
    // 写入前完成两级容量检查；不让非法细节缓冲造成半张参考图已被覆盖。
    if(!validGraySource(env,source,sw,sh,stride)||!validGrayDestination(env,dest,w,h)||
        !validGrayDestination(env,detail,dw,dh))return JNI_FALSE;
    const auto* src=static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    auto* dst=static_cast<uint8_t*>(env->GetDirectBufferAddress(dest));
    auto* fine=static_cast<uint8_t*>(env->GetDirectBufferAddress(detail));
    if(dst==fine && (w!=dw || h!=dh))return JNI_FALSE;
    fillGray(src,sw,sh,stride,fine,dw,dh);
    if(dst==fine)return JNI_TRUE;
    if(dw%w==0 && dh%h==0) {
        // floor((x*dw/w)*sw/dw)==floor(x*sw/w)：只在整数嵌套格使用，逐字节等价。
        const int sx=dw/w,sy=dh/h;
        for(int y=0;y<h;++y)for(int x=0;x<w;++x)dst[y*w+x]=fine[y*sy*dw+x*sx];
    } else {
        // 奇数尺寸/取整后的格不嵌套，保留原RGBA采样，不能用近似缩小改变唇部跟踪。
        fillGray(src,sw,sh,stride,dst,w,h);
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
    const Pyramid previous(a,w,h),next(b,w,h);
    std::array<Match,N> matches{};int count=0;
    for(int i=0;i<n;++i) {
        if(!std::isfinite(coords[i*2]) || !std::isfinite(coords[i*2+1])) return nullptr;
        Point p{coords[i*2]*w,coords[i*2+1]*h},q{},back{};
        if(p.x<0 || p.x>=w || p.y<0 || p.y>=h) continue;
        if(locate(previous,next,p,q) && locate(next,previous,q,back) && std::hypot(back.x-p.x,back.y-p.y)<1.6f)
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

namespace {
// 在整脸预测附近寻找局部残差。双向校验与弱纹理拒绝避免把遮挡当嘴唇运动。
bool localMatch(const uint8_t* a,const uint8_t* b,int w,int h,Point p,Point predicted,Point& out) {
    ReferencePatch patch;if(!patch.load(a,w,h,p))return false;
    const int qx=int(std::lround(predicted.x)),qy=int(std::lround(predicted.y));
    constexpr int radius=4;float best=10.f;int bx=0,by=0;
    for(int y=-radius;y<=radius;++y)for(int x=-radius;x<=radius;++x) {
        float score=patch.score(b,w,h,int(std::lround(predicted.x+x)),int(std::lround(predicted.y+y)));
        if(score<best){best=score;bx=x;by=y;}
    }
    if(best>.18f||std::abs(bx)==radius||std::abs(by)==radius)return false;
    Point q{float(qx+bx),float(qy+by)};
    // 二次峰值拟合仅做亚像素定位，不按时钟外推。
    auto sub=[](float left,float center,float right){float den=left-2*center+right;
        return den>1e-5f?std::clamp(.5f*(left-right)/den,-.5f,.5f):0.f;};
    out={q.x+p.x-std::round(p.x)+sub(patch.score(b,w,h,int(q.x)-1,int(q.y)),best,patch.score(b,w,h,int(q.x)+1,int(q.y))),
         q.y+p.y-std::round(p.y)+sub(patch.score(b,w,h,int(q.x),int(q.y)-1),best,patch.score(b,w,h,int(q.x),int(q.y)+1))};
    return true;
}
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_beautysdk_mediapipe_NativeFaceMotion_refineLips(JNIEnv* env,jobject,jobject reference,jobject current,jint w,jint h,jfloatArray points,jfloatArray predicted) {
    constexpr int lips=20;
    auto* a=static_cast<const uint8_t*>(env->GetDirectBufferAddress(reference));
    auto* b=static_cast<const uint8_t*>(env->GetDirectBufferAddress(current));
    if(!a||!b||!points||!predicted||w<16||h<16||w>320||h>320||env->GetArrayLength(points)!=lips*2||env->GetArrayLength(predicted)!=lips*2||
       env->GetDirectBufferCapacity(reference)<int64_t(w)*h||env->GetDirectBufferCapacity(current)<int64_t(w)*h)return nullptr;
    std::array<float,lips*2> p{},q{},result{};
    env->GetFloatArrayRegion(points,0,lips*2,p.data());env->GetFloatArrayRegion(predicted,0,lips*2,q.data());
    if(env->ExceptionCheck())return nullptr;
    std::array<Point,lips> residual{};std::array<bool,lips> valid{};int count=0;
    for(int i=0;i<lips;++i) {
        if(!std::isfinite(p[i*2])||!std::isfinite(p[i*2+1])||!std::isfinite(q[i*2])||!std::isfinite(q[i*2+1])||
           p[i*2]<0||p[i*2]>1||p[i*2+1]<0||p[i*2+1]>1||q[i*2]<0||q[i*2]>1||q[i*2+1]<0||q[i*2+1]>1)return nullptr;
        Point start{p[i*2]*w,p[i*2+1]*h},guess{q[i*2]*w,q[i*2+1]*h},found{},back{};
        if(localMatch(a,b,w,h,start,guess,found)&&localMatch(b,a,w,h,found,start,back)&&std::hypot(back.x-start.x,back.y-start.y)<1.2f) {
            valid[i]=true;residual[i]={found.x-guess.x,found.y-guess.y};++count;
        }
    }
    // 恢复先前20点匹配预算；局部遮挡不能因单个嘴角失配撤销全部可靠点。
    // 仍要求至少70%通过，保留双向/弱纹理/搜索边界检查，不放宽匹配质量阈值。
    if(count<14)return nullptr;
    for(int i=0;i<lips;++i) {
        Point r=residual[i];
        if(!valid[i]) {
            float total=0;r={0,0};
            for(int j=0;j<lips;++j)if(valid[j]) {
                float dx=(p[i*2]-p[j*2])*w,dy=(p[i*2+1]-p[j*2+1])*h;
                float weight=1.f/(1.f+dx*dx+dy*dy);total+=weight;r.x+=weight*residual[j].x;r.y+=weight*residual[j].y;
            }
            r.x/=total;r.y/=total;
        }
        result[i*2]=q[i*2]+r.x/w;result[i*2+1]=q[i*2+1]+r.y/h;
    }
    auto out=env->NewFloatArray(lips*2);if(out)env->SetFloatArrayRegion(out,0,lips*2,result.data());return out;
}

// GPUPixel 算法适配：Copyright © 2021 PixPark，Apache-2.0。
// 上游 Shader、参考代码及来源见 third_party/gpupixel；本文件为 BeautySDK 的 JNI/EGL 适配。
#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <array>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include "gpupixel_shaders.h"

namespace {
constexpr std::array<GLenum, 8> kFlags = {GL_BLEND, GL_DEPTH_TEST, GL_STENCIL_TEST,
    GL_SCISSOR_TEST, GL_CULL_FACE, GL_RASTERIZER_DISCARD, GL_SAMPLE_ALPHA_TO_COVERAGE, GL_SAMPLE_COVERAGE};

// 完整保存本适配器触及的 ES3 状态；包括美白 LUT 使用的 0 至 6 号纹理单元。
struct State {
    GLint program{}, draw{}, read{}, active{}, vao{}, buffer{}, unpack{};
    GLint viewport[4]{}, store[4]{};
    GLboolean mask[4]{};
    std::array<GLboolean, kFlags.size()> flags{};
    GLint textures[7]{}, samplers[7]{}, oes{};
    static constexpr GLenum stores[] = {GL_UNPACK_ALIGNMENT, GL_UNPACK_ROW_LENGTH,
        GL_UNPACK_SKIP_ROWS, GL_UNPACK_SKIP_PIXELS};
    State() {
        glGetIntegerv(GL_CURRENT_PROGRAM, &program);
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &active);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &buffer);
        glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpack);
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetBooleanv(GL_COLOR_WRITEMASK, mask);
        for (size_t i=0; i<kFlags.size(); ++i) flags[i] = glIsEnabled(kFlags[i]);
        for (int i=0; i<4; ++i) glGetIntegerv(stores[i], &store[i]);
        for (int i=0; i<7; ++i) {
            glActiveTexture(GL_TEXTURE0+i);
            glGetIntegerv(GL_TEXTURE_BINDING_2D, &textures[i]);
            glGetIntegerv(GL_SAMPLER_BINDING, &samplers[i]);
        }
        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_EXTERNAL_OES, &oes);
    }
    ~State() {
        glUseProgram(program);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, buffer);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
        for (int i=0; i<4; ++i) glPixelStorei(stores[i], store[i]);
        glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        glColorMask(mask[0],mask[1],mask[2],mask[3]);
        for (size_t i=0; i<kFlags.size(); ++i) {
            if (flags[i]) glEnable(kFlags[i]); else glDisable(kFlags[i]);
        }
        for (int i=0; i<7; ++i) {
            glActiveTexture(GL_TEXTURE0+i);
            glBindTexture(GL_TEXTURE_2D,textures[i]); glBindSampler(i,samplers[i]);
        }
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_EXTERNAL_OES,oes);
        glActiveTexture(active);
    }
};

// 编译失败提供日志；绝不向 Java 层返回一个不可用的 program。
GLuint Shader(GLenum type, const std::string& text) {
    GLuint id=glCreateShader(type);
    const char* source=text.c_str(); glShaderSource(id,1,&source,nullptr); glCompileShader(id);
    GLint ok{}; glGetShaderiv(id,GL_COMPILE_STATUS,&ok);
    if (!ok) {
        char log[4096]{}; glGetShaderInfoLog(id,sizeof(log),nullptr,log); glDeleteShader(id);
        throw std::runtime_error(std::string("GPUPixel shader: ")+log);
    }
    return id;
}

// 每个 program 在初始化时缓存 uniform 位置，不在视频逐帧路径查询。
struct Program {
    GLuint id{};
    GLint image{}, transform{}, step{}, image2{}, image3{}, smooth{}, white{}, width{}, height{}, sharpen{};
    GLint lut[4]{}, controls{}, pairs{}, eyes{}, aspect{}, slim{}, big{}, intensity{}, makeupKind{}, regions{}, down{}, extra{};
    void Init(const std::string& vertex, const std::string& fragment) {
        GLuint v=Shader(GL_VERTEX_SHADER,vertex), f=0;
        try {
            f=Shader(GL_FRAGMENT_SHADER,fragment);
            id=glCreateProgram(); glAttachShader(id,v); glAttachShader(id,f);
            glBindAttribLocation(id,0,"position"); glBindAttribLocation(id,1,"inputTextureCoordinate");
            glLinkProgram(id);
            GLint ok{}; glGetProgramiv(id,GL_LINK_STATUS,&ok);
            if (!ok) {
                char log[4096]{}; glGetProgramInfoLog(id,sizeof(log),nullptr,log);
                throw std::runtime_error(std::string("GPUPixel program: ")+log);
            }
        } catch (...) { glDeleteShader(v); if (f) glDeleteShader(f); throw; }
        glDeleteShader(v); glDeleteShader(f);
        image=glGetUniformLocation(id,"inputImageTexture"); transform=glGetUniformLocation(id,"transform");
        step=glGetUniformLocation(id,"stepOffset"); image2=glGetUniformLocation(id,"inputImageTexture2");
        image3=glGetUniformLocation(id,"inputImageTexture3"); smooth=glGetUniformLocation(id,"blurAlpha");
        white=glGetUniformLocation(id,"whiten"); width=glGetUniformLocation(id,"widthOffset");
        height=glGetUniformLocation(id,"heightOffset"); sharpen=glGetUniformLocation(id,"sharpen");
        controls=glGetUniformLocation(id,"controls");pairs=glGetUniformLocation(id,"pairs[0]");
        eyes=glGetUniformLocation(id,"eyes[0]");aspect=glGetUniformLocation(id,"aspect");
        slim=glGetUniformLocation(id,"slim");big=glGetUniformLocation(id,"big");
        intensity=glGetUniformLocation(id,"intensity");
        makeupKind=glGetUniformLocation(id,"makeupKind");
        regions=glGetUniformLocation(id,"regions[0]");down=glGetUniformLocation(id,"faceDown");
        extra=glGetUniformLocation(id,"extra");
        const char* names[]={"lookUpGray","lookUpOrigin","lookUpSkin","lookUpCustom"};
        for (int i=0; i<4; ++i) lut[i]=glGetUniformLocation(id,names[i]);
    }
};

constexpr const char* kVertex = R"(
attribute vec4 position; attribute vec2 inputTextureCoordinate;
varying highp vec2 uv;
void main(){ gl_Position=position; uv=inputTextureCoordinate; }
)";
constexpr const char* kNormalizeVertex = R"(#version 300 es
in vec4 position; in vec2 inputTextureCoordinate;
out vec2 uv;
void main(){ gl_Position=position; uv=inputTextureCoordinate; }
)";
// radius=4 的 BoxMonoBlurFilter 特化：通过线性采样合并相邻两个点，9 tap 变成 5 次采样。
constexpr const char* kBlur = R"(
precision highp float; varying highp vec2 uv;
uniform sampler2D inputImageTexture; uniform vec2 stepOffset;
void main(){
  gl_FragColor=texture2D(inputImageTexture,uv)/9.0;
  gl_FragColor+=(texture2D(inputImageTexture,uv+stepOffset*1.5)
               +texture2D(inputImageTexture,uv-stepOffset*1.5)
               +texture2D(inputImageTexture,uv+stepOffset*3.5)
               +texture2D(inputImageTexture,uv-stepOffset*3.5))*(2.0/9.0);
})";
// 对应 BoxDifferenceFilter，delta 保持上游默认值 7.07。
constexpr const char* kDifference = R"(
precision highp float; varying highp vec2 uv;
uniform sampler2D inputImageTexture; uniform sampler2D inputImageTexture2;
void main(){ vec3 d=(texture2D(inputImageTexture,uv).rgb-texture2D(inputImageTexture2,uv).rgb)*7.07;
gl_FragColor=vec4(min(d*d,vec3(1.0)),1.0); }
)";

// 调色合并为一次绘制。锐化仅开启时读取邻域；alpha始终取中心采样。
// 红润为肤色倾向的全图色彩调整，不声称人脸分割/局部腮红。
constexpr const char* kAdjust = R"(
precision highp float; varying highp vec2 uv;
uniform sampler2D inputImageTexture; uniform vec2 stepOffset;
uniform float controls[5];
void main(){
 vec4 c=texture2D(inputImageTexture,uv); vec3 rgb=c.rgb;
 if(controls[4]>0.0){
  vec3 mean=(texture2D(inputImageTexture,uv+vec2(stepOffset.x,0.)).rgb+
   texture2D(inputImageTexture,uv-vec2(stepOffset.x,0.)).rgb+
   texture2D(inputImageTexture,uv+vec2(0.,stepOffset.y)).rgb+
   texture2D(inputImageTexture,uv-vec2(0.,stepOffset.y)).rgb)*.25;
  rgb+=controls[4]*(rgb-mean)*.8;
 }
 rgb=(rgb-.5)*controls[2]+.5+controls[1]*.25;
 float luma=dot(rgb,vec3(.2126,.7152,.0722));
 rgb=mix(vec3(luma),rgb,controls[3]);
 float skin=clamp((rgb.r-rgb.b)*3.,0.,1.)*clamp((rgb.g-rgb.b)*4.,0.,1.);
 rgb+=controls[0]*skin*vec3(.08,-.015,.015);
 gl_FragColor=vec4(clamp(rgb,0.,1.),c.a);
})";
// 改编自固定版本GPUPixel face_reshape_filter的curveWarp/enlargeEye。
// 输入已映射为MediaPipe语义点；不导入上游检测器或假装106点与478点可直接互换。
constexpr const char* kWarp = R"(
uniform vec4 pairs[9]; uniform vec3 eyes[2];
uniform float aspect; uniform float slim; uniform float big;
// 以下三项为BeautySDK局部扩展，合并在原美型pass，不额外分配FBO。
uniform vec4 regions[3]; uniform vec2 faceDown; uniform vec3 extra;
float influence(vec2 d,vec2 radii){
 float r2=dot(d/max(radii,vec2(.00001)),d/max(radii,vec2(.00001)));
 float t=max(1.-r2,0.);return t*t;
}
float metric(vec2 v){return length(vec2(v.x,v.y/aspect));}
vec2 warp(vec2 p){
 if(slim>0.) for(int i=0;i<9;++i){
  vec2 start=pairs[i].xy,end=pairs[i].zw;
  float radius=max(metric(end-start),.0001);
  // 三次平滑衰减让影响域边界的一阶导数连续，减少轮廓折痕。
  float ratio=1.-smoothstep(0.,1.,metric(p-start)/radius);
  p-=(end-start)*(slim*.10)*ratio;
 }
 if(big>0.) for(int i=0;i<2;++i){
  float distanceToEye=metric(p-eyes[i].xy)/max(eyes[i].z,.0001);
  // 旧二次曲线在眼区边界突然截断；平滑曲线保留中心放大并柔和退出。
  float weight=1.-(1.-smoothstep(0.,1.,distanceToEye))*(big*.18);
  p=eyes[i].xy+(p-eyes[i].xy)*weight;
 }
 // 原有美型单独启用时不执行扩展区域计算。
 if(all(equal(extra,vec3(0.)))) return p;
 // 使用像素等距空间和脸部方向，支持原始帧旋转、镜像与非方形分辨率。
 vec2 q=vec2(p.x,p.y/aspect);vec2 right=vec2(faceDown.y,-faceDown.x);
 if(extra.x>0.) {
  vec2 d=q-vec2(regions[0].x,regions[0].y/aspect);
  vec2 local=vec2(dot(d,right),dot(d,faceDown));
  q+=right*local.x*(extra.x*.35)*influence(local,regions[0].zw);
 }
 if(extra.y!=0.) {
  vec2 d=q-vec2(regions[1].x,regions[1].y/aspect);
  q-=faceDown*(extra.y*regions[1].w)*influence(d,vec2(regions[1].z));
 }
 if(extra.z!=0.) {
  vec2 d=q-vec2(regions[2].x,regions[2].y/aspect);
  vec2 local=vec2(dot(d,right),dot(d,faceDown));
  q-=d*(extra.z*.18)*influence(local,regions[2].zw);
 }
 return vec2(q.x,q.y*aspect);
}
void main(){ color=texture(inputImageTexture,(transform*vec4(warp(uv),0.,1.)).xy); }
)";

// 局部网格贴妆：保留原始明暗细节，分开控制口红染色与腮红柔光；不改视频alpha。
constexpr const char* kMakeupVertex=R"(#version 300 es
in vec2 position; in vec2 inputTextureCoordinate;
out vec2 uv; out vec2 sourceUv;
void main(){gl_Position=vec4(position,0.,1.);uv=inputTextureCoordinate;sourceUv=position*.5+.5;}
)";
constexpr const char* kMakeupBody=R"(
uniform sampler2D inputImageTexture2; uniform float intensity; uniform int makeupKind;
void main(){
 vec4 bg=texture(inputImageTexture,(transform*vec4(sourceUv,0.,1.)).xy);
 // 程序化睫毛沿眼睑生成细条曲面；导数抗锯齿，不需要逐帧Bitmap或额外贴图。
 if(makeupKind==2){
  float edge=abs(uv.x*2.-1.);
  float feather=max(fwidth(edge),.04);
  float coverage=1.-smoothstep(1.-feather,1.,edge);
  coverage*=1.-smoothstep(.86,1.,uv.y);
  color=vec4(mix(bg.rgb,vec3(.035,.025,.025),coverage*intensity),bg.a);
  return;
 }
 vec4 fg=texture(inputImageTexture2,uv);
 // 资源素材为直通RGBA；保留输入透明度，使用连续网格而非刚性眼镜四边形。
 if(makeupKind==3){color=vec4(mix(bg.rgb,fg.rgb,fg.a*intensity),bg.a);return;}
 vec3 weights=vec3(.2126,.7152,.0722);
 float luminance=dot(bg.rgb,weights);
 // 白色是上游素材的中性区域，不能直接把白底当唇色覆盖。
 float chroma=max(fg.r,max(fg.g,fg.b))-min(fg.r,min(fg.g,fg.b));
 float coverage=fg.a*smoothstep(.015,.16,chroma);
 vec3 target;
 if(makeupKind==0){
  // 用素材色相染色，明度来自原唇，避免乘色反复压暗；保留原唇明暗纹理。
  float materialLuma=max(dot(fg.rgb,weights),.08);
  target=clamp(fg.rgb*(luminance/materialLuma),0.,1.);
  coverage*=.72;
 }else{
  // 每半张腮红纹理对应一个脸颊；椭圆衰减避免矩形边界，柔光避免脏黑。
  vec2 local=vec2(fract(uv.x*2.),uv.y)*2.-1.;
  coverage*=1.-smoothstep(.25,1.,dot(local,local));
  vec3 soft=(1.-2.*fg.rgb)*bg.rgb*bg.rgb+2.*fg.rgb*bg.rgb;
  target=clamp(soft,0.,1.);
  coverage*=.65;
 }
 color=vec4(mix(bg.rgb,target,clamp(coverage*intensity,0.,1.)),bg.a);
}
)";

// FBO 按尺寸复用；五个槽分别存归一化输入、水平均值、均值、差分及最终结果。
struct Target { GLuint texture{}, framebuffer{}; };
struct Renderer {
    EGLContext context=eglGetCurrentContext();
    std::thread::id thread=std::this_thread::get_id();
    Program normalize2d, normalizeOes, blur, difference, beauty, adjust, warp2d, warpOes, makeup2d, makeupOes;
    GLuint vao{}, vbo{}, meshVao{}, meshVbo{}, luts[6]{};
    std::array<Target,5> targets{};
    int width{}, height{};
    bool abandon=false;
    void Check() const {
        if (context==EGL_NO_CONTEXT || context!=eglGetCurrentContext() || thread!=std::this_thread::get_id())
            throw std::runtime_error("GPUPixel requires the creating EGL context/thread");
    }
    ~Renderer() {
        if (abandon) return;
        for (auto* p : {&normalize2d,&normalizeOes,&blur,&difference,&beauty,&adjust,&warp2d,&warpOes,&makeup2d,&makeupOes}) if (p->id) glDeleteProgram(p->id);
        glDeleteTextures(6,luts);
        for (auto& t:targets) { glDeleteTextures(1,&t.texture); glDeleteFramebuffers(1,&t.framebuffer); }
        glDeleteBuffers(1,&vbo); glDeleteVertexArrays(1,&vao);
        glDeleteBuffers(1,&meshVbo); glDeleteVertexArrays(1,&meshVao);
    }
    void Prepare() const {
        for (auto flag:kFlags) glDisable(flag);
        glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
        glBindVertexArray(vao);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
        glPixelStorei(GL_UNPACK_ALIGNMENT,1); glPixelStorei(GL_UNPACK_ROW_LENGTH,0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS,0); glPixelStorei(GL_UNPACK_SKIP_PIXELS,0);
        for (int i=0; i<7; ++i) glBindSampler(i,0);
    }
    void Init(JNIEnv* env,jobjectArray buffers,jintArray dimensions) {
        Check();
        const std::string body="precision highp float; in vec2 uv; out vec4 color; uniform mat4 transform;\n";
        const std::string sample="void main(){color=texture(inputImageTexture,(transform*vec4(uv,0.,1.)).xy);}";
        normalize2d.Init(kNormalizeVertex,"#version 300 es\n"+body+"uniform sampler2D inputImageTexture;\n"+sample);
        // OES 程序按首次 OES 输入延迟创建，纯 2D 宿主不依赖外部纹理扩展。
        blur.Init(kVertex,kBlur); difference.Init(kVertex,kDifference);
        beauty.Init(kBeautyVertex,kBeautyFragment);
        adjust.Init(kVertex,kAdjust);
        const GLfloat vertices[]={-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1};
        glGenVertexArrays(1,&vao); glBindVertexArray(vao);
        glGenBuffers(1,&vbo); glBindBuffer(GL_ARRAY_BUFFER,vbo);
        glBufferData(GL_ARRAY_BUFFER,sizeof(vertices),vertices,GL_STATIC_DRAW);
        glEnableVertexAttribArray(0); glEnableVertexAttribArray(1);
        glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,4*sizeof(float),nullptr);
        glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,4*sizeof(float),reinterpret_cast<void*>(2*sizeof(float)));
        // 独立网格VAO，避免更改宿主或全屏四边形的顶点属性。
        glGenVertexArrays(1,&meshVao);glBindVertexArray(meshVao);
        glGenBuffers(1,&meshVbo);glBindBuffer(GL_ARRAY_BUFFER,meshVbo);
        glBufferData(GL_ARRAY_BUFFER,1440*sizeof(float),nullptr,GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(0);glEnableVertexAttribArray(1);
        glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,4*sizeof(float),nullptr);
        glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,4*sizeof(float),reinterpret_cast<void*>(2*sizeof(float)));
        Prepare();
        jint sizes[12]{}; env->GetIntArrayRegion(dimensions,0,12,sizes);
        glGenTextures(6,luts);
        for (int i=0; i<6; ++i) {
            auto buffer=env->GetObjectArrayElement(buffers,i);
            auto data=env->GetDirectBufferAddress(buffer);
            auto capacity=env->GetDirectBufferCapacity(buffer);
            const int w=sizes[i*2], h=sizes[i*2+1];
            if (!data || w<=0 || h<=0 || w>4096 || h>4096 || capacity<static_cast<jlong>(w)*h*4) {
                env->DeleteLocalRef(buffer); throw std::runtime_error("Invalid GPUPixel LUT buffer");
            }
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,luts[i]);
            Parameters(); glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,data);
            env->DeleteLocalRef(buffer);
        }
    }
    static void Parameters() {
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    }
    void Allocate(int w,int h) {
        if (width==w && height==h) return;
        GLint max{}; glGetIntegerv(GL_MAX_TEXTURE_SIZE,&max);
        if (w<=0 || h<=0 || w>max || h>max) throw std::runtime_error("Invalid GPUPixel frame dimensions");
        for (auto& t:targets) {
            if (!t.texture) { glGenTextures(1,&t.texture); glGenFramebuffers(1,&t.framebuffer); }
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,t.texture); Parameters();
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,t.framebuffer);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,t.texture,0);
            if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
                throw std::runtime_error("Incomplete GPUPixel framebuffer");
        }
        width=w; height=h;
    }
    static void Bind(const Program& p,GLuint texture,int unit,GLint location,GLenum target=GL_TEXTURE_2D) {
        glUseProgram(p.id); glActiveTexture(GL_TEXTURE0+unit); glBindTexture(target,texture); glUniform1i(location,unit);
    }
    void Draw(int target) const {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,targets[target].framebuffer);
        glViewport(0,0,width,height); glDrawArrays(GL_TRIANGLE_STRIP,0,4);
    }
    GLuint Render(GLuint input,bool oes,int w,int h,const float* matrix,float smoothing,float whitening,const float* controls) {
        Check(); State saved; Prepare();
        for (const auto& t:targets) if (t.texture && t.texture==input)
            throw std::runtime_error("Cannot feed GPUPixel output back into the same renderer");
        Allocate(w,h);
        auto& normal=oes ? normalizeOes : normalize2d;
        if (!normal.id) {
            normal.Init(kNormalizeVertex,std::string("#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n")+
                "precision highp float; in vec2 uv; out vec4 color; uniform mat4 transform;\n"
                "uniform samplerExternalOES inputImageTexture;\n"
                "void main(){color=texture(inputImageTexture,(transform*vec4(uv,0.,1.)).xy);}");
        }
        Bind(normal,input,0,normal.image,oes ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D);
        glUniformMatrix4fv(normal.transform,1,GL_FALSE,matrix); Draw(0);
        GLuint result=targets[0].texture;
        if(smoothing>0 || whitening>0) {
        // 上游两条支路使用相同的 radius/spacing，这里共享均值，减少重复的两次 blur 绘制。
        // 只美白时最终 Shader 不读取 mean/variance，跳过磨皮的三个中间绘制。
        if (smoothing>0) {
            Bind(blur,targets[0].texture,0,blur.image); glUniform2f(blur.step,4.f/w,0); Draw(1);
            Bind(blur,targets[1].texture,0,blur.image); glUniform2f(blur.step,0,4.f/h); Draw(2);
            Bind(difference,targets[0].texture,0,difference.image);
            Bind(difference,targets[2].texture,1,difference.image2); Draw(3);
        }
        Bind(beauty,targets[0].texture,0,beauty.image);
        Bind(beauty,targets[2].texture,1,beauty.image2); Bind(beauty,targets[3].texture,2,beauty.image3);
        for (int i=0; i<4; ++i) Bind(beauty,luts[i],i+3,beauty.lut[i]);
        glUniform1f(beauty.smooth,smoothing>0 ? smoothing : -1.f);
        glUniform1f(beauty.white,whitening); glUniform1f(beauty.sharpen,0);
        glUniform1f(beauty.width,1.f/w); glUniform1f(beauty.height,1.f/h); Draw(4);
        result=targets[4].texture;
        }
        if(controls[0]!=0 || controls[1]!=0 || controls[2]!=1 || controls[3]!=1 || controls[4]!=0) {
            Bind(adjust,result,0,adjust.image);
            glUniform1fv(adjust.controls,5,controls);glUniform2f(adjust.step,1.f/w,1.f/h);Draw(1);
            result=targets[1].texture;
        }
        return result;
    }
    // 先复制背景，再绘制局部网格；槽3差分已消费，复用而非增加全尺寸FBO。
    GLuint Makeup(GLuint input,bool oes,int w,int h,const float* matrix,float lipstick,float blusher,float eyelashes,GLuint eyeTexture,const float* vertices) {
        Check();State saved;Prepare();Allocate(w,h);
        if(input==targets[3].texture) throw std::runtime_error("Makeup input aliases output");
        auto& normal=oes ? normalizeOes : normalize2d;
        const std::string extension=oes ? "#extension GL_OES_EGL_image_external_essl3 : require\n" : "";
        const std::string sampler=oes ? "uniform samplerExternalOES inputImageTexture;\n" : "uniform sampler2D inputImageTexture;\n";
        if(!normal.id) normal.Init(kNormalizeVertex,"#version 300 es\n"+extension+
            "precision highp float;in vec2 uv;out vec4 color;uniform mat4 transform;\n"+sampler+
            "void main(){color=texture(inputImageTexture,(transform*vec4(uv,0.,1.)).xy);}");
        Bind(normal,input,0,normal.image,oes ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D);
        glUniformMatrix4fv(normal.transform,1,GL_FALSE,matrix);Draw(3);
        auto& p=oes ? makeupOes : makeup2d;
        if(!p.id) p.Init(kMakeupVertex,"#version 300 es\n"+extension+
            "precision highp float;in vec2 uv;in vec2 sourceUv;out vec4 color;uniform mat4 transform;\n"+sampler+kMakeupBody);
        Bind(p,input,0,p.image,oes ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D);
        glUniformMatrix4fv(p.transform,1,GL_FALSE,matrix);
        glBindVertexArray(meshVao);glBindBuffer(GL_ARRAY_BUFFER,meshVbo);
        // 未开启睫毛时只上传原贴妆网格，不为新增效果支付无效带宽。
        glBufferSubData(GL_ARRAY_BUFFER,0,(eyelashes>0 ? 1440 : 288)*sizeof(float),vertices);
        if(blusher>0){glUniform1i(p.makeupKind,1);Bind(p,luts[5],1,p.image2);glUniform1f(p.intensity,blusher);glDrawArrays(GL_TRIANGLES,60,12);}
        if(lipstick>0){glUniform1i(p.makeupKind,0);Bind(p,luts[4],1,p.image2);glUniform1f(p.intensity,lipstick);glDrawArrays(GL_TRIANGLES,0,60);}
        if(eyelashes>0){if(eyeTexture) Bind(p,eyeTexture,1,p.image2);glUniform1i(p.makeupKind,eyeTexture ? 3 : 2);glUniform1f(p.intensity,eyelashes);glDrawArrays(GL_TRIANGLES,72,288);}
        return targets[3].texture;
    }
    GLuint Reshape(GLuint input,bool oes,int w,int h,const float* matrix,const float* strengths,const float* geometry) {
        Check();State saved;Prepare();Allocate(w,h);
        // 槽2只在之前磨皮时作为中间均值，此时已消费完，不与输入/最终调色槽冲突。
        if(input==targets[2].texture) throw std::runtime_error("Reshape input aliases output");
        auto& p=oes ? warpOes : warp2d;
        if(!p.id) p.Init(kNormalizeVertex,std::string("#version 300 es\n")+
            (oes ? "#extension GL_OES_EGL_image_external_essl3 : require\n" : "")+
            "precision highp float; in vec2 uv; out vec4 color; uniform mat4 transform;\n"+
            (oes ? "uniform samplerExternalOES inputImageTexture;\n" : "uniform sampler2D inputImageTexture;\n")+kWarp);
        Bind(p,input,0,p.image,oes ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D);
        glUniformMatrix4fv(p.transform,1,GL_FALSE,matrix);
        glUniform1f(p.aspect,float(w)/h);glUniform1f(p.slim,strengths[0]);glUniform1f(p.big,strengths[1]);
        glUniform4fv(p.pairs,9,geometry);glUniform3fv(p.eyes,2,geometry+36);
        glUniform4fv(p.regions,3,geometry+42);glUniform2fv(p.down,1,geometry+54);
        glUniform3fv(p.extra,1,strengths+2);Draw(2);
        return targets[2].texture;
    }
};

void Throw(JNIEnv* env,const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_beautysdk_gpupixel_NativeBeauty_create(JNIEnv* env,jobject,jobjectArray buffers,jintArray dimensions) {
    try {
        if (!buffers || !dimensions || env->GetArrayLength(buffers)!=6 || env->GetArrayLength(dimensions)!=12)
            throw std::runtime_error("GPUPixel requires four LUTs and two makeup textures");
        if (eglGetCurrentContext()==EGL_NO_CONTEXT) throw std::runtime_error("No current EGL context");
        State saved;
        auto renderer=std::make_unique<Renderer>(); renderer->Init(env,buffers,dimensions);
        return reinterpret_cast<jlong>(renderer.release());
    } catch (const std::exception& e) { Throw(env,e); return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_beautysdk_gpupixel_NativeBeauty_renderNative(JNIEnv* env,jobject,jlong handle,jint texture,jboolean oes,
    jint width,jint height,jfloatArray transform,jfloat smoothing,jfloat whitening,jfloatArray adjustments) {
    try {
        if (!handle || texture<=0 || !transform || env->GetArrayLength(transform)!=16 ||
            !std::isfinite(smoothing) || !std::isfinite(whitening) || smoothing<0 || smoothing>1 || whitening<0 || whitening>1)
            throw std::runtime_error("Invalid GPUPixel render arguments");
        if(!adjustments || env->GetArrayLength(adjustments)!=5) throw std::runtime_error("Invalid adjustments");
        float controls[5];env->GetFloatArrayRegion(adjustments,0,5,controls);
        for(float v:controls) if(!std::isfinite(v)) throw std::runtime_error("Non-finite adjustment");
        if(controls[0]<0 || controls[0]>1 || controls[1]<-1 || controls[1]>1 ||
           controls[2]<0 || controls[2]>2 || controls[3]<0 || controls[3]>2 || controls[4]<0 || controls[4]>1)
            throw std::runtime_error("Adjustment out of range");
        float matrix[16]; env->GetFloatArrayRegion(transform,0,16,matrix);
        for (float value:matrix) if (!std::isfinite(value)) throw std::runtime_error("Non-finite transform");
        return static_cast<jint>(reinterpret_cast<Renderer*>(handle)->Render(texture,oes,width,height,matrix,smoothing,whitening,controls));
    } catch (const std::exception& e) { Throw(env,e); return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_beautysdk_gpupixel_NativeBeauty_reshapeNative(JNIEnv* env,jobject,jlong handle,jint texture,jboolean oes,
    jint width,jint height,jfloatArray transform,jfloatArray strengths,jfloatArray geometry) {
    try {
        if(!handle || texture<=0 || !transform || env->GetArrayLength(transform)!=16 ||
           !geometry || env->GetArrayLength(geometry)!=56 || !strengths || env->GetArrayLength(strengths)!=5) throw std::runtime_error("Invalid reshape arguments");
        float matrix[16],points[56],values[5];
        env->GetFloatArrayRegion(strengths,0,5,values);
        for(int i=0;i<5;++i) {
            if(!std::isfinite(values[i]) || values[i]>1.f || values[i]<(i>=3 ? -1.f : 0.f))
                throw std::runtime_error("Invalid reshape strength");
        }
        env->GetFloatArrayRegion(transform,0,16,matrix);
        env->GetFloatArrayRegion(geometry,0,56,points);
        for(float v:matrix) if(!std::isfinite(v)) throw std::runtime_error("Non-finite transform");
        for(float v:points) if(!std::isfinite(v)) throw std::runtime_error("Non-finite landmark");
        return static_cast<jint>(reinterpret_cast<Renderer*>(handle)->Reshape(texture,oes,width,height,matrix,values,points));
    } catch(const std::exception& e) { Throw(env,e);return 0; }
}

// 固定长度数组和有限数校验，防止不可信坐标越界访问native内存。
extern "C" JNIEXPORT jint JNICALL
Java_com_beautysdk_gpupixel_NativeBeauty_makeupNative(JNIEnv* env,jobject,jlong handle,jint texture,jboolean oes,
    jint width,jint height,jfloatArray transform,jfloat lipstick,jfloat blusher,jfloat eyelashes,jint eyeTexture,jfloatArray vertices) {
    try {
        if(!handle || texture<=0 || !transform || env->GetArrayLength(transform)!=16 ||
           eyeTexture<0 || !vertices || env->GetArrayLength(vertices)!=1440 || !std::isfinite(lipstick) || !std::isfinite(blusher) || !std::isfinite(eyelashes) ||
           lipstick<0 || lipstick>1 || blusher<0 || blusher>1 || eyelashes<0 || eyelashes>1) throw std::runtime_error("Invalid makeup arguments");
        float matrix[16],mesh[1440];env->GetFloatArrayRegion(transform,0,16,matrix);
        const int count=eyelashes>0 ? 1440 : 288;
        env->GetFloatArrayRegion(vertices,0,count,mesh);
        for(float v:matrix) if(!std::isfinite(v)) throw std::runtime_error("Non-finite transform");
        for(int i=0;i<count;++i) if(!std::isfinite(mesh[i])) throw std::runtime_error("Non-finite makeup vertex");
        return static_cast<jint>(reinterpret_cast<Renderer*>(handle)->Makeup(texture,oes,width,height,matrix,lipstick,blusher,eyelashes,eyeTexture,mesh));
    } catch(const std::exception& e) { Throw(env,e);return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_beautysdk_gpupixel_NativeBeauty_release(JNIEnv* env,jobject,jlong handle,jboolean abandon) {
    if (!handle) return;
    try {
        auto* renderer=reinterpret_cast<Renderer*>(handle);
        if (abandon) {
            if (renderer->context==eglGetCurrentContext()) throw std::runtime_error("Use normal release with current context");
            renderer->abandon=true; delete renderer;
        } else {
            renderer->Check(); State saved; delete renderer;
        }
    } catch (const std::exception& e) { Throw(env,e); }
}

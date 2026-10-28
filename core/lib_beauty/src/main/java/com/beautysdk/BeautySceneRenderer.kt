package com.beautysdk

import android.opengl.EGL14
import android.opengl.GLES30 as GL
import java.nio.ByteBuffer

/** 场景组合器：背景先替换，美颜/挂件居中处理，运镜最后统一裁切。
 * 与delegate在同一ES3上下文/线程使用；默认拥有delegate，close同步释放。
 * 2D/OES借用输入；输出仅有效至下一次render/close。不跨context同步、不读回视频。
 */
class BeautySceneRenderer(private val delegate: BeautyRenderer,
    private val masks: ForegroundMaskProvider? = null) : BeautyRenderer {
    private var backgroundPass: SceneGlPass?=null
    private var motionPass: SceneGlPass?=null
    private val motion=CameraMotionController()
    override fun render(frame: VideoFrame,settings: BeautySettings,face: FaceObservation?): VideoFrame {
        require(frame is TextureFrame)
        val scene=settings.scene
        if(scene.isIdentity) { motion.reset();return delegate.render(frame,settings,face) }
        var input=frame
        if(scene.needsSegmentation) {
            val mask=masks?.snapshot(frame.info)?.takeIf { it.isUsable(frame.info) }
            if(mask!=null) input=(backgroundPass ?: SceneGlPass().also { backgroundPass=it }).render(
                frame,scene,mask,floatArrayOf(0f,0f,1f,1f))
        }
        val image=delegate.render(input,settings.copy(scene=BeautyScene()),face) as TextureFrame
        if(scene.motion==CameraMotion.NONE) { motion.reset();return image }
        return (motionPass ?: SceneGlPass().also { motionPass=it }).render(image,
            scene.copy(background=BackgroundMode.NONE),null,motion.crop(frame.info,scene,face))
    }
    /** 必须在所属GL线程换源；蒙版运行时另行reset。 */
    fun reset() { motion.reset() }
    override fun close() {
        try { backgroundPass?.close();motionPass?.close() } finally { delegate.close() }
        backgroundPass=null;motionPass=null;motion.reset()
    }
}

/** 单次场景绘制：复用FBO，蒙版对象更新时才上传R8小纹理，背景图片只在资源变化时上传。 */
internal class SceneGlPass : AutoCloseable {
    private val owner=Thread.currentThread()
    private val context=EGL14.eglGetCurrentContext()
    private val programs=mutableMapOf<TextureTarget,Int>()
    private var texture=0;private var framebuffer=0;private var width=0;private var height=0
    private val extra=IntArray(2)
    private var lastMask:ForegroundMask?=null
    private var lastImage:StickerImage?=null
    private var maskWidth=0;private var maskHeight=0
    private var upload:ByteBuffer?=null
    private var closed=false
    init { check(context!=EGL14.EGL_NO_CONTEXT) }
    private fun checkOwner() { check(!closed && owner===Thread.currentThread() && EGL14.eglGetCurrentContext()==context) }
    fun render(frame:TextureFrame,scene:BeautyScene,mask:ForegroundMask?,crop:FloatArray):TextureFrame {
        checkOwner()
        require(frame.target!=TextureTarget.TEXTURE_2D || frame.textureId!=texture)
        val saved=State()
        try {
            val program=programs.getOrPut(frame.target) { createProgram(frame.target) }
            GL.glActiveTexture(GL.GL_TEXTURE0)
            allocate(frame.info.width,frame.info.height)
            GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER,framebuffer)
            GL.glViewport(0,0,width,height);State.flags.forEach { GL.glDisable(it) }
            GL.glColorMask(true,true,true,true);GL.glUseProgram(program)
            fun uniform(name:String)=GL.glGetUniformLocation(program,name)
            GL.glBindTexture(if(frame.target==TextureTarget.TEXTURE_2D) GL.GL_TEXTURE_2D else 0x8D65,frame.textureId)
            GL.glBindSampler(0,0)
            GL.glUniform1i(uniform("image"),0)
            GL.glUniformMatrix4fv(uniform("transform"),1,false,frame.copyTransform(),0)
            GL.glUniform4fv(uniform("crop"),1,crop,0)
            GL.glUniform2f(uniform("size"),width.toFloat(),height.toFloat())
            GL.glUniform2f(uniform("maskSize"),(mask?.width ?: 1).toFloat(),(mask?.height ?: 1).toFloat())
            GL.glUniform2f(uniform("maskOffset"),mask?.offsetX?:0f,mask?.offsetY?:0f)
            GL.glUniform1f(uniform("blurRadius"),scene.blurRadius)
            GL.glUniform2f(uniform("focus"),scene.backgroundFocusX,scene.backgroundFocusY)
            GL.glUniform1i(uniform("mode"),if(mask==null) 0 else scene.background.ordinal)
            GL.glUniform3f(uniform("backgroundColor"),((scene.backgroundColor ushr 16) and 255)/255f,
                ((scene.backgroundColor ushr 8) and 255)/255f,(scene.backgroundColor and 255)/255f)
            val swap=frame.info.rotationDegrees==90 || frame.info.rotationDegrees==270
            val displayAspect=if(swap) height.toFloat()/width else width.toFloat()/height
            GL.glUniform2f(uniform("aspects"),displayAspect,scene.backgroundImage?.let { it.width.toFloat()/it.height } ?: displayAspect)
            GL.glUniform1i(uniform("rotation"),frame.info.rotationDegrees/90)
            GL.glUniform1i(uniform("mirrored"),if(frame.info.mirrored) 1 else 0)
            val initializeExtra=extra[0]==0
            if(initializeExtra) GL.glGenTextures(2,extra,0)
            GL.glBindBuffer(GL.GL_PIXEL_UNPACK_BUFFER,0)
            GL.glPixelStorei(GL.GL_UNPACK_ALIGNMENT,1);GL.glPixelStorei(GL.GL_UNPACK_ROW_LENGTH,0)
            GL.glPixelStorei(GL.GL_UNPACK_SKIP_ROWS,0);GL.glPixelStorei(GL.GL_UNPACK_SKIP_PIXELS,0)
            for(i in 0..1) {
                GL.glActiveTexture(GL.GL_TEXTURE1+i);GL.glBindTexture(GL.GL_TEXTURE_2D,extra[i]);GL.glBindSampler(i+1,0)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
                if(initializeExtra) GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,1,1,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(4))
            }
            GL.glActiveTexture(GL.GL_TEXTURE1);GL.glUniform1i(uniform("maskImage"),1)
            if(mask!=null && mask!==lastMask) {
                val count=mask.width*mask.height
                val bytes=upload?.takeIf { it.capacity()>=count } ?: ByteBuffer.allocateDirect(count).also { upload=it }
                bytes.clear();bytes.put(mask.pixels());bytes.flip()
                if(maskWidth!=mask.width || maskHeight!=mask.height) {
                    GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_R8,mask.width,mask.height,0,GL.GL_RED,GL.GL_UNSIGNED_BYTE,bytes)
                    maskWidth=mask.width;maskHeight=mask.height
                }else GL.glTexSubImage2D(GL.GL_TEXTURE_2D,0,0,0,mask.width,mask.height,GL.GL_RED,GL.GL_UNSIGNED_BYTE,bytes)
                lastMask=mask
            }
            GL.glActiveTexture(GL.GL_TEXTURE2);GL.glUniform1i(uniform("backgroundImage"),2)
            val art=scene.backgroundImage
            if(art!=null && art!==lastImage) {
                val bytes=ByteBuffer.allocateDirect(art.width*art.height*4)
                for(y in 0 until art.height) for(x in 0 until art.width) {
                    val c=art.pixel(x,y);bytes.put((c ushr 16).toByte()).put((c ushr 8).toByte()).put(c.toByte()).put(255.toByte())
                }
                bytes.flip();GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,art.width,art.height,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,bytes)
                lastImage=art
            }
            GL.glDrawArrays(GL.GL_TRIANGLES,0,3)
            return TextureFrame(frame.info,texture,TextureTarget.TEXTURE_2D,IDENTITY)
        } finally { saved.restore() }
    }
    private fun allocate(w: Int, h: Int) {
        if (texture != 0 && width == w && height == h) return
        if (texture == 0) {
            val ids = IntArray(1)
            GL.glGenTextures(1, ids, 0); texture = ids[0]
            GL.glGenFramebuffers(1, ids, 0); framebuffer = ids[0]
        }
        GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glBindBuffer(GL.GL_PIXEL_UNPACK_BUFFER, 0)
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, w, h, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
        GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER, framebuffer)
        GL.glFramebufferTexture2D(GL.GL_DRAW_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, texture, 0)
        check(GL.glCheckFramebufferStatus(GL.GL_DRAW_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE) { "Incomplete beauty FBO" }
        width = w; height = h
    }
    /** 按输入纹理类型编译并链接效果 Shader；失败时释放已创建的中间对象。 */
    private fun createProgram(target: TextureTarget): Int {
        /** 编译单个 Shader 并检查日志；编译失败时抛出可诊断错误。 */
        fun shader(type: Int, source: String): Int {
            val id = GL.glCreateShader(type)
            GL.glShaderSource(id, source); GL.glCompileShader(id)
            val status = IntArray(1); GL.glGetShaderiv(id, GL.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GL.glGetShaderInfoLog(id); GL.glDeleteShader(id)
                error("Beauty shader: $log")
            }
            return id
        }
        val vertex = shader(GL.GL_VERTEX_SHADER, VERTEX)
        var fragment = 0; var program = 0
        try {
            val oes = target == TextureTarget.EXTERNAL_OES
            fragment = shader(GL.GL_FRAGMENT_SHADER, "#version 300 es\n" +
                (if (oes) "#extension GL_OES_EGL_image_external_essl3 : require\n" else "") +
                "precision highp float;\nuniform ${if (oes) "samplerExternalOES" else "sampler2D"} image;\n" + FRAGMENT)
            program = GL.glCreateProgram()
            GL.glAttachShader(program, vertex); GL.glAttachShader(program, fragment); GL.glLinkProgram(program)
            val status = IntArray(1); GL.glGetProgramiv(program, GL.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Beauty program: ${GL.glGetProgramInfoLog(program)}" }
            return program
        } catch (error: Exception) { if (program != 0) GL.glDeleteProgram(program); throw error }
        finally { GL.glDeleteShader(vertex); if (fragment != 0) GL.glDeleteShader(fragment) }
    }
    override fun close() {
        if(closed) return
        checkOwner();closed=true
        programs.values.forEach { GL.glDeleteProgram(it) }
        if(texture!=0) GL.glDeleteTextures(1,intArrayOf(texture),0)
        if(framebuffer!=0) GL.glDeleteFramebuffers(1,intArrayOf(framebuffer),0)
        GL.glDeleteTextures(2,extra,0);programs.clear();lastImage=null;lastMask=null;upload=null
    }
    private class State {
        /** 读取单个整型 GL 状态，供状态快照保存宿主环境。 */
        private fun int(name: Int) = IntArray(1).also { GL.glGetIntegerv(name, it, 0) }[0]
        private val program = int(GL.GL_CURRENT_PROGRAM)
        private val fbo = int(GL.GL_DRAW_FRAMEBUFFER_BINDING)
        private val active = int(GL.GL_ACTIVE_TEXTURE)
        private val viewport = IntArray(4).also { GL.glGetIntegerv(GL.GL_VIEWPORT, it, 0) }
        private val mask = BooleanArray(4).also { GL.glGetBooleanv(GL.GL_COLOR_WRITEMASK, it, 0) }
        private val enabled = flags.map { GL.glIsEnabled(it) }
        private val unpack = int(GL.GL_PIXEL_UNPACK_BUFFER_BINDING)
        private val pixelStore = intArrayOf(GL.GL_UNPACK_ALIGNMENT, GL.GL_UNPACK_ROW_LENGTH,
            GL.GL_UNPACK_SKIP_ROWS, GL.GL_UNPACK_SKIP_PIXELS)
        private val pixelValues = pixelStore.map { int(it) }
        private val extraBindings = IntArray(3)
        private val extraSamplers = IntArray(3)
        private val binding: Int
        private val oes: Int
        private val sampler: Int
        init {
            for (i in 0..2) {
                GL.glActiveTexture(GL.GL_TEXTURE1 + i)
                extraBindings[i] = int(GL.GL_TEXTURE_BINDING_2D)
                extraSamplers[i] = int(GL.GL_SAMPLER_BINDING)
            }
            GL.glActiveTexture(GL.GL_TEXTURE0)
            binding = int(GL.GL_TEXTURE_BINDING_2D)
            // OES 查询仅在扩展存在时执行，避免污染 GL error 状态。
            oes = if (GL.glGetString(GL.GL_EXTENSIONS)?.contains("GL_OES_EGL_image_external") == true) int(0x8D67) else -1
            sampler = int(GL.GL_SAMPLER_BINDING)
        }
        /** 恢复渲染前保存的绑定、视口、开关、纹理单元及像素传输状态。 */
        fun restore() {
            GL.glUseProgram(program); GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER, fbo)
            GL.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GL.glColorMask(mask[0], mask[1], mask[2], mask[3])
            flags.forEachIndexed { i, flag -> if (enabled[i]) GL.glEnable(flag) else GL.glDisable(flag) }
            GL.glBindBuffer(GL.GL_PIXEL_UNPACK_BUFFER, unpack)
            pixelStore.forEachIndexed { i, name -> GL.glPixelStorei(name, pixelValues[i]) }
            for (i in 0..2) {
                GL.glActiveTexture(GL.GL_TEXTURE1 + i)
                GL.glBindTexture(GL.GL_TEXTURE_2D, extraBindings[i])
                GL.glBindSampler(i + 1, extraSamplers[i])
            }
            GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, binding)
            if (oes >= 0) GL.glBindTexture(0x8D65, oes)
            GL.glBindSampler(0, sampler); GL.glActiveTexture(active)
        }
        companion object {
            val flags = intArrayOf(GL.GL_BLEND, GL.GL_DEPTH_TEST, GL.GL_STENCIL_TEST, GL.GL_SCISSOR_TEST,
                GL.GL_CULL_FACE, GL.GL_RASTERIZER_DISCARD, GL.GL_DITHER)
        }
    }
    companion object {
        private val IDENTITY=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
        private val VERTEX="""#version 300 es
            out vec2 uv;
            void main() {
                vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
                uv=p;gl_Position=vec4(p*2.0-1.0,0.0,1.0);
            }
        """.trimIndent()
        private val FRAGMENT="""
            in vec2 uv;out vec4 color;
            uniform mat4 transform;uniform vec4 crop;uniform vec2 size,aspects,focus,maskSize,maskOffset;
            uniform sampler2D maskImage,backgroundImage;
            uniform int mode,rotation,mirrored;uniform float blurRadius;uniform vec3 backgroundColor;
            vec4 sampleImage(vec2 p) { return texture(image,(transform*vec4(clamp(p,0.0,1.0),0.0,1.0)).xy); }
            // 仅在不确定轮廓处，结合当前原图颜色重建四邻域蒙版。
            // 采样点使用蒙版像素中心；沿用原始坐标，兼容OES变换及不同输出分辨率。
            float confidenceAt(vec2 p) {
                vec2 q=p-maskOffset;
                if(any(lessThan(q,vec2(0.0))) || any(greaterThan(q,vec2(1.0)))) return 0.0;
                return texture(maskImage,q).r;
            }
            float foregroundAlpha(vec2 p,vec3 center) {
                float confidence=confidenceAt(p);
                if(confidence<=0.02) return 0.0;
                if(confidence>=0.98) return 1.0;
                vec2 grid=(p-maskOffset)*maskSize-0.5;
                vec2 base=floor(grid),fraction=fract(grid);
                float sum=0.0,weights=0.0;
                for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
                    vec2 offset=vec2(float(x),float(y));
                    vec2 q=clamp((base+offset+0.5)/maskSize,0.5/maskSize,1.0-0.5/maskSize);
                    vec2 spatial=mix(1.0-fraction,fraction,offset);
                    vec3 difference=sampleImage(q+maskOffset).rgb-center;
                    float weight=spatial.x*spatial.y*exp(-dot(difference,difference)*32.0);
                    sum+=texture(maskImage,q).r*weight;weights+=weight;
                }
                float refined=weights>0.00001 ? sum/weights : confidence;
                // 颜色引导后的置信度保持连续，避免硬阈值造成剪纸边。
                // 宽过渡保留细发丝/半透明边缘；仅对低置信背景及完整前景做端点清理。
                // 不混合前后帧alpha，避免在运动时人为增加轮廓拖尾。
                return smoothstep(0.08,0.92,refined);
            }
            vec2 upright(vec2 p) {
                if(rotation==1) p=vec2(1.0-p.y,p.x);
                else if(rotation==2) p=1.0-p;
                else if(rotation==3) p=vec2(p.y,1.0-p.x);
                if(mirrored==1) p.x=1.0-p.x;
                return p;
            }
            void main() {
                vec2 p=crop.xy+uv*crop.zw;vec4 source=sampleImage(p);
                if(mode==0) { color=source;return; }
                float a=foregroundAlpha(p,source.rgb);
                // 完整前景无需执行背景虚化的25次采样。
                if(a>=0.9999) { color=source;return; }
                vec3 bg=backgroundColor;
                if(mode==1) {
                    vec3 total=vec3(0.0);float weights=0.0;
                    // 背景模糊只累计可信背景，防止头发/肤色被模糊到轮廓外形成光晕。
                    for(int y=-2;y<=2;y++) for(int x=-2;x<=2;x++) {
                        vec2 q=clamp(p+vec2(float(x),float(y))*blurRadius/size,0.0,1.0);
                        float weight=1.0-smoothstep(0.20,0.65,confidenceAt(q));
                        total+=sampleImage(q).rgb*weight;weights+=weight;
                    }
                    bg=weights>0.0001 ? total/weights : source.rgb;
                } else if(mode==3) {
                    vec2 q=upright(p)-0.5;
                    vec2 extent=vec2(min(1.0,aspects.x/aspects.y),min(1.0,aspects.y/aspects.x));
                    q*=extent;
                    bg=texture(backgroundImage,q+clamp(focus,extent*0.5,1.0-extent*0.5)).rgb;
                }
                color=vec4(mix(bg,source.rgb,a),source.a);
            }
        """.trimIndent()
    }
}

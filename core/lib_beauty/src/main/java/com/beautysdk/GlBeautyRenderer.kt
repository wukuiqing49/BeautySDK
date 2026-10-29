package com.beautysdk

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
import android.opengl.EGL14
import android.opengl.GLES30 as GL
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/** OpenGL ES 3.0 纹理渲染。在宿主当前 EGL context/线程构造、render、close。
 * 不创建 EGL、不跨 context 使用纹理、不做 GPU 读回。2D 输出属于 SDK，下一次 render 或
 * close 即失效；引擎异步消费时，宿主必须先复制或 fence 同步，不能无限持有这个纹理。
 * transform 采样后输出 identity transform；rotation/mirrored 元数据保持原值，勿重复应用。
 */
class GlBeautyRenderer : BeautyRenderer {
    private val owner = Thread.currentThread()
    private val context = EGL14.eglGetCurrentContext()
    // 缓存当前程序的参数位置，随程序销毁清空。
    private val uniformLocations = mutableMapOf<Int, MutableMap<String, Int>>()
    private val programs = mutableMapOf<TextureTarget, Int>()
    // 三个素材槽仅在图片对象变化时上传；不按视频帧重复解码、创建纹理。
    private val stickerTextures = IntArray(3)
    private val stickerImages = arrayOfNulls<StickerImage>(3)
    private var texture = 0
    private var framebuffer = 0
    private var width = 0
    private var height = 0
    private var closed = false
    private val stickerPoseSmoother = StickerPoseSmoother()
    init {
        check(context != EGL14.EGL_NO_CONTEXT) { "A current EGL context is required" }
        check(GL.glGetString(GL.GL_VERSION)?.contains("OpenGL ES 3") == true) { "OpenGL ES 3.0 is required" }
    }

    /** 在当前 EGL 上下文采样 OES/2D 纹理并由 Shader 合成；输出 FBO 复用至下次 render/close，结束后恢复宿主 GL 状态。 */
    override fun render(frame: VideoFrame, settings: BeautySettings, face: FaceObservation?): VideoFrame {
        if (!BeautyLicensing.allowsSettings(settings)) return frame
        checkContext(); check(!closed)
        require(frame is TextureFrame) { "GlBeautyRenderer only accepts TextureFrame" }
        require(settings.scene.isIdentity) { "Use BeautySceneRenderer for scene effects" }
        require(settings.adjustments.isIdentity) { "Select GPUPixel for extended beauty adjustments" }
        if (settings.isIdentity) { stickerPoseSmoother.clear(); return frame }
        require(frame.target != TextureTarget.TEXTURE_2D || frame.textureId != texture) { "Cannot sample the output texture while rendering to it" }
        val saved = State()
        try {
            val program = programs.getOrPut(frame.target) { createProgram(frame.target) }
            GL.glActiveTexture(GL.GL_TEXTURE0)
            allocate(frame.info.width, frame.info.height)
            GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER, framebuffer)
            GL.glViewport(0, 0, width, height)
            State.flags.forEach { GL.glDisable(it) }
            GL.glColorMask(true, true, true, true)
            GL.glUseProgram(program)
            val target = if (frame.target == TextureTarget.TEXTURE_2D) GL.GL_TEXTURE_2D else 0x8D65
            GL.glBindTexture(target, frame.textureId)
            GL.glBindSampler(0, 0)
            /** 查询当前效果程序的 uniform 位置，供本帧上传美颜和贴纸参数。 */
            val locations = uniformLocations.getOrPut(program) { mutableMapOf() }
            fun uniform(name: String) = locations.getOrPut(name) { GL.glGetUniformLocation(program, name) }
            GL.glUniform1i(uniform("image"), 0)
            GL.glUniformMatrix4fv(uniform("transform"), 1, false, frame.copyTransform(), 0)
            GL.glUniform2f(uniform("size"), width.toFloat(), height.toFloat())
            GL.glUniform1f(uniform("smoothAmount"), settings.smoothing)
            GL.glUniform1f(uniform("whiteAmount"), settings.whitening)
            val tint = when (settings.filter) {
                BeautyFilter.NONE -> floatArrayOf(0f, 0f, 0f)
                BeautyFilter.WARM -> floatArrayOf(8f / 255, 0f, -6f / 255)
                BeautyFilter.COOL -> floatArrayOf(-6f / 255, 0f, 8f / 255)
            }
            GL.glUniform3fv(uniform("tint"), 1, tint, 0)
            // 仅平滑挂件参数；没有有效人脸/没有贴纸时立即清理，不跨失效段保留姿态。
            val hasStickers = settings.glasses || settings.moustache || settings.hat || !settings.stickerPack?.layers.isNullOrEmpty()
            if (face == null || !hasStickers) stickerPoseSmoother.clear()
            val placement = stickerPoseSmoother.update(StickerGeometry.place(face, frame.info), frame.info)
            val scale = if (settings.animateStickers) 1f + .04f * sin((frame.info.timestampNs % 1_000_000_000) * (2 * PI / 1e9)).toFloat() else 1f
            /** 上传挂件中心、像素跨度及角度；缺锚点或关闭时上传零跨度以隐藏挂件。 */
            fun pose(name: String, value: StickerPose?, enabled: Boolean) {
                GL.glUniform4f(uniform(name), value?.center?.x ?: 0f, value?.center?.y ?: 0f,
                    if (enabled) (value?.spanPixels ?: 0f) * scale else 0f, value?.angleRadians ?: 0f)
            }
            pose("glasses", placement.glasses, settings.glasses)
            pose("moustache", placement.moustache, settings.moustache)
            pose("hat", placement.hat, settings.hat)
            val layers = settings.stickerPack?.layers.orEmpty()
            GL.glUniform1f(uniform("imageHandedness"), if (frame.info.mirrored) -1f else 1f)
            for (i in 0..2) {
                val layer = layers.getOrNull(i)
                bindSticker(i, layer?.image)
                GL.glUniform1i(uniform("sticker$i"), i + 1)
                pose("imagePose$i", layer?.pose(placement, frame.info), layer != null)
                GL.glUniform4f(uniform("imageLayout$i"), layer?.pivotX ?: 0f, layer?.pivotY ?: 0f,
                    layer?.widthInAnchorSpans ?: 1f,
                    layer?.image?.let { it.width.toFloat() / it.height } ?: 1f)
            }
            GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
            return TextureFrame(frame.info, texture, TextureTarget.TEXTURE_2D, IDENTITY)
        } finally { saved.restore() }
    }

    /** 维护指定素材纹理槽：仅图片对象变化时上传像素，空槽释放旧纹理，避免逐帧解码和重复上传。 */
    private fun bindSticker(slot: Int, image: StickerImage?) {
        GL.glActiveTexture(GL.GL_TEXTURE1 + slot)
        GL.glBindSampler(slot + 1, 0)
        if (image == null) {
            if (stickerTextures[slot] != 0) GL.glDeleteTextures(1, intArrayOf(stickerTextures[slot]), 0)
            stickerTextures[slot] = 0; stickerImages[slot] = null
            GL.glBindTexture(GL.GL_TEXTURE_2D, 0)
            return
        }
        if (stickerTextures[slot] == 0) {
            val ids = IntArray(1); GL.glGenTextures(1, ids, 0); stickerTextures[slot] = ids[0]
        }
        GL.glBindTexture(GL.GL_TEXTURE_2D, stickerTextures[slot])
        if (stickerImages[slot] === image) return
        val bytes = ByteBuffer.allocateDirect(image.width * image.height * 4)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val argb = image.pixel(x,y)
            bytes.put((argb ushr 16).toByte()).put((argb ushr 8).toByte()).put(argb.toByte()).put((argb ushr 24).toByte())
        }
        bytes.flip()
        GL.glBindBuffer(GL.GL_PIXEL_UNPACK_BUFFER, 0)
        GL.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1)
        GL.glPixelStorei(GL.GL_UNPACK_ROW_LENGTH, 0)
        GL.glPixelStorei(GL.GL_UNPACK_SKIP_ROWS, 0)
        GL.glPixelStorei(GL.GL_UNPACK_SKIP_PIXELS, 0)
        // 配合Shader的texture采样执行双线性过滤，避免细线素材缩放时像素跳变。
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, image.width, image.height, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, bytes)
        stickerImages[slot] = image
    }

    /** 尺寸不变时复用输出纹理/FBO，变化时重新分配并检查帧缓冲完整性。 */
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
    /** 校验创建线程和 EGL 上下文，拒绝跨线程或跨上下文使用纹理资源。 */
    private fun checkContext() {
        check(Thread.currentThread() === owner && EGL14.eglGetCurrentContext() == context) { "Use the creating EGL context/thread" }
    }
    /** 在创建时的 EGL 上下文释放程序、FBO、输出纹理及素材槽；重复关闭不重复删除资源。 */
    override fun close() {
        checkContext()
        if (!closed) {
            closed = true
            GL.glDeleteTextures(3, stickerTextures, 0)
            stickerTextures.fill(0); stickerImages.fill(null)
            programs.values.forEach { GL.glDeleteProgram(it) }; programs.clear(); uniformLocations.clear()
            if (texture != 0) GL.glDeleteTextures(1, intArrayOf(texture), 0)
            if (framebuffer != 0) GL.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        }
    }

    /** 进入SDK绘制前保存宿主GL状态，绘制完成或异常时恢复，避免污染直播引擎。 */
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
        private val IDENTITY = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        private val VERTEX = """#version 300 es
            out vec2 uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                uv = p; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()
        private val FRAGMENT = """
            in vec2 uv; out vec4 color;
            uniform mat4 transform;
            uniform vec2 size;
            uniform float smoothAmount, whiteAmount;
            uniform vec3 tint;
            uniform vec4 glasses, moustache, hat;
            uniform sampler2D sticker0, sticker1, sticker2;
            uniform vec4 imagePose0, imagePose1, imagePose2;
            uniform vec4 imageLayout0, imageLayout1, imageLayout2;
            uniform float imageHandedness;
            vec4 sampleImage(vec2 p) { return texture(image, (transform * vec4(clamp(p,0.0,1.0),0.0,1.0)).xy); }
            vec2 local(vec4 pose) {
                vec2 d = (uv - pose.xy) * size / max(pose.z, 1.0);
                float c = cos(pose.w), s = sin(pose.w);
                return vec2(c*d.x+s*d.y, -s*d.x+c*d.y);
            }
            vec4 overlayImage(vec4 base, sampler2D art, vec4 pose, vec4 geometry) {
                if (pose.z <= 0.0) return base;
                vec2 p = local(pose);
                p = vec2(p.x, p.y * imageHandedness * geometry.w) / geometry.z + geometry.xy;
                if (any(lessThan(p,vec2(0.0))) || any(greaterThanEqual(p,vec2(1.0)))) return base;
                vec4 src = texture(art,p);
                float alpha = src.a + base.a * (1.0-src.a);
                if (src.a <= 0.0) return base;
                return vec4((src.rgb*src.a + base.rgb*base.a*(1.0-src.a))/alpha,alpha);
            }
            void main() {
                vec4 center = sampleImage(uv);
                vec3 sum = vec3(0.0); float weights = 0.0;
                if (smoothAmount > 0.0) {
                    for (int y=-1; y<=1; y++) for (int x=-1; x<=1; x++) {
                        vec3 n = sampleImage(uv + vec2(float(x),float(y))/size).rgb;
                        vec3 diff = n-center.rgb; float w = exp(-dot(diff,diff)*24.0);
                        sum += n*w; weights += w;
                    }
                }
                vec3 rgb = weights > 0.0 ? mix(center.rgb,sum/weights,smoothAmount*0.65) : center.rgb;
                rgb = mix(rgb,vec3(1.0),whiteAmount*0.18) + tint;
                vec2 g = local(glasses), m = local(moustache), h = local(hat);
                float ring = length(vec2((abs(g.x)-0.5)/0.34,g.y/0.27));
                bool glass = glasses.z > 0.0 && (abs(ring-1.0)<0.12 ||
                    (abs(g.x)<0.17 && abs(g.y)<0.035) || (abs(g.x)>0.82 && abs(g.x)<1.04 && abs(g.y)<0.035));
                bool hair = moustache.z > 0.0 && length(vec2((abs(m.x)-0.22)/0.3,(m.y-0.04)/0.12))<1.0;
                bool cap = hat.z > 0.0 && ((abs(h.x)<1.05 && h.y> -0.95 && h.y< -0.81) ||
                    (abs(h.x)<0.68 && h.y> -1.85 && h.y<= -0.95));
                color = vec4(cap ? vec3(48.0,48.0,160.0)/255.0 :
                    (glass || hair ? vec3(24.0/255.0) : clamp(rgb,0.0,1.0)),center.a);
                color = overlayImage(color,sticker0,imagePose0,imageLayout0);
                color = overlayImage(color,sticker1,imagePose1,imageLayout1);
                color = overlayImage(color,sticker2,imagePose2,imageLayout2);
            }
        """.trimIndent()
    }
}

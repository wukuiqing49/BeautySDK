package com.beautysdk

import android.opengl.EGL14
import android.opengl.GLES30 as GL
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** 只有纹理输入时的可选检测桥接器，不替代已有独立RGBA支路。
 * 在创建它的ES3上下文/线程调用submit/reset/close；借用analysis，关闭不释放analysis。
 * analysis须允许同视场缩小帧，Session须读取同一共享运行时；旋转/镜像留在元数据中。
 * GPU先缩小，再向两个PBO提交读回；只映射已完成fence，不主动等待，不保证驱动调用零阻塞。
 * 检测图只采样原始输入，必须在美颜/贴纸合成之前调用，避免贴纸污染检测。
 */
class GlTextureAnalysisBridge @JvmOverloads constructor(
    private val analysis: BeautyAnalysisRuntime? = null,
    private val maxSide: Int = 320,
    private val intervalNs: Long = 33_333_333L,
    private val maxReadbackAgeNs: Long = 100_000_000L,
    /** 可选小图消费者：frame为借用的小RGBA，sourceInfo保留全分辨率原始几何；返回前完成复制。
     * 配置该入口时不再向人脸runtime提交，可用于独立分割；reset由宿主同时通知消费者。
     */
    private val onAnalysisFrame: ((RgbaFrame, FrameInfo) -> Boolean)? = null,
) : AutoCloseable {
    private class Slot {
        var pbo = 0
        var size = 0
        var fence = 0L
        var info: FrameInfo? = null
        var generation = 0L
        var sourceInfo: FrameInfo? = null
    }
    private val owner = Thread.currentThread()
    private val context = EGL14.eglGetCurrentContext()
    private val slots = List(2) { Slot() }
    private val programs = mutableMapOf<TextureTarget, Int>()
    private var texture = 0
    private var fbo = 0
    private var vao = 0
    private var sampler = 0
    private var width = 0
    private var height = 0
    private var cpu: ByteBuffer? = null
    private var geometry: FrameInfo? = null
    private var transform: FloatArray? = null
    private var lastTime = -1L
    private var lastQueued = -1L
    private var enabled = false
    private var closed = false
    private var localGeneration=0L
    private fun generationToken() = analysis?.generationToken() ?: localGeneration
    init {
        require(analysis!=null || onAnalysisFrame!=null)
        require(maxSide in 16..2048 && intervalNs >= 0 && maxReadbackAgeNs >= 0)
        check(context != EGL14.EGL_NO_CONTEXT && GL.glGetString(GL.GL_VERSION)?.contains("OpenGL ES 3") == true)
    }

    /** 每个新视频帧调用一次，先投递已完成小图，再按频率尝试采样本帧。
     * 返回值仅表示有小图被analysis接受，不表示本帧已经完成检测。
     * 关闭效果时取消在途结果、不采样；重复时间戳不读回。换同尺寸视频源必须显式reset。
     */
    fun submit(frame: TextureFrame, needsFaceAnalysis: Boolean): Boolean {
        checkOwner(); check(!closed)
        analysis?.setEnabled(needsFaceAnalysis)
        if (enabled != needsFaceAnalysis) { clearPending();localGeneration++; enabled = needsFaceAnalysis }
        if (!enabled) return false
        val matrix = frame.copyTransform()
        val old = geometry
        if (old != null && (old.width != frame.info.width || old.height != frame.info.height ||
                old.rotationDegrees != frame.info.rotationDegrees || old.mirrored != frame.info.mirrored ||
                !matrix.contentEquals(transform))) reset()
        geometry = frame.info; transform = matrix
        if (frame.info.timestampNs <= lastTime) return false
        lastTime = frame.info.timestampNs
        val state = State()
        try {
            val accepted = consume(frame.info.timestampNs)
            if (lastQueued < 0 || frame.info.timestampNs - lastQueued >= intervalNs) {
                val slot = slots.firstOrNull { it.fence == 0L }
                if (slot != null) {
                    queue(frame, matrix, slot, generationToken())
                    lastQueued = frame.info.timestampNs
                }
            }
            return accepted
        } finally { state.restore() }
    }

    /** 只取最新已完成结果，保留真实采样时间；过期及旧会话结果在CPU复制前丢弃。 */
    private fun consume(now: Long): Boolean {
        var newest: Slot? = null
        for (slot in slots) {
            if (slot.fence == 0L) continue
            val status = GL.glClientWaitSync(slot.fence, 0, 0)
            check(status != GL.GL_WAIT_FAILED) { "Analysis readback fence failed" }
            if (status != GL.GL_ALREADY_SIGNALED && status != GL.GL_CONDITION_SATISFIED) continue
            val info = checkNotNull(slot.info)
            if (now - info.timestampNs > maxReadbackAgeNs || slot.generation != generationToken()) {
                release(slot); continue
            }
            if (newest == null || info.timestampNs > checkNotNull(newest.info).timestampNs) {
                newest?.let(::release); newest = slot
            } else release(slot)
        }
        val selected = newest ?: return false
        GL.glBindBuffer(GL.GL_PIXEL_PACK_BUFFER, selected.pbo)
        try {
            val mapped = GL.glMapBufferRange(GL.GL_PIXEL_PACK_BUFFER, 0, selected.size, GL.GL_MAP_READ_BIT) as? ByteBuffer
                ?: error("Analysis readback mapping failed")
            val storage = cpu?.takeIf { it.capacity() >= selected.size }
                ?: ByteBuffer.allocateDirect(selected.size).also { cpu = it }
            try {
                storage.clear(); storage.limit(selected.size)
                mapped.position(0); mapped.limit(selected.size); storage.put(mapped); storage.flip()
            } finally { check(GL.glUnmapBuffer(GL.GL_PIXEL_PACK_BUFFER)) { "Analysis readback data invalid" } }
            val info = checkNotNull(selected.info)
            val frame=RgbaFrame(info,storage,info.width*4)
            return onAnalysisFrame?.invoke(frame,checkNotNull(selected.sourceInfo))
                ?: checkNotNull(analysis).submit(frame,selected.generation)
        } finally { release(selected) }
    }

    /** 单个小图FBO复用，PBO最多两个；不读回高清纹理，不创建Bitmap。 */
    private fun queue(frame: TextureFrame, matrix: FloatArray, slot: Slot, generation: Long) {
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(frame.info.width, frame.info.height))
        val w = maxOf(1, (frame.info.width * scale).roundToInt())
        val h = maxOf(1, (frame.info.height * scale).roundToInt())
        GL.glActiveTexture(GL.GL_TEXTURE0)
        if (texture == 0) texture = generate { GL.glGenTextures(1, it, 0) }
        if (fbo == 0) fbo = generate { GL.glGenFramebuffers(1, it, 0) }
        if (vao == 0) vao = generate { GL.glGenVertexArrays(1, it, 0) }
        if (sampler == 0) {
            sampler = generate { GL.glGenSamplers(1, it, 0) }
            GL.glSamplerParameteri(sampler, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
            GL.glSamplerParameteri(sampler, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glSamplerParameteri(sampler, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glSamplerParameteri(sampler, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        }
        GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
        if (w != width || h != height) {
            GL.glBindBuffer(GL.GL_PIXEL_UNPACK_BUFFER, 0)
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, w, h, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
            width = w; height = h
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, texture, 0)
        check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
        val program = programs.getOrPut(frame.target) { createProgram(frame.target) }
        GL.glUseProgram(program); GL.glBindVertexArray(vao)
        GL.glViewport(0, 0, w, h); State.flags.forEach { GL.glDisable(it) }
        GL.glColorMask(true, true, true, true)
        GL.glBindTexture(if (frame.target == TextureTarget.TEXTURE_2D) GL.GL_TEXTURE_2D else 0x8D65, frame.textureId)
        GL.glBindSampler(0, sampler)
        GL.glUniform1i(GL.glGetUniformLocation(program, "image"), 0)
        GL.glUniformMatrix4fv(GL.glGetUniformLocation(program, "transform"), 1, false, matrix, 0)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        if (slot.pbo == 0) slot.pbo = generate { GL.glGenBuffers(1, it, 0) }
        GL.glBindBuffer(GL.GL_PIXEL_PACK_BUFFER, slot.pbo)
        val size = w * h * 4
        if (slot.size != size) { GL.glBufferData(GL.GL_PIXEL_PACK_BUFFER, size, null, GL.GL_STREAM_READ); slot.size = size }
        GL.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1)
        GL.glPixelStorei(GL.GL_PACK_ROW_LENGTH, 0); GL.glPixelStorei(GL.GL_PACK_SKIP_ROWS, 0); GL.glPixelStorei(GL.GL_PACK_SKIP_PIXELS, 0)
        GL.glReadBuffer(GL.GL_COLOR_ATTACHMENT0)
        GL.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, 0)
        check(GL.glGetError() == GL.GL_NO_ERROR) { "Analysis sampling failed (including preexisting host GL errors)" }
        slot.fence = GL.glFenceSync(GL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        check(slot.fence != 0L)
        slot.sourceInfo=frame.info
        slot.info = frame.info.copy(width = w, height = h)
        slot.generation = generation
        GL.glFlush() // 提交命令而非glFinish；下次只以timeout=0查询是否完成。
    }
    /** 换镜头/裁剪/视频源时显式作废读回和检测；不等待旧GPU命令完成。 */
    fun reset() { checkOwner(); check(!closed); clearPending();localGeneration++; analysis?.reset() }
    private fun clearPending() {
        slots.forEach(::release); geometry = null; transform = null; lastTime = -1; lastQueued = -1
    }
    private fun release(slot: Slot) {
        if (slot.fence != 0L) GL.glDeleteSync(slot.fence)
        slot.fence = 0; slot.info = null;slot.sourceInfo=null
    }
    /** 必须先停止提交，在创建上下文关闭；借用analysis由宿主另行释放。 */
    override fun close() {
        checkOwner(); if (closed) return
        clearPending(); slots.forEach { if (it.pbo != 0) GL.glDeleteBuffers(1, intArrayOf(it.pbo), 0) }
        programs.values.forEach { GL.glDeleteProgram(it) }; programs.clear()
        if (texture != 0) GL.glDeleteTextures(1, intArrayOf(texture), 0)
        if (fbo != 0) GL.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (vao != 0) GL.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (sampler != 0) GL.glDeleteSamplers(1, intArrayOf(sampler), 0)
        cpu = null; closed = true
    }
    private fun checkOwner() { check(Thread.currentThread() === owner && EGL14.eglGetCurrentContext() == context) }
    private fun generate(action: (IntArray) -> Unit) = IntArray(1).also(action)[0].also { check(it != 0) }
    private fun createProgram(target: TextureTarget): Int {
        fun shader(type: Int, source: String): Int {
            val id = GL.glCreateShader(type)
            GL.glShaderSource(id, source); GL.glCompileShader(id)
            val ok = IntArray(1); GL.glGetShaderiv(id, GL.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { val log = GL.glGetShaderInfoLog(id); GL.glDeleteShader(id); error(log) }
            return id
        }
        val vertex = shader(GL.GL_VERTEX_SHADER, """#version 300 es
            out vec2 uv;
            void main() { vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
                uv=p; gl_Position=vec4(p*2.0-1.0,0.0,1.0); }
        """.trimIndent())
        var fragment = 0; var program = 0
        try {
            val oes = target == TextureTarget.EXTERNAL_OES
            fragment = shader(GL.GL_FRAGMENT_SHADER, "#version 300 es\n" +
                (if (oes) "#extension GL_OES_EGL_image_external_essl3 : require\n" else "") +
                "precision highp float; in vec2 uv; out vec4 color; uniform mat4 transform; uniform " +
                (if (oes) "samplerExternalOES" else "sampler2D") +
                " image; void main(){color=texture(image,(transform*vec4(uv,0.0,1.0)).xy);}")
            program = GL.glCreateProgram(); GL.glAttachShader(program, vertex); GL.glAttachShader(program, fragment)
            GL.glLinkProgram(program)
            val ok = IntArray(1); GL.glGetProgramiv(program, GL.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { GL.glGetProgramInfoLog(program) }; return program
        } catch (e: Exception) { if (program != 0) GL.glDeleteProgram(program); throw e }
        finally { GL.glDeleteShader(vertex); if (fragment != 0) GL.glDeleteShader(fragment) }
    }

    /** 保存并恢复本桥接器修改的宿主状态，不更改宿主纹理参数或其FBO的readBuffer。 */
    private class State {
        private fun int(name: Int) = IntArray(1).also { GL.glGetIntegerv(name, it, 0) }[0]
        private val active = int(GL.GL_ACTIVE_TEXTURE)
        private val program = int(GL.GL_CURRENT_PROGRAM)
        private val vao = int(GL.GL_VERTEX_ARRAY_BINDING)
        private val read = int(GL.GL_READ_FRAMEBUFFER_BINDING)
        private val draw = int(GL.GL_DRAW_FRAMEBUFFER_BINDING)
        private val pack = int(GL.GL_PIXEL_PACK_BUFFER_BINDING)
        private val unpack = int(GL.GL_PIXEL_UNPACK_BUFFER_BINDING)
        private val viewport = IntArray(4).also { GL.glGetIntegerv(GL.GL_VIEWPORT, it, 0) }
        private val mask = BooleanArray(4).also { GL.glGetBooleanv(GL.GL_COLOR_WRITEMASK, it, 0) }
        private val enabled = flags.map { GL.glIsEnabled(it) }
        private val stores = intArrayOf(GL.GL_PACK_ALIGNMENT,GL.GL_PACK_ROW_LENGTH,GL.GL_PACK_SKIP_ROWS,GL.GL_PACK_SKIP_PIXELS)
        private val values = stores.map { int(it) }
        private val texture2d: Int
        private val textureOes: Int
        private val sampler: Int
        init {
            GL.glActiveTexture(GL.GL_TEXTURE0)
            texture2d = int(GL.GL_TEXTURE_BINDING_2D); textureOes = int(0x8D67); sampler = int(GL.GL_SAMPLER_BINDING)
        }
        fun restore() {
            GL.glUseProgram(program); GL.glBindVertexArray(vao)
            GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER,read); GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER,draw)
            GL.glBindBuffer(GL.GL_PIXEL_PACK_BUFFER,pack); GL.glBindBuffer(GL.GL_PIXEL_UNPACK_BUFFER,unpack)
            stores.forEachIndexed { i, name -> GL.glPixelStorei(name,values[i]) }
            GL.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]); GL.glColorMask(mask[0],mask[1],mask[2],mask[3])
            flags.forEachIndexed { i, name -> if(enabled[i]) GL.glEnable(name) else GL.glDisable(name) }
            GL.glActiveTexture(GL.GL_TEXTURE0)
            GL.glBindTexture(GL.GL_TEXTURE_2D,texture2d); GL.glBindTexture(0x8D65,textureOes); GL.glBindSampler(0,sampler)
            GL.glActiveTexture(active)
        }
        companion object {
            val flags = intArrayOf(GL.GL_BLEND,GL.GL_DEPTH_TEST,GL.GL_STENCIL_TEST,GL.GL_SCISSOR_TEST,
                GL.GL_CULL_FACE,GL.GL_RASTERIZER_DISCARD,GL.GL_DITHER)
        }
    }
}

package com.beautysdk

import android.opengl.EGL14
import android.opengl.GLES30 as GL
import java.util.concurrent.atomic.AtomicBoolean

/** 有界2D输出副本池，解决下一次Session.render覆盖异步消费者纹理的问题。
 * 构造、copy、close须在同一ES3 EGL context/thread。额外一次GPU拷贝，不是零拷贝。
 * Lease.close可由消费线程调用，但必须在消费GPU工作实际完成后归还。
 * 不创建跨context fence；跨共享context必须由适配层完成生产/消费双向GPU同步。
 * OES必须先由宿主规范化为2D；原图旁路可能返回OES，不能直接交给此池。
 */
class GlTextureOutputPool(private val capacity: Int = 3) : AutoCloseable {
    private class Slot {
        val leased = AtomicBoolean(false)
        var texture = 0
        var width = 0
        var height = 0
    }
    /** 独占输出租约，close幂等。归还后不得继续读取frame或访问其纹理。 */
    class Lease internal constructor(val frame: TextureFrame, private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }
    private val owner = Thread.currentThread()
    private val context = EGL14.eglGetCurrentContext()
    private val slots: List<Slot>
    private var framebuffer = 0
    private var closed = false
    init {
        require(capacity in 1..8)
        check(context != EGL14.EGL_NO_CONTEXT && GL.glGetString(GL.GL_VERSION)?.contains("OpenGL ES 3") == true)
        slots = List(capacity) { Slot() }
    }
    /** 池满立即返回null，宿主可丢弃本次处理结果或按其契约旁路，禁止等待编码线程。
     * 输入是当前上下文可访问且已完成生产同步的RGBA 2D纹理；保留原采样矩阵及方向元数据。
     */
    fun copy(frame: TextureFrame): Lease? {
        checkOwner(); check(!closed)
        require(frame.target == TextureTarget.TEXTURE_2D) { "Normalize OES to 2D before leasing" }
        val slot = slots.firstOrNull { it.leased.compareAndSet(false, true) } ?: return null
        val oldRead = integer(GL.GL_READ_FRAMEBUFFER_BINDING)
        val oldTexture = integer(GL.GL_TEXTURE_BINDING_2D)
        val oldUnpack = integer(GL.GL_PIXEL_UNPACK_BUFFER_BINDING)
        try {
            if (framebuffer == 0) framebuffer = generate { GL.glGenFramebuffers(1, it, 0) }
            if (slot.texture == 0) slot.texture = generate { GL.glGenTextures(1, it, 0) }
            GL.glBindTexture(GL.GL_TEXTURE_2D, slot.texture)
            if (slot.width != frame.info.width || slot.height != frame.info.height) {
                GL.glBindBuffer(GL.GL_PIXEL_UNPACK_BUFFER, 0)
                GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, frame.info.width, frame.info.height,
                    0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            }
            GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, framebuffer)
            GL.glFramebufferTexture2D(GL.GL_READ_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, frame.textureId, 0)
            check(GL.glCheckFramebufferStatus(GL.GL_READ_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE) { "Source texture is not readable" }
            GL.glCopyTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, 0, 0, frame.info.width, frame.info.height)
            check(GL.glGetError() == GL.GL_NO_ERROR) { "Texture copy failed (including preexisting host GL errors)" }
            slot.width = frame.info.width; slot.height = frame.info.height
            return Lease(TextureFrame(frame.info, slot.texture, TextureTarget.TEXTURE_2D, frame.copyTransform())) {
                slot.leased.set(false)
            }
        } catch (error: Exception) {
            slot.leased.set(false)
            throw error
        } finally {
            // 不让池FBO长期持有引擎输入纹理，避免延迟引擎释放资源。
            if (framebuffer != 0) {
                GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, framebuffer)
                GL.glFramebufferTexture2D(GL.GL_READ_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, 0, 0)
            }
            GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER, oldRead)
            GL.glBindTexture(GL.GL_TEXTURE_2D, oldTexture)
            GL.glBindBuffer(GL.GL_PIXEL_UNPACK_BUFFER, oldUnpack)
        }
    }
    /** 先停止生产并等待消费者归还全部租约，再于创建上下文释放；尚有租约时拒绝销毁。 */
    override fun close() {
        checkOwner()
        if (closed) return
        check(slots.none { it.leased.get() }) { "Return all texture leases before closing the pool" }
        slots.forEach { if (it.texture != 0) GL.glDeleteTextures(1, intArrayOf(it.texture), 0) }
        if (framebuffer != 0) GL.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        closed = true
    }
    private fun checkOwner() {
        check(Thread.currentThread() === owner && EGL14.eglGetCurrentContext() == context) { "Pool requires its creating EGL context/thread" }
    }
    private fun integer(name: Int) = IntArray(1).also { GL.glGetIntegerv(name, it, 0) }[0]
    private fun generate(action: (IntArray) -> Unit) = IntArray(1).also(action)[0].also { check(it != 0) }
}

package com.beautysdk.camera

import java.nio.ByteBuffer

enum class CameraLens { FRONT, BACK }
enum class CameraStatus { STARTING, BOUND, STOPPED, ERROR }

data class CameraConfig @JvmOverloads constructor(
    val lens: CameraLens = CameraLens.FRONT,
    val width: Int = 640,
    val height: Int = 480,
    val maxAnalysisFps: Int = 10,
) {
    init { require(width in 16..4096 && height in 16..4096 && maxAnalysisFps in 1..30) }
}

/** RGBA8888 帧，默认独占副本；明确选择借用模式时仅在 onFrame 回调内有效。
 * 原点左上，像素未转正/镜像；显示顺序为顺时针 rotationDegrees 再水平 mirrored。
 * timestampNs 来自相机的单调纳秒时间线，不能直接与 System.nanoTime 比较；跨 session 可重置。
 */
class CameraFrame internal constructor(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val rotationDegrees: Int,
    val lens: CameraLens,
    val sessionId: Long,
    private val pixels: ByteBuffer,
    val rowStrideBytes: Int,
    val borrowed: Boolean = false,
) {
    internal constructor(width: Int, height: Int, timestampNs: Long, rotationDegrees: Int,
                         lens: CameraLens, sessionId: Long, rgba: ByteArray) :
        this(width, height, timestampNs, rotationDegrees, lens, sessionId, ByteBuffer.wrap(rgba), width * 4)
    private var valid = true
    /** 借用帧回调结束后禁止再次获取视图；调用方也不得保存已获取的 ByteBuffer。 */
    internal fun invalidate() { if (borrowed) valid = false }
    val mirrored: Boolean get() = lens == CameraLens.FRONT
    fun readOnlyPixels(): ByteBuffer {
        check(valid) { "Borrowed camera frame has expired" }
        return pixels.asReadOnlyBuffer()
    }
}

interface CameraFrameListener {
    /** 仅同步消费者可启用；回调结束相机立即释放图像，禁止保存帧/缓冲或提交给异步任务。
     * 默认 false 保留原有独占副本契约；CameraX 分析线程与预览线程独立。
     */
    fun acceptsBorrowedFrames(): Boolean = false
    /** 在复制 RGBA 前检查，纯纹理旁路时省去分析支路的分配和像素打包。工作线程调用。 */
    fun needsFrame(): Boolean = true
    /** 单一分析线程同步调用。借用模式禁止持有输入，独占模式可保存。慢消费者会丢帧。 */
    fun onFrame(frame: CameraFrame)
    /** 与 onFrame 同线程，在已开始的帧回调结束后调用，供处理器释放线程相关资源。 */
    fun onStreamStopped() = Unit
}

/** 单平面 RGBA 打包；支持 position、行/像素 stride 及 crop。保持输入 position 不变。 */
internal object RgbaPacking {
    fun copy(buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
             left: Int, top: Int, width: Int, height: Int): ByteArray {
        require(left >= 0 && top >= 0 && width > 0 && height > 0 && pixelStride >= 4)
        val rowEnd = (left.toLong() + width - 1) * pixelStride + 4
        require(rowStride >= rowEnd)
        val input = buffer.slice()
        val end = (top.toLong() + height - 1) * rowStride + rowEnd
        require(end <= input.remaining()) { "RGBA plane is too small" }
        val output = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 4))
        // 连续 RGBA 按行批量复制，保留像素间存在间隙时的兼容路径。
        if (pixelStride == 4) {
            val rowBytes = width * 4
            if (rowStride == rowBytes) {
                input.position(top * rowStride + left * 4)
                input.get(output)
            } else for (y in 0 until height) {
                input.position((top + y) * rowStride + left * 4)
                input.get(output, y * rowBytes, rowBytes)
            }
            return output
        }
        for (y in 0 until height) for (x in 0 until width) {
            val src = (top+y)*rowStride+(left+x)*pixelStride
            val dst = (y*width+x)*4
            for (c in 0..3) output[dst+c] = input.get(src+c)
        }
        return output
    }
}

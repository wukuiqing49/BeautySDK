package com.beautysdk

import java.nio.ByteBuffer

/** 原始缓冲区尺寸；顺时针旋转后再做水平镜像。时间戳单位为纳秒，采用同一单调时钟。 */
data class FrameInfo @JvmOverloads constructor(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val rotationDegrees: Int = 0,
    val mirrored: Boolean = false,
) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(timestampNs >= 0) { "Timestamp must be non-negative" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Invalid rotation" }
    }
}

/**
 * 借用帧：SDK 不拥有缓冲区/纹理，不释放、不缓存，不延长宿主资源有效期。
 * 输入资源在 process 返回前保持有效；旁路返回输入时，输出有效期仍由宿主管理。
 */
sealed interface VideoFrame { val info: FrameInfo }

/** RGBA8888，非预乘 alpha，sRGB；数据从传入 buffer 的 position 起算，支持行 padding。 */
class RgbaFrame(
    override val info: FrameInfo,
    buffer: ByteBuffer,
    val rowStrideBytes: Int,
) : VideoFrame {
    private val pixels = buffer.slice().asReadOnlyBuffer()
    init {
        val rowBytes = info.width.toLong() * 4
        require(rowStrideBytes.toLong() >= rowBytes) { "Row stride is too small" }
        val required = (info.height - 1L) * rowStrideBytes + rowBytes
        require(pixels.remaining().toLong() >= required) { "Buffer is too small" }
    }
    /** 独立 position/limit 的只读视图；底层内存仍归宿主，异步使用必须复制或 retain。 */
    fun readOnlyPixels(): ByteBuffer = pixels.asReadOnlyBuffer()
}

/** 输入纹理目标：普通2D纹理或相机常用的外部OES纹理。 */
enum class TextureTarget { TEXTURE_2D, EXTERNAL_OES }

/**
 * 借用的纹理句柄；GlBeautyRenderer 在宿主当前 EGL 上下文中采样，不接管输入纹理。
 * transform 为列主序 4x4 采样矩阵（含生产者裁剪），不包含 FrameInfo 的旋转/镜像。
 * GL 创建、渲染和释放必须使用同一 EGL context/thread；不得跨上下文直接传递 ID。
 */
class TextureFrame(
    override val info: FrameInfo,
    val textureId: Int,
    val target: TextureTarget,
    transform: FloatArray,
) : VideoFrame {
    private val samplingTransform = transform.copyOf()
    init {
        require(textureId > 0) { "Texture ID must be positive" }
        require(samplingTransform.size == 16 && samplingTransform.all { it.isFinite() }) {
            "Transform must contain 16 finite values"
        }
    }
    /** 返回采样矩阵副本，调用者修改数组不会改变本帧的纹理描述。 */
    fun copyTransform(): FloatArray = samplingTransform.copyOf()
}

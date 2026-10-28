package com.beautysdk

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** 明确颜色矩阵和量化范围，不根据分辨率猜测颜色空间。 */
enum class YuvColorSpace { BT601_LIMITED, BT601_FULL, BT709_LIMITED, BT709_FULL }

/** 借用的YUV平面，从buffer.position开始；支持padding及交错UV的pixelStride=2。 */
class YuvPlane(buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int) {
    private val bytes = buffer.slice().asReadOnlyBuffer()
    init { require(rowStride > 0 && pixelStride > 0) }
    internal fun validate(width: Int, height: Int) {
        val row = (width - 1L) * pixelStride + 1
        require(rowStride >= row && (height - 1L) * rowStride + row <= bytes.remaining()) {
            "YUV plane stride or capacity is invalid"
        }
    }
    internal fun at(x: Int, y: Int) = bytes.get(y * rowStride + x * pixelStride).toInt() and 255
}

/** I420或YUV_420_888的三平面视图。NV12/NV21可将交错区分别定位到U/V首字节，pixelStride设为2。
 * 不持有图像所有权；转换结束前宿主不可释放底层内存。裁剪需由宿主明确构造，不隐式猜测cropRect。
 */
class Yuv420Frame(val info: FrameInfo, val y: YuvPlane, val u: YuvPlane, val v: YuvPlane,
    val colorSpace: YuvColorSpace) {
    init {
        y.validate(info.width, info.height)
        val cw = info.width / 2 + info.width % 2
        val ch = info.height / 2 + info.height % 2
        u.validate(cw, ch); v.validate(cw, ch)
    }
}

/** 可复用的小图转换器：先采样缩小再转换，不创建全分辨率RGBA或Bitmap。
 * 这是Kotlin兼容实现，不是libyuv/SIMD，不用于宣称高清实时性能。
 * convert同线程调用；返回缓冲借用至下次convert。submit到分析运行时后即可复用。
 */
class Yuv420RgbaConverter(private val maxSide: Int = 320) {
    private var owner: Thread? = null
    private var output: ByteBuffer? = null
    init { require(maxSide in 16..2048) }
    fun convert(frame: Yuv420Frame): RgbaFrame {
        val current = Thread.currentThread()
        if (owner == null) owner = current
        check(owner === current) { "Converter must use one thread" }
        val info = frame.info
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(info.width, info.height))
        val width = maxOf(1, (info.width * scale).roundToInt())
        val height = maxOf(1, (info.height * scale).roundToInt())
        val size = width * height * 4
        val buffer = output?.takeIf { it.capacity() >= size }
            ?: ByteBuffer.allocateDirect(size).also { output = it }
        buffer.clear(); buffer.limit(size)
        val limited = frame.colorSpace == YuvColorSpace.BT601_LIMITED || frame.colorSpace == YuvColorSpace.BT709_LIMITED
        val hd = frame.colorSpace == YuvColorSpace.BT709_LIMITED || frame.colorSpace == YuvColorSpace.BT709_FULL
        val kr = if (hd) .2126 else .299
        val kb = if (hd) .0722 else .114
        val kg = 1 - kr - kb
        val ys = if (limited) 255.0 / 219 else 1.0
        val cs = if (limited) 255.0 / 224 else 1.0
        for (dy in 0 until height) {
            val sy = minOf(info.height - 1, ((dy + .5) * info.height / height).toInt())
            for (dx in 0 until width) {
                val sx = minOf(info.width - 1, ((dx + .5) * info.width / width).toInt())
                val yy = (frame.y.at(sx, sy) - if (limited) 16 else 0) * ys
                val cb = (frame.u.at(sx / 2, sy / 2) - 128) * cs
                val cr = (frame.v.at(sx / 2, sy / 2) - 128) * cs
                buffer.put((yy + 2 * (1 - kr) * cr).roundToInt().coerceIn(0, 255).toByte())
                buffer.put((yy - 2 * kb * (1 - kb) / kg * cb - 2 * kr * (1 - kr) / kg * cr).roundToInt().coerceIn(0, 255).toByte())
                buffer.put((yy + 2 * (1 - kb) * cb).roundToInt().coerceIn(0, 255).toByte())
                buffer.put(255.toByte())
            }
        }
        buffer.flip()
        return RgbaFrame(info.copy(width = width, height = height), buffer, width * 4)
    }
}

package com.beautysdk

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** 单一分析线程独占的模型输入；缩放、去 alpha、旋转和镜像一次写入原生缓冲。
 * 不生成 Bitmap 或中间像素数组。返回内存仅在下一次 prepare/clear 前有效，禁止并发使用。
 */
class ReusableRgbInput(private val maxSide: Int = 320, private val writer: RgbPixelWriter? = null) {
    init { require(maxSide in 16..2048) }
    private var storage: ByteBuffer? = null
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /** 同步读取带行 padding 的借用 RGBA；不改变输入 position/limit，不持有源缓冲。 */
    fun prepare(frame: RgbaFrame): ByteBuffer {
        val info = frame.info
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(info.width, info.height))
        val w = maxOf(1, (info.width * scale).roundToInt())
        val h = maxOf(1, (info.height * scale).roundToInt())
        val swap = info.rotationDegrees == 90 || info.rotationDegrees == 270
        width = if (swap) h else w
        height = if (swap) w else h
        val size = width * height * 3
        val out = storage?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).also { storage = it }
        out.clear()
        if (writer?.write(frame, out, width, height) == true) {
            out.position(0); out.limit(size)
            return out
        }
        val source = frame.readOnlyPixels()
        // 按输出顺序写入，逆向求取源像素，避免额外的 RGB 数组、旋转数组及整图复制。
        for (dy in 0 until height) for (dx in 0 until width) {
            val rx = if (info.mirrored) width - 1 - dx else dx
            val x: Int
            val y: Int
            when (info.rotationDegrees) {
                90 -> { x = dy; y = h - 1 - rx }
                180 -> { x = w - 1 - rx; y = h - 1 - dy }
                270 -> { x = w - 1 - dy; y = rx }
                else -> { x = rx; y = dy }
            }
            val sx = (x.toLong() * info.width / w).toInt()
            val sy = (y.toLong() * info.height / h).toInt()
            val offset = sy * frame.rowStrideBytes + sx * 4
            out.put(source.get(offset)).put(source.get(offset + 1)).put(source.get(offset + 2))
        }
        out.flip()
        return out
    }

    /** 在分析停止后释放持有的缓冲引用；不得与模型读取并发。 */
    fun clear() { storage = null; width = 0; height = 0 }
}

/** 可选原生写入器；只在同步调用期间借用源/目标，不得保存指针。
 * 成功时填满目标 RGB 并返回 true；不支持的输入返回 false，调用者使用通用转换。
 */
fun interface RgbPixelWriter {
    fun write(frame: RgbaFrame, destination: ByteBuffer, width: Int, height: Int): Boolean
}

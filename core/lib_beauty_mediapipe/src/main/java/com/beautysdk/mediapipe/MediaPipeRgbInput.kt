package com.beautysdk.mediapipe

import com.beautysdk.ReusableRgbInput
import com.beautysdk.RgbaFrame
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** MediaPipe 0.10.32 的 RGB 行按4字节对齐；只改变存储步长，不改变图像尺寸和关键点空间。
 * 由分析线程独占，尺寸不变时复用 DirectByteBuffer；模型同步返回前不得再次写入。
 */
internal class MediaPipeRgbInput {
    private var storage: ByteBuffer? = null
    private var fallback: ReusableRgbInput? = null
    private var fallbackMaxSide = 0
    var width = 0
        private set
    var height = 0
        private set
    var rowStrideBytes = 0
        private set

    private fun allocate(w: Int, h: Int): ByteBuffer {
        require(w > 0 && h > 0)
        width = w; height = h
        rowStrideBytes = Math.addExact(Math.multiplyExact(w,3),3) and -4
        val size = Math.multiplyExact(rowStrideBytes,h)
        val out = storage?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).also { storage = it }
        out.clear()
        return out
    }

    /** 静态/公共RGB入口已转正，逐行批量复制像素并初始化尾部padding，不烘焙新尺寸。 */
    fun prepare(pixels: ByteArray, w: Int, h: Int): ByteBuffer {
        require(pixels.size.toLong() == w.toLong()*h*3)
        val out = allocate(w,h)
        val bytes = w*3
        if(bytes == rowStrideBytes) out.put(pixels)
        else for(y in 0 until h) {
            out.put(pixels,y*bytes,bytes)
            repeat(rowStrideBytes-bytes) { out.put(0.toByte()) }
        }
        out.flip()
        return out
    }

    /** Direct RGBA单次JNI完成缩小、旋转、镜像和带padding的RGB写入，无紧凑中间图。
     * 堆缓冲沿用公共参考转换，再按行批量打包，不为进入JNI复制整张源帧。
     */
    fun prepare(frame: RgbaFrame, maxSide: Int): ByteBuffer {
        require(maxSide in 16..2048)
        val info = frame.info
        val scale = minOf(1.0,maxSide.toDouble()/maxOf(info.width,info.height))
        val w = maxOf(1,(info.width*scale).roundToInt())
        val h = maxOf(1,(info.height*scale).roundToInt())
        val swap = info.rotationDegrees==90 || info.rotationDegrees==270
        val out = allocate(if(swap) h else w,if(swap) w else h)
        if(!NativeRgbaConverter.writeAligned(frame,out,width,height,rowStrideBytes)) {
            if(fallback==null || fallbackMaxSide!=maxSide) {
                fallback?.clear();fallback=ReusableRgbInput(maxSide);fallbackMaxSide=maxSide
            }
            val compact = fallback!!.prepare(frame)
            val bytes = width*3
            for(y in 0 until height) {
                compact.limit((y+1)*bytes)
                out.put(compact)
                repeat(rowStrideBytes-bytes) { out.put(0.toByte()) }
            }
        }
        out.position(0);out.limit(out.capacity())
        return out
    }

    /** 跟随检测器关闭，释放本对象持有的输入缓冲与堆缓冲兼容路径。 */
    fun clear() {
        storage=null;fallback?.clear();fallback=null;fallbackMaxSide=0
        width=0;height=0;rowStrideBytes=0
    }
}

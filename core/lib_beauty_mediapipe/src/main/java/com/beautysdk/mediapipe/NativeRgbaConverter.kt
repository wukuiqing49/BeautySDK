package com.beautysdk.mediapipe

import com.beautysdk.RgbaFrame
import com.beautysdk.RgbPixelWriter
import java.nio.ByteBuffer

/** 一次 JNI 调用完成 RGBA 缩小、转正、镜像及去 alpha；直接读写缓冲，不创建图像数组。 */
internal object NativeRgbaConverter : RgbPixelWriter {
    init { System.loadLibrary("beauty_rgb_input") }

    override fun write(frame: RgbaFrame, destination: ByteBuffer, width: Int, height: Int): Boolean {
        return writeAligned(frame,destination,width,height,width*3)
    }

    /** MediaPipe专用的目标行步长；原有公共紧凑RGB写入器仍保持width*3布局。 */
    fun writeAligned(frame: RgbaFrame, destination: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int): Boolean {
        val source = frame.readOnlyPixels()
        // 堆缓冲保留通用路径，不为使用 JNI 额外复制整张输入。
        if (!source.isDirect || !destination.isDirect) return false
        val info = frame.info
        return convert(source, info.width, info.height, frame.rowStrideBytes,
            info.rotationDegrees, info.mirrored, destination, width, height,rowStrideBytes)
    }

    /** 原生函数再次验证容量和步长；返回前完成写入，不持有 JNI 引用/内存指针。 */
    private external fun convert(source: ByteBuffer, sourceWidth: Int, sourceHeight: Int,
        stride: Int, rotation: Int, mirrored: Boolean, destination: ByteBuffer,
        width: Int, height: Int, destinationStride: Int): Boolean
}

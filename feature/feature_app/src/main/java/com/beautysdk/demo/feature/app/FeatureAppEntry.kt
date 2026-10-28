package com.beautysdk.demo.feature.app

import com.wkq.util.CoreUtils
import com.beautysdk.BeautyProcessor
import com.beautysdk.FrameInfo
import com.beautysdk.RgbaFrame
import java.nio.ByteBuffer

object FeatureAppEntry {

    fun isAvailable(): Boolean = CoreUtils.isInitialized()

    /** 演示模块编排 SDK 调用；图像处理实现保留在 core/lib_beauty。 */
    fun runFrameCheck(): Boolean {
        if (!isAvailable()) return false
        val frame = RgbaFrame(FrameInfo(1, 1, 0), ByteBuffer.wrap(byteArrayOf(64, 96, 127, -1)), 4)
        return BeautyProcessor().use { processor ->
            processor.start()
            val output = processor.process(frame)
            processor.stop()
            output === frame && frame.readOnlyPixels().get(0) == 64.toByte()
        }
    }
}

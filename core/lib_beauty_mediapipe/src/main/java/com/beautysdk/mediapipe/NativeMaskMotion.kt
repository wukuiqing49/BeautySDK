package com.beautysdk.mediapipe

import java.nio.ByteBuffer

/** 模型采样图到最新同视场图的有界平移匹配；不预测、不链式累积，不以人脸框移动整个人像。
 * 仅在前景纹理、双向匹配及全局一致性均足够时返回位移，否则明确拒绝补偿。
 */
internal object NativeMaskMotion {
    init { System.loadLibrary("beauty_rgb_input") }
    external fun estimate(reference:ByteBuffer,current:ByteBuffer,confidence:ByteBuffer,
        width:Int,height:Int,rowStride:Int):FloatArray?
}

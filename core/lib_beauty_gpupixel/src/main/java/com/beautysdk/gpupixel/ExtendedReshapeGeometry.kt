package com.beautysdk.gpupixel

import com.beautysdk.FaceObservation
import com.beautysdk.FrameInfo
import kotlin.math.hypot

/**
 * BeautySDK自定义鼻/下巴/嘴部二维形变区域，不冒充GPUPixel上游原生能力。
 * 追加在复用数组42..55：三个xy/radius区域、像素等距空间的脸部朝下单位向量。
 * 返回位掩码1鼻、2下巴、4嘴；每个区域独立校验，不用人脸框猜关键点。
 */
internal fun extendedReshapeGeometry(face: FaceObservation?, info: FrameInfo, out: FloatArray): Int {
    require(out.size==56)
    out.fill(0f,42,56)
    if(face==null || face.landmarks.size<478) return 0
    val points=face.landmarks
    fun valid(i: Int)=points[i].let {it.x in 0f..1f && it.y in 0f..1f}
    if(!valid(168) || !valid(152)) return 0
    val aspect=info.width.toFloat()/info.height
    val top=points[168];val chin=points[152]
    val dx=chin.x-top.x;val dy=(chin.y-top.y)/aspect
    val height=hypot(dx,dy)
    if(height<8f/info.width) return 0
    out[54]=dx/height;out[55]=dy/height
    var result=2
    out[46]=chin.x;out[47]=chin.y;out[48]=height*.32f;out[49]=height*.06f
    if(valid(1) && valid(98) && valid(327)) {
        val left=points[98];val right=points[327]
        val width=hypot(left.x-right.x,(left.y-right.y)/aspect)
        if(width>=2f/info.width) {
            out[42]=points[1].x;out[43]=points[1].y
            out[44]=width*.85f;out[45]=width*1.3f;result=result or 1
        }
    }
    if(valid(61) && valid(291) && valid(13) && valid(14)) {
        val left=points[61];val right=points[291]
        val width=hypot(left.x-right.x,(left.y-right.y)/aspect)
        if(width>=4f/info.width) {
            out[50]=(points[13].x+points[14].x)*.5f
            out[51]=(points[13].y+points[14].y)*.5f
            out[52]=width*.9f;out[53]=width*.5f;result=result or 4
        }
    }
    return result
}

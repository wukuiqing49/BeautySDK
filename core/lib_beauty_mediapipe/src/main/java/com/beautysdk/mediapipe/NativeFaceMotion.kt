package com.beautysdk.mediapipe

import com.beautysdk.*
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** 实验性C++局部块匹配，适合短时间小幅运动；快速转头/遮挡必须交回模型重新定位。
 * 原始方向灰度最长边160，native只估计平移/缩放/平面旋转，不声称三维姿态跟踪。
 */
class NativeFaceMotion : FaceMotionEstimator {
    companion object { init { System.loadLibrary("beauty_rgb_input") } }
    override fun prepare(frame: RgbaFrame,reuse: MotionImage?): MotionImage {
        val scale=minOf(1.0,160.0/maxOf(frame.info.width,frame.info.height))
        val w=maxOf(1,(frame.info.width*scale).roundToInt());val h=maxOf(1,(frame.info.height*scale).roundToInt())
        // 独占小灰度快照，检测和跟踪并发时不能覆盖仍被另一线程读取的参考。
        val pixels=reuse?.takeIf { it.width==w && it.height==h }?.pixels ?: ByteBuffer.allocateDirect(w*h)
        pixels.clear()
        check(gray(frame.readOnlyPixels(),frame.info.width,frame.info.height,frame.rowStrideBytes,pixels,w,h))
        return MotionImage(frame.info,w,h,pixels)
    }
    override fun estimate(reference: MotionImage,current: MotionImage,face: FaceObservation): FaceObservation? {
        if(reference.width!=current.width || reference.height!=current.height) return null
        val indices=intArrayOf(33,133,362,263,1,61,291,0,4,168,197,50,280,205,425,152)
        if(face.landmarks.size<478) return null
        val points=FloatArray(indices.size*2)
        indices.forEachIndexed { i,index -> points[i*2]=face.landmarks[index].x;points[i*2+1]=face.landmarks[index].y }
        val t=match(reference.pixels,current.pixels,reference.width,reference.height,points) ?: return null
        val w=reference.width.toFloat();val h=reference.height.toFloat()
        fun move(p: FacePoint): FacePoint = FacePoint((t[0]*p.x*w-t[1]*p.y*h+t[2])/w,
            (t[1]*p.x*w+t[0]*p.y*h+t[3])/h)
        val b=face.bounds
        val corners=listOf(FacePoint(b.left,b.top),FacePoint(b.right,b.top),FacePoint(b.left,b.bottom),FacePoint(b.right,b.bottom)).map(::move)
        val l=corners.minOf { it.x }.coerceIn(0f,1f);val r=corners.maxOf { it.x }.coerceIn(0f,1f)
        val top=corners.minOf { it.y }.coerceIn(0f,1f);val bottom=corners.maxOf { it.y }.coerceIn(0f,1f)
        if(r<=l || bottom<=top) return null
        val anchors=face.anchors.mapValues { move(it.value) }
        if(anchors.values.any { it.x !in 0f..1f || it.y !in 0f..1f }) return null
        return FaceObservation(FaceBounds(l,top,r,bottom),anchors,face.landmarks.map(::move))
    }
    private external fun gray(source: ByteBuffer,sw: Int,sh: Int,stride: Int,destination: ByteBuffer,w: Int,h: Int): Boolean
    private external fun match(reference: ByteBuffer,current: ByteBuffer,w: Int,h: Int,points: FloatArray): FloatArray?
}

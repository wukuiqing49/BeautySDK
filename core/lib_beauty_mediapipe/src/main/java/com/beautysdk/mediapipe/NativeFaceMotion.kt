package com.beautysdk.mediapipe

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
import com.beautysdk.*
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** 实验性C++有界金字塔光流与唇部局部块匹配；快速转头/遮挡仍须模型重新定位。
 * 整脸灰度最长边160；可选320细节图估计唇部局部残差，仍不声称三维姿态或遮挡分割。
 */
class NativeFaceMotion @JvmOverloads constructor(private val enableLipTracking: Boolean = false) : FaceMotionEstimator {
    private class DetailedImage(info: FrameInfo,w: Int,h: Int,pixels: ByteBuffer,val detail: MotionImage) : MotionImage(info,w,h,pixels)
    private val anchorIndices=intArrayOf(33,133,362,263,1,61,291,0,4,168,197,50,280,205,425,152)
    private val lipIndices=intArrayOf(61,185,40,39,37,0,267,269,270,409,291,375,321,405,314,17,84,181,91,146,
        78,191,80,81,82,13,312,311,310,415,308,324,318,402,317,14,87,178,88,95)
    // 匹配稀疏20点，将残差沿轮廓传播到完整40点，避免每帧加倍块搜索。
    private val sampledSlots=intArrayOf(0,2,4,5,6,8,10,12,14,15,16,18,20,23,25,27,30,33,35,37)
    private val triangles=IntArray(120).also { out ->
        for(i in 0 until 20) {
            val j=(i+1)%20;val k=i*6
            out[k]=i;out[k+1]=j;out[k+2]=20+i
            out[k+3]=20+i;out[k+4]=j;out[k+5]=20+j
        }
    }
    companion object { init { System.loadLibrary("beauty_rgb_input") } }
    override fun prepare(frame: RgbaFrame,reuse: MotionImage?): MotionImage {
        val scale=minOf(1.0,160.0/maxOf(frame.info.width,frame.info.height))
        val w=maxOf(1,(frame.info.width*scale).roundToInt());val h=maxOf(1,(frame.info.height*scale).roundToInt())
        // 独占小灰度快照，检测和跟踪并发时不能覆盖仍被另一线程读取的参考。
        val pixels=reuse?.takeIf { it.width==w && it.height==h }?.pixels ?: ByteBuffer.allocateDirect(w*h)
        pixels.clear()
        if(!enableLipTracking) {
            check(gray(frame.readOnlyPixels(),frame.info.width,frame.info.height,frame.rowStrideBytes,pixels,w,h))
            return MotionImage(frame.info,w,h,pixels)
        }
        val detailScale=minOf(1.0,320.0/maxOf(frame.info.width,frame.info.height))
        val dw=maxOf(1,(frame.info.width*detailScale).roundToInt());val dh=maxOf(1,(frame.info.height*detailScale).roundToInt())
        val detailPixels=if(w==dw && h==dh) pixels else
            (reuse as? DetailedImage)?.detail?.takeIf { it.width==dw && it.height==dh }?.pixels
            ?: ByteBuffer.allocateDirect(dw*dh)
        detailPixels.clear()
        // 一次JNI转换；采样格严格嵌套时从细节灰度取点，不重复读取RGBA或改变原采样位置。
        check(grayPair(frame.readOnlyPixels(),frame.info.width,frame.info.height,frame.rowStrideBytes,
            pixels,w,h,detailPixels,dw,dh))
        return DetailedImage(frame.info,w,h,pixels,MotionImage(frame.info,dw,dh,detailPixels))
    }
    override fun estimate(reference: MotionImage,current: MotionImage,face: FaceObservation): FaceObservation? {
        if (!BeautyLicensing.allows(LicensedFeature.FACE)) return null
        if(reference.width!=current.width || reference.height!=current.height) return null
        if(face.landmarks.size<478) return null
        val points=FloatArray(anchorIndices.size*2)
        anchorIndices.forEachIndexed { i,index -> points[i*2]=face.landmarks[index].x;points[i*2+1]=face.landmarks[index].y }
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
        val landmarks=face.landmarks.mapTo(ArrayList<FacePoint>(face.landmarks.size),::move)
        if(enableLipTracking && reference is DetailedImage && current is DetailedImage &&
            reference.detail.width==current.detail.width && reference.detail.height==current.detail.height) {
            val a=reference.detail;val c=current.detail
            val contour=FloatArray(lipIndices.size*2)
            lipIndices.forEachIndexed { i,index ->contour[2*i]=landmarks[index].x;contour[2*i+1]=landmarks[index].y }
            val original=FloatArray(sampledSlots.size*2);val predicted=FloatArray(original.size)
            sampledSlots.forEachIndexed { i,slot ->
                val p=face.landmarks[lipIndices[slot]]
                original[2*i]=p.x;original[2*i+1]=p.y
                predicted[2*i]=contour[2*slot];predicted[2*i+1]=contour[2*slot+1]
            }
            val sparse=refineLips(a.pixels,c.pixels,a.width,a.height,original,predicted)
            if(sparse!=null) {
                val refined=contour.copyOf()
                // 传播位移而非重建唇形：保留每个模型点的原始轮廓细节及全局运动。
                for(ring in 0..1) {
                    val start=if(ring==0)0 else 12;val end=if(ring==0)12 else 20;val base=ring*20
                    for(i in start until end) {
                        val next=if(i+1==end)start else i+1
                        val from=sampledSlots[i]-base;val to=if(next==start)20 else sampledSlots[next]-base
                        for(j in from until to) {
                            val t=(j-from).toFloat()/(to-from);val slot=base+j
                            for(axis in 0..1) {
                                val d0=sparse[2*i+axis]-predicted[2*i+axis]
                                val d1=sparse[2*next+axis]-predicted[2*next+axis]
                                refined[2*slot+axis]=contour[2*slot+axis]+d0+(d1-d0)*t
                            }
                        }
                    }
                }
                if(validLipMesh(contour,refined))lipIndices.forEachIndexed { i,index -> landmarks[index]=FacePoint(refined[2*i],refined[2*i+1]) }
            }
        }
        // 锚点与局部网格保持一致；无可靠局部匹配时保持原整脸结果。
        val updated=anchors.toMutableMap()
        // LEFT/RIGHT是在转正空间定义的身份，不能在原始旋转/镜像空间按x重新排序。
        // 以源观测对应关系传递局部校正，避免模型帧与跟踪帧之间胡子翻转180度。
        fun distance(a:FacePoint,b:FacePoint):Float {
            val x=a.x-b.x;val y=a.y-b.y;return x*x+y*y
        }
        for(anchor in listOf(FaceAnchor.LEFT_MOUTH,FaceAnchor.RIGHT_MOUTH)) {
            val original=face.anchors[anchor] ?: continue
            val index=if(distance(original,face.landmarks[61])<=distance(original,face.landmarks[291]))61 else 291
            updated[anchor]=landmarks[index]
        }
        if(FaceAnchor.UPPER_LIP in face.anchors)updated[FaceAnchor.UPPER_LIP]=landmarks[0]
        return FaceObservation(FaceBounds(l,top,r,bottom),updated,landmarks)
    }
    private fun validLipMesh(before: FloatArray,after: FloatArray): Boolean {
        if(after.size!=before.size || after.any { !it.isFinite() || it !in 0f..1f })return false
        fun area(p: FloatArray,a: Int,b: Int,c: Int)=
            (p[b*2]-p[a*2])*(p[c*2+1]-p[a*2+1])-(p[b*2+1]-p[a*2+1])*(p[c*2]-p[a*2])
        for(i in triangles.indices step 3) {
            val old=area(before,triangles[i],triangles[i+1],triangles[i+2])
            val next=area(after,triangles[i],triangles[i+1],triangles[i+2])
            if(kotlin.math.abs(old)>1e-7f && (old*next<=0f || kotlin.math.abs(next/old) !in .25f..3f))return false
        }
        return true
    }
    private external fun refineLips(reference: ByteBuffer,current: ByteBuffer,w: Int,h: Int,points: FloatArray,predicted: FloatArray): FloatArray?
    private external fun gray(source: ByteBuffer,sw: Int,sh: Int,stride: Int,destination: ByteBuffer,w: Int,h: Int): Boolean
    private external fun grayPair(source: ByteBuffer,sw: Int,sh: Int,stride: Int,
        destination: ByteBuffer,w: Int,h: Int,detail: ByteBuffer,dw: Int,dh: Int): Boolean
    private external fun match(reference: ByteBuffer,current: ByteBuffer,w: Int,h: Int,points: FloatArray): FloatArray?
}

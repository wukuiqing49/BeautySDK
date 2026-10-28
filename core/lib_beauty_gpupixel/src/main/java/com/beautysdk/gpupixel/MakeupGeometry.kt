package com.beautysdk.gpupixel

import com.beautysdk.FaceObservation
import com.beautysdk.FrameInfo
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.min

// 固定GPUPixel规范脸的唇部UV与三角形拓扑，Apache-2.0；来源及校验见third_party/gpupixel。
// 只取嘴唇环，不绘制张嘴后的内部区域，避免把牙齿和口腔染红。
private val lipPoints=intArrayOf(61,40,37,0,267,270,291,321,314,17,84,91,78,81,13,311,308,402,14,178)
private val lipUv=floatArrayOf(.426186f,.593516f,.453348f,.586128f,.481258f,.582594f,
    .5f,.584476f,.518742f,.582594f,.546652f,.586128f,.573814f,.593516f,
    .556544f,.620391f,.53132f,.639672f,.5f,.644911f,.46868f,.639672f,.443456f,.620391f,
    .433718f,.595595f,.466898f,.597025f,.5f,.599883f,.533102f,.597025f,
    .566282f,.595595f,.534634f,.61072f,.5f,.616173f,.465366f,.61072f)
private val lipTriangles=intArrayOf(0,1,12,12,1,13,13,1,2,2,13,14,2,14,3,
    3,14,4,4,14,15,4,15,5,5,15,16,5,16,6,
    6,16,7,16,7,17,17,7,8,17,8,18,18,8,9,
    18,9,10,18,10,19,19,10,11,19,11,12,12,11,0)
private val quadTriangles=intArrayOf(0,1,2,2,1,3)

/**
 * 原始帧坐标下构建局部贴妆网格，返回位掩码：1唇部有效、2脸颊有效。
 * 调用者复用288浮点数（60个唇顶点＋12个脸颊顶点，每个xyuv）；不创建逐帧集合。
 * 不使用上游106点索引直接寻址MediaPipe；缺点、越界、退化区域分别旁路。
 */
internal fun makeupGeometry(face: FaceObservation?, info: FrameInfo, out: FloatArray, regions: Int = 3): Int {
    require(out.size>=288)
    require(regions in 0..3)
    if(regions==0) return 0
    if(face==null || face.landmarks.size<478) return 0
    val points=face.landmarks
    fun valid(index: Int)=points[index].let { it.x in 0f..1f && it.y in 0f..1f }
    var result=0
    if(regions and 1 != 0 && lipPoints.all(::valid) &&
        hypot((points[61].x-points[291].x)*info.width,(points[61].y-points[291].y)*info.height)>=4f &&
        hypot((points[0].x-points[17].x)*info.width,(points[0].y-points[17].y)*info.height)>=2f) {
        for(i in lipTriangles.indices) {
            val index=lipTriangles[i];val p=points[lipPoints[index]];val offset=i*4
            out[offset]=p.x*2f-1f;out[offset+1]=p.y*2f-1f
            out[offset+2]=(lipUv[index*2]*1280f-502.5f)/262.5f
            out[offset+3]=(lipUv[index*2+1]*1280f-710f)/167.5f
        }
        result=result or 1
    }
    if(regions and 2 == 0) return result
    if(!valid(33) || !valid(263) || !valid(50) || !valid(280) || !valid(152)) return result
    val left=points[33];val right=points[263]
    val dx=(right.x-left.x)*info.width;val dy=(right.y-left.y)*info.height
    val span=hypot(dx,dy)
    if(span<8f) return result
    val ux=dx/span;val uy=dy/span
    // 以真实下巴确定朝下方向，适配横竖帧和镜像，不能假定原始帧y轴就是脸部朝下。
    val chin=points[152]
    val sign=if(-uy*(chin.x-(left.x+right.x)*.5f)*info.width+
        ux*(chin.y-(left.y+right.y)*.5f)*info.height>=0f) 1f else -1f
    val vx=-uy*sign;val vy=ux*sign
    for(side in 0..1) {
        val center=points[if(side==0) 50 else 280]
        // 按每侧鼻颊投影距离收缩，避免转头时远侧腮红仍保持正脸宽度并越过鼻部。
        // 只改变当前帧局部几何，不新增检测、不引入积压或逐帧内存分配。
        val halfWidth=if(valid(1)) {
            val nose=points[1]
            val noseDistance=abs((center.x-nose.x)*info.width*ux+(center.y-nose.y)*info.height*uy)
            min(span*.27f,noseDistance*.85f)
        } else span*.27f
        for(i in quadTriangles.indices) {
            val corner=quadTriangles[i];val x=if(corner%2==0) -1f else 1f
            val y=if(corner<2) -1f else 1f
            val offset=240+(side*6+i)*4
            out[offset]=(center.x+(ux*x*halfWidth+vx*y*.23f*span)/info.width)*2f-1f
            out[offset+1]=(center.y+(uy*x*halfWidth+vy*y*.23f*span)/info.height)*2f-1f
            out[offset+2]=side*.5f+(x+1f)*.25f
            out[offset+3]=(y+1f)*.5f
        }
    }
    return result or 2
}

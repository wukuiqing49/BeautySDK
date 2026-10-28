package com.beautysdk.gpupixel

import com.beautysdk.FaceObservation
import com.beautysdk.FrameInfo
import kotlin.math.hypot

private val upperLids=arrayOf(
    intArrayOf(33,246,161,160,159,158,157,173,133),
    intArrayOf(263,466,388,387,386,385,384,398,362))
// 长度分布避免八根完全等长，眼头较短、中段舒展。
private val lashProfile=floatArrayOf(.70f,.88f,.98f,1f,.97f,.88f,.73f,.53f)
private val lashTriangles=intArrayOf(0,1,2,2,1,3)

/**
 * 内置自然款上睫毛：每眼8根、每根3段弯曲细带，根部逐段贴合真实上眼睑。
 * 输入输出均为原始帧坐标，不依赖CameraX；旋转、镜像由同一纹理契约处理。
 * 写入调用者复用数组288..1439；无逐帧图片、集合、FBO或检测任务。
 * 闭眼仍保留向外伸出的睫毛，而非把素材整体压扁；缺点或退化的眼睛单独旁路。
 * 此实现没有手部/头发遮挡分割，也不是3D睫毛模型。
 */
internal fun eyelashGeometry(face: FaceObservation?,info: FrameInfo,out: FloatArray): Boolean {
    require(out.size==1440)
    out.fill(0f,288,1440)
    if(face==null || face.landmarks.size<478) return false
    val points=face.landmarks
    fun valid(i:Int)=points[i].let {it.x in 0f..1f && it.y in 0f..1f}
    var any=false
    for(eye in 0..1) {
        val ids=upperLids[eye]
        val brow=if(eye==0) 105 else 334
        val lower=if(eye==0) 145 else 374
        if(ids.any {!valid(it)} || !valid(brow) || !valid(lower)) continue
        val first=points[ids[0]];val last=points[ids[8]]
        val dx=(last.x-first.x)*info.width;val dy=(last.y-first.y)*info.height
        val span=hypot(dx,dy)
        if(span<8f) continue
        val ax=dx/span;val ay=dy/span
        val middle=points[ids[4]];val eyebrow=points[brow]
        // 用眉毛确定向眼外的法线；不假定图像向上就是脸部向上。
        val sign=if(-ay*(eyebrow.x-middle.x)*info.width+
            ax*(eyebrow.y-middle.y)*info.height>=0f) 1f else -1f
        val nx=-ay*sign;val ny=ax*sign
        // 开眼程度只看眼轴法向距离；水平漂移不能误判成睁眼。
        val gap=kotlin.math.abs((middle.x-points[lower].x)*info.width*nx+
            (middle.y-points[lower].y)*info.height*ny)
        val amount=(gap/span/.30f).coerceIn(0f,1f)
        val open=amount*amount*(3f-2f*amount)
        for(lash in 0..7) {
            val a=points[ids[lash]];val b=points[ids[lash+1]]
            val rootX=(a.x+b.x)*.5f*info.width
            val rootY=(a.y+b.y)*.5f*info.height
            val position=(lash+.5f)/8f
            val length=span*.20f*lashProfile[lash]*(.65f+.35f*open)
            // 混合局部眼睑法线与眼轴法线，睫毛沿弯曲眼睑展开而不是平行刷子。
            val tx=(b.x-a.x)*info.width;val ty=(b.y-a.y)*info.height
            val tangent=hypot(tx,ty)
            val direction=if(-ty*nx+tx*ny>=0f) 1f else -1f
            var normalX=nx;var normalY=ny
            if(tangent>.001f) {
                normalX=nx*.65f-ty/tangent*direction*.35f
                normalY=ny*.65f+tx/tangent*direction*.35f
                val magnitude=hypot(normalX,normalY)
                normalX/=magnitude;normalY/=magnitude
            }
            val sweep=(position-.5f)*span*.17f
            for(segment in 0..2) for(vertex in lashTriangles.indices) {
                val corner=lashTriangles[vertex]
                val t=(segment+if(corner>=2) 1f else 0f)/3f
                val side=if(corner%2==0) -1f else 1f
                // 低分辨率下亚像素细带无法覆盖采样中心；像素下限配合Shader羽化避免断根。
                val width=(span*.008f).coerceAtLeast(.65f)*(1f-t)+.15f
                val along=sweep*t*t+side*width
                val offset=288+((eye*8+lash)*18+segment*6+vertex)*4
                out[offset]=(rootX+normalX*length*t+ax*along)/info.width*2f-1f
                out[offset+1]=(rootY+normalY*length*t+ay*along)/info.height*2f-1f
                out[offset+2]=if(side<0f) 0f else 1f
                out[offset+3]=t
            }
        }
        any=true
    }
    return any
}

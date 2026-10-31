package com.beautysdk.gpupixel

import com.beautysdk.FaceObservation
import com.beautysdk.FrameInfo
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.min

// MediaPipe官方FACEMESH_LIPS：完整外环20点＋内环20点，同一方向排列。
private val lipPoints=intArrayOf(61,185,40,39,37,0,267,269,270,409,291,375,321,405,314,17,84,181,91,146,
    78,191,80,81,82,13,312,311,310,415,308,324,318,402,317,14,87,178,88,95)
private val quadTriangles=intArrayOf(0,1,2,2,1,3)

/**
 * 原始帧坐标下构建局部贴妆网格，返回位掩码：1唇部有效、2脸颊有效。
 * 复用288浮点数：0..23为唇部包围框6个xyuv顶点、24..103为40点原始归一化坐标，
 * 240..287仍为脸颊顶点；其余唇槽不绘制。完整40点驱动环带三角网格，不用模板唇形裁剪。
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
        var left=1f;var right=0f;var top=1f;var bottom=0f
        for(i in 0 until 20) {
            val p=points[lipPoints[i]]
            left=minOf(left,p.x);right=maxOf(right,p.x);top=minOf(top,p.y);bottom=maxOf(bottom,p.y)
        }
        for(i in quadTriangles.indices) {
            val corner=quadTriangles[i];val offset=i*4
            out[offset]=(if(corner%2==0)left else right)*2f-1f
            out[offset+1]=(if(corner<2)top else bottom)*2f-1f
            out[offset+2]=0f;out[offset+3]=0f
        }
        for(i in lipPoints.indices) {
            val p=points[lipPoints[i]]
            out[24+i*2]=p.x;out[25+i*2]=p.y
        }
        result=result or 1
    }
    if(regions and 2 == 0) return result
    if(!valid(33) || !valid(263) || !valid(152)) return result
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
    // 每侧独立失效，不能因为一侧出画而继续绘制旧顶点或关闭另一侧。
    out.fill(0f,240,288)
    var cheeks=false
    for(side in 0..1) {
        if(!valid(if(side==0)50 else 280))continue
        val center=points[if(side==0) 50 else 280]
        // 鼻侧/轮廓侧分别约束，不把近侧和远侧都当作对称矩形。
        val innerWidth=if(valid(1)) {
            val nose=points[1]
            val noseDistance=abs((center.x-nose.x)*info.width*ux+(center.y-nose.y)*info.height*uy)
            min(span*.32f,noseDistance*.88f)
        } else span*.32f
        val edgeIndex=if(side==0)234 else 454
        val outward=if(side==0)-1f else 1f
        val edgeDistance=if(valid(edgeIndex))points[edgeIndex].let {
            ((it.x-center.x)*info.width*ux+(it.y-center.y)*info.height*uy)*outward
        }else 0f
        val outerWidth=if(edgeDistance>0f)min(span*.42f,edgeDistance*.90f)else span*.34f
        val eye=if(side==0)left else right
        val eyeDistance=(center.x-eye.x)*info.width*vx+(center.y-eye.y)*info.height*vy
        val chinDistance=(chin.x-center.x)*info.width*vx+(chin.y-center.y)*info.height*vy
        // 横向铺开并向外上方抬起，避免鼻翼两侧各出现一个圆形集中斑点。
        val up=min(span*.12f,eyeDistance*.50f)
        val down=min(span*.15f,chinDistance*.28f)
        val lift=min(span*.10f,eyeDistance*.22f)
        if(innerWidth<1f||outerWidth<1f||up<1f||down<1f)continue
        cheeks=true
        for(i in quadTriangles.indices) {
            val corner=quadTriangles[i];val x=if(corner%2==0) -1f else 1f
            val y=if(corner<2) -1f else 1f
            val width=if(x*outward>0f)outerWidth else innerWidth
            val height=if(y<0f)up else down
            val vertical=y*height-if(x*outward>0f)lift else 0f
            val offset=240+(side*6+i)*4
            out[offset]=(center.x+(ux*x*width+vx*vertical)/info.width)*2f-1f
            out[offset+1]=(center.y+(uy*x*width+vy*vertical)/info.height)*2f-1f
            out[offset+2]=side*.5f+(x+1f)*.25f
            out[offset+3]=(y+1f)*.5f
        }
    }
    return if(cheeks)result or 2 else result
}

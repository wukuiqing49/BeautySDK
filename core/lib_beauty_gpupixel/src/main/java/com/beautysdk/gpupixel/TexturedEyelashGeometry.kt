package com.beautysdk.gpupixel

import com.beautysdk.*
import kotlin.math.abs
import kotlin.math.hypot

private val textureLids=arrayOf(intArrayOf(33,246,161,160,159,158,157,173,133),
    intArrayOf(263,466,388,387,386,385,384,398,362))
private val textureTriangles=intArrayOf(0,1,2,2,1,3)

/** V4图片睫毛：9列×4行网格。素材根部曲线映射眼睑，其上方图像随开合眼弯曲。 */
internal fun texturedEyelashGeometry(face: FaceObservation?,info: FrameInfo,pack: EyeMakeup,out: FloatArray): Boolean {
    require(out.size==1440);out.fill(0f,288,1440)
    if(face==null || face.landmarks.size<478) return false
    val points=face.landmarks
    fun valid(i:Int)=points[i].let {it.x in 0f..1f && it.y in 0f..1f}
    var any=false
    for(eye in 0..1) {
        val ids=textureLids[eye];val brow=if(eye==0) 105 else 334;val lower=if(eye==0) 145 else 374
        if(ids.any {!valid(it)} || !valid(brow) || !valid(lower)) continue
        val a=points[ids[0]];val b=points[ids[8]]
        val dx=(b.x-a.x)*info.width;val dy=(b.y-a.y)*info.height;val span=hypot(dx,dy)
        if(span<8f) continue
        val ax=dx/span;val ay=dy/span;val mid=points[ids[4]];val eyebrow=points[brow]
        val sign=if(-ay*(eyebrow.x-mid.x)*info.width+ax*(eyebrow.y-mid.y)*info.height>=0) 1f else -1f
        val nx=-ay*sign;val ny=ax*sign
        val gap=abs((mid.x-points[lower].x)*info.width*nx+(mid.y-points[lower].y)*info.height*ny)
        val amount=(gap/span/.3f).coerceIn(0f,1f);val open=amount*amount*(3-2*amount)
        val height=span*pack.length*(.65f+.35f*open)
        for(column in 0..7) for(row in 0..2) for(v in textureTriangles.indices) {
            val corner=textureTriangles[v];val col=column+corner%2
            val t=(row+if(corner>=2) 1f else 0f)/3f
            val root=points[ids[col]]
            val before=points[ids[(col-1).coerceAtLeast(0)]];val after=points[ids[(col+1).coerceAtMost(8)]]
            val tx=(after.x-before.x)*info.width;val ty=(after.y-before.y)*info.height
            val size=hypot(tx,ty);val direction=if(-ty*nx+tx*ny>=0f) 1f else -1f
            var px=nx;var py=ny
            if(size>.001f) {px=nx*.65f-ty/size*direction*.35f;py=ny*.65f+tx/size*direction*.35f
                val n=hypot(px,py);px/=n;py/=n}
            val uv=pack.root[if(eye==1 && !pack.mirrorRight) 8-col else col]
            val offset=288+((eye*8+column)*18+row*6+v)*4
            out[offset]=(root.x+px*height*t/info.width)*2-1
            out[offset+1]=(root.y+py*height*t/info.height)*2-1
            out[offset+2]=uv.x;out[offset+3]=uv.y*(1-t)
        }
        any=true
    }
    return any
}

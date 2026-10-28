package com.beautysdk.gpupixel

import com.beautysdk.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

/** 验证公共帧几何契约，不依赖相机或GL，也不保存人脸图像。 */
class EyelashGeometryTest {
    private fun points(): MutableList<FacePoint> {
        val p=MutableList(478){FacePoint(.5f,.5f)}
        val eyes=arrayOf(intArrayOf(33,246,161,160,159,158,157,173,133),
            intArrayOf(263,466,388,387,386,385,384,398,362))
        for(eye in 0..1) {
            for(i in 0..8) {
                val t=i/8f
                p[eyes[eye][i]]=FacePoint(if(eye==0) .18f+t*.22f else .82f-t*.22f,
                    .42f-sin(t*Math.PI).toFloat()*.035f)
            }
            p[if(eye==0) 105 else 334]=FacePoint(if(eye==0) .29f else .71f,.30f)
            p[if(eye==0) 145 else 374]=FacePoint(if(eye==0) .29f else .71f,.46f)
        }
        return p
    }
    private fun face(points:List<FacePoint>)=FaceObservation(FaceBounds(.05f,.05f,.95f,.95f),landmarks=points)

    @Test fun rawRotationAndMirrorPreserveGeometryOnNonSquareFrames() {
        val p=points();val base=FloatArray(1440)
        assertTrue(eyelashGeometry(face(p),FrameInfo(640,480,0),base))
        for(mirror in listOf(false,true)) {
            val mapped=p.map {if(mirror) FacePoint(1f-it.x,it.y) else FacePoint(1f-it.y,it.x)}
            val out=FloatArray(1440)
            assertTrue(eyelashGeometry(face(mapped),if(mirror) FrameInfo(640,480,0) else FrameInfo(480,640,0),out))
            for(i in 288 until 1440 step 4) {
                assertEquals(if(mirror) -base[i] else -base[i+1],out[i],.00001f)
                assertEquals(if(mirror) base[i+1] else base[i],out[i+1],.00001f)
                assertEquals(base[i+2],out[i+2],0f)
            }
        }
    }

    @Test fun horizontalLowerLidDriftCannotReopenClosedEye() {
        val p=points();p[145]=p[159];p[374]=p[386]
        val base=FloatArray(1440);val shifted=FloatArray(1440)
        eyelashGeometry(face(p),FrameInfo(640,480,0),base)
        p[145]=p[145].copy(x=p[145].x+.04f);p[374]=p[374].copy(x=p[374].x-.04f)
        eyelashGeometry(face(p),FrameInfo(640,480,0),shifted)
        assertArrayEquals(base,shifted,.00001f)
    }

    @Test fun missingEyeClearsOnlyItsReusedVerticesAndNullClearsAllLashes() {
        val p=points();val out=FloatArray(1440){42f}
        p[33]=FacePoint(-1f,-1f)
        assertTrue(eyelashGeometry(face(p),FrameInfo(640,480,0),out))
        assertTrue((288 until 864).all {out[it]==0f})
        assertTrue((864 until 1440).any {out[it]!=0f})
        assertEquals(42f,out[0],0f)
        assertFalse(eyelashGeometry(null,FrameInfo(640,480,0),out))
        assertTrue((288 until 1440).all {out[it]==0f})
    }
}

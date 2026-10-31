package com.beautysdk.gpupixel

import com.beautysdk.*
import org.junit.Assert.*
import org.junit.Test

class BlusherGeometryTest {
    private fun points()=MutableList(478){FacePoint(-2f,-2f)}.apply {
        this[33]=FacePoint(.3f,.3f);this[263]=FacePoint(.7f,.3f)
        this[50]=FacePoint(.3f,.46f);this[280]=FacePoint(.7f,.46f)
        this[1]=FacePoint(.5f,.43f);this[152]=FacePoint(.5f,.82f)
        this[234]=FacePoint(.14f,.5f);this[454]=FacePoint(.86f,.5f)
    }
    private fun face(p:List<FacePoint>)=FaceObservation(FaceBounds(.05f,.1f,.95f,.9f),landmarks=p)

    @Test fun cheeksStayBetweenEyesNoseAndOuterFaceWithoutTouchingOtherMeshes() {
        val out=FloatArray(1440){37f}
        assertEquals(2,makeupGeometry(face(points()),FrameInfo(400,600,0),out,2))
        for(side in 0..1)for(i in 240+side*24 until 264+side*24 step 4){
            val x=(out[i]+1f)/2;val y=(out[i+1]+1f)/2
            assertTrue(if(side==0)x in .14f.. .5f else x in .5f.. .86f)
            assertTrue(y in .3f.. .82f)
        }
        for(i in 0 until 240)assertEquals(37f,out[i],0f)
        for(i in 288 until 1440)assertEquals(37f,out[i],0f)
    }

    @Test fun oneMissingCheekClearsItsOldVerticesAndKeepsTheOther() {
        val p=points();val out=FloatArray(1440)
        assertEquals(2,makeupGeometry(face(p),FrameInfo(400,600,0),out,2))
        val right=out.copyOfRange(264,288)
        p[50]=FacePoint(-.1f,.46f)
        assertEquals(2,makeupGeometry(face(p),FrameInfo(400,600,1),out,2))
        assertArrayEquals(FloatArray(24),out.copyOfRange(240,264),0f)
        assertArrayEquals(right,out.copyOfRange(264,288),0f)
        p[280]=FacePoint(1.1f,.46f)
        assertEquals(0,makeupGeometry(face(p),FrameInfo(400,600,2),out,2))
        assertArrayEquals(FloatArray(48),out.copyOfRange(240,288),0f)
    }

    @Test fun rotatedAndMirroredNonSquareFramesKeepTheSameCoverage() {
        val p=points();val base=FloatArray(1440)
        assertEquals(2,makeupGeometry(face(p),FrameInfo(400,600,0),base,2))
        for(mirror in listOf(false,true)){
            val mapped=p.map{if(mirror)FacePoint(1f-it.x,it.y)else FacePoint(1f-it.y,it.x)}
            val out=FloatArray(1440)
            assertEquals(2,makeupGeometry(face(mapped),if(mirror)FrameInfo(400,600,0)else FrameInfo(600,400,0),out,2))
            for(i in 240 until 288 step 4){
                assertEquals(if(mirror)-base[i]else -base[i+1],out[i],.00001f)
                assertEquals(if(mirror)base[i+1]else base[i],out[i+1],.00001f)
                assertEquals(base[i+2],out[i+2],0f);assertEquals(base[i+3],out[i+3],0f)
            }
        }
    }
}

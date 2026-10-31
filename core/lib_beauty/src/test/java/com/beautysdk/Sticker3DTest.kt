package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class Sticker3DTest {
    @Test fun cachedPoseExpiresAtRealSampleTimeAndInvalidatesForNewGeometryAndSettings() {
        val cache=Sticker3DPoseCache(150)
        val source=face(); val info=FrameInfo(640,480,100)
        val cap=BuiltInStickers3D.baseballCap()
        val first=cache.get(source,info,cap)!!
        assertSame(first,cache.get(source,info.copy(timestampNs=250),cap.copy()))
        assertNull(cache.get(source,info.copy(timestampNs=251),cap))
        assertNull(cache.get(null,info,cap))
        assertNull(cache.get(source,info.copy(timestampNs=99),cap))
        val restored=cache.get(source,info,cap)!!
        assertNotSame(first,restored)
        val moved=cache.get(source,info,cap.copy(offsetY=.2f))!!
        assertFalse(restored.matrix.contentEquals(moved.matrix))
        val turned=cache.get(face(.6f),info,cap)!!
        assertFalse(restored.matrix.contentEquals(turned.matrix))
        val resized=cache.get(source,info.copy(width=1280),cap)!!
        assertNotSame(restored,resized)
        assertArrayEquals(Sticker3DGeometry.pose(source,info.copy(width=1280),cap,150)!!.matrix,resized.matrix,0f)
        cache.clear()
        assertNotSame(resized,cache.get(source,info.copy(width=1280),cap))
    }
    private fun face(yaw:Float=0f,time:Long=100):FaceObservation {
        val points=MutableList(478){FacePoint3D(.5f,.5f,0f)}
        fun set(i:Int,x:Float,y:Float,z:Float=0f){points[i]=FacePoint3D(.5f+x*cos(yaw)+z*sin(yaw),.5f+y,-x*sin(yaw)+z*cos(yaw))}
        set(33,-.2f,0f);set(263,.2f,0f);set(10,0f,-.3f);set(152,0f,.3f)
        return FaceObservation(FaceBounds(.1f,.1f,.9f,.9f),geometry3D=FaceGeometry3D(points,time))
    }
    private fun rejected(block:()->Unit){try{block();fail("Expected invalid input rejection")}catch(_:IllegalArgumentException){}}

    @Test fun meshCopiesBuffersAndRejectsInvalidData() {
        val positions=floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f)
        val colors=FloatArray(12){1f};val indices=intArrayOf(0,1,2)
        val mesh=StickerMesh3D(positions,colors,indices)
        positions[0]=3f;colors[0]=0f;indices[0]=2
        assertEquals(0f,mesh.copyPositions()[0],0f);assertEquals(1f,mesh.copyColors()[0],0f)
        assertEquals(0,mesh.copyIndices()[0]);mesh.copyPositions()[0]=2f
        assertEquals(0f,mesh.copyPositions()[0],0f)
        rejected{StickerMesh3D(positions,colors,intArrayOf(0,1,3))}
        rejected{StickerMesh3D(positions.copyOf().apply{this[0]=Float.NaN},colors,indices)}
        rejected{StickerMesh3D(positions,colors.copyOf().apply{this[3]=.5f},indices)}
        rejected{StickerMesh3D(FloatArray(32769*3),FloatArray(32769*4){1f},intArrayOf(0,1,2))}
        rejected{Sticker3D(mesh,scale=Float.NaN)}
    }
    @Test fun geometryIsImmutableAndKeepsRealSamplingTime() {
        val points=MutableList(478){FacePoint3D(.5f,.5f,.1f)}
        val matrix=FloatArray(16){it.toFloat()};val geometry=FaceGeometry3D(points,50,matrix)
        points[0]=FacePoint3D(0f,0f,0f);matrix[0]=20f
        assertEquals(.5f,geometry.points[0].x,0f);assertEquals(0f,geometry.copyCanonicalTransform()!![0],0f)
        geometry.copyCanonicalTransform()!![0]=10f
        assertEquals(0f,geometry.copyCanonicalTransform()!![0],0f)
        rejected{FaceGeometry3D(points,-1)}
        rejected{FaceGeometry3D(points,0,FloatArray(15))}
    }
    @Test fun rotationsAndMirrorsMapXYAndDepthInPixelUnits() {
        val source=face()
        for(rotation in listOf(0,90,180,270))for(mirror in listOf(false,true)) {
            val info=FrameInfo(640,480,100,rotation,mirror)
            val mapped=AnalysisCoordinates(info).toRaw(source)
            val input=source.geometry3D!!.points[33];val actual=mapped.geometry3D!!.points[33]
            val expected=AnalysisCoordinates(info).toRaw(FacePoint(input.x,input.y))
            assertEquals(expected.x,actual.x,1e-6f);assertEquals(expected.y,actual.y,1e-6f)
            val tilted=AnalysisCoordinates(info).toRaw(face(.5f))
            val z=face(.5f).geometry3D!!.points[33].z
            assertEquals(z*(if(rotation%180==0)640f else 480f)/640,tilted.geometry3D!!.points[33].z,1e-6f)
            assertEquals(100L,mapped.geometry3D!!.timestampNs)
            assertNotNull(Sticker3DGeometry.pose(mapped,info,BuiltInStickers3D.glasses(),150))
        }
    }
    @Test fun crossResolutionAndCropPreserveDepthButStretchDrops3D() {
        val source=face(.4f)
        val sourceInfo=FrameGeometry(640,480)
        val uniform=AnalysisFrameMapping(sourceInfo,FrameGeometry(1280,960)).map(source)!!
        assertEquals(source.geometry3D!!.points[33].z,uniform.geometry3D!!.points[33].z,1e-6f)
        val cropped=AnalysisFrameMapping(sourceInfo,FrameGeometry(640,480),FrameRegion(.25f,.25f,.75f,.75f)).map(source)!!
        assertEquals(source.geometry3D!!.points[33].z*2,cropped.geometry3D!!.points[33].z,1e-6f)
        val stretched=AnalysisFrameMapping(sourceInfo,FrameGeometry(1280,480)).map(source)!!
        assertNull(stretched.geometry3D);assertNotNull(stretched.bounds)
    }
    @Test fun yawChangesProjectedWidthAndDepthWithoutFlatteningTheModel() {
        val info=FrameInfo(640,480,100)
        val front=Sticker3DGeometry.pose(face(),info,BuiltInStickers3D.glasses(),150)!!
        val turned=Sticker3DGeometry.pose(face(.7f),info,BuiltInStickers3D.glasses(),150)!!
        assertEquals(front.span,turned.span,.001f)
        assertTrue(abs(turned.matrix[0])<abs(front.matrix[0]))
        assertTrue(abs(turned.matrix[2])>.001f)
        assertTrue(abs(turned.matrix[8])>.01f) // 模型自身深度也贡献屏幕横向位移。
    }
    @Test fun absentStaleFutureAndDegeneratePoseHideImmediately() {
        val info=FrameInfo(640,480,300);val sticker=BuiltInStickers3D.glasses()
        assertNull(Sticker3DGeometry.pose(null,info,sticker,150))
        assertNull(Sticker3DGeometry.pose(face(time=100),info,sticker,150))
        assertNull(Sticker3DGeometry.pose(face(time=301),info,sticker,150))
        assertNotNull(Sticker3DGeometry.pose(face(time=150),info,sticker,150))
        val degenerate=FaceObservation(FaceBounds(.1f,.1f,.9f,.9f),geometry3D=FaceGeometry3D(List(478){FacePoint3D(.5f,.5f,0f)},300))
        assertNull(Sticker3DGeometry.pose(degenerate,info,sticker,150))
    }
    @Test fun builtInsHaveTrueDepthAndDemandFaceAnalysis() {
        for(sticker in listOf(BuiltInStickers3D.glasses(),BuiltInStickers3D.topHat(),BuiltInStickers3D.crown(),BuiltInStickers3D.pearlCatEars(),BuiltInStickers3D.headphones(),BuiltInStickers3D.baseballCap())) {
            assertTrue(sticker.mesh.triangleCount>50)
            val z=sticker.mesh.copyPositions().filterIndexed{index,_->index%3==2}
            assertTrue(z.max()-z.min()>.2f)
            val settings=BeautySettings(sticker3D=sticker)
            assertFalse(settings.isIdentity);assertTrue(settings.requiresFaceAnalysis)
            assertTrue(settings.copy(sticker3D=null).isIdentity)
        }
    }
    @Test fun capFitsForeheadAndHeadDepthIgnoresAccessoryPlacement() {
        val original=face();val info=FrameInfo(640,480,100)
        val cap=BuiltInStickers3D.baseballCap()
        assertTrue(cap.mesh.usesHeadVolume);assertFalse(BuiltInStickers3D.glasses().mesh.usesHeadVolume)
        val base=Sticker3DGeometry.pose(original,info,cap,150)!!
        val moved=Sticker3DGeometry.pose(original,info,cap.copy(scale=1.2f,offsetY=.2f,offsetZ=.3f),150)!!
        assertArrayEquals(base.headMatrix!!,moved.headMatrix!!,0f)
        assertFalse(base.matrix.contentEquals(moved.matrix))
        val points=original.geometry3D!!.points.toMutableList()
        points[10]=FacePoint3D(.5f,.1f,0f)
        points[234]=FacePoint3D(.1f,.5f,0f);points[454]=FacePoint3D(.9f,.5f,0f)
        val bigger=FaceObservation(original.bounds,geometry3D=FaceGeometry3D(points,100))
        val fitted=Sticker3DGeometry.pose(bigger,info,cap,150)!!
        assertTrue(fitted.matrix[0]>base.matrix[0])
        assertTrue(fitted.matrix[13]<base.matrix[13])
        assertTrue(fitted.headMatrix!!.all{it.isFinite()})
        assertNull(Sticker3DGeometry.pose(original,info.copy(timestampNs=251),cap,150))
    }
}

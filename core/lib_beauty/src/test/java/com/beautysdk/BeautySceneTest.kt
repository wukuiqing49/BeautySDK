package com.beautysdk
import org.junit.Assert.*
import org.junit.Test

class BeautySceneTest {
    @Test fun alignedMaskKeepsModelExpiryAndRejectsFramesBeforeAlignment() {
        val info=FrameInfo(192,240,100_000_000)
        val mask=ForegroundMask(info,2,2,java.nio.ByteBuffer.wrap(ByteArray(4)),160_000_000)
        assertFalse(mask.isUsable(info.copy(timestampNs=150_000_000)))
        assertTrue(mask.isUsable(info.copy(timestampNs=160_000_000)))
        assertFalse(mask.isUsable(info.copy(timestampNs=350_000_001)))
        val scaled=mask.forScaledOutput(info.copy(width=768,height=960,timestampNs=200_000_000))!!
        assertEquals(info.timestampNs,scaled.info.timestampNs)
        assertEquals(160_000_000,scaled.alignmentTimestampNs)
        val raw=scaled.withoutMotionAlignment()
        assertEquals(info.timestampNs,raw.alignmentTimestampNs)
        assertTrue(raw.isUsable(info.copy(width=768,height=960,timestampNs=150_000_000)))
        assertNull(mask.forScaledOutput(info.copy(width=768,height=960,timestampNs=150_000_000)))
        assertNull(mask.forScaledOutput(info.copy(width=768,height=960,timestampNs=350_000_001)))
    }
    @Test fun bufferMaskCopiesOnlyRemainingBytesAndDoesNotRetainBorrowedStorage() {
        for(direct in listOf(false,true)) {
            val buffer=if(direct)java.nio.ByteBuffer.allocateDirect(8)else java.nio.ByteBuffer.allocate(8)
            buffer.put(byteArrayOf(9,8,0,64,127,-1,7,6));buffer.position(2);buffer.limit(6)
            val mask=ForegroundMask(FrameInfo(2,2,0),2,2,buffer.asReadOnlyBuffer())
            assertEquals(2,buffer.position());assertEquals(6,buffer.limit())
            buffer.put(2,55)
            val result=ByteArray(4);mask.pixels().get(result)
            assertArrayEquals(byteArrayOf(0,64,127,-1),result)
            assertTrue(mask.pixels().isReadOnly)
            try { ForegroundMask(FrameInfo(2,2,0),2,1,buffer);fail("reject wrong remaining size") }
            catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun explicitlyScaledMaskKeepsRealSampleAgeAndRejectsOtherGeometry() {
        for(rotation in listOf(0,90,180,270))for(mirror in listOf(false,true)) {
            val sample=FrameInfo(320,240,100_000_000,rotation,mirror)
            val mask=ForegroundMask(sample,2,2,byteArrayOf(0,64,127,-1))
            val output=FrameInfo(1280,960,200_000_000,rotation,mirror)
            assertFalse(mask.isUsable(output))
            val mapped=requireNotNull(mask.forScaledOutput(output))
            assertEquals(sample.timestampNs,mapped.info.timestampNs)
            assertTrue(mapped.isUsable(output));assertEquals(64,mapped.pixels().get(1).toInt())
            assertNull(mask.forScaledOutput(output.copy(timestampNs=350_000_001)))
            assertNull(mask.forScaledOutput(output.copy(timestampNs=99_000_000)))
            assertNull(mask.forScaledOutput(output.copy(width=1920,height=1080)))
            assertNull(mask.forScaledOutput(output.copy(mirrored=!mirror)))
            assertNull(mask.forScaledOutput(output.copy(rotationDegrees=(rotation+90)%360)))
        }
    }
    @Test fun maskRejectsFutureStaleAndGeometryAndDefendsPixels() {
        val info=FrameInfo(640,480,100_000_000,90,true)
        val bytes=byteArrayOf(1,2,3,4)
        val mask=ForegroundMask(info,2,2,bytes);bytes[0]=0
        assertEquals(1,mask.pixels().get(0).toInt());assertTrue(mask.pixels().isReadOnly)
        assertTrue(mask.isUsable(info.copy(timestampNs=350_000_000)))
        assertFalse(mask.isUsable(info.copy(timestampNs=350_000_001)))
        assertFalse(mask.isUsable(info.copy(timestampNs=99_999_999)))
        assertFalse(mask.isUsable(info.copy(rotationDegrees=0)))
        assertFalse(mask.isUsable(info.copy(mirrored=false)))
        assertFalse(mask.isUsable(info.copy(width=320)))
    }
    @Test fun motionsStayInsideImageAcrossDirectionsAndReset() {
        for(rotation in listOf(0,90,180,270)) for(mirror in listOf(false,true))
            for(mode in CameraMotion.entries) {
                val c=CameraMotionController();val scene=BeautyScene(motion=mode,zoom=2f,timeline=if(mode==CameraMotion.KEYFRAMES) MotionTimeline(listOf(MotionKeyframe(0,.5f,.5f,1f),MotionKeyframe(4000,.5f,.5f,1f))) else null)
                val face=FaceObservation(FaceBounds(.8f,.05f,1f,.2f))
                for(ms in 0..8000 step 100) {
                    val crop=c.crop(FrameInfo(640,360,ms*1_000_000L,rotation,mirror),scene,face)
                    assertTrue(crop[0]>=0 && crop[1]>=0)
                    assertTrue(crop[0]+crop[2]<=1.00001f && crop[1]+crop[3]<=1.00001f)
                    assertEquals(crop[2],crop[3],0f)
                }
                c.reset()
                assertArrayEquals(floatArrayOf(.25f,.25f,.5f,.5f),c.crop(FrameInfo(640,360,0,rotation,mirror),
                    BeautyScene(motion=CameraMotion.FACE_FOLLOW,zoom=2f),face),0f)
            }
    }
    @Test fun pushLoopAndSettingsDemandAndValidation() {
        val c=CameraMotionController();val s=BeautyScene(motion=CameraMotion.PUSH_IN,zoom=2f)
        assertEquals(1f,c.crop(FrameInfo(8,8,0),s,null)[2],0f)
        assertEquals(.5f,c.crop(FrameInfo(8,8,2_000_000_000),s,null)[2],.00001f)
        assertEquals(1f,c.crop(FrameInfo(8,8,4_000_000_000),s,null)[2],.00001f)
        assertFalse(BeautySettings(scene=BeautyScene(background=BackgroundMode.BLUR)).requiresFaceAnalysis)
        assertTrue(BeautySettings(scene=BeautyScene(motion=CameraMotion.FACE_FOLLOW)).requiresFaceAnalysis)
        assertFalse(BeautySettings(scene=s).isIdentity)
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,.9f,2.1f)) {
            try { BeautyScene(zoom=value);fail("invalid zoom") } catch(_:IllegalArgumentException) { }
        }
        try { BeautyScene(background=BackgroundMode.IMAGE);fail("missing image") } catch(_:IllegalArgumentException) { }
    }
}

package com.beautysdk
import org.junit.Test
import org.junit.Assert.*
class MotionTimelineTest {
    private fun timeline(loop:Boolean=true)=MotionTimeline(listOf(MotionKeyframe(0,.5f,.5f,1f),MotionKeyframe(2000,.6f,.4f,2f,MotionEasing.LINEAR),MotionKeyframe(4000,.5f,.5f,1f)),loop)
    @Test fun interpolationLoopAndHold() {
        assertEquals(1.5f,timeline().sample(1000).zoom,.00001f)
        assertEquals(1f,timeline().sample(4000).zoom,0f)
        assertEquals(1f,timeline(false).sample(5000).zoom,0f)
        assertEquals(1f,timeline().sample(-1).zoom,0f)
    }
    @Test fun timelineRejectsUnclosedLoopAndDuplicateTime() {
        for(frames in listOf(listOf(MotionKeyframe(0,.5f,.5f,1f),MotionKeyframe(0,.5f,.5f,1f)),listOf(MotionKeyframe(0,.5f,.5f,1f),MotionKeyframe(1000,.6f,.5f,1f)))) {
            try { MotionTimeline(frames);fail("invalid timeline") } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun changingTimelineResetsClockWithoutResettingEveryFrame() {
        val c=CameraMotionController();val scene=BeautyScene(motion=CameraMotion.KEYFRAMES,timeline=timeline())
        c.crop(FrameInfo(640,480,0),scene,null)
        assertEquals(.5f,c.crop(FrameInfo(640,480,2_000_000_000),scene,null)[2],.00001f)
        assertEquals(1f,c.crop(FrameInfo(640,480,2_100_000_000),scene.copy(timeline=timeline()),null)[2],.00001f)
    }
}

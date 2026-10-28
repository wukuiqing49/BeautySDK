package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class StickerPoseSmootherTest {
    private fun pose(x: Float, span: Float=100f, angle: Float=0f)=
        StickerPlacement(StickerPose(FacePoint(x,.5f),span,angle),null)
    private fun info(t: Long, size: Int=1000)=FrameInfo(size,size,t)

    @Test fun smallMotionRemainsVisibleAndFastMotionFollowsImmediately() {
        val filter=StickerPoseSmoother()
        filter.update(pose(.5f),info(1))
        val small=filter.update(pose(.502f,101f,.02f),info(33_000_001)).glasses!!
        assertTrue(small.center.x in .50001f.. .50199f)
        assertTrue(small.spanPixels in 100.01f..100.99f)
        assertTrue(small.angleRadians in .0001f.. .01999f)
        val fast=pose(.6f,120f,.3f)
        assertEquals(fast,filter.update(fast,info(66_000_001)))
    }

    @Test fun scaleRelativeMotionWorksAcrossResolutions() {
        fun sample(size: Int):Float {
            val filter=StickerPoseSmoother()
            filter.update(pose(.5f,size*.1f),info(1,size))
            return filter.update(pose(.502f,size*.1f),info(33_000_001,size)).glasses!!.center.x
        }
        assertEquals(sample(640),sample(1920),.000001f)
    }

    @Test fun nullGeometryChangesTimeReversalAndClearDoNotKeepOldPose() {
        val filter=StickerPoseSmoother()
        filter.update(pose(.5f),info(1))
        assertNull(filter.update(StickerPlacement(null,null),info(33_000_001)).glasses)
        assertEquals(pose(.502f),filter.update(pose(.502f),info(66_000_001)))
        assertEquals(pose(.504f),filter.update(pose(.504f),info(99_000_001,640)))
        assertEquals(pose(.506f),filter.update(pose(.506f),info(1,640)))
        filter.clear()
        assertEquals(pose(.508f),filter.update(pose(.508f),info(33_000_001,640)))
    }

    @Test fun angleWrapUsesShortPathAndDoesNotHide() {
        val filter=StickerPoseSmoother()
        filter.update(pose(.5f,angle=PI.toFloat()-.01f),info(1))
        val actual=filter.update(pose(.5f,angle= -PI.toFloat()+.01f),info(33_000_001)).glasses!!
        assertTrue(actual.angleRadians>3.12f)
        assertTrue(actual.angleRadians<3.16f)
    }

    @Test fun equalElapsedTimeHasEqualResponseAtDifferentRenderRates() {
        fun sample(step:Long):Float {
            val filter=StickerPoseSmoother()
            filter.update(pose(.5f),info(0))
            var result=.5f
            var t=step
            while(t<=60_000_000L) {
                result=filter.update(pose(.502f),info(t)).glasses!!.center.x
                t+=step
            }
            return result
        }
        assertEquals(sample(10_000_000),sample(30_000_000),.000001f)
    }
}

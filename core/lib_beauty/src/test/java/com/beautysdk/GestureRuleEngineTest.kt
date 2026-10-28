package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GestureRuleEngineTest {
    private fun info(ms: Long,rotation: Int=0,mirror: Boolean=false)=FrameInfo(1000,1000,ms*1_000_000,rotation,mirror)
    private fun hand(kind: GestureKind?=GestureKind.OPEN_PALM,score: Float=.95f,
                     transform: (FacePoint)->FacePoint={it},pinch: Boolean=false,
                     side: Handedness=Handedness.LEFT): HandObservation {
        val p=MutableList(21){FacePoint(.5f,.65f)}
        p[0]=FacePoint(.5f,.8f)
        p[1]=FacePoint(.4f,.7f);p[2]=FacePoint(.33f,.62f);p[3]=FacePoint(.26f,.54f);p[4]=FacePoint(.19f,.46f)
        for((finger,x) in listOf(.36f,.46f,.56f,.66f).withIndex()) {
            val start=5+finger*4
            for(j in 0..3)p[start+j]=FacePoint(x,.6f-j*.12f)
        }
        if(pinch){p[4]=FacePoint(.355f,.25f);p[8]=FacePoint(.365f,.25f)}
        return HandObservation(p.map(transform),side,.95f,kind,score)
    }
    private fun engine(experimental: Boolean=true)=GestureRuleEngine(GestureConfig(experimentalRules=experimental))
    private fun triggers(e: List<GestureEvent>,kind: GestureKind)=e.filter {it.kind==kind && it.phase==GestureEventPhase.TRIGGER}

    @Test fun allSevenModelGesturesNeedStableSamplesAndTriggerOnlyOnceWhileHeld() {
        for(kind in HandObservation.MODEL_GESTURES) {
            val engine=engine(false)
            assertTrue(engine.process(info(0),listOf(hand(kind))).isEmpty())
            assertTrue(engine.process(info(100),listOf(hand(kind))).isEmpty())
            val event=engine.process(info(200),listOf(hand(kind))).single()
            assertEquals(kind,event.kind);assertEquals(GestureSource.MODEL,event.source);assertEquals(.95f,event.score)
            for(t in 300L..2000L step 100)assertTrue(engine.process(info(t),listOf(hand(kind))).isEmpty())
        }
    }
    @Test fun lowScoreUnknownAndAbsentHandsDoNotTriggerModelEvents() {
        val engine=engine(false)
        for(t in 0L..500L step 100)assertTrue(engine.process(info(t),listOf(hand(score=.2f))).isEmpty())
        assertTrue(engine.process(info(600),listOf(hand(null))).isEmpty())
        assertTrue(engine.process(info(700),emptyList()).isEmpty())
    }
    @Test fun releaseThenReturnRearmsButSingleFrameDropoutDoesNotRepeat() {
        val engine=engine(false)
        for(t in listOf(0L,100,200))engine.process(info(t),listOf(hand()))
        engine.process(info(300),listOf(hand(null)))
        assertTrue(engine.process(info(400),listOf(hand())).isEmpty())
        engine.process(info(500),listOf(hand(null)))
        engine.process(info(700),listOf(hand(null)))
        engine.process(info(800),listOf(hand()))
        engine.process(info(900),listOf(hand()))
        assertEquals(1,engine.process(info(1000),listOf(hand())).size)
    }
    @Test fun resetGeometryGapAndDuplicateFramesCannotFinishOldStablePose() {
        val engine=engine(false)
        engine.process(info(0),listOf(hand()))
        assertTrue(engine.process(info(0),listOf(hand())).isEmpty())
        assertTrue(engine.process(info(400),listOf(hand())).isEmpty())
        assertTrue(engine.process(info(500,90),listOf(hand())).isEmpty())
        engine.reset()
        assertTrue(engine.process(info(0),listOf(hand())).isEmpty())
    }
    @Test fun detectionOrderSwapKeepsHandTrackIdentity() {
        val engine=engine(false)
        val left=hand(transform={FacePoint(it.x*.45f,it.y*.45f)},side=Handedness.LEFT)
        val right=hand(transform={FacePoint(.5f+it.x*.45f,it.y*.45f)},side=Handedness.RIGHT)
        engine.process(info(0),listOf(left,right));engine.process(info(100),listOf(right,left))
        val events=engine.process(info(200),listOf(right,left))
        assertEquals(2,events.size);assertEquals(listOf(2L,1L),events.map {it.trackIds.single()})
    }
    @Test fun okAndFingerCountsAreRuleEventsWithNoInventedProbability() {
        val engine=engine()
        for(t in listOf(0L,100))engine.process(info(t),listOf(hand(pinch=true)))
        val events=engine.process(info(200),listOf(hand(pinch=true)))
        val ok=triggers(events,GestureKind.OK).single()
        assertEquals(GestureSource.EXPERIMENTAL_RULE,ok.source);assertNull(ok.score)
        assertTrue(events.any {it.kind in GestureKind.NUMBER_0..GestureKind.NUMBER_5})
    }
    @Test fun pinchHasStartUpdateDeltaEndAndNoDuplicateStart() {
        val engine=engine()
        engine.process(info(0),listOf(hand(pinch=true)));engine.process(info(100),listOf(hand(pinch=true)))
        assertEquals(GestureEventPhase.START,engine.process(info(200),listOf(hand(pinch=true))).single {it.kind==GestureKind.PINCH}.phase)
        val update=engine.process(info(300),listOf(hand(pinch=true,transform={FacePoint(it.x+.02f,it.y)}))).single {it.kind==GestureKind.PINCH}
        assertEquals(GestureEventPhase.UPDATE,update.phase);assertEquals(.02f,update.delta.x,.0001f)
        val end=engine.process(info(400),listOf(hand())).single {it.kind==GestureKind.PINCH}
        assertEquals(GestureEventPhase.END,end.phase)
        assertFalse(engine.process(info(500),listOf(hand())).any {it.kind==GestureKind.PINCH})
    }
    @Test fun lostHandCancelsPinchAndNewHandMustRestabilize() {
        val engine=engine()
        for(t in listOf(0L,100,200))engine.process(info(t),listOf(hand(pinch=true)))
        assertEquals(GestureEventPhase.CANCEL,engine.process(info(300),emptyList()).single().phase)
        assertFalse(engine.process(info(400),listOf(hand(pinch=true))).any {it.kind==GestureKind.PINCH})
    }
    @Test fun briefHandLossCannotBypassTriggerCooldown() {
        val engine=engine(false)
        engine.process(info(0),listOf(hand()));engine.process(info(100),listOf(hand()))
        assertEquals(1,engine.process(info(200),listOf(hand())).size)
        engine.process(info(300),emptyList())
        engine.process(info(400),listOf(hand()));engine.process(info(500),listOf(hand()))
        assertTrue(engine.process(info(600),listOf(hand())).isEmpty())
    }
    @Test fun briefHandLossKeepsHeldPoseLatchedEvenAfterCooldownExpires() {
        val engine=engine(false)
        for(t in listOf(0L,100,200))engine.process(info(t),listOf(hand()))
        engine.process(info(300),emptyList())
        for(t in 400L..2000L step 100)
            assertTrue("held pose at $t",engine.process(info(t),listOf(hand())).isEmpty())
        engine.process(info(2100),listOf(hand(null)))
        engine.process(info(2300),listOf(hand(null)))
        engine.process(info(2400),listOf(hand()))
        assertEquals(1,engine.process(info(2600),listOf(hand())).size)
    }
    @Test fun isolatedLateNegativeSampleDoesNotRearmHeldPose() {
        val engine=engine(false)
        for(t in 0L..1200L step 100)engine.process(info(t),listOf(hand()))
        engine.process(info(1400),listOf(hand(null)))
        for(t in 1500L..2500L step 100)assertTrue(engine.process(info(t),listOf(hand())).isEmpty())
    }
    @Test fun releaseDurationIsRecognizedWhenNextSampleIsAlreadyTheReturningPose() {
        val engine=engine(false)
        for(t in 0L..1200L step 100)engine.process(info(t),listOf(hand()))
        engine.process(info(1300),listOf(hand(null)))
        assertTrue(engine.process(info(1500),listOf(hand())).isEmpty())
        assertEquals(1,engine.process(info(1700),listOf(hand())).size)
    }
    @Test fun cooldownRejectionDoesNotPermanentlyConsumeNewCandidate() {
        val engine=engine(false)
        for(t in listOf(0L,100,200))engine.process(info(t),listOf(hand()))
        // 超过跟踪丢失预算，重新进入时仍需遵守跨跟踪冷却。
        for(t in listOf(300L,500,600))engine.process(info(t),emptyList())
        for(t in listOf(700L,800,900))assertTrue(engine.process(info(t),listOf(hand())).isEmpty())
        assertEquals(1,engine.process(info(1000),listOf(hand())).size)
        for(t in 1100L..2000L step 100)assertTrue(engine.process(info(t),listOf(hand())).isEmpty())
    }
    @Test fun briefPairLossDoesNotRepeatPrayerAfterCooldown() {
        val engine=engine()
        val pair=listOf(hand(null),hand(null,side=Handedness.RIGHT,transform={FacePoint(it.x+.05f,it.y)}))
        for(t in 0L..1200L step 100)engine.process(info(t),pair)
        engine.process(info(1300),listOf(pair[0]))
        for(t in 1400L..2400L step 100)
            assertTrue(triggers(engine.process(info(t),pair),GestureKind.PRAYER).isEmpty())
    }
    @Test fun gapCancelsPinchBeforeRestartingPose() {
        val engine=engine()
        for(t in listOf(0L,100,200))engine.process(info(t),listOf(hand(pinch=true)))
        val events=engine.process(info(700),listOf(hand(pinch=true)))
        assertEquals(GestureEventPhase.CANCEL,events.single().phase)
    }
    @Test fun pinchAndOkAreInvariantUnderAllRotationsAndMirrors() {
        for(rotation in listOf(0,90,180,270))for(mirror in listOf(false,true)) {
            val engine=engine()
            val frame=info(0,rotation,mirror)
            val coords=AnalysisCoordinates(frame)
            val input=hand(pinch=true,transform=coords::toRaw)
            engine.process(frame,listOf(input));engine.process(info(100,rotation,mirror),listOf(input))
            val events=engine.process(info(200,rotation,mirror),listOf(input))
            assertEquals(1,triggers(events,GestureKind.OK).size)
            val pinch=events.single {it.kind==GestureKind.PINCH}
            val raw=coords.toRaw(FacePoint(.36f,.25f))
            assertEquals(raw.x,pinch.position.x,.0001f);assertEquals(raw.y,pinch.position.y,.0001f)
        }
    }
    @Test fun twoPinchesProduceScaleAndEndWhenOneReleases() {
        val engine=engine()
        fun hands(offset: Float=0f)=listOf(
            hand(pinch=true,transform={FacePoint(it.x*.4f,it.y*.4f)},side=Handedness.LEFT),
            hand(pinch=true,transform={FacePoint(.5f+offset+it.x*.4f,it.y*.4f)},side=Handedness.RIGHT))
        engine.process(info(0),hands());engine.process(info(100),hands())
        assertEquals(GestureEventPhase.START,engine.process(info(200),hands()).single {it.kind==GestureKind.TWO_HAND_SCALE}.phase)
        val event=engine.process(info(300),hands(.1f)).single {it.kind==GestureKind.TWO_HAND_SCALE}
        assertEquals(1.2f,event.scale,.001f)
        val ended=engine.process(info(400),listOf(hands()[0],hand(transform={FacePoint(.5f+it.x*.4f,it.y*.4f)},side=Handedness.RIGHT)))
        assertEquals(GestureEventPhase.END,ended.single {it.kind==GestureKind.TWO_HAND_SCALE}.phase)
    }
    @Test fun waveNeedsAlternatingTravelAndDoesNotTriggerFromStillPalm() {
        val engine=engine();val all=mutableListOf<GestureEvent>()
        val offsets=listOf(0f,.15f,.3f,.15f,0f,.15f,.3f)
        offsets.forEachIndexed {i,x -> all+=engine.process(info(i*100L),listOf(hand(transform={FacePoint(it.x+x,it.y)})))}
        assertEquals(1,triggers(all,GestureKind.WAVE).size)
        val still=engine();val fixed=mutableListOf<GestureEvent>()
        for(t in 0L..1800L step 100)fixed+=still.process(info(t),listOf(hand()))
        assertTrue(triggers(fixed,GestureKind.WAVE).isEmpty());assertTrue(triggers(fixed,GestureKind.CIRCLE).isEmpty())
    }
    @Test fun circleRequiresClosedConsistentTrajectory() {
        val engine=engine();val all=mutableListOf<GestureEvent>()
        for(i in 0..16) {
            val angle=i*2*PI/16
            all+=engine.process(info(i*100L),listOf(hand(GestureKind.POINTING_UP,transform={
                FacePoint(it.x+.2f*cos(angle).toFloat(),it.y+.2f*sin(angle).toFloat())})))
        }
        assertEquals(1,triggers(all,GestureKind.CIRCLE).size)
    }
    @Test fun clapRequiresTwoPalmsSeparatingBeforeApproach() {
        val engine=engine();val all=mutableListOf<GestureEvent>()
        for((i,gap) in listOf(.55f,.4f,.25f,.1f).withIndex()) {
            val left=hand(transform={FacePoint(it.x*.5f,it.y*.5f)},side=Handedness.LEFT)
            val right=hand(transform={FacePoint(it.x*.5f+gap,it.y*.5f)},side=Handedness.RIGHT)
            all+=engine.process(info(i*100L),listOf(left,right))
        }
        assertEquals(1,triggers(all,GestureKind.CLAP).size)
    }
    @Test fun degenerateLandmarksCannotStartCustomInteractions() {
        val engine=engine()
        val hand=HandObservation(List(21){FacePoint(.5f,.5f)})
        for(t in 0L..500L step 100)assertTrue(engine.process(info(t),listOf(hand)).isEmpty())
    }
    private fun fingers(count: Int): HandObservation {
        val p=hand(null).landmarks.toMutableList()
        if(count<5){p[3]=FacePoint(.4f,.65f);p[4]=FacePoint(.45f,.7f)}
        for(i in 0..3)if(i>=count) {
            val start=5+i*4;val x=p[start].x
            p[start+1]=FacePoint(x,.5f);p[start+2]=FacePoint(x,.6f);p[start+3]=FacePoint(x,.7f)
        }
        return HandObservation(p)
    }
    @Test fun fingerCountZeroThroughFiveAndFingerHeartHavePositiveFixtures() {
        for(count in 0..5) {
            val engine=engine();val pose=fingers(count)
            engine.process(info(0),listOf(pose));engine.process(info(100),listOf(pose))
            val events=engine.process(info(200),listOf(pose))
            assertEquals("count $count",1,triggers(events,GestureKind.entries[GestureKind.NUMBER_0.ordinal+count]).size)
        }
        val points=fingers(0).landmarks.toMutableList()
        points[4]=FacePoint(.355f,.3f);points[8]=FacePoint(.365f,.3f)
        val pose=HandObservation(points);val engine=engine()
        engine.process(info(0),listOf(pose));engine.process(info(100),listOf(pose))
        assertEquals(1,triggers(engine.process(info(200),listOf(pose)),GestureKind.FINGER_HEART).size)
    }
    @Test fun twoHandHeartAndPrayerHaveSeparatePositiveFixturesAndSingleHandCannotTriggerThem() {
        val a=hand(null,transform={FacePoint(.1f+it.x*.5f,.3f+it.y*.5f)}).landmarks.toMutableList()
        val b=hand(null,transform={FacePoint(.45f+it.x*.5f,.3f+it.y*.5f)}).landmarks.toMutableList()
        a[4]=FacePoint(.46f,.55f);a[8]=FacePoint(.46f,.35f)
        b[4]=FacePoint(.54f,.55f);b[8]=FacePoint(.54f,.35f)
        val heart=listOf(HandObservation(a,Handedness.LEFT,.9f),HandObservation(b,Handedness.RIGHT,.9f))
        val engine=engine();engine.process(info(0),heart);engine.process(info(100),heart)
        assertEquals(1,triggers(engine.process(info(200),heart),GestureKind.TWO_HAND_HEART).size)
        val prayer=listOf(hand(null,side=Handedness.LEFT),hand(null,side=Handedness.RIGHT,
            transform={FacePoint(it.x+.05f,it.y)}))
        engine.reset();engine.process(info(0),prayer);engine.process(info(100),prayer)
        assertEquals(1,triggers(engine.process(info(200),prayer),GestureKind.PRAYER).size)
        engine.reset()
        for(t in 0L..500L step 100) {
            val events=engine.process(info(t),listOf(prayer[0]))
            assertFalse(events.any {it.kind==GestureKind.PRAYER || it.kind==GestureKind.TWO_HAND_HEART})
        }
    }
}

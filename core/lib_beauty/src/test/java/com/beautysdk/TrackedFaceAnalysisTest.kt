package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

class TrackedFaceAnalysisTest {
    @Test fun slowSeedPreparationDoesNotBlockSnapshotSubmitOrReset()=verifySlowSeedPreparation(false)
    @Test fun closeDuringSeedPreparationDoesNotWaitOrPublish()=verifySlowSeedPreparation(true)
    private fun verifySlowSeedPreparation(close:Boolean) {
        val preparing=CountDownLatch(1);val release=CountDownLatch(1)
        val closed=CountDownLatch(1);val completed=LinkedBlockingQueue<Long>()
        val detectionThread=java.util.concurrent.atomic.AtomicReference<Thread>()
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        val face=FaceObservation(FaceBounds(.2f,.2f,.8f,.8f),landmarks=List(478){FacePoint(.5f,.5f)})
        val runtime=TrackedFaceAnalysis(FaceAnalyzerFactory { object:FaceAnalyzer {
            override val id="blocked-seed";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage):List<FaceObservation> {
                detectionThread.set(Thread.currentThread());return listOf(face)
            }
            override fun close(){closed.countDown()}
        } },object:FaceMotionEstimator {
            override fun prepare(frame:RgbaFrame,reuse:MotionImage?):MotionImage {
                // 两支路线程同名，以实际推理线程身份区分。
                if(Thread.currentThread()===detectionThread.get() && frame.info.timestampNs==1L) {
                    preparing.countDown();check(release.await(5,TimeUnit.SECONDS))
                }
                return MotionImage(frame.info,2,2,ByteBuffer.allocateDirect(4))
            }
            override fun estimate(reference:MotionImage,current:MotionImage,face:FaceObservation)=face
        },onTiming={completed.add(it.frameTimestampNs)})
        fun frame(t:Long)=RgbaFrame(FrameInfo(2,2,t),ByteBuffer.allocateDirect(16),8)
        try {
            runtime.setTrackingEnabled(true)
            runtime.submit(frame(1))
            assertTrue(preparing.await(5,TimeUnit.SECONDS))
            executor.submit {
                assertNull(runtime.snapshot(frame(2).info,100).result)
                runtime.submit(frame(2))
                if(close) runtime.close() else runtime.reset()
                assertNull(runtime.snapshot(frame(3).info,100).result)
            }.get(1,TimeUnit.SECONDS)
            release.countDown()
            // reset按既有契约延迟到下一任务再释放旧后端；close在worker随后释放。
            if(!close) runtime.submit(frame(4))
            assertTrue(closed.await(5,TimeUnit.SECONDS))
            if(!close) {
                assertEquals("Old generation must not publish",4L,completed.poll(5,TimeUnit.SECONDS))
                assertEquals(4L,runtime.snapshot(frame(5).info,100).result!!.info.timestampNs)
            } else assertEquals(FaceAnalysisState.DISABLED,runtime.snapshot(frame(5).info,100).state)
            assertTrue(completed.isEmpty())
        } finally {release.countDown();runtime.close();executor.shutdownNow()}
    }
    @Test fun busyTrackingKeepsOnlyLatestPendingImage() {
        val detected=CountDownLatch(1);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val completed=LinkedBlockingQueue<Long>()
        val face=FaceObservation(FaceBounds(.2f,.2f,.8f,.8f),landmarks=List(478){FacePoint(.5f,.5f)})
        val runtime=TrackedFaceAnalysis(FaceAnalyzerFactory{object:FaceAnalyzer {
            override val id="latest-motion-seed";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage)=listOf(face)
            override fun close()=Unit
        }},object:FaceMotionEstimator {
            override fun prepare(frame:RgbaFrame,reuse:MotionImage?)=MotionImage(frame.info,2,2,ByteBuffer.allocateDirect(4))
            override fun estimate(reference:MotionImage,current:MotionImage,face:FaceObservation):FaceObservation {
                if(current.info.timestampNs==10L){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
                return face
            }
        },onTiming={detected.countDown()},onTrackingTiming={completed.add(it.frameTimestampNs)})
        fun frame(t:Long)=RgbaFrame(FrameInfo(2,2,t),ByteBuffer.allocateDirect(16),8)
        try {
            runtime.setTrackingEnabled(true);runtime.submit(frame(1))
            assertTrue(detected.await(5,TimeUnit.SECONDS));assertEquals(1L,completed.poll(5,TimeUnit.SECONDS))
            runtime.submit(frame(10),runDetection=false);assertTrue(entered.await(5,TimeUnit.SECONDS))
            runtime.submit(frame(20),runDetection=false);runtime.submit(frame(30),runDetection=false)
            release.countDown()
            assertEquals(10L,completed.poll(5,TimeUnit.SECONDS))
            assertEquals("Middle pending image must be replaced by newest",30L,completed.poll(5,TimeUnit.SECONDS))
            val snapshot=runtime.snapshot(frame(31).info,150_000_000)
            assertTrue(snapshot.tracked);assertEquals(30L,snapshot.result!!.info.timestampNs)
            assertEquals("Tracking must retain actual model seed age",1L,snapshot.detectionTimestampNs)
            runtime.reset();assertNull(runtime.snapshot(frame(32).info,150_000_000).result)
        } finally {release.countDown();runtime.close()}
    }
    @Test fun failedOptionalMotionCannotSuppressNewerRealModel() {
        val modelDone=LinkedBlockingQueue<Long>();val motionDone=LinkedBlockingQueue<Long>()
        val face=FaceObservation(FaceBounds(.2f,.2f,.8f,.8f),landmarks=List(478){FacePoint(.5f,.5f)})
        val runtime=TrackedFaceAnalysis(FaceAnalyzerFactory{object:FaceAnalyzer {
            override val id="newer-real-model";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage)=if(image.info.timestampNs>=250_000_000L)emptyList()else listOf(face)
            override fun close()=Unit
        }},object:FaceMotionEstimator {
            override fun prepare(frame:RgbaFrame,reuse:MotionImage?)=MotionImage(frame.info,2,2,ByteBuffer.allocateDirect(4))
            override fun estimate(reference:MotionImage,current:MotionImage,face:FaceObservation)=if(current.info.timestampNs<100_000_000L)face else null
        },onTiming={modelDone.add(it.frameTimestampNs)},onTrackingTiming={motionDone.add(it.frameTimestampNs)})
        fun frame(t:Long)=RgbaFrame(FrameInfo(2,2,t),ByteBuffer.allocateDirect(16),8)
        fun submit(t:Long,model:Boolean){runtime.submit(frame(t),runDetection=model);if(model)assertEquals(t,modelDone.poll(5,TimeUnit.SECONDS));assertEquals(t,motionDone.poll(5,TimeUnit.SECONDS))}
        try {
            runtime.setTrackingEnabled(true)
            submit(1,true);submit(50_000_000L,false)
            assertTrue(runtime.snapshot(frame(50_000_000L).info,150_000_000L).tracked)
            submit(100_000_000L,true);submit(130_000_000L,false)
            val current=runtime.snapshot(frame(140_000_000L).info,150_000_000L)
            assertEquals("Optional motion failure must not veto a newer actual detection",FaceAnalysisState.READY,current.state)
            assertFalse(current.tracked);assertEquals(100_000_000L,current.result!!.info.timestampNs)
            assertEquals(40_000_000L,current.resultAgeNs)
            assertEquals(FaceAnalysisState.STALE,runtime.snapshot(frame(251_000_000L).info,150_000_000L).state)
            submit(250_000_000L,true)
            assertEquals(FaceAnalysisState.NO_FACE,runtime.snapshot(frame(260_000_000L).info,150_000_000L).state)
            runtime.reset();assertNull(runtime.snapshot(frame(270_000_000L).info,150_000_000L).result)
        } finally {runtime.close()}
    }
    @Test fun failedMotionStopsFrozenPositionAndNewModelCanRecover() {
        val modelDone=LinkedBlockingQueue<Long>()
        val motionDone=LinkedBlockingQueue<Long>()
        val face=FaceObservation(FaceBounds(.2f,.2f,.8f,.8f),landmarks=List(478) { FacePoint(.5f,.5f) })
        val runtime=TrackedFaceAnalysis(FaceAnalyzerFactory { object:FaceAnalyzer {
            override val id="hold";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage)=if(image.info.timestampNs>=250_000_000L) emptyList() else listOf(face)
            override fun close()=Unit
        } },object:FaceMotionEstimator {
            override fun prepare(frame:RgbaFrame,reuse:MotionImage?)=MotionImage(frame.info,2,2,ByteBuffer.allocateDirect(4))
            override fun estimate(reference:MotionImage,current:MotionImage,face:FaceObservation)=
                if(current.info.timestampNs<220_000_000L) face else null
        },onTiming={modelDone.add(it.frameTimestampNs)},onTrackingTiming={motionDone.add(it.frameTimestampNs)})
        fun frame(t:Long)=RgbaFrame(FrameInfo(2,2,t),ByteBuffer.allocateDirect(16),8)
        // 回调发生在worker释放busy前，用有界重试而非睡眠猜测下一帧已可提交。
        fun submitMotion(start:Long):Long {
            var t=start
            val deadline=System.nanoTime()+2_000_000_000L
            while(System.nanoTime()<deadline) {
                runtime.submit(frame(t),runDetection=false)
                if(motionDone.poll(10,TimeUnit.MILLISECONDS)==t) return t
                t++
            }
            error("motion worker timeout")
        }
        try {
            runtime.setTrackingEnabled(true)
            runtime.submit(frame(1));assertEquals(1L,modelDone.poll(5,TimeUnit.SECONDS))
            submitMotion(200_000_000L)
            assertTrue(runtime.snapshot(frame(210_000_000L).info,150_000_000L).tracked)
            submitMotion(220_000_000L)
            val held=runtime.snapshot(frame(230_000_000L).info,150_000_000L)
            assertFalse(held.tracked)
            assertNull("Failed matching must not paint the last successful position",held.result)
            assertEquals(FaceAnalysisState.STALE,held.state)
            // 失败帧不能刷新采样时间，50ms帧期限和300ms参考期限都仍生效。
            assertFalse(runtime.snapshot(frame(260_000_000L).info,50_000_000L).tracked)
            assertFalse(runtime.snapshot(frame(301_000_000L).info,150_000_000L).tracked)
            runtime.submit(frame(250_000_000L));assertEquals(250_000_000L,modelDone.poll(5,TimeUnit.SECONDS))
            assertEquals(FaceAnalysisState.NO_FACE,runtime.snapshot(frame(260_000_000L).info,150_000_000L).state)
            runtime.reset();assertNull(runtime.snapshot(frame(270_000_000L).info,150_000_000L).result)
        } finally { runtime.close() }
    }

    @Test fun adjacentFramesBridgeDisplacementBeyondOldReferenceSearchAndReplayLateModel() {
        val modelDone=LinkedBlockingQueue<Long>();val motionDone=LinkedBlockingQueue<Long>()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val matches=java.util.Collections.synchronizedList(mutableListOf<Pair<Long,Long>>())
        fun face(x:Float)=FaceObservation(FaceBounds(.1f,.1f,.9f,.9f),landmarks=List(478){FacePoint(x,.5f)})
        fun frame(t:Long)=RgbaFrame(FrameInfo(2,2,t),ByteBuffer.allocateDirect(16),8)
        val runtime=TrackedFaceAnalysis(FaceAnalyzerFactory { object:FaceAnalyzer {
            override val id="delayed-correction";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage):List<FaceObservation> {
                if(image.info.timestampNs==30L) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
                return listOf(face(if(image.info.timestampNs==30L).4f else .1f))
            }
            override fun close()=Unit
        }},object:FaceMotionEstimator {
            override fun prepare(frame:RgbaFrame,reuse:MotionImage?):MotionImage {
                val pixels=reuse?.pixels ?: ByteBuffer.allocateDirect(1)
                pixels.put(0,frame.info.timestampNs.toByte())
                return MotionImage(frame.info,1,1,pixels)
            }
            override fun estimate(reference:MotionImage,current:MotionImage,face:FaceObservation):FaceObservation? {
                assertEquals("Reference buffer was overwritten",reference.info.timestampNs.toByte(),reference.pixels.get(0))
                matches+=reference.info.timestampNs to current.info.timestampNs
                val step=current.info.timestampNs-reference.info.timestampNs
                if(step>10) return null
                return FaceObservation(face.bounds,landmarks=face.landmarks.map{FacePoint(it.x+step*.001f,it.y)})
            }
        },onTiming={modelDone.add(it.frameTimestampNs)},onTrackingTiming={motionDone.add(it.frameTimestampNs)})
        fun track(t:Long) {runtime.submit(frame(t),runDetection=false);assertEquals(t,motionDone.poll(5,TimeUnit.SECONDS))}
        try {
            runtime.setTrackingEnabled(true);runtime.submit(frame(10))
            assertEquals(10L,modelDone.poll(5,TimeUnit.SECONDS));assertEquals(10L,motionDone.poll(5,TimeUnit.SECONDS))
            track(20)
            runtime.submit(frame(30));assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(30L,motionDone.poll(5,TimeUnit.SECONDS))
            for(t in 40L..60L step 10) track(t)
            val before=runtime.snapshot(frame(60).info,100)
            assertTrue(before.tracked);assertEquals(.15f,before.result!!.faces.single().landmarks[0].x,.0001f)
            release.countDown();assertEquals(30L,modelDone.poll(5,TimeUnit.SECONDS))
            track(70)
            val corrected=runtime.snapshot(frame(70).info,100)
            assertTrue(corrected.tracked);assertEquals(30L,corrected.detectionTimestampNs)
            assertEquals("Late correction must be moved to frame 70, not rendered at frame 30",.44f,
                corrected.result!!.faces.single().landmarks[0].x,.0001f)
            for(t in 80L..150L step 10) track(t) // 超过8张，覆盖灰度槽复用。
            assertTrue(runtime.snapshot(frame(150).info,100).tracked)
            assertTrue("Normal tracking must use adjacent images",matches.all{it.second-it.first<=10})
            track(180)
            assertEquals(FaceAnalysisState.STALE,runtime.snapshot(frame(180).info,200).state)
            runtime.submit(frame(190));assertEquals(190L,modelDone.poll(5,TimeUnit.SECONDS))
            assertEquals(190L,motionDone.poll(5,TimeUnit.SECONDS))
            track(200)
            val recovered=runtime.snapshot(frame(200).info,100)
            assertTrue("A fresh model must recover a broken tracking chain",recovered.tracked)
            assertEquals(190L,recovered.detectionTimestampNs)
        } finally {release.countDown();runtime.close()}
    }

    @Test fun modelHandoffKeepsNewerCompletedTracking() = checkHandoff(false)
    @Test fun modelHandoffKeepsInFlightTracking() = checkHandoff(true)

    /** 用锁存器复现：100ms模型晚于200ms跟踪返回，位置不能倒退到100ms。 */
    private fun checkHandoff(inFlight: Boolean) {
        val releaseModel=CountDownLatch(1)
        val releaseMotion=CountDownLatch(if(inFlight) 1 else 0)
        val motionStarted=CountDownLatch(1)
        val modelDone=LinkedBlockingQueue<Long>()
        val motionDone=LinkedBlockingQueue<Long>()
        val face=FaceObservation(FaceBounds(.2f,.2f,.8f,.8f),landmarks=List(478) { FacePoint(.5f,.5f) })
        val runtime=TrackedFaceAnalysis(FaceAnalyzerFactory { object:FaceAnalyzer {
            override val id="handoff"; override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage):List<FaceObservation> {
                if(image.info.timestampNs==100_000_000L) check(releaseModel.await(5,TimeUnit.SECONDS))
                return if(image.info.timestampNs==250_000_000L) emptyList() else listOf(face)
            }
            override fun close()=Unit
        } },object:FaceMotionEstimator {
            override fun prepare(frame:RgbaFrame,reuse:MotionImage?)=MotionImage(frame.info,2,2,ByteBuffer.allocateDirect(4))
            override fun estimate(reference:MotionImage,current:MotionImage,face:FaceObservation):FaceObservation {
                if(current.info.timestampNs>=200_000_000L) {
                    motionStarted.countDown();check(releaseMotion.await(5,TimeUnit.SECONDS))
                }
                return face
            }
        },onTiming={modelDone.add(it.frameTimestampNs)},onTrackingTiming={motionDone.add(it.frameTimestampNs)})
        fun frame(t:Long)=RgbaFrame(FrameInfo(2,2,t),ByteBuffer.allocateDirect(16),8)
        try {
            runtime.setTrackingEnabled(true)
            runtime.submit(frame(1)); assertEquals(1L,modelDone.poll(5,TimeUnit.SECONDS))
            runtime.submit(frame(100_000_000L))
            var t=200_000_000L
            val deadline=System.nanoTime()+2_000_000_000L
            while(motionStarted.count>0 && System.nanoTime()<deadline) {
                runtime.submit(frame(t++),runDetection=false)
                motionStarted.await(1,TimeUnit.MILLISECONDS)
            }
            assertEquals(0L,motionStarted.count)
            fun awaitTracked():FaceAnalysisSnapshot {
                val until=System.nanoTime()+2_000_000_000L
                do {
                    val snapshot=runtime.snapshot(frame(220_000_000L).info,150_000_000L)
                    if(snapshot.tracked && snapshot.result!!.info.timestampNs>=200_000_000L) return snapshot
                    Thread.yield()
                } while(System.nanoTime()<until)
                error("new tracked frame was discarded")
            }
            val before=if(!inFlight) awaitTracked() else null
            releaseModel.countDown();assertEquals(100_000_000L,modelDone.poll(5,TimeUnit.SECONDS))
            releaseMotion.countDown()
            val after=awaitTracked()
            assertEquals(1L,after.detectionTimestampNs)
            if(before!=null) assertEquals(before.result!!.info.timestampNs,after.result!!.info.timestampNs)
            // 参考图更新不延长旧跟踪的校正期限；新无脸结果必须撤销旧位置。
            assertFalse(runtime.snapshot(frame(301_000_000L).info,150_000_000L).tracked)
            runtime.submit(frame(250_000_000L));assertEquals(250_000_000L,modelDone.poll(5,TimeUnit.SECONDS))
            assertEquals(FaceAnalysisState.NO_FACE,runtime.snapshot(frame(260_000_000L).info,150_000_000L).state)
            runtime.submit(frame(270_000_000L));assertEquals(270_000_000L,modelDone.poll(5,TimeUnit.SECONDS))
            // 重新检出后，不能复用无脸前的跟踪结果。
            assertFalse(runtime.snapshot(frame(280_000_000L).info,150_000_000L).tracked)
            runtime.reset();assertNull(runtime.snapshot(frame(280_000_000L).info,150_000_000L).result)
        } finally { releaseModel.countDown();releaseMotion.countDown();runtime.close() }
    }

    @Test fun publicSessionUsesOptionalMotionPipelineAndKeepsLifecycle() {
        val detected=CountDownLatch(1)
        val face=FaceObservation(FaceBounds(.2f,.2f,.8f,.8f),landmarks=List(478) { FacePoint(.5f,.5f) })
        val session=BeautySession(factory=FaceAnalyzerFactory { object: FaceAnalyzer {
            override val id="session-motion";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage)=listOf(face)
            override fun close()=Unit
        } },motionEstimator=object:FaceMotionEstimator {
            override fun prepare(frame:RgbaFrame,reuse:MotionImage?)=MotionImage(frame.info,2,2,ByteBuffer.allocateDirect(4))
            override fun estimate(reference:MotionImage,current:MotionImage,face:FaceObservation)=face
        },onAnalysisTiming={detected.countDown()},analysisIntervalNs=250_000_000L)
        session.use {
            it.settings=BeautySettings(glasses=true)
            it.start();it.submitAnalysis(RgbaFrame(FrameInfo(2,2,1),ByteBuffer.allocateDirect(16),8))
            assertTrue(detected.await(5,TimeUnit.SECONDS))
            assertEquals(1,it.latestFaces(FrameInfo(2,2,2))!!.faces.size)
            val until=System.nanoTime()+2_000_000_000L
            var timestamp=200_000_000L
            // 模型限频250ms，此时只能通过新图像跟踪得到200ms处的结果。
            while(System.nanoTime()<until) {
                assertFalse(it.submitAnalysis(RgbaFrame(FrameInfo(2,2,timestamp),ByteBuffer.allocateDirect(16),8)))
                val result=it.latestFaces(FrameInfo(2,2,timestamp))
                if(result!=null && result.info.timestampNs>=200_000_000L) break
                timestamp++
                Thread.yield()
            }
            assertNotNull(it.latestFaces(FrameInfo(2,2,timestamp)))
            it.stop();assertNull(it.latestFaces(FrameInfo(2,2,3)))
        }
    }

    @Test fun freshMotionBridgesStaleDetectionButCannotOutliveCorrectionOrReset() {
        val release=CountDownLatch(1)
        val detected=LinkedBlockingQueue<Long>();val tracked=LinkedBlockingQueue<Long>()
        val face=FaceObservation(FaceBounds(.2f,.2f,.8f,.8f),landmarks=List(478) { FacePoint(.5f,.5f) })
        var calls=0
        val runtime=TrackedFaceAnalysis(FaceAnalyzerFactory { object:FaceAnalyzer {
            override val id="fixture";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image: AnalysisImage): List<FaceObservation> {
                if(++calls>1) { check(release.await(5,TimeUnit.SECONDS));return emptyList() }
                return listOf(face)
            }
            override fun close()=Unit
        } },object:FaceMotionEstimator {
            override fun prepare(frame: RgbaFrame,reuse: MotionImage?)=MotionImage(frame.info,2,2,ByteBuffer.allocateDirect(4))
            override fun estimate(reference:MotionImage,current:MotionImage,face:FaceObservation)=face
        },onTiming={detected.add(it.frameTimestampNs)},onTrackingTiming={tracked.add(it.frameTimestampNs)})
        fun frame(t:Long)=RgbaFrame(FrameInfo(2,2,t),ByteBuffer.allocateDirect(16),8)
        fun awaitTime(queue:LinkedBlockingQueue<Long>,time:Long) {
            repeat(5) { if(queue.poll(2,TimeUnit.SECONDS)==time) return }
            fail("missing $time")
        }
        try {
            runtime.setTrackingEnabled(true)
            runtime.submit(frame(1));awaitTime(detected,1);awaitTime(tracked,1)
            // 等待首个轻量worker退出busy，再提交；不使用睡眠猜测同步时序。
            val deadline=System.nanoTime()+2_000_000_000L
            var t=200_000_000L
            while(System.nanoTime()<deadline) {
                runtime.submit(frame(t))
                if(tracked.poll(10,TimeUnit.MILLISECONDS)==t) break
                t++
            }
            val result=runtime.snapshot(frame(t).info,150_000_000)
            assertTrue(result.tracked);assertEquals(1L,result.detectionTimestampNs)
            assertEquals(t,result.result!!.info.timestampNs)
            assertNull(runtime.snapshot(frame(400_000_001).info,150_000_000).result)
            release.countDown()
            awaitTime(detected,t)
            assertFalse(runtime.snapshot(frame(t+1).info,150_000_000).tracked)
            assertEquals(FaceAnalysisState.NO_FACE,runtime.snapshot(frame(t+1).info,150_000_000).state)
            runtime.reset();assertNull(runtime.snapshot(frame(t+2).info,150_000_000).result)
        } finally { release.countDown();runtime.close() }
    }
}

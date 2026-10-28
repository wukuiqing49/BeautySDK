package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

class TrackedFaceAnalysisTest {
    @Test fun failedMotionKeepsLastSuccessWithoutRefreshingTimestamp() {
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
            val success=submitMotion(200_000_000L)
            assertTrue(runtime.snapshot(frame(210_000_000L).info,150_000_000L).tracked)
            submitMotion(220_000_000L)
            val held=runtime.snapshot(frame(230_000_000L).info,150_000_000L)
            assertTrue(held.tracked)
            assertEquals(success,held.result!!.info.timestampNs)
            assertEquals(230_000_000L-success,held.resultAgeNs)
            assertEquals(1L,held.detectionTimestampNs)
            // 失败帧不能刷新采样时间，50ms帧期限和300ms参考期限都仍生效。
            assertFalse(runtime.snapshot(frame(260_000_000L).info,50_000_000L).tracked)
            assertFalse(runtime.snapshot(frame(301_000_000L).info,150_000_000L).tracked)
            runtime.submit(frame(250_000_000L));assertEquals(250_000_000L,modelDone.poll(5,TimeUnit.SECONDS))
            assertEquals(FaceAnalysisState.NO_FACE,runtime.snapshot(frame(260_000_000L).info,150_000_000L).state)
            runtime.reset();assertNull(runtime.snapshot(frame(270_000_000L).info,150_000_000L).result)
        } finally { runtime.close() }
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

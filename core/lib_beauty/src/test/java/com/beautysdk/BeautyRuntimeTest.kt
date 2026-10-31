package com.beautysdk

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BeautyRuntimeTest {
    private fun frame(time: Long)=RgbaFrame(FrameInfo(2,2,time),ByteBuffer.allocateDirect(16),8)
    private fun factory(calls: AtomicInteger)=FaceAnalyzerFactory { object: FaceAnalyzer {
        override val id="fixture";override val capabilities=emptySet<FaceCapability>()
        override fun analyze(image: AnalysisImage):List<FaceObservation> { calls.incrementAndGet();return listOf(FaceObservation(FaceBounds(0f,0f,1f,1f))) }
        override fun close()=Unit
    } }
    @Test fun noStickerSkipsModelAndDemandChangeInvalidatesResults() {
        val calls=AtomicInteger();val done=CountDownLatch(1)
        BeautySession(factory(calls),onAnalysisTiming={done.countDown()}).use { session ->
            session.start();session.settings=BeautySettings(smoothing=.4f)
            assertFalse(session.submitAnalysis(frame(1)));assertEquals(0,calls.get())
            session.settings=BeautySettings(glasses=true)
            assertTrue(session.submitAnalysis(frame(2)));assertTrue(done.await(5,TimeUnit.SECONDS))
            assertNotNull(session.latestFaces(frame(2).info))
            session.settings=BeautySettings()
            assertNull(session.latestFaces(frame(3).info));assertFalse(session.submitAnalysis(frame(3)))
        }
    }
    @Test fun motionBranchReceivesConfiguredAnalysisSize() {
        val size=AtomicInteger();val done=CountDownLatch(1)
        val motion=object: FaceMotionEstimator {
            override fun prepare(frame:RgbaFrame,reuse:MotionImage?)=MotionImage(frame.info,2,2,ByteBuffer.allocateDirect(4))
            override fun estimate(reference:MotionImage,current:MotionImage,face:FaceObservation)=face
        }
        BeautySession(factory=FaceAnalyzerFactory { object: FaceAnalyzer {
            override val id="sizing";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage)=emptyList<FaceObservation>()
            override fun analyzeRgba(frame:RgbaFrame,maxSide:Int):List<FaceObservation> { size.set(maxSide);return emptyList() }
            override fun close()=Unit
        } },analysisMaxSide=192,motionEstimator=motion,onAnalysisTiming={done.countDown()}).use {
            it.settings=BeautySettings(hat=true);it.start();it.submitAnalysis(frame(1))
            assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(192,size.get())
        }
    }
    @Test fun redrawKeepsSamplingTimestampAndRejectsDifferentFrame() {
        var renders=0
        val renderer=object:BeautyRenderer {
            override fun render(frame:VideoFrame,settings:BeautySettings,face:FaceObservation?):VideoFrame { renders++;return frame }
            override fun close()=Unit
        }
        BeautySession(renderer=renderer).use {
            it.start();val input=frame(7);it.process(input);assertSame(input,it.redraw(input));assertEquals(2,renders)
            try { it.redraw(frame(8));fail("different sample must not redraw") } catch(_:IllegalArgumentException) {}
            it.process(frame(8));assertEquals(3,renders)
        }
    }
    @Test fun lateAnalysisCanRefreshSameVideoSampleWithoutResubmitting() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val completed=CountDownLatch(1)
        val calls=AtomicInteger()
        val expected=FaceObservation(FaceBounds(.2f,.2f,.8f,.8f))
        val observed=mutableListOf<FaceObservation?>()
        val factory=FaceAnalyzerFactory { object:FaceAnalyzer {
            override val id="delayed-fixture";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage):List<FaceObservation> {
                calls.incrementAndGet();entered.countDown()
                check(release.await(5,TimeUnit.SECONDS));return listOf(expected)
            }
            override fun close()=Unit
        } }
        val renderer=object:BeautyRenderer {
            override fun render(frame:VideoFrame,settings:BeautySettings,face:FaceObservation?):VideoFrame {
                observed.add(face);return frame
            }
            override fun close()=Unit
        }
        BeautyAnalysisRuntime(factory,onTiming={completed.countDown()}).use { runtime ->
            runtime.setEnabled(true)
            BeautySession(renderer=renderer,sharedAnalysis=runtime).use { session ->
                session.start();session.settings=BeautySettings(glasses=true)
                val input=frame(7)
                try {
                    assertTrue(runtime.submit(input));assertTrue(entered.await(5,TimeUnit.SECONDS))
                    session.process(input);assertNull(observed.last())
                    release.countDown();assertTrue(completed.await(5,TimeUnit.SECONDS))
                    // 异步发布本身不改变已合成画面，宿主必须显式补画。
                    assertNull(observed.last())
                    assertSame(input,session.redraw(input));assertSame(expected,observed.last())
                    assertEquals(7L,session.lastFrameAnalysis.result!!.info.timestampNs)
                    assertEquals(0L,session.lastFrameAnalysis.resultAgeNs)
                    assertEquals(1,calls.get())
                } finally { release.countDown() }
            }
        }
    }
    @Test fun borrowedRuntimeSurvivesRenderSessionClose() {
        val calls=AtomicInteger();val done=CountDownLatch(1)
        BeautyAnalysisRuntime(factory(calls),onTiming={done.countDown()}).use { runtime ->
            runtime.setEnabled(true)
            val session=BeautySession(sharedAnalysis=runtime);session.start();session.close()
            assertTrue(runtime.submit(frame(1)));assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(1,calls.get())
        }
    }
    @Test fun percentilesAreBoundedAndReportMissingStages() {
        val stats=BeautyTimingWindow(16)
        for(i in 1..20) stats.add(FaceAnalysisTiming(i.toLong(),0,0,i*1_000_000L,null,1,null))
        val summary=stats.drain()
        assertTrue(summary.contains("n=20"));assertTrue(summary.contains("totalP95Ms=20.0"))
        assertTrue(summary.contains("totalSamples=16"));assertTrue(summary.contains("prepareMs=unavailable"))
        assertTrue(stats.summary().contains("n=0"))
    }
    @Test fun frameMetricsSeparateRedrawsAndDoNotInventResultAge() {
        val stats=BeautyFrameWindow(16)
        val cold=BeautyFrameWindow(16)
        cold.record(1,1,FaceAnalysisSnapshot(FaceAnalysisState.DISABLED))
        assertTrue(cold.drain().contains("localConsumeFps=unavailable"))
        val snapshot=FaceAnalysisSnapshot(FaceAnalysisState.DISABLED)
        stats.record(1,2_000_000,snapshot);stats.record(1,3_000_000,snapshot);stats.record(2,4_000_000,snapshot)
        val text=stats.drain()
        assertTrue(text.contains("videoFrames=2 redraws=1"))
        assertTrue(text.contains("cpuSubmitP99Ms=4.0"));assertTrue(text.contains("resultAgeP95Ms=unavailable"))
    }
    @Test fun borrowedPipelineDoesNotDisableResetOrCloseExternalAnalysis() {
        val calls=AtomicInteger();val done=CountDownLatch(1)
        BeautyAnalysisRuntime(factory(calls),onTiming={done.countDown()}).use { runtime ->
            runtime.setEnabled(true)
            assertTrue(runtime.submit(frame(1)))
            assertTrue(done.await(5,TimeUnit.SECONDS))
            val generation=runtime.generationToken()
            val pipeline=BeautyEnginePipeline(renderer=RgbaBeautyRenderer(),textureAnalysis=false,sharedAnalysis=runtime)
            pipeline.start()
            // 同一采样的RGBA已提交，管线不应关闭需求或重置宿主检测结果。
            pipeline.process(frame(1));pipeline.redraw(frame(1));pipeline.resetSource()
            pipeline.stop();pipeline.close()
            assertEquals(generation,runtime.generationToken())
            assertEquals(FaceAnalysisState.READY,runtime.snapshot(frame(1).info,100).state)
            assertTrue(runtime.submit(frame(2)))
        }
    }
    @Test fun enginePipelineCanTrackExternalFramesWithoutCameraTypes() {
        val matched=CountDownLatch(1)
        val face=FaceObservation(FaceBounds(.2f,.2f,.8f,.8f),landmarks=List(478) { FacePoint(.5f,.5f) })
        val motion=object:FaceMotionEstimator {
            override fun prepare(frame:RgbaFrame,reuse:MotionImage?)=MotionImage(frame.info,2,2,ByteBuffer.allocateDirect(4))
            override fun estimate(reference:MotionImage,current:MotionImage,face:FaceObservation):FaceObservation {
                matched.countDown();return face
            }
        }
        val factory=FaceAnalyzerFactory { object:FaceAnalyzer {
            override val id="engine-fixture";override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage)=listOf(face)
            override fun close()=Unit
        } }
        BeautyEnginePipeline(factory=factory,renderer=RgbaBeautyRenderer(),textureAnalysis=false,
            analysisIntervalNs=100_000_000L,motionEstimator=motion).use { pipeline ->
            pipeline.settings=BeautySettings(glasses=true);pipeline.start()
            // 模拟引擎标准帧，先等首份检测，再在模型限频期间跟踪新帧。
            pipeline.process(frame(1))
            val deadline=System.nanoTime()+5_000_000_000L
            while(pipeline.lastFrameAnalysis.state!=FaceAnalysisState.READY && System.nanoTime()<deadline) {
                pipeline.redraw(frame(1));Thread.yield()
            }
            assertEquals(FaceAnalysisState.READY,pipeline.lastFrameAnalysis.state)
            var next=33_000_001L
            while(matched.count>0 && System.nanoTime()<deadline) {
                pipeline.process(frame(next++))
                matched.await(10,TimeUnit.MILLISECONDS)
            }
            assertEquals(0L,matched.count)
            pipeline.resetSource()
            assertEquals(FaceAnalysisState.DISABLED,pipeline.lastFrameAnalysis.state)
        }
    }
}

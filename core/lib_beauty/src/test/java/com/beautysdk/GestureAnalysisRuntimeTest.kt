package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GestureAnalysisRuntimeTest {
    private fun frame(ms: Long=0,width: Int=16,height: Int=16,rotation: Int=0,mirror: Boolean=false): RgbaFrame =
        RgbaFrame(FrameInfo(width,height,ms*1_000_000,rotation,mirror),ByteBuffer.allocateDirect(width*height*4),width*4)
    private val hand=HandObservation(List(21){i -> FacePoint(.2f+i*.02f,.3f+i*.01f)},
        Handedness.LEFT,.9f,GestureKind.VICTORY,.95f)
    private fun await(runtime: GestureAnalysisRuntime,info: FrameInfo): GestureSnapshot {
        val end=System.nanoTime()+3_000_000_000
        while(System.nanoTime()<end) {
            val s=runtime.snapshot(info)
            if(s.result?.info?.timestampNs==info.timestampNs || s.state==GestureAnalysisState.ERROR)return s
            Thread.yield()
        }
        error("Gesture analysis did not complete")
    }
    private fun factory(block: (RgbaFrame)->List<HandObservation>)=GestureAnalyzerFactory {object:GestureAnalyzer {
        override fun analyzeRgba(frame: RgbaFrame,maxSide: Int)=block(frame)
        override fun close()=Unit
    }}
    @Test fun disabledDoesNotCreateModelAndCloseIsIdempotent() {
        val created=AtomicInteger()
        val runtime=GestureAnalysisRuntime(GestureAnalyzerFactory {created.incrementAndGet();error("disabled")})
        assertFalse(runtime.submit(frame()));assertEquals(0,created.get())
        assertEquals(GestureAnalysisState.DISABLED,runtime.snapshot(frame().info).state)
        runtime.close();runtime.close();assertTrue(runtime.pollEvents(frame().info).isEmpty())
    }
    @Test fun borrowedRgbaIsCopiedBeforeReturnAndBusyDropsWithoutAccumulation() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        val runtime=GestureAnalysisRuntime(factory {f ->
            entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))
            assertEquals(42,f.readOnlyPixels().get(0).toInt());done.countDown();listOf(hand)
        },intervalNs=0)
        try {
            runtime.setEnabled(true)
            val buffer=ByteBuffer.allocateDirect(16*16*4).apply {put(0,42.toByte())}
            val f=RgbaFrame(frame().info,buffer,16*4)
            assertTrue(runtime.submit(f));assertTrue(entered.await(3,TimeUnit.SECONDS))
            buffer.put(0,0.toByte())
            for(i in 1L..20)assertFalse(runtime.submit(frame(i)))
            release.countDown();assertTrue(done.await(3,TimeUnit.SECONDS))
            assertEquals(GestureAnalysisState.READY,await(runtime,frame().info).state)
        } finally {release.countDown();runtime.close()}
    }
    @Test fun resetRejectsInFlightResultAndStaleCaptureToken() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val closed=CountDownLatch(1)
        val runtime=GestureAnalysisRuntime(GestureAnalyzerFactory {object:GestureAnalyzer {
            override fun analyzeRgba(frame:RgbaFrame,maxSide:Int):List<HandObservation> {
                entered.countDown();release.await(3,TimeUnit.SECONDS);return listOf(hand)
            }
            override fun close(){closed.countDown()}
        }})
        runtime.setEnabled(true);val token=runtime.generationToken();runtime.submit(frame(),token)
        assertTrue(entered.await(3,TimeUnit.SECONDS));runtime.reset()
        assertFalse(runtime.submit(frame(1),token));release.countDown();runtime.close()
        assertTrue(closed.await(3,TimeUnit.SECONDS))
        assertEquals(GestureAnalysisState.DISABLED,runtime.snapshot(frame().info).state)
        assertTrue(runtime.pollEvents(frame(1000).info).isEmpty())
    }
    @Test fun futureExpiredWrongGeometryAndScaledOutputAreHandledExplicitly() {
        GestureAnalysisRuntime(factory {listOf(hand)},allowScaledFrames=true).use { runtime ->
            runtime.setEnabled(true);runtime.submit(frame(100));assertEquals(GestureAnalysisState.READY,await(runtime,frame(100).info).state)
            assertEquals(GestureAnalysisState.PROCESSING,runtime.snapshot(frame(99).info).state)
            assertEquals(GestureAnalysisState.STALE,runtime.snapshot(frame(401).info).state)
            assertEquals(GestureAnalysisState.READY,runtime.snapshot(frame(200,32,32).info).state)
            assertEquals(GestureAnalysisState.PROCESSING,runtime.snapshot(frame(200,32,16).info).state)
            assertEquals(GestureAnalysisState.PROCESSING,runtime.snapshot(frame(200,rotation=90).info).state)
            assertEquals(GestureAnalysisState.PROCESSING,runtime.snapshot(frame(200,mirror=true).info).state)
        }
    }
    @Test fun eventsAreDeliveredOnceNotInFutureAndNoHandsInvalidateSnapshot() {
        var hands=listOf(hand)
        GestureAnalysisRuntime(factory {hands},config=GestureConfig(stableNs=0,experimentalRules=false),intervalNs=0).use {runtime ->
            runtime.setEnabled(true);runtime.submit(frame(100));await(runtime,frame(100).info)
            assertTrue(runtime.pollEvents(frame(99).info).isEmpty())
            assertEquals(GestureKind.VICTORY,runtime.pollEvents(frame(100).info).single().kind)
            assertTrue(runtime.pollEvents(frame(100).info).isEmpty())
            hands=emptyList()
            // 等待上一次任务从发布段退出，避免依赖调度时序。
            val until=System.nanoTime()+3_000_000_000L
            var time=200L
            while(!runtime.submit(frame(time)) && System.nanoTime()<until)time++
            assertEquals(GestureAnalysisState.NO_HANDS,await(runtime,frame(time).info).state)
            assertTrue(runtime.pollEvents(frame(time+301).info).isEmpty())
        }
    }
    @Test fun modelErrorClearsStateAndDoesNotMasqueradeAsNoHands() {
        GestureAnalysisRuntime(factory {throw IllegalStateException("fixture failure")}).use {runtime ->
            runtime.setEnabled(true);runtime.submit(frame())
            val s=await(runtime,frame().info)
            assertEquals(GestureAnalysisState.ERROR,s.state);assertNull(s.result);assertTrue(s.error!!.contains("fixture failure"))
        }
    }
    @Test fun concurrentPollingCannotDeliverTheSameTriggerTwice() {
        GestureAnalysisRuntime(factory {listOf(hand)},config=GestureConfig(stableNs=0,experimentalRules=false),intervalNs=0).use {runtime ->
            runtime.setEnabled(true);runtime.submit(frame(100));await(runtime,frame(100).info)
            val start=CountDownLatch(1);val done=CountDownLatch(2);val delivered=AtomicInteger()
            repeat(2) {
                Thread {
                    try {start.await();delivered.addAndGet(runtime.pollEvents(frame(100).info).size)}
                    finally {done.countDown()}
                }.start()
            }
            start.countDown();assertTrue(done.await(3,TimeUnit.SECONDS))
            assertEquals(1,delivered.get())
            for(t in 101L..200L)assertTrue(runtime.pollEvents(frame(t).info).isEmpty())
        }
    }
    @Test fun modelCreationInferenceAndReleaseStayOnSameWorker() {
        val done=CountDownLatch(1)
        val runtime=GestureAnalysisRuntime(GestureAnalyzerFactory {
            val owner=Thread.currentThread()
            object:GestureAnalyzer {
                override fun analyzeRgba(frame:RgbaFrame,maxSide:Int):List<HandObservation> {assertSame(owner,Thread.currentThread());return emptyList()}
                override fun close(){assertSame(owner,Thread.currentThread());done.countDown()}
            }
        })
        runtime.setEnabled(true);runtime.submit(frame());assertEquals(GestureAnalysisState.NO_HANDS,await(runtime,frame().info).state)
        runtime.close();assertTrue(done.await(3,TimeUnit.SECONDS))
    }
}

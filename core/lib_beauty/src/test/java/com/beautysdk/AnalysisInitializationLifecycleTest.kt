package com.beautysdk

import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AnalysisInitializationLifecycleTest {
    private fun frame(t:Long)=RgbaFrame(FrameInfo(2,2,t),ByteBuffer.allocateDirect(16),8)
    private fun verify(reset:Boolean) {
        val creating=CountDownLatch(1);val release=CountDownLatch(1);val closed=CountDownLatch(1);val done=CountDownLatch(1)
        val calls=AtomicInteger();val factories=AtomicInteger()
        val runtime=AsyncFaceAnalysis(FaceAnalyzerFactory {
            val number=factories.incrementAndGet()
            if(number==1){creating.countDown();check(release.await(5,TimeUnit.SECONDS))}
            object:FaceAnalyzer {
                override val id="fixture"
                override val capabilities=emptySet<FaceCapability>()
                override fun analyze(image:AnalysisImage):List<FaceObservation> {calls.incrementAndGet();return emptyList()}
                override fun close(){closed.countDown()}
            }
        },keepLatestPending=true,onTiming={done.countDown()})
        try {
            runtime.submit(frame(1));assertTrue(creating.await(5,TimeUnit.SECONDS))
            if(reset){runtime.reset();runtime.submit(frame(2))}else runtime.close()
            release.countDown()
            assertTrue(closed.await(5,TimeUnit.SECONDS))
            if(reset){assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(2,factories.get());assertEquals(1,calls.get())}
            else {assertEquals(0,calls.get());assertEquals(FaceAnalysisState.DISABLED,runtime.snapshot(frame(2).info,100).state)}
        } finally {release.countDown();runtime.close()}
    }
    @Test fun closeDuringInitializationSkipsInferenceAndReleasesOnWorker()=verify(false)
    @Test fun resetDuringInitializationOnlyAnalyzesNewGeneration()=verify(true)
}

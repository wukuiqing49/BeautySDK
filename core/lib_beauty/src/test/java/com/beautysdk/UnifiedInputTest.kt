package com.beautysdk

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class UnifiedInputTest {
    private val face = FaceObservation(FaceBounds(.1f,.1f,.9f,.9f))
    private fun frame(time: Long) = RgbaFrame(FrameInfo(2,2,time),
        ByteBuffer.wrap(byteArrayOf(1,2,3,4,5,6,7,8,99,99,99,99,9,10,11,12,13,14,15,16)),12)

    @Test fun engineCanReleaseSourceImmediatelyAndWorkerUsesDirectRgba() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var workerBytes: ByteArray? = null
        var workerThread: Thread? = null
        val bytes = ByteBuffer.allocateDirect(16).apply { repeat(16) { put(it.toByte()) }; flip() }
        val input = RgbaFrame(FrameInfo(2,2,1),bytes,8)
        val scheduler = AsyncFaceAnalysis(FaceAnalyzerFactory { object : FaceAnalyzer {
            override val id = "engine-fixture"
            override val capabilities = emptySet<FaceCapability>()
            override fun analyze(image: AnalysisImage): List<FaceObservation> = error("Legacy RGB path used")
            override fun analyzeRgba(frame: RgbaFrame,maxSide: Int): List<FaceObservation> {
                workerThread = Thread.currentThread()
                entered.countDown(); check(release.await(5,TimeUnit.SECONDS))
                assertTrue(frame.readOnlyPixels().isDirect)
                workerBytes = ByteArray(16).also { frame.readOnlyPixels().get(it) }
                return listOf(face)
            }
            override fun close() { closed.countDown() }
        } },onTiming={ finished.countDown() })
        try {
            val generation = scheduler.generationToken()
            assertTrue(scheduler.submit(input,generation))
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            // 模拟引擎在回调返回后立即复用输入内存。
            for (i in 0 until 16) bytes.put(i,127)
            assertFalse(scheduler.submit(frame(2),generation))
            release.countDown(); assertTrue(finished.await(5,TimeUnit.SECONDS))
            assertArrayEquals(ByteArray(16) { it.toByte() },workerBytes)
            assertNotSame(Thread.currentThread(),workerThread)
            scheduler.reset()
            assertFalse(scheduler.submit(frame(3),generation))
            assertNull(scheduler.latest(input.info,100))
        } finally { release.countDown(); scheduler.close(); assertTrue(closed.await(5,TimeUnit.SECONDS)) }
    }

    @Test fun latestPendingReplacesIntermediateFramesWithoutOverwritingActiveInput() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val timestamps = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val observed = java.util.Collections.synchronizedList(mutableListOf<Byte>())
        val errors = java.util.Collections.synchronizedList(mutableListOf<Exception>())
        val scheduler = AsyncFaceAnalysis(FaceAnalyzerFactory { object : FaceAnalyzer {
            override val id = "latest-frame"
            override val capabilities = emptySet<FaceCapability>()
            override fun analyze(image: AnalysisImage): List<FaceObservation> = error("RGB path")
            override fun analyzeRgba(frame: RgbaFrame, maxSide: Int): List<FaceObservation> {
                if (frame.info.timestampNs == 1L) {
                    entered.countDown(); check(release.await(5,TimeUnit.SECONDS))
                }
                timestamps.add(frame.info.timestampNs)
                observed.add(frame.readOnlyPixels().get(0))
                return listOf(face)
            }
            override fun close() = Unit
        } }, onError={errors.add(it)}, onTiming={done.countDown()}, keepLatestPending=true)
        try {
            assertTrue(scheduler.submit(frame(1)))
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertTrue(scheduler.submit(frame(2)))
            val source = ByteBuffer.allocateDirect(16).apply { repeat(16) { put(42.toByte()) }; flip() }
            assertTrue(scheduler.submit(RgbaFrame(FrameInfo(2,2,3),source,8)))
            source.put(0,99.toByte())
            release.countDown()
            assertTrue(done.await(5,TimeUnit.SECONDS))
            assertTrue(errors.toString(),errors.isEmpty())
            assertEquals(listOf(1L,3L),timestamps)
            assertEquals(listOf(1.toByte(),42.toByte()),observed)
        } finally { release.countDown(); scheduler.close() }
    }

    @Test fun resetDropsPendingAndOldCompletion() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val scheduler = AsyncFaceAnalysis(FaceAnalyzerFactory { object : FaceAnalyzer {
            override val id = "reset-pending"
            override val capabilities = emptySet<FaceCapability>()
            override fun analyze(image: AnalysisImage): List<FaceObservation> {
                calls.incrementAndGet(); entered.countDown()
                check(release.await(5,TimeUnit.SECONDS)); return listOf(face)
            }
            override fun close() { closed.countDown() }
        } },keepLatestPending=true)
        try {
            scheduler.submit(frame(1)); assertTrue(entered.await(5,TimeUnit.SECONDS))
            scheduler.submit(frame(2)); scheduler.reset()
            assertNull(scheduler.latest(FrameInfo(2,2,3),100))
            scheduler.close(); release.countDown()
            assertTrue(closed.await(5,TimeUnit.SECONDS))
            assertEquals(1,calls.get())
            assertNull(scheduler.latest(FrameInfo(2,2,3),100))
        } finally { release.countDown(); scheduler.close() }
    }

    @Test fun publicSessionSharesSmallAnalysisWithLargeTextureWithoutInventingDimensions() {
        val done = CountDownLatch(1)
        var renderedFace: FaceObservation? = null
        val session = BeautySession(factory=FaceAnalyzerFactory { object : FaceAnalyzer {
            override val id = "camera-or-engine"
            override val capabilities = emptySet<FaceCapability>()
            override fun analyze(image: AnalysisImage): List<FaceObservation> {
                assertEquals(2,image.width); assertEquals(2,image.height)
                assertArrayEquals(byteArrayOf(1,2,3,5,6,7,9,10,11,13,14,15),image.pixels)
                return listOf(face)
            }
            override fun close() = Unit
        } }, renderer=object : BeautyRenderer {
            override fun render(frame: VideoFrame,settings: BeautySettings,face: FaceObservation?): VideoFrame {
                renderedFace = face; return frame
            }
            override fun close() = Unit
        },allowScaledAnalysis=true,onAnalysisTiming={done.countDown()})
        session.use {
            it.settings=BeautySettings(glasses=true)
            it.start(); assertTrue(it.submitAnalysis(frame(1)))
            assertTrue(done.await(5,TimeUnit.SECONDS))
            val matrix = FloatArray(16).apply { this[0]=1f;this[5]=1f;this[10]=1f;this[15]=1f }
            val texture = TextureFrame(FrameInfo(720,720,2),7,TextureTarget.TEXTURE_2D,matrix)
            assertSame(texture,it.process(texture)); assertSame(face,renderedFace)
            assertEquals(2,it.lastFrameAnalysis.result!!.info.width)
            assertNull(it.latestFaces(texture.info.copy(mirrored=true)))
            assertNull(it.latestFaces(texture.info.copy(width=1280,height=720)))
            it.stop(); assertNull(it.latestFaces(texture.info))
        }
    }
}

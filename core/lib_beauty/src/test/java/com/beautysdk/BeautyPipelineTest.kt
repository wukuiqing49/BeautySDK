package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BeautyPipelineTest {
    @Test fun analysisErrorAndNoFaceHaveDistinctDiagnosticStates() {
        val failed=CountDownLatch(1); val closed=CountDownLatch(1)
        val calls=AtomicInteger()
        val scheduler=AsyncFaceAnalysis(FaceAnalyzerFactory { object : FaceAnalyzer {
            override val id="test"
            override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image: AnalysisImage): List<FaceObservation> {
                if (calls.incrementAndGet()==1) error("fixture model unavailable")
                return emptyList()
            }
            override fun close() { closed.countDown() }
        } },onError={ failed.countDown() })
        try {
            scheduler.submit(frame(1)); assertTrue(failed.await(5,TimeUnit.SECONDS))
            val error=scheduler.snapshot(frame(1).info,100)
            assertEquals(FaceAnalysisState.ERROR,error.state)
            assertTrue(error.error!!.contains("fixture model unavailable"))
            assertNull(error.result)
            var time=2L; val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while (!scheduler.submit(frame(time))) {
                assertTrue(System.nanoTime()<deadline); time++; Thread.yield()
            }
            while (scheduler.latest(frame(time).info,100)==null && System.nanoTime()<deadline) Thread.yield()
            val recovered=scheduler.snapshot(frame(time).info,100)
            assertEquals(FaceAnalysisState.NO_FACE,recovered.state)
            assertNull(recovered.error); assertNotNull(recovered.durationNs)
            scheduler.reset(); assertNull(scheduler.snapshot(frame(time).info,100).error)
        } finally { scheduler.close(); assertTrue(closed.await(5,TimeUnit.SECONDS)) }
    }
    @Test fun hatTracksEveryOrientationAndMirrorAndHidesWithoutEyes() {
        for (rotation in listOf(0,90,180,270)) for (mirror in listOf(false,true)) {
            val info = FrameInfo(200,200,1,rotation,mirror)
            val orientation = AnalysisOrientation.upright(AnalysisImage(info,200,200,ByteArray(200*200*3)))
            val face = orientation.toRaw(FaceObservation(FaceBounds(.1f,.1f,.9f,.9f), mapOf(
                FaceAnchor.LEFT_EYE to FacePoint(.4f,.7f), FaceAnchor.RIGHT_EYE to FacePoint(.6f,.7f))))
            val hat = StickerGeometry.place(face,info).hat
            val crown = orientation.toRaw(FacePoint(.5f,.4f))
            val chin = orientation.toRaw(FacePoint(.5f,.9f))
            assertTrue("$rotation/$mirror",StickerGeometry.coversHat(crown.x,crown.y,hat,info,1f))
            assertFalse(StickerGeometry.coversHat(chin.x,chin.y,hat,info,1f))
            val input = RgbaFrame(info,ByteBuffer.allocate(200*200*4),800)
            val output = RgbaBeautyRenderer().render(input,BeautySettings(hat=true),face) as RgbaFrame
            val pixel = crown.y.times(200).toInt().coerceIn(0,199)*800 + crown.x.times(200).toInt().coerceIn(0,199)*4
            assertEquals(160,output.readOnlyPixels().get(pixel+2).toInt() and 255)
            assertEquals(0,input.readOnlyPixels().get(pixel+2).toInt())
        }
        assertNull(StickerGeometry.place(FaceObservation(FaceBounds(0f,0f,1f,1f)),FrameInfo(100,100,1)).hat)
        assertFalse(BeautySettings(hat=true).isIdentity)
    }
    @Test fun analysisSamplingAndTextureGeometryShareOneTimeline() {
        val session = BeautySession(
            factory = FaceAnalyzerFactory { object : FaceAnalyzer {
                override val id = "test"
                override val capabilities = setOf(FaceCapability.BOUNDS)
                override fun analyze(image: AnalysisImage) = listOf(FaceObservation(FaceBounds(0f,0f,1f,1f)))
                override fun close() = Unit
            } },
            renderer = object : BeautyRenderer {
                override fun render(frame: VideoFrame, settings: BeautySettings, face: FaceObservation?) = frame
                override fun close() = Unit
            },
            analysisIntervalNs = 100,
        )
        fun awaitResult(info: FrameInfo) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (session.latestFaces(info) == null && System.nanoTime() < deadline) Thread.yield()
            assertNotNull(session.latestFaces(info))
        }
        session.use {
            it.settings=BeautySettings(glasses=true)
            it.start()
            assertTrue(it.submitAnalysis(frame(10)))
            awaitResult(frame(10).info)
            assertFalse(it.submitAnalysis(frame(20)))
            // 新方向先提交分析，再渲染同时间戳的纹理，不能清掉刚产生的结果。
            var rotatedTime = 30L
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!it.submitAnalysis(frame(rotatedTime,90))) {
                assertTrue(System.nanoTime() < deadline)
                rotatedTime++
                Thread.yield()
            }
            awaitResult(frame(rotatedTime,90).info)
            val matrix = FloatArray(16).apply { this[0]=1f; this[5]=1f; this[10]=1f; this[15]=1f }
            it.process(TextureFrame(frame(rotatedTime,90).info,1,TextureTarget.TEXTURE_2D,matrix))
            assertNotNull(it.latestFaces(frame(rotatedTime,90).info))
            it.stop(); it.start()
            // 重启后不受上一路采样时间限制。
            var restartedTime = 1L
            val restartDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!it.submitAnalysis(frame(restartedTime))) {
                assertTrue(System.nanoTime() < restartDeadline)
                restartedTime++
                Thread.yield()
            }
        }
    }
    @Test fun rotationAndMirrorMapPixelCentersBackForEveryOrientation() {
        for (rotation in listOf(0,90,180,270)) for (mirror in listOf(false,true)) {
            val data = ByteArray(2*3*3) { (it / 3).toByte() }
            val oriented = AnalysisOrientation.upright(AnalysisImage(FrameInfo(2,3,0,rotation,mirror),2,3,data))
            val upright = oriented.image
            for (y in 0 until upright.height) for (x in 0 until upright.width) {
                val raw = oriented.toRaw(FacePoint((x+.5f)/upright.width,(y+.5f)/upright.height))
                val rx = (raw.x*2).toInt().coerceIn(0,1); val ry = (raw.y*3).toInt().coerceIn(0,2)
                assertEquals("$rotation / $mirror",data[(ry*2+rx)*3],upright.pixels[(y*upright.width+x)*3])
            }
        }
    }
    @Test fun reusableOrientationBufferMatchesOwnedPixelsAndDoesNotModifySource() {
        for (rotation in listOf(0,90,180,270)) for (mirror in listOf(false,true)) {
            val data = ByteArray(3*5*3) { it.toByte() }
            val saved = data.copyOf()
            val source = AnalysisImage(FrameInfo(3,5,10,rotation,mirror),3,5,data)
            val owned = AnalysisOrientation.upright(source)
            val scratch = ByteArray(data.size)
            val reused = AnalysisOrientation.upright(source,scratch)
            assertArrayEquals(owned.image.pixels,reused.image.pixels)
            assertArrayEquals(saved,source.pixels)
            assertEquals(owned.image.width,reused.image.width)
            assertEquals(owned.image.height,reused.image.height)
            if (rotation != 0 || mirror) {
                assertSame(scratch,reused.image.pixels)
                assertThrows(IllegalArgumentException::class.java) { AnalysisOrientation.upright(source,data) }
                assertThrows(IllegalArgumentException::class.java) { AnalysisOrientation.upright(source,ByteArray(1)) }
            }
            val point=FacePoint(.2f,.7f)
            assertEquals(owned.toRaw(point),reused.toRaw(point))
        }
    }
    private fun frame(time: Long = 1, rotation: Int = 0) = RgbaFrame(FrameInfo(2, 1, time, rotation, true),
        ByteBuffer.wrap(byteArrayOf(32,64,96,127, 48,80,112,-1, 9,9,9,9)), 12)

    @Test fun cpuBeautyPreservesAlphaMetadataAndInput() {
        val input = frame()
        val output = RgbaBeautyRenderer().render(input, BeautySettings(whitening = 1f), null) as RgbaFrame
        assertEquals(input.info, output.info)
        assertEquals(8, output.readOnlyPixels().remaining())
        assertTrue((output.readOnlyPixels().get(0).toInt() and 255) > 32)
        assertEquals(127, output.readOnlyPixels().get(3).toInt())
        assertEquals(-1, output.readOnlyPixels().get(7).toInt())
        assertEquals(32, input.readOnlyPixels().get(0).toInt())
    }
    @Test fun copyHandlesBufferPositionAndStride() {
        val bytes = byteArrayOf(99, 1,2,3,4, 5,6,7,8, 0,0,0,0, 9,10,11,12, 13,14,15,16)
        val buffer = ByteBuffer.wrap(bytes).apply { position(1) }
        val image = AnalysisImage.copyOf(RgbaFrame(FrameInfo(2,2,0), buffer, 12))
        assertArrayEquals(byteArrayOf(1,2,3,5,6,7,9,10,11,13,14,15), image.pixels)
        assertEquals(1, buffer.position())
    }
    @Test fun missingPreciseLipDoesNotInventMoustache() {
        val face = FaceObservation(FaceBounds(.1f,.1f,.9f,.9f), mapOf(
            FaceAnchor.LEFT_EYE to FacePoint(.3f,.4f), FaceAnchor.RIGHT_EYE to FacePoint(.7f,.5f),
            FaceAnchor.LEFT_MOUTH to FacePoint(.4f,.7f), FaceAnchor.RIGHT_MOUTH to FacePoint(.6f,.7f)))
        val placement = StickerGeometry.place(face, FrameInfo(100,200,0))
        assertNotNull(placement.glasses)
        assertNull(placement.moustache)
        assertEquals(kotlin.math.atan2(20f,40f), placement.glasses!!.angleRadians, .0001f)
    }
    @Test fun schedulerDropsBusyFramesAndRejectsLateResultsAfterReset() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1)
        val count = AtomicInteger()
        val scheduler = AsyncFaceAnalysis(FaceAnalyzerFactory { object : FaceAnalyzer {
            override val id = "test"; override val capabilities = emptySet<FaceCapability>()
            override fun analyze(image: AnalysisImage): List<FaceObservation> {
                count.incrementAndGet(); entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS))
                return listOf(FaceObservation(FaceBounds(0f,0f,1f,1f)))
            }
            override fun close() { closed.countDown() }
        } })
        try {
            assertTrue(scheduler.submit(frame(1)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(FaceAnalysisState.PROCESSING,scheduler.snapshot(frame(1).info,100).state)
            assertFalse(scheduler.submit(frame(2)))
            scheduler.reset()
            release.countDown()
            scheduler.close()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertNull(scheduler.latest(frame(3).info, 100))
            assertEquals(1, count.get())
        } finally { release.countDown(); scheduler.close() }
    }
    @Test fun resultExpiryAndGeometryAreChecked() {
        val done = CountDownLatch(1)
        val scheduler = AsyncFaceAnalysis(FaceAnalyzerFactory { object : FaceAnalyzer {
            override val id = "test"; override val capabilities = emptySet<FaceCapability>()
            override fun analyze(image: AnalysisImage): List<FaceObservation> {
                return listOf(FaceObservation(FaceBounds(0f,0f,1f,1f)))
            }
            override fun close() { done.countDown() }
        } })
        try {
            scheduler.submit(frame(10))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (scheduler.latest(frame(11).info,10) == null && System.nanoTime() < deadline) Thread.yield()
            assertNotNull(scheduler.latest(frame(20).info,10))
            assertEquals(FaceAnalysisState.READY,scheduler.snapshot(frame(20).info,10).state)
            assertEquals(FaceAnalysisState.STALE,scheduler.snapshot(frame(21).info,10).state)
            assertEquals(11L,scheduler.snapshot(frame(21).info,10).resultAgeNs)
            assertNull(scheduler.latest(frame(21).info,10))
            assertNull(scheduler.latest(frame(9).info,10))
            assertNull(scheduler.latest(frame(11,90).info,10))
        } finally { scheduler.close(); assertTrue(done.await(5,TimeUnit.SECONDS)) }
    }
    @Test fun sessionResetsTimeAndReportsRendererErrorsWithBypass() {
        val failures = AtomicInteger()
        val renderer = object : BeautyRenderer {
            override fun render(frame: VideoFrame, settings: BeautySettings, face: FaceObservation?): VideoFrame = error("render error")
            override fun close() = Unit
        }
        BeautySession(renderer = renderer, onError = { failures.incrementAndGet() }).use {
            it.start(); val input = frame(20); assertSame(input,it.process(input))
            it.stop(); it.start(); it.process(frame(1)); it.stop()
        }
        assertEquals(2,failures.get())
    }
}

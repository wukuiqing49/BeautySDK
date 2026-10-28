package com.beautysdk.demo

import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30 as GL
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class BeautyDeviceTest {
    @Test fun asyncCameraCadenceKeepsStickersThenHidesExpiredResults() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        val bitmap=instrumentation.context.assets.open("portrait.jpg").use { BitmapFactory.decodeStream(it) }!!
        val scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,240,300,true)
        val colors=IntArray(240*300)
        scaled.getPixels(colors,0,240,0,0,240,300)
        val bytes=ByteBuffer.allocate(240*300*4)
        colors.forEach { bytes.put((it ushr 16).toByte()).put((it ushr 8).toByte()).put(it.toByte()).put((it ushr 24).toByte()) }
        bytes.flip(); if (scaled !== bitmap) scaled.recycle(); bitmap.recycle()
        val sticker=app.assets.open("beauty_stickers/round_glasses.png").use { BitmapFactory.decodeStream(it) }!!
        val argb=IntArray(sticker.width*sticker.height)
        sticker.getPixels(argb,0,sticker.width,0,0,sticker.width,sticker.height)
        val settings=BeautySettings(smoothing=.4f,whitening=.3f,stickerPack=StickerPack(listOf(
            ImageSticker(StickerImage(sticker.width,sticker.height,argb),StickerAnchor.EYES))))
        sticker.recycle()
        val release=java.util.concurrent.CountDownLatch(1)
        val closed=java.util.concurrent.CountDownLatch(1)
        val factory=FaceAnalyzerFactory {
            val detector=MediaPipeFaceAnalyzer(app)
            object : FaceAnalyzer {
                var count=0
                override val id=detector.id
                override val capabilities=detector.capabilities
                override fun analyze(image: AnalysisImage): List<FaceObservation> {
                    if (++count > 1) assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS))
                    return detector.analyze(image)
                }
                override fun close() { try { detector.close() } finally { closed.countDown() } }
            }
        }
        fun frame(t: Long)=RgbaFrame(FrameInfo(240,300,t),bytes,240*4)
        val session=BeautySession(factory=factory,maxResultAgeNs=com.beautysdk.demo.feature.app.CameraBeautyController.MAX_RESULT_AGE_NS)
        try {
            session.settings=settings; session.start(); session.process(frame(1_000_000))
            val deadline=System.nanoTime()+10_000_000_000L
            while (session.latestFaces(frame(1_000_000).info)==null && System.nanoTime()<deadline) Thread.yield()
            assertEquals(1,session.latestFaces(frame(1_000_000).info)?.faces?.size)
            val started=System.nanoTime()
            val output=session.process(frame(351_000_000)) as RgbaFrame
            val elapsedMs=(System.nanoTime()-started)/1_000_000
            assertEquals(FaceAnalysisState.READY,session.lastFrameAnalysis.state)
            val plain=RgbaBeautyRenderer().render(frame(351_000_000),settings.copy(stickerPack=null),null) as RgbaFrame
            val actual=output.readOnlyPixels(); val base=plain.readOnlyPixels()
            assertTrue((0 until actual.remaining()).count { actual.get(it)!=base.get(it) }>100)
            session.process(frame(851_000_000))
            assertEquals(FaceAnalysisState.STALE,session.lastFrameAnalysis.state)
            assertNull(session.lastFrameAnalysis.result)
            instrumentation.sendStatus(0,android.os.Bundle().apply {
                putString("stream","\nCPU render 240x300 (single fixture sample): ${elapsedMs}ms; PNG active at 350ms age, hidden at 850ms.\n")
            })
        } finally { release.countDown(); session.close(); assertTrue(closed.await(10,java.util.concurrent.TimeUnit.SECONDS)) }
    }
    @Test fun demoLoadsAllSixPngAssetsAndCompositesActualPortrait() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        // 仅缓存项目公开测试人像；不用设备相机或用户相册，结束时删除本轮临时文件。
        val file = java.io.File.createTempFile("sticker-fixture-", ".jpg", app.cacheDir)
        val controller = com.beautysdk.demo.feature.app.BeautyDemoController(app)
        try {
            instrumentation.context.assets.open("portrait.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
            for ((index, style) in listOf(1 to 1,2 to 2,1 to 3).withIndex()) {
                val done = java.util.concurrent.CountDownLatch(1)
                val result = java.util.concurrent.atomic.AtomicReference<Result<com.beautysdk.demo.feature.app.DemoImage>>()
                controller.render(if (index==0) android.net.Uri.fromFile(file) else null,
                    com.beautysdk.demo.feature.app.DemoBackend.MEDIAPIPE,
                    com.beautysdk.demo.feature.app.DemoEffects(0f,0f,false,true,true,true,style.first,style.second)) {
                    result.set(it); done.countDown()
                }
                assertTrue(done.await(30,java.util.concurrent.TimeUnit.SECONDS))
                val rendered = result.get().getOrThrow()
                assertEquals(1,rendered.faceCount)
                val count = rendered.original.width*rendered.original.height
                val before = IntArray(count); val after = IntArray(count)
                rendered.original.getPixels(before,0,rendered.original.width,0,0,rendered.original.width,rendered.original.height)
                rendered.processed.getPixels(after,0,rendered.processed.width,0,0,rendered.processed.width,rendered.processed.height)
                assertTrue("Preset $style did not draw PNG pixels",before.indices.count { before[it] != after[it] } > 100)
                rendered.processed.recycle()
            }
        } finally { controller.close(); file.delete() }
    }
    @Test fun demoUiRendersImageAndFrameCheck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = android.content.Intent(instrumentation.targetContext, ImageLabActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ImageLabActivity
        try {
            instrumentation.runOnMainSync {
                activity.findViewById<android.view.View>(R.id.run_check).performClick()
                activity.findViewById<android.view.View>(R.id.apply_effects).performClick()
            }
            val deadline = System.nanoTime() + 15_000_000_000L
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync {
                    ready = activity.findViewById<android.widget.ImageView>(R.id.processed_image).drawable != null
                }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Demo output image was not rendered",ready)
            instrumentation.runOnMainSync {
                assertEquals(activity.getString(R.string.check_passed), activity.findViewById<android.widget.TextView>(R.id.result).text)
                assertTrue(activity.findViewById<android.view.View>(R.id.apply_effects).isEnabled)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun portrait(): AnalysisImage {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("portrait.jpg").use { BitmapFactory.decodeStream(it) }!!
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val rgb = ByteArray(pixels.size * 3)
            pixels.forEachIndexed { i, c -> rgb[i*3]=(c shr 16).toByte(); rgb[i*3+1]=(c shr 8).toByte(); rgb[i*3+2]=c.toByte() }
            return AnalysisImage(FrameInfo(bitmap.width,bitmap.height,1_000_000),bitmap.width,bitmap.height,rgb)
        } finally { bitmap.recycle() }
    }
    @Test fun nativeRgbaMatchesReferenceForAllOrientationsAndStride() {
        // 仅测试通过反射取得 internal 写入器，避免为了测试扩大 SDK 公共 API。
        val writer = Class.forName("com.beautysdk.mediapipe.NativeRgbaConverter")
            .getField("INSTANCE").get(null) as RgbPixelWriter
        val source = ByteBuffer.allocateDirect(23 * 152 + 7)
        for (i in 0 until source.capacity()) source.put(i,(i * 31).toByte())
        source.position(7)
        val native = ReusableRgbInput(16,writer)
        val reference = ReusableRgbInput(16)
        for (rotation in listOf(0,90,180,270)) for (mirror in listOf(false,true)) {
            val frame = RgbaFrame(FrameInfo(35,23,1,rotation,mirror),source,152)
            val expected = reference.prepare(frame)
            val actual = native.prepare(frame)
            assertTrue(writer.write(frame,actual,native.width,native.height))
            assertEquals(expected,actual)
            assertSame(actual,native.prepare(frame))
            assertEquals(7,source.position())
            // 容量不足必须返回 false，不能越界写入。
            assertFalse(writer.write(frame,ByteBuffer.allocateDirect(1),16,11))
        }
    }

    @Test fun mediaPipeDetectsActualPortraitAndReleases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MediaPipeFaceAnalyzer(context).use { analyzer ->
            val image = portrait()
            val rgba = ByteBuffer.allocateDirect(image.width * image.height * 4)
            for (i in image.pixels.indices step 3) {
                rgba.put(image.pixels[i]).put(image.pixels[i+1]).put(image.pixels[i+2]).put(255.toByte())
            }
            rgba.flip()
            // 直接 RGBA 输入连续复用原生缓冲；与旧 RGB 接口交替调用验证生命周期。
            repeat(3) { index ->
                val info = image.info.copy(width=image.width,height=image.height,timestampNs=(index+1)*33_000_000L)
                val faces = if (index % 2 == 0) analyzer.analyzeRgba(RgbaFrame(info,rgba,image.width*4))
                    else analyzer.analyze(AnalysisImage(info,image.width,image.height,image.pixels))
                val face = faces.single()
                assertEquals(478,face.landmarks.size)
                assertEquals(6,face.anchors.size)
                assertNotNull(StickerGeometry.place(face,FrameInfo(820,1024,0)).glasses)
                assertNotNull(StickerGeometry.place(face,FrameInfo(820,1024,0)).moustache)
                assertTrue(analyzer.lastPreparationNs >= 0)
                assertTrue(analyzer.lastInferenceNs > 0)
            }
        }
    }
    @Test fun glMatchesCpuAndRestoresHostState() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display,versions,0,versions,1))
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0))
        val context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
        val surface = EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,16,EGL14.EGL_HEIGHT,16,EGL14.EGL_NONE),0)
        check(EGL14.eglMakeCurrent(display,surface,surface,context))
        val ids = IntArray(1)
        try {
            val pixels = ByteArray(16*16*4) { i -> if (i % 4 == 3) 127 else (40 + (i % 3) * 30).toByte() }
            val data = ByteBuffer.allocateDirect(pixels.size).put(pixels).apply { flip() }
            GL.glGenTextures(1,ids,0); val inputTexture = ids[0]
            GL.glBindTexture(GL.GL_TEXTURE_2D,inputTexture)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,16,16,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
            val info = FrameInfo(16,16,100,90,true)
            val identity = floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
            val input = TextureFrame(info,inputTexture,TextureTarget.TEXTURE_2D,identity)
            // 输出租用池：池满不等待；输入被覆盖后已租用副本仍保持原像素。
            val pool = GlTextureOutputPool(1)
            val leased = checkNotNull(pool.copy(input))
            assertNull(pool.copy(input))
            assertThrows(IllegalStateException::class.java) { pool.close() }
            GL.glBindTexture(GL.GL_TEXTURE_2D,inputTexture)
            GL.glTexSubImage2D(GL.GL_TEXTURE_2D,0,0,0,16,16,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,
                ByteBuffer.allocateDirect(pixels.size))
            val readFbo = IntArray(1); GL.glGenFramebuffers(1,readFbo,0)
            GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER,readFbo[0])
            GL.glFramebufferTexture2D(GL.GL_READ_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,leased.frame.textureId,0)
            val copied = ByteBuffer.allocateDirect(pixels.size)
            GL.glReadPixels(0,0,16,16,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,copied)
            val copiedBytes = ByteArray(pixels.size); copied.get(copiedBytes)
            assertArrayEquals(pixels,copiedBytes)
            GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER,0)
            GL.glDeleteFramebuffers(1,readFbo,0)
            Thread { leased.close(); leased.close() }.apply { start(); join() }
            checkNotNull(pool.copy(input)).close()
            pool.close()
            GL.glBindTexture(GL.GL_TEXTURE_2D,inputTexture)
            data.position(0)
            GL.glTexSubImage2D(GL.GL_TEXTURE_2D,0,0,0,16,16,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
            val procedural = BeautySettings(.4f,.6f,BeautyFilter.WARM,true,true,hat=true)
            fun art(name: String, anchor: StickerAnchor = StickerAnchor.EYES, pivotY: Float = .5f): ImageSticker {
                val app = InstrumentationRegistry.getInstrumentation().targetContext
                val bitmap = app.assets.open("beauty_stickers/$name.png").use { BitmapFactory.decodeStream(it) }!!
                try {
                    val argb = IntArray(bitmap.width*bitmap.height)
                    bitmap.getPixels(argb,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    return ImageSticker(StickerImage(bitmap.width,bitmap.height,argb),anchor,2.5f,.5f,pivotY)
                } finally { bitmap.recycle() }
            }
            val packs = listOf(
                StickerPack(listOf(art("round_glasses"),art("curled_moustache",StickerAnchor.UPPER_LIP),art("blue_top_hat",pivotY=1.45f))),
                StickerPack(listOf(art("heart_glasses"),art("gold_crown",pivotY=1.45f))),
                StickerPack(listOf(art("cat_ears",pivotY=1.45f))),
            )
            val editedPack=StickerPack(packs.first().layers.map { it.copy(offsetX=.2f,offsetY=-.15f,rotationDegrees=15f) })
            val cases = listOf(procedural,BeautySettings(stickerPack=editedPack)) + packs.map { BeautySettings(stickerPack=it) } +
                listOf(BeautySettings(whitening=.2f))
            val face = FaceObservation(FaceBounds(0f,0f,1f,1f),mapOf(
                FaceAnchor.LEFT_EYE to FacePoint(.3f,.4f),FaceAnchor.RIGHT_EYE to FacePoint(.7f,.4f),
                FaceAnchor.LEFT_MOUTH to FacePoint(.4f,.7f),FaceAnchor.RIGHT_MOUTH to FacePoint(.6f,.7f),
                FaceAnchor.UPPER_LIP to FacePoint(.5f,.65f)))
            GlBeautyRenderer().use { renderer ->
              for (effects in cases) {
                for (unit in 1..3) {
                    GL.glActiveTexture(GL.GL_TEXTURE0+unit)
                    GL.glBindTexture(GL.GL_TEXTURE_2D,inputTexture)
                }
                GL.glPixelStorei(GL.GL_UNPACK_ROW_LENGTH,32)
                GL.glPixelStorei(GL.GL_UNPACK_SKIP_ROWS,2)
                GL.glViewport(1,2,7,9); GL.glEnable(GL.GL_SCISSOR_TEST)
                GL.glColorMask(false,true,false,true); GL.glActiveTexture(GL.GL_TEXTURE3)
                val output = renderer.render(input,effects,face) as TextureFrame
                assertEquals(info,output.info)
                val viewport = IntArray(4); GL.glGetIntegerv(GL.GL_VIEWPORT,viewport,0)
                assertArrayEquals(intArrayOf(1,2,7,9),viewport)
                assertTrue(GL.glIsEnabled(GL.GL_SCISSOR_TEST))
                val active = IntArray(1); GL.glGetIntegerv(GL.GL_ACTIVE_TEXTURE,active,0)
                assertEquals(GL.GL_TEXTURE3,active[0])
                val restored = IntArray(1)
                GL.glGetIntegerv(GL.GL_UNPACK_ROW_LENGTH,restored,0); assertEquals(32,restored[0])
                GL.glGetIntegerv(GL.GL_UNPACK_SKIP_ROWS,restored,0); assertEquals(2,restored[0])
                for (unit in 1..3) {
                    GL.glActiveTexture(GL.GL_TEXTURE0+unit)
                    GL.glGetIntegerv(GL.GL_TEXTURE_BINDING_2D,restored,0)
                    assertEquals(inputTexture,restored[0])
                }
                val mask = BooleanArray(4); GL.glGetBooleanv(GL.GL_COLOR_WRITEMASK,mask,0)
                assertTrue(mask.contentEquals(booleanArrayOf(false,true,false,true)))
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                GL.glGenFramebuffers(1,ids,0); val fbo = ids[0]
                try {
                    GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,fbo)
                    GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,output.textureId,0)
                    val actual = ByteBuffer.allocateDirect(pixels.size)
                    GL.glReadPixels(0,0,16,16,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,actual)
                    val expected = (RgbaBeautyRenderer().render(RgbaFrame(info,ByteBuffer.wrap(pixels),64),effects,face) as RgbaFrame).readOnlyPixels()
                    // 确认礼帽确实覆盖像素，避免只比较了未命中挂件的背景。
                    if (effects.hat) assertTrue((0 until 256).any { (expected.get(it*4).toInt() and 255) == 48 &&
                        (expected.get(it*4+2).toInt() and 255) == 160 })
                    if (effects.stickerPack != null) assertTrue((0 until 256).any {
                        (expected.get(it*4+3).toInt() and 255) > 127 })
                    for (i in pixels.indices) assertTrue("case ${cases.indexOf(effects)} channel $i: ${actual.get(i).toInt() and 255} vs ${expected.get(i).toInt() and 255}",
                        kotlin.math.abs((actual.get(i).toInt() and 255)-(expected.get(i).toInt() and 255)) <= 2)
                } finally { GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0); GL.glDeleteFramebuffers(1,intArrayOf(fbo),0) }
              }
            }
            GL.glDeleteTextures(1,intArrayOf(inputTexture),0)
        } finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display)
        }
    }
}

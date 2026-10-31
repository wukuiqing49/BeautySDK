package com.beautysdk.demo

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30 as GL
import android.view.Surface
import com.beautysdk.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** 纯GPU固定图案测试，不采集/保存摄像头画面；glFinish仅用于测试制造确定的完成条件。 */
class TextureBridgeDeviceTest {
    @Test fun workerOwnsFrameWhileMappedPboSlotsAreReused()=withGl {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val observed=java.util.concurrent.LinkedBlockingQueue<Int>()
        val runtime=BeautyAnalysisRuntime(FaceAnalyzerFactory{object:FaceAnalyzer {
            override val id="borrowed-pbo-ownership"
            override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage):List<FaceObservation> = error("RGBA required")
            override fun analyzeRgba(frame:RgbaFrame,maxSide:Int):List<FaceObservation> {
                if(frame.info.timestampNs==1L){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
                observed.add(frame.readOnlyPixels().get(0).toInt() and 255)
                return emptyList()
            }
            override fun close()=Unit
        }},intervalNs=0,allowScaledFrames=true)
        val ids=IntArray(1);GL.glGenTextures(1,ids,0);GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
        val bridge=GlTextureAnalysisBridge(runtime,maxSide=16,intervalNs=0)
        fun deliver(t:Long,value:Int) {
            val pixels=ByteBuffer.allocateDirect(32*16*4)
            repeat(32*16){pixels.put(value.toByte()).put(0).put(0).put(255.toByte())};pixels.flip()
            GL.glActiveTexture(GL.GL_TEXTURE0);GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,32,16,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,pixels)
            bridge.submit(TextureFrame(FrameInfo(32,16,t),ids[0],TextureTarget.TEXTURE_2D,identity),true)
            GL.glFinish();assertTrue(bridge.poll(t+1))
        }
        try {
            deliver(1,71);assertTrue(entered.await(5,TimeUnit.SECONDS))
            // 模型仍在读取首帧时，多次复用PBO及可替换待处理槽。
            deliver(10,81);deliver(20,91);deliver(30,99)
            release.countDown()
            assertEquals(71,observed.poll(5,TimeUnit.SECONDS))
            assertEquals(99,observed.poll(5,TimeUnit.SECONDS))
            assertNull(observed.poll(30,TimeUnit.MILLISECONDS))
        } finally {release.countDown();bridge.close();runtime.close();GL.glDeleteTextures(1,ids,0)}
    }
    @Test fun borrowedPboFailureUnmapsAndLaterSamplesRecover()=withGl {
        val ids=IntArray(1);GL.glGenTextures(1,ids,0);GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
        val pixels=ByteBuffer.allocateDirect(32*16*4)
        repeat(32*16){pixels.put(71).put(82).put(93).put(255.toByte())};pixels.flip()
        GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,32,16,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,pixels)
        var calls=0
        val bridge=GlTextureAnalysisBridge(maxSide=16,intervalNs=0,onAnalysisFrame={frame,_->
            calls++
            assertTrue(frame.readOnlyPixels().isReadOnly)
            assertEquals(71,frame.readOnlyPixels().get(0).toInt() and 255)
            if(calls==1)throw IllegalStateException("fixture consumer failure")
            true
        })
        try {
            bridge.submit(TextureFrame(FrameInfo(32,16,1),ids[0],TextureTarget.TEXTURE_2D,identity),true);GL.glFinish()
            try {bridge.poll(2);fail("Consumer failure must propagate")}catch(e:IllegalStateException){assertEquals("fixture consumer failure",e.message)}
            repeat(3){i->
                val t=10L+i*10
                bridge.submit(TextureFrame(FrameInfo(32,16,t),ids[0],TextureTarget.TEXTURE_2D,identity),true);GL.glFinish()
                assertTrue(bridge.poll(t+1))
            }
            assertEquals(4,calls);assertEquals(GL.GL_NO_ERROR,GL.glGetError())
        } finally {bridge.close();GL.glDeleteTextures(1,ids,0)}
    }
    @Test fun completedSmallImagePollAvoidsExtraCapturePeriod()=withGl {
        val ids=IntArray(1);GL.glGenTextures(1,ids,0);GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
        GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,640,360,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(640*360*4))
        fun run(early:Boolean):List<Double> {
            val ages=mutableListOf<Double>()
            val bridge=GlTextureAnalysisBridge(maxSide=320,intervalNs=0,onAnalysisFrame={frame,_->ages.add((System.nanoTime()-frame.info.timestampNs)/1e6);true})
            try {
                repeat(16) {
                    val start=System.nanoTime()
                    bridge.submit(TextureFrame(FrameInfo(640,360,start),ids[0],TextureTarget.TEXTURE_2D,identity),true)
                    if(early){Thread.sleep(8);bridge.poll(System.nanoTime())}
                    val remaining=33_333_333L-(System.nanoTime()-start)
                    if(remaining>0)Thread.sleep(remaining/1_000_000,(remaining%1_000_000).toInt())
                }
                bridge.poll(System.nanoTime())
                return ages.drop(2)
            } finally {bridge.close()}
        }
        try {
            val normal=run(false);val early=run(true)
            assertTrue(normal.size>=10);assertTrue(early.size>=10)
            fun median(values:List<Double>)=values.sorted()[values.size/2]
            assertTrue("Early delivery must avoid next-frame wait on this fixture",median(early)<median(normal))
            val message=listOf("next_frame" to normal,"early_poll" to early).joinToString("\n"){(mode,values)->
                val sorted=values.sorted();"READBACK mode=$mode samples=${values.size} meanMs=${values.average()} p50Ms=${median(values)} p95Ms=${sorted[((sorted.size-1)*.95).toInt()]}"
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","\n$message\n")})
        } finally {GL.glDeleteTextures(1,ids,0)}
    }
    @Test fun earlyPollDeliversWithoutNextVideoFrameAndKeepsTimestamp()=withGl {
        val ids=IntArray(1);GL.glGenTextures(1,ids,0);GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
        GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,32,16,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(32*16*4))
        val delivered=mutableListOf<FrameInfo>()
        val bridge=GlTextureAnalysisBridge(maxSide=16,intervalNs=0,onAnalysisFrame={frame,_->delivered.add(frame.info);true})
        fun frame(t:Long)=TextureFrame(FrameInfo(32,16,t,90,true),ids[0],TextureTarget.TEXTURE_2D,identity)
        try {
            assertFalse(bridge.submit(frame(1),true));GL.glFinish() // 仅夹具保证GPU完成。
            GL.glViewport(2,3,7,9);GL.glActiveTexture(GL.GL_TEXTURE3)
            assertTrue("No second video frame is needed to deliver",bridge.poll(8_000_001))
            assertEquals(listOf(FrameInfo(16,8,1,90,true)),delivered)
            assertFalse("Polling must not sample again",bridge.poll(9_000_001))
            val value=IntArray(1);GL.glGetIntegerv(GL.GL_ACTIVE_TEXTURE,value,0);assertEquals(GL.GL_TEXTURE3,value[0])
            val viewport=IntArray(4);GL.glGetIntegerv(GL.GL_VIEWPORT,viewport,0);assertArrayEquals(intArrayOf(2,3,7,9),viewport)
            bridge.submit(frame(40_000_001),true);GL.glFinish();bridge.reset()
            assertFalse("Reset must discard queued sample",bridge.poll(48_000_001))
            bridge.submit(frame(80_000_001),true);GL.glFinish()
            assertFalse("Readback expiration still applies",bridge.poll(190_000_001))
            bridge.submit(frame(220_000_001),true);GL.glFinish();bridge.submit(frame(220_000_002),false)
            assertFalse("Disabled bridge cannot deliver old sample",bridge.poll(228_000_001))
            assertEquals(1,delivered.size)
            // 实机30fps时间戳间隔可略低于33,333,333ns，Demo显式0间隔不得隔帧采样。
            repeat(3){i->
                val time=1_000_000_000L+i*33_327_109L
                bridge.submit(frame(time),true);GL.glFinish()
                assertTrue(bridge.poll(time+8_000_000L))
                assertEquals(time,delivered.last().timestampNs)
            }
            assertEquals(4,delivered.size)
        } finally {bridge.close();GL.glDeleteTextures(1,ids,0)}
    }
    private val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
    private fun withGl(block: () -> Unit) {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val versions=IntArray(2); check(EGL14.eglInitialize(display,versions,0,versions,1))
        val configs=arrayOfNulls<EGLConfig>(1); val count=IntArray(1)
        check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,count,0))
        val context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,32,EGL14.EGL_HEIGHT,16,EGL14.EGL_NONE),0)
        check(EGL14.eglMakeCurrent(display,surface,surface,context))
        try { block() } finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display)
        }
    }
    private class Capture {
        var bytes=byteArrayOf()
        var info: FrameInfo?=null
        val done=CountDownLatch(1)
        val runtime=BeautyAnalysisRuntime(FaceAnalyzerFactory { object : FaceAnalyzer {
            override val id="texture-bridge-fixture"
            override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image: AnalysisImage): List<FaceObservation> = error("Expected RGBA")
            override fun analyzeRgba(frame: RgbaFrame,maxSide: Int): List<FaceObservation> {
                info=frame.info; bytes=ByteArray(frame.readOnlyPixels().remaining()).also { frame.readOnlyPixels().get(it) }
                return listOf(FaceObservation(FaceBounds(.1f,.1f,.9f,.9f)))
            }
            override fun close() { }
        } },allowScaledFrames=true,onTiming={ done.countDown() })
    }
    @Test fun texture2dDownsamplesTransformsAndRestoresState() = withGl {
        val ids=IntArray(1); GL.glGenTextures(1,ids,0)
        val data=ByteBuffer.allocateDirect(32*16*4)
        repeat(16) { y -> repeat(32) { x -> data.put((if(x<16)200 else 0).toByte()).put((if(y<8)180 else 0).toByte()).put((if(x>=16)100 else 0).toByte()).put(255.toByte()) } }; data.flip()
        GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0]); GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,32,16,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
        val flip=identity.copyOf().apply { this[0]=-1f; this[12]=1f }
        val capture=Capture(); val bridge=GlTextureAnalysisBridge(capture.runtime,maxSide=16,intervalNs=0)
        fun frame(t: Long)=TextureFrame(FrameInfo(32,16,t,90,true),ids[0],TextureTarget.TEXTURE_2D,flip)
        try {
            assertFalse(bridge.submit(frame(1),false)); assertFalse(bridge.submit(frame(2),true))
            GL.glFinish()
            GL.glViewport(2,3,7,9); GL.glEnable(GL.GL_SCISSOR_TEST)
            GL.glPixelStorei(GL.GL_PACK_ROW_LENGTH,47); GL.glPixelStorei(GL.GL_PACK_SKIP_ROWS,2)
            GL.glActiveTexture(GL.GL_TEXTURE3)
            assertTrue(bridge.submit(frame(3),true))
            assertTrue(capture.done.await(5,TimeUnit.SECONDS))
            assertEquals(FrameInfo(16,8,2,90,true),capture.info)
            fun pixel(x:Int,y:Int,c:Int)=capture.bytes[(y*16+x)*4+c].toInt() and 255
            assertEquals(100,pixel(0,0,2)); assertEquals(180,pixel(0,0,1))
            assertEquals(200,pixel(15,7,0)); assertEquals(0,pixel(15,7,1))
            val value=IntArray(1)
            GL.glGetIntegerv(GL.GL_ACTIVE_TEXTURE,value,0); assertEquals(GL.GL_TEXTURE3,value[0])
            GL.glGetIntegerv(GL.GL_PACK_ROW_LENGTH,value,0); assertEquals(47,value[0])
            GL.glGetIntegerv(GL.GL_PACK_SKIP_ROWS,value,0); assertEquals(2,value[0])
            assertTrue(GL.glIsEnabled(GL.GL_SCISSOR_TEST))
            val view=IntArray(4); GL.glGetIntegerv(GL.GL_VIEWPORT,view,0); assertArrayEquals(intArrayOf(2,3,7,9),view)
            val session=BeautySession(renderer=GlBeautyRenderer(),sharedAnalysis=capture.runtime,allowScaledAnalysis=true)
            try {
                session.settings=BeautySettings(glasses=true); session.start()
                assertTrue(session.process(frame(4)) is TextureFrame)
                assertEquals(FaceAnalysisState.READY,session.lastFrameAnalysis.state)
                assertEquals(2L,session.lastFrameAnalysis.result?.info?.timestampNs)
            } finally { session.close() }
            bridge.reset(); assertNull(capture.runtime.snapshot(frame(4).info,100).result)
            assertFalse(bridge.submit(frame(5),true)); GL.glFinish()
            assertFalse(bridge.submit(frame(6),false))
            assertEquals(FaceAnalysisState.DISABLED,capture.runtime.snapshot(frame(6).info,100).state)
            assertFalse(bridge.submit(frame(7),true)) // 不交付关闭前排队的第5帧。
        } finally { bridge.close(); capture.runtime.close(); GL.glDeleteTextures(1,ids,0) }
    }
    @Test fun staleAndGeometryChangedReadbacksAreDiscarded() = withGl {
        val ids=IntArray(1); GL.glGenTextures(1,ids,0)
        GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
        GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,32,16,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(32*16*4))
        val capture=Capture(); val bridge=GlTextureAnalysisBridge(capture.runtime,maxSide=16,intervalNs=100,maxReadbackAgeNs=10)
        fun frame(t:Long,rotation:Int=0)=TextureFrame(FrameInfo(32,16,t,rotation),ids[0],TextureTarget.TEXTURE_2D,identity)
        try {
            assertFalse(bridge.submit(frame(1),true)); GL.glFinish()
            assertFalse(bridge.submit(frame(1),true)) // 重复采样时间不映射、不重复提交。
            assertFalse(bridge.submit(frame(20),true)) // 完成但过期，且限频不重新采样。
            assertFalse(bridge.submit(frame(101),true)); GL.glFinish()
            assertFalse(bridge.submit(frame(102,90),true)) // 转向使101帧作废。
            GL.glFinish()
            assertTrue(bridge.submit(frame(103,90),true))
            assertTrue(capture.done.await(5,TimeUnit.SECONDS))
            assertEquals(FrameInfo(16,8,102,90),capture.info)
        } finally { bridge.close(); capture.runtime.close(); GL.glDeleteTextures(1,ids,0) }
    }
    @Test fun unifiedEngineCallbackProcessesAndWritesTexture() = withGl {
        val ids=IntArray(1); GL.glGenTextures(1,ids,0)
        GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
        val pixels=ByteBuffer.allocateDirect(32*16*4)
        repeat(32*16) { pixels.put(20).put(30).put(40).put(255.toByte()) }; pixels.flip()
        GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,32,16,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,pixels)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
        val analyzed=CountDownLatch(1)
        val pipeline=BeautyEnginePipeline(factory=FaceAnalyzerFactory { object:FaceAnalyzer {
            override val id="engine-texture-fixture"
            override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image:AnalysisImage):List<FaceObservation> = error("Expected RGBA")
            override fun analyzeRgba(frame:RgbaFrame,maxSide:Int):List<FaceObservation> {
                analyzed.countDown(); return listOf(FaceObservation(FaceBounds(.1f,.1f,.9f,.9f)))
            }
            override fun close() { }
        } },analysisMaxSide=16,analysisIntervalNs=0)
        pipeline.settings=BeautySettings(whitening=1f,glasses=true)
        var writes=0
        var output:TextureFrame?=null
        val adapter=object:BeautyEngineFrameAdapter<TextureFrame> {
            override fun read(frame:TextureFrame)=frame
            override fun write(frame:TextureFrame,result:VideoFrame):Boolean { writes++; output=result as TextureFrame; return true }
            override fun passthrough(frame:TextureFrame):Boolean = error("Unexpected bypass")
        }
        val connector=BeautyEngineConnector(adapter,pipeline)
        fun frame(t:Long)=TextureFrame(FrameInfo(32,16,t),ids[0],TextureTarget.TEXTURE_2D,identity)
        try {
            connector.start()
            assertEquals(BeautyEngineFrameResult.PROCESSED,connector.onFrame(frame(1)))
            GL.glFinish()
            assertEquals(BeautyEngineFrameResult.PROCESSED,connector.onFrame(frame(2)))
            assertTrue(analyzed.await(5,TimeUnit.SECONDS))
            var time=3L
            val deadline=System.nanoTime()+5_000_000_000L
            while(pipeline.lastFrameAnalysis.state!=FaceAnalysisState.READY && System.nanoTime()<deadline) {
                connector.onFrame(frame(time++)); Thread.yield()
            }
            assertEquals(FaceAnalysisState.READY,pipeline.lastFrameAnalysis.state)
            assertTrue(writes>=2)
            val framebuffer=IntArray(1); GL.glGenFramebuffers(1,framebuffer,0)
            GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER,framebuffer[0])
            GL.glFramebufferTexture2D(GL.GL_READ_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,checkNotNull(output).textureId,0)
            val result=ByteBuffer.allocateDirect(4)
            GL.glReadPixels(0,0,1,1,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,result)
            assertTrue(kotlin.math.abs((result.get(0).toInt() and 255)-62)<=1)
            GL.glBindFramebuffer(GL.GL_READ_FRAMEBUFFER,0); GL.glDeleteFramebuffers(1,framebuffer,0)
            connector.resetSource(); assertTrue(connector.onFrame(frame(1)).accepted)
        } finally { connector.close(); GL.glDeleteTextures(1,ids,0) }
    }
    @Test fun oesSurfaceInputCanGenerateDetectionSmallImage() = withGl {
        val ids=IntArray(1); GL.glGenTextures(1,ids,0)
        GL.glBindTexture(0x8D65,ids[0])
        val producer=SurfaceTexture(ids[0]); producer.setDefaultBufferSize(32,16)
        val surface=Surface(producer)
        val capture=Capture(); val bridge=GlTextureAnalysisBridge(capture.runtime,maxSide=16,intervalNs=0)
        try {
            val canvas=surface.lockCanvas(null); canvas.drawColor(Color.RED); surface.unlockCanvasAndPost(canvas)
            producer.updateTexImage()
            val matrix=FloatArray(16); producer.getTransformMatrix(matrix)
            fun frame(t:Long)=TextureFrame(FrameInfo(32,16,t),ids[0],TextureTarget.EXTERNAL_OES,matrix)
            assertFalse(bridge.submit(frame(10),true)); GL.glFinish()
            assertTrue(bridge.submit(frame(11),true))
            assertTrue(capture.done.await(5,TimeUnit.SECONDS))
            assertEquals(FrameInfo(16,8,10),capture.info)
            assertEquals(255,capture.bytes[0].toInt() and 255)
            assertEquals(0,capture.bytes[1].toInt() and 255)
            assertEquals(0,capture.bytes[2].toInt() and 255)
            assertEquals(GL.GL_NO_ERROR,GL.glGetError())
        } finally { bridge.close(); capture.runtime.close(); surface.release(); producer.release(); GL.glDeleteTextures(1,ids,0) }
    }
}

package com.beautysdk.demo
import android.opengl.EGL14
import android.opengl.GLES30 as GL
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeForegroundMasks
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** 固定纹理和公开测试人像，不保存相机图像。 */
class SceneDeviceTest {
    /** 公开固定人像走实际GL融合，输出仅用于人工检查静态轮廓，不是实时相机截图。 */
    @Test fun publicPortraitExportsActualGlCompositeForEdgeReview() = withEgl {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
        val original=inst.context.assets.open("portrait.jpg").use{android.graphics.BitmapFactory.decodeStream(it)}!!
        val image=android.graphics.Bitmap.createScaledBitmap(original,192,240,true)
        val colors=IntArray(192*240);image.getPixels(colors,0,192,0,0,192,240)
        if(image!==original)image.recycle();original.recycle()
        val rgb=ByteArray(colors.size*3);val rgba=ByteBuffer.allocateDirect(colors.size*4)
        colors.forEachIndexed{i,c->rgb[i*3]=(c ushr 16).toByte();rgb[i*3+1]=(c ushr 8).toByte();rgb[i*3+2]=c.toByte()
            rgba.put(rgb[i*3]).put(rgb[i*3+1]).put(rgb[i*3+2]).put(255.toByte())};rgba.flip()
        val ids=IntArray(1);GL.glGenTextures(1,ids,0)
        try {
            GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0]);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,192,240,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,rgba)
            MediaPipeForegroundMasks(app,MediaPipeForegroundMasks.Execution.GPU).use{masks->
                val info=FrameInfo(192,240,1_000_000)
                masks.setEnabled(true);masks.submit(AnalysisImage(info,192,240,rgb))
                val end=System.nanoTime()+15_000_000_000L
                while(masks.snapshot(info)==null&&System.nanoTime()<end)Thread.sleep(5)
                assertNotNull(masks.snapshot(info))
                BeautySceneRenderer(GlBeautyRenderer(),masks).use{renderer->
                    val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
                    val frame=TextureFrame(info,ids[0],TextureTarget.TEXTURE_2D,identity)
                    val bytes=read(renderer.render(frame,BeautySettings(scene=BeautyScene(background=BackgroundMode.COLOR,
                        backgroundColor=0xff304050.toInt())),null) as TextureFrame)
                    val output=IntArray(colors.size){i->0xff000000.toInt() or((bytes[i*4].toInt() and 255) shl 16)or
                        ((bytes[i*4+1].toInt() and 255) shl 8)or(bytes[i*4+2].toInt() and 255)}
                    val bitmap=android.graphics.Bitmap.createBitmap(output,192,240,android.graphics.Bitmap.Config.ARGB_8888)
                    java.io.File(app.cacheDir,"portrait-scene-gl-public.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle();assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                }
            }
        }finally{GL.glDeleteTextures(1,ids,0)}
    }
    /** 半透明轮廓应保留连续置信度，不应被窄阈值压成接近不透明。 */
    @Test fun uncertainEdgesKeepSoftAlphaAndMaskUpdatesReachSameTexture() = withEgl {
        val id=IntArray(1);GL.glGenTextures(1,id,0)
        try {
            GL.glBindTexture(GL.GL_TEXTURE_2D,id[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            val pixels=ByteBuffer.allocateDirect(64*64*4)
            repeat(64*64){pixels.put(255.toByte()).put(0).put(0).put(173.toByte())};pixels.flip()
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,64,64,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,pixels)
            val info=FrameInfo(64,64,0)
            var mask=ForegroundMask(info,16,16,ByteArray(256){128.toByte()})
            val transform=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
            BeautySceneRenderer(GlBeautyRenderer(),ForegroundMaskProvider{mask}).use{renderer->
                val frame=TextureFrame(info,id[0],TextureTarget.TEXTURE_2D,transform)
                val settings=BeautySettings(scene=BeautyScene(background=BackgroundMode.COLOR,backgroundColor=0xff00ff00.toInt()))
                val first=renderer.render(frame,settings,null) as TextureFrame
                val bytes=read(first);assertTrue((bytes[0].toInt() and 255) in 120..140)
                assertTrue((bytes[1].toInt() and 255) in 115..135);assertEquals(173,bytes[3].toInt() and 255)
                mask=ForegroundMask(info,16,16,ByteArray(256))
                val second=renderer.render(frame,settings,null) as TextureFrame
                assertEquals(first.textureId,second.textureId)
                val changed=read(second);assertEquals(0,changed[0].toInt() and 255);assertEquals(255,changed[1].toInt() and 255)
                // 校正由GL采样应用：移出旧轮廓后必须露出背景，不能clamp复制旧前景。
                mask=ForegroundMask(info,16,16,ByteBuffer.wrap(ByteArray(256){255.toByte()}),info.timestampNs,.25f,0f)
                val shifted=read(renderer.render(frame,settings,null) as TextureFrame)
                assertEquals(0,shifted[(32*64+4)*4].toInt() and 255)
                assertEquals(255,shifted[(32*64+4)*4+1].toInt() and 255)
                assertEquals(255,shifted[(32*64+40)*4].toInt() and 255)
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        }finally{GL.glDeleteTextures(1,id,0)}
    }

    /** 高频原图轮廓配低分辨率蒙版：验证放大漏边与虚化吸入前景，不使用真人图像。 */
    @Test fun lowResolutionMaskKeepsColorEdgeAndBlurDoesNotBleedForeground() = withEgl {
        val ids=IntArray(1);GL.glGenTextures(1,ids,0)
        try {
            GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            val data=ByteBuffer.allocateDirect(64*64*4)
            for(y in 0..63)for(x in 0..63) {
                data.put(if(x<32)255.toByte() else 0).put(0)
                    .put(if(x>=32)255.toByte() else 0).put(173.toByte())
            }
            data.flip();GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,64,64,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
            val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
            for(rotation in listOf(0,90,180,270))for(mirrored in listOf(false,true)) {
                val info=FrameInfo(64,64,0,rotation,mirrored)
                val mask=ForegroundMask(info,16,16,ByteArray(256){if(it%16<8)255.toByte() else 0})
                BeautySceneRenderer(GlBeautyRenderer(),ForegroundMaskProvider{mask}).use { renderer ->
                    val frame=TextureFrame(info,ids[0],TextureTarget.TEXTURE_2D,identity)
                    val replaced=read(renderer.render(frame,BeautySettings(scene=BeautyScene(
                        background=BackgroundMode.COLOR,backgroundColor=0xff00ff00.toInt())),null) as TextureFrame)
                    fun channel(bytes:ByteArray,x:Int,c:Int)=bytes[(32*64+x)*4+c].toInt() and 255
                    assertTrue("Foreground must stay opaque at the true edge",channel(replaced,31,0)>250)
                    assertTrue("Background must not retain blue fringe",channel(replaced,32,2)<4)
                    assertTrue(channel(replaced,32,1)>250)
                    assertEquals(173,channel(replaced,32,3))
                    val blurred=read(renderer.render(frame,BeautySettings(scene=BeautyScene(
                        background=BackgroundMode.BLUR,blurRadius=8f)),null) as TextureFrame)
                    assertTrue("Blur must not spread red foreground into blue background",channel(blurred,32,0)<4)
                    assertTrue(channel(blurred,32,2)>250)
                    assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                }
            }
        } finally { GL.glDeleteTextures(1,ids,0) }
    }
    @Test fun backgroundPixelsMotionAndState() = withEgl {
        val ids=IntArray(1);GL.glGenTextures(1,ids,0)
        try {
            GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            val pixels=ByteBuffer.allocateDirect(64*64*4)
            for(y in 0..63) for(x in 0..63) pixels.put((x*4).toByte()).put((y*4).toByte()).put(0).put(173.toByte())
            pixels.flip();GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,64,64,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,pixels)
            val info=FrameInfo(64,64,0)
            val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
            val input=TextureFrame(info,ids[0],TextureTarget.TEXTURE_2D,identity)
            var mask:ForegroundMask?=ForegroundMask(info,2,2,ByteArray(4))
            BeautySceneRenderer(GlBeautyRenderer(),ForegroundMaskProvider { mask }).use { renderer ->
                assertSame(input,renderer.render(input,BeautySettings(),null))
                val settings=BeautySettings(scene=BeautyScene(background=BackgroundMode.COLOR,backgroundColor=0xff123456.toInt()))
                GL.glViewport(2,3,17,19);GL.glActiveTexture(GL.GL_TEXTURE6);GL.glEnable(GL.GL_SCISSOR_TEST)
                val output=renderer.render(input,settings,null) as TextureFrame
                assertEquals(info,output.info)
                val vp=IntArray(4);GL.glGetIntegerv(GL.GL_VIEWPORT,vp,0);assertArrayEquals(intArrayOf(2,3,17,19),vp)
                assertTrue(GL.glIsEnabled(GL.GL_SCISSOR_TEST));GL.glDisable(GL.GL_SCISSOR_TEST)
                val data=read(output)
                for(i in 0 until 4096) { assertEquals(18,data[i*4].toInt() and 255);assertEquals(52,data[i*4+1].toInt() and 255)
                    assertEquals(86,data[i*4+2].toInt() and 255);assertEquals(173,data[i*4+3].toInt() and 255) }
                mask=ForegroundMask(info,2,2,ByteArray(4) { 255.toByte() })
                val preserved=read(renderer.render(input,settings,null) as TextureFrame)
                assertEquals(128,preserved[(32*64+32)*4].toInt() and 255)
                mask=null;assertSame(input,renderer.render(input,settings,null))
                mask=ForegroundMask(info.copy(timestampNs=1),2,2,ByteArray(4))
                assertSame(input,renderer.render(input,settings,null))
                mask=ForegroundMask(info,2,2,ByteArray(4))
                val focusArt=StickerImage(128,64,IntArray(128*64) { i -> 0xff000000.toInt() or ((i%128)*2 shl 16) })
                fun focusRed(x:Float):Int {
                    val config=BeautySettings(scene=BeautyScene(background=BackgroundMode.IMAGE,backgroundImage=focusArt,backgroundFocusX=x))
                    return read(renderer.render(input,config,null) as TextureFrame)[(32*64+32)*4].toInt() and 255
                }
                assertTrue("Image focus must change cover crop",focusRed(.75f)-focusRed(.25f)>100)
                val blur=read(renderer.render(input,BeautySettings(scene=BeautyScene(background=BackgroundMode.BLUR)),null) as TextureFrame)
                assertTrue("Background blur must sample neighboring pixels",(blur[0].toInt() and 255)>5)
                val art=StickerImage(1,1,intArrayOf(0xff998877.toInt()))
                val image=read(renderer.render(input,BeautySettings(scene=BeautyScene(background=BackgroundMode.IMAGE,backgroundImage=art)),null) as TextureFrame)
                assertEquals(153,image[0].toInt() and 255)
                val zoom=BeautySettings(scene=BeautyScene(motion=CameraMotion.PUSH_IN,zoom=2f))
                renderer.render(input,zoom,null)
                val motion=read(renderer.render(TextureFrame(info.copy(timestampNs=2_000_000_000),ids[0],TextureTarget.TEXTURE_2D,identity),zoom,null) as TextureFrame)
                assertTrue((motion[0].toInt() and 255)>50)
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        } finally { GL.glDeleteTextures(1,ids,0) }
    }
    @Test fun realModelReturnsBoundedMaskAndResetInvalidatesIt() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val errors=java.util.concurrent.atomic.AtomicReference<Exception?>()
        MediaPipeForegroundMasks(app) { errors.set(it) }.use { masks ->
            masks.setEnabled(true)
            val info=FrameInfo(128,96,100_000_000,90,true)
            val pixels=ByteBuffer.allocateDirect(128*96*4)
            for(i in 0 until 128*96) pixels.put(90).put(80).put(70).put(255.toByte())
            pixels.flip()
            assertTrue(masks.submit(RgbaFrame(info,pixels,128*4)))
            val deadline=System.nanoTime()+15_000_000_000L
            var result=masks.snapshot(info)
            while(result==null && errors.get()==null && System.nanoTime()<deadline) { Thread.sleep(20);result=masks.snapshot(info) }
            errors.get()?.let { throw it }
            assertNotNull(result);assertTrue(result!!.isUsable(info));assertEquals(128,result.width);assertEquals(96,result.height)
            masks.reset();assertNull(masks.snapshot(info))
            val inst=InstrumentationRegistry.getInstrumentation()
            val bitmap=inst.context.assets.open("portrait.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }!!
            val scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,192,240,true)
            val colors=IntArray(192*240);scaled.getPixels(colors,0,192,0,0,192,240)
            val rgb=ByteArray(colors.size*3)
            colors.forEachIndexed { i,c -> rgb[i*3]=(c ushr 16).toByte();rgb[i*3+1]=(c ushr 8).toByte();rgb[i*3+2]=c.toByte() }
            if(scaled!==bitmap) scaled.recycle();bitmap.recycle()
            val portraitInfo=FrameInfo(192,240,200_000_000)
            assertTrue(masks.submit(AnalysisImage(portraitInfo,192,240,rgb)))
            val end=System.nanoTime()+15_000_000_000L
            var person=masks.snapshot(portraitInfo)
            while(person==null && errors.get()==null && System.nanoTime()<end) { Thread.sleep(20);person=masks.snapshot(portraitInfo) }
            errors.get()?.let { throw it }
            assertNotNull(person)
            val confidence=person!!.pixels();var low=0;var high=0
            while(confidence.hasRemaining()) { val v=confidence.get().toInt() and 255;if(v<64) low++;if(v>192) high++ }
            assertTrue("Expected background confidence",low>100);assertTrue("Expected person confidence",high>100)
            val hd=portraitInfo.copy(width=768,height=960,timestampNs=250_000_000)
            assertNull(masks.snapshot(hd)) // 默认严格契约。
            masks.allowScaledFrames=true
            val mapped=requireNotNull(masks.snapshot(hd))
            assertEquals(portraitInfo.timestampNs,mapped.info.timestampNs)
            assertSame(mapped,masks.snapshot(hd.copy(timestampNs=260_000_000)))
            assertNull(masks.snapshot(hd.copy(width=1280,height=720)))
            assertNull(masks.snapshot(hd.copy(timestampNs=500_000_001)))
            masks.reset();assertNull(masks.snapshot(hd))
            masks.setEnabled(false);assertFalse(masks.submit(RgbaFrame(info,pixels,128*4)))
        }
    }
    @Test fun textureOnlyBridgePreservesSourceGeometryForSegmentation() = withEgl {
        val ids=IntArray(1);GL.glGenTextures(1,ids,0)
        val errors=java.util.concurrent.atomic.AtomicReference<Exception?>()
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        try {
            GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
            val data=ByteBuffer.allocateDirect(64*32*4)
            repeat(64*32) { data.put(100).put(80).put(60).put(255.toByte()) };data.flip()
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,64,32,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
            val matrix=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
            MediaPipeForegroundMasks(app) { errors.set(it) }.use { masks ->
                com.beautysdk.mediapipe.GlForegroundMaskBridge(masks).use { bridge ->
                    val info=FrameInfo(64,32,100_000_000,270,true)
                    bridge.submit(TextureFrame(info,ids[0],TextureTarget.TEXTURE_2D,matrix),true)
                    GL.glFinish() // 仅测试确保PBO已完成，生产桥不等待。
                    assertTrue(bridge.submit(TextureFrame(info.copy(timestampNs=180_000_000),ids[0],TextureTarget.TEXTURE_2D,matrix),true))
                    val deadline=System.nanoTime()+15_000_000_000L
                    var mask=masks.snapshot(info)
                    while(mask==null && errors.get()==null && System.nanoTime()<deadline) { Thread.sleep(20);mask=masks.snapshot(info) }
                    errors.get()?.let { throw it }
                    assertNotNull(mask);assertEquals(info,mask!!.info)
                    bridge.reset();assertNull(masks.snapshot(info))
                }
            }
        } finally { GL.glDeleteTextures(1,ids,0) }
    }
    @Test fun oesInputCompositesBackgroundAndMotion() = withEgl {
        val id=IntArray(1);GL.glGenTextures(1,id,0);GL.glBindTexture(0x8D65,id[0])
        GL.glTexParameteri(0x8D65,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
        GL.glTexParameteri(0x8D65,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
        val producer=android.graphics.SurfaceTexture(id[0]);producer.setDefaultBufferSize(64,32)
        val surface=android.view.Surface(producer)
        try {
            val canvas=surface.lockCanvas(null);canvas.drawColor(android.graphics.Color.RED);surface.unlockCanvasAndPost(canvas)
            producer.updateTexImage();val matrix=FloatArray(16);producer.getTransformMatrix(matrix)
            val info=FrameInfo(64,32,0,90,true)
            val mask=ForegroundMask(info,2,2,ByteArray(4))
            BeautySceneRenderer(GlBeautyRenderer(),ForegroundMaskProvider { mask }).use { renderer ->
                val settings=BeautySettings(scene=BeautyScene(background=BackgroundMode.COLOR,backgroundColor=0xff224466.toInt(),motion=CameraMotion.PAN))
                val output=renderer.render(TextureFrame(info,id[0],TextureTarget.EXTERNAL_OES,matrix),settings,null) as TextureFrame
                val pixels=read(output)
                assertEquals(TextureTarget.TEXTURE_2D,output.target);assertEquals(info,output.info)
                assertEquals(34,pixels[0].toInt() and 255);assertEquals(68,pixels[1].toInt() and 255)
                assertEquals(102,pixels[2].toInt() and 255);assertEquals(255,pixels[3].toInt() and 255)
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        } finally { surface.release();producer.release();GL.glDeleteTextures(1,id,0) }
    }
    /** 仅测试中读回像素断言，生产实时管线不读回。 */
    private fun read(frame: TextureFrame): ByteArray {
        val ids=IntArray(1); GL.glGenFramebuffers(1,ids,0)
        try {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,ids[0])
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,frame.textureId,0)
            assertEquals(GL.GL_FRAMEBUFFER_COMPLETE,GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER))
            val data=ByteBuffer.allocateDirect(frame.info.width*frame.info.height*4)
            GL.glReadPixels(0,0,frame.info.width,frame.info.height,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
            return ByteArray(data.capacity()).also { data.get(it) }
        } finally { GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0); GL.glDeleteFramebuffers(1,ids,0) }
    }

    /** 独立离屏上下文，不启动相机、不使用用户图片。 */
    private fun withEgl(block: () -> Unit) {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
        val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
        check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,0x40,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,
            EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
        val context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,64,EGL14.EGL_HEIGHT,64,EGL14.EGL_NONE),0)
        check(EGL14.eglMakeCurrent(display,surface,surface,context))
        try { block() } finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display)
        }
    }
}

package com.beautysdk.demo

import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.GLES30 as GL
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.*

/** 仅生成纹理/公开人像夹具，不保存相机帧；真实ES3深度缓冲、姿态及资源释放回归。 */
class Sticker3DDeviceTest {
    @Test fun repeatedDetectionFramesStayIdenticalThenExpireAndRecover()=withEgl {
        withInput { input ->
            GlSticker3DRenderer(GlBeautyRenderer()).use { renderer ->
                val settings=BeautySettings(sticker3D=BuiltInStickers3D.baseballCap())
                val observation=face()
                val expected=read(renderer.render(input,settings,observation) as TextureFrame)
                val original=read(input)
                assertFalse(original.contentEquals(expected))
                for(age in listOf(0L,30_000_000L,80_000_000L,150_000_000L)) {
                    val current=TextureFrame(info.copy(timestampNs=1+age),input.textureId,input.target,identity)
                    assertArrayEquals("Cached cap age=$age",expected,read(renderer.render(current,settings,observation) as TextureFrame))
                }
                val expired=TextureFrame(info.copy(timestampNs=150_000_002L),input.textureId,input.target,identity)
                assertSame(expired,renderer.render(expired,settings,observation))
                assertSame(input,renderer.render(input,settings,null))
                val fresh=FaceObservation(observation.bounds,geometry3D=FaceGeometry3D(observation.geometry3D!!.points,expired.info.timestampNs))
                assertArrayEquals(expected,read(renderer.render(expired,settings,fresh) as TextureFrame))
                assertSame(input,renderer.render(input,BeautySettings(),observation))
                assertArrayEquals(expected,read(renderer.render(input,settings,observation) as TextureFrame))
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        }
    }
    private val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
    private val info=FrameInfo(128,128,1)
    private fun face(yaw:Float=0f):FaceObservation {
        val points=MutableList(478){FacePoint3D(.5f,.5f,0f)}
        fun point(x:Float,y:Float,z:Float=0f)=FacePoint3D(.5f+x*cos(yaw)+z*sin(yaw),.5f+y,-x*sin(yaw)+z*cos(yaw))
        val oval=intArrayOf(10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,
            152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109)
        oval.forEachIndexed { i,index -> val a=-PI/2+i*2*PI/oval.size;points[index]=point(.32f*cos(a).toFloat(),.38f*sin(a).toFloat()) }
        points[33]=point(-.18f,0f);points[263]=point(.18f,0f);points[1]=point(0f,.1f,-.01f)
        return FaceObservation(FaceBounds(.1f,.1f,.9f,.9f),geometry3D=FaceGeometry3D(points,1))
    }
    private fun triangle(z:Float,occlusion:Boolean=true):Sticker3D {
        return Sticker3D(StickerMesh3D(floatArrayOf(-.25f,-.2f,z,.25f,-.2f,z,0f,.25f,z),
            floatArrayOf(1f,0f,0f,1f,1f,0f,0f,1f,1f,0f,0f,1f),intArrayOf(0,1,2)),faceOcclusion=occlusion)
    }
    @Test fun realDepthOccludesRearTrianglesAndPreservesVideoAlpha()=withEgl {
        withInput { input ->
            GlSticker3DRenderer(GlBeautyRenderer()).use { renderer ->
                val original=read(input)
                val hidden=read(renderer.render(input,BeautySettings(sticker3D=triangle(-.4f)),face()) as TextureFrame)
                assertArrayEquals("Rear triangle must be hidden by face depth",original,hidden)
                val visible=read(renderer.render(input,BeautySettings(sticker3D=triangle(-.4f,false)),face()) as TextureFrame)
                assertTrue(original.indices.any { it%4!=3 && original[it]!=visible[it] })
                val front=read(renderer.render(input,BeautySettings(sticker3D=triangle(.4f)),face()) as TextureFrame)
                assertTrue(original.indices.any { it%4!=3 && original[it]!=front[it] })
                for(i in 0 until 128*128)assertEquals(173,front[i*4+3].toInt() and 255)
                assertSame(input,renderer.render(input,BeautySettings(sticker3D=triangle(.4f)),null))
                val expired=TextureFrame(info.copy(timestampNs=150_000_002L),input.textureId,input.target,identity)
                assertSame(expired,renderer.render(expired,BeautySettings(sticker3D=triangle(.4f)),face()))
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        }
    }
    @Test fun headVolumeHidesAboveFaceRearGeometryAndKeepsFrontAndExpiry()=withEgl {
        withInput { input ->
            val raw=face()
            val observation=FaceObservation(raw.bounds,geometry3D=FaceGeometry3D(raw.geometry3D!!.points.map{FacePoint3D(.5f+(it.x-.5f)*.6f,.62f+(it.y-.5f)*.6f,it.z*.6f)},1))
            val xyz=floatArrayOf(-.08f,1.10f,-.9f,.08f,1.10f,-.9f,0f,1.20f,-.9f)
            val colors=floatArrayOf(1f,0f,0f,1f,1f,0f,0f,1f,1f,0f,0f,1f)
            val indices=intArrayOf(0,1,2)
            val fitted=Sticker3D(StickerMesh3D(xyz,colors,indices,true))
            GlSticker3DRenderer(GlBeautyRenderer()).use { renderer ->
                val original=read(input)
                assertArrayEquals("Estimated skull must hide rear geometry above the face oval",original,read(renderer.render(input,BeautySettings(sticker3D=fitted),observation) as TextureFrame))
                for(rotation in listOf(0,90,180,270))for(mirror in listOf(false,true)){
                    val geometry=info.copy(rotationDegrees=rotation,mirrored=mirror)
                    val rotated=TextureFrame(geometry,input.textureId,input.target,identity)
                    val mapped=AnalysisCoordinates(geometry).toRaw(observation)
                    assertArrayEquals("Head depth rotation=$rotation mirror=$mirror",original,read(renderer.render(rotated,BeautySettings(sticker3D=fitted),mapped) as TextureFrame))
                }
                val without=read(renderer.render(input,BeautySettings(sticker3D=fitted.copy(faceOcclusion=false)),observation) as TextureFrame)
                assertTrue(original.indices.any{original[it]!=without[it]})
                val front=fitted.copy(mesh=StickerMesh3D(xyz.copyOf().apply{for(i in 2 until size step 3)this[i]=.65f},colors,indices,true))
                val visible=read(renderer.render(input,BeautySettings(sticker3D=front),observation) as TextureFrame)
                assertTrue(original.indices.any{original[it]!=visible[it]})
                assertSame(input,renderer.render(input,BeautySettings(sticker3D=fitted),null))
                val expired=TextureFrame(info.copy(timestampNs=150_000_002L),input.textureId,input.target,identity)
                assertSame(expired,renderer.render(expired,BeautySettings(sticker3D=fitted),observation))
                for(i in 0 until 128*128)assertEquals(173,visible[i*4+3].toInt() and 255)
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        }
    }
    @Test fun baseballCapHasFrontSideBackAndInteriorWithEstimatedHead()=withEgl {
        withInput(info.copy(width=512,height=512)){input->
            GlSticker3DRenderer(GlBeautyRenderer()).use{renderer->
                val images=mutableListOf<ByteArray>()
                for((name,angle,pitch) in listOf(Triple("front",0f,0f),Triple("left",-.95f,0f),Triple("right",.95f,0f),Triple("back-synthetic",PI.toFloat(),0f),Triple("above",0f,.65f),Triple("interior",0f,-.65f))){
                    val raw=face(angle)
                    val points=raw.geometry3D!!.points.map{val dy=it.y-.5f;FacePoint3D(.5f+(it.x-.5f)*.6f,.62f+(dy*cos(pitch)-it.z*sin(pitch))*.6f,(dy*sin(pitch)+it.z*cos(pitch))*.6f)}
                    val observation=FaceObservation(raw.bounds,geometry3D=FaceGeometry3D(points,1))
                    val pixels=read(renderer.render(input,BeautySettings(sticker3D=BuiltInStickers3D.baseballCap()),observation) as TextureFrame)
                    assertTrue("$name cap must be visible",pixels.indices.count{it%4!=3&&pixels[it]!=(32).toByte()}>1000)
                    if(InstrumentationRegistry.getArguments().getString("export3dFixture")=="true")exportFixture("cap-$name",pixels,512)
                    images+=pixels
                }
                for(i in 1 until images.size)assertFalse(images[0].contentEquals(images[i]))
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        }
    }
    @Test fun rigidMeshesTurnInDepthAndCanSwitchRepeatedly()=withEgl {
        withInput { input ->
            GlSticker3DRenderer(GlBeautyRenderer()).use { renderer ->
                val original=read(input)
                for(sticker in listOf(BuiltInStickers3D.glasses(),BuiltInStickers3D.topHat(),BuiltInStickers3D.crown(),BuiltInStickers3D.pearlCatEars(),BuiltInStickers3D.headphones(),BuiltInStickers3D.baseballCap(),BuiltInStickers3D.glasses())) {
                    val settings=BeautySettings(sticker3D=sticker)
                    val frontal=read(renderer.render(input,settings,face()) as TextureFrame)
                    val turned=read(renderer.render(input,settings,face(.65f)) as TextureFrame)
                    if(InstrumentationRegistry.getArguments().getString("export3dFixture")=="true") {
                        val name=when(sticker){BuiltInStickers3D.glasses()->"glasses";BuiltInStickers3D.topHat()->"hat";BuiltInStickers3D.crown()->"crown";BuiltInStickers3D.pearlCatEars()->"cat";BuiltInStickers3D.baseballCap()->"cap";else->"headphones"}
                        exportFixture("$name-front",frontal);exportFixture("$name-yaw",turned)
                    }
                    assertTrue("Mesh must change pixels",original.indices.count { original[it]!=frontal[it] }>40)
                    assertTrue("Yaw must change projection",frontal.indices.count { frontal[it]!=turned[it] }>40)
                }
                try {
                    renderer.render(input,BeautySettings(sticker3D=BuiltInStickers3D.glasses(),adjustments=BeautyAdjustments(bigEyes=.5f)),face())
                    fail("2D reshape must be explicitly rejected")
                }catch(_:IllegalArgumentException){}
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        }
    }
    @Test fun nativeBeautyAndFinalCameraMotionComposeWith3D()=withEgl {
        withInput { input ->
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            BeautySceneRenderer(GlSticker3DRenderer(com.beautysdk.gpupixel.GpuPixelBeautyRenderer(context))).use { renderer ->
                val settings=BeautySettings(smoothing=.3f,whitening=.2f,
                    adjustments=BeautyAdjustments(brightness=.1f),sticker3D=BuiltInStickers3D.topHat(),
                    scene=BeautyScene(motion=CameraMotion.FACE_FOLLOW,zoom=1.4f))
                val output=renderer.render(input,settings,face()) as TextureFrame
                assertEquals(info,output.info)
                val pixels=read(output)
                assertTrue(pixels.indices.count{it%4!=3 && pixels[it]!=(32).toByte()}>100)
                for(i in 0 until 128*128)assertEquals(173,pixels[i*4+3].toInt() and 255)
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        }
    }
    @Test fun rotationsMirrorsAndFboResizePreserveProjection()=withEgl {
        withInput { input ->
            GlSticker3DRenderer(GlBeautyRenderer()).use { renderer ->
                val sticker=triangle(.4f,false)
                val settings=BeautySettings(sticker3D=sticker)
                val baseline=read(renderer.render(input,settings,face()) as TextureFrame)
                fun colored(bytes:ByteArray,x:Int,y:Int)= (0..2).any{bytes[(y*128+x)*4+it]!=(32).toByte()}
                for(rotation in listOf(0,90,180,270))for(mirror in listOf(false,true)) {
                    val rotatedInfo=info.copy(rotationDegrees=rotation,mirrored=mirror)
                    val rotatedInput=TextureFrame(rotatedInfo,input.textureId,input.target,identity)
                    val transformedFace=AnalysisCoordinates(rotatedInfo).toRaw(face())
                    val actual=read(renderer.render(rotatedInput,settings,transformedFace) as TextureFrame)
                    var mismatch=0
                    for(y in 0 until 128)for(x in 0 until 128) {
                        val (u,v)=when(rotation){90->127-y to x;180->127-x to 127-y;270->y to 127-x;else->x to y}
                        if(colored(actual,x,y)!=colored(baseline,if(mirror)127-u else u,v))mismatch++
                    }
                    assertTrue("Projection rotation=$rotation mirror=$mirror mismatch=$mismatch",mismatch<=8)
                }
                withInput(FrameInfo(64,96,1)) { small ->
                    val resized=renderer.render(small,settings,face()) as TextureFrame
                    assertEquals(64,resized.info.width);assertEquals(96,resized.info.height)
                    assertEquals(64*96*4,read(resized).size)
                }
                val restored=renderer.render(input,settings,face()) as TextureFrame
                assertArrayEquals(baseline,read(restored))
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        }
    }
    @Test fun restoresHostDepthVaoFramebufferAndViewport()=withEgl {
        withInput { input ->
            val vao=IntArray(1);GL.glGenVertexArrays(1,vao,0);GL.glBindVertexArray(vao[0])
            val buffer=IntArray(1);GL.glGenBuffers(1,buffer,0);GL.glBindBuffer(GL.GL_ARRAY_BUFFER,buffer[0])
            GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER,buffer[0])
            val rbo=IntArray(1);GL.glGenRenderbuffers(1,rbo,0);GL.glBindRenderbuffer(GL.GL_RENDERBUFFER,rbo[0])
            GL.glViewport(2,3,17,19);GL.glDepthMask(false);GL.glDepthFunc(GL.GL_GREATER)
            GL.glDepthRangef(.2f,.8f);GL.glClearDepthf(.4f);GL.glColorMask(false,true,false,true)
            GL.glEnable(GL.GL_SCISSOR_TEST);GL.glEnable(GL.GL_CULL_FACE);GL.glEnable(GL.GL_DEPTH_TEST)
            GL.glActiveTexture(GL.GL_TEXTURE6)
            try {
                GlSticker3DRenderer(GlBeautyRenderer()).use { renderer ->
                    renderer.render(input,BeautySettings(sticker3D=BuiltInStickers3D.baseballCap()),face())
                    fun integer(name:Int)=IntArray(1).also{GL.glGetIntegerv(name,it,0)}[0]
                    assertEquals(vao[0],integer(GL.GL_VERTEX_ARRAY_BINDING))
                    assertEquals(buffer[0],integer(GL.GL_ARRAY_BUFFER_BINDING))
                    assertEquals(buffer[0],integer(GL.GL_ELEMENT_ARRAY_BUFFER_BINDING))
                    assertEquals(rbo[0],integer(GL.GL_RENDERBUFFER_BINDING))
                    assertEquals(0,integer(GL.GL_DRAW_FRAMEBUFFER_BINDING))
                    assertEquals(GL.GL_TEXTURE6,integer(GL.GL_ACTIVE_TEXTURE));assertEquals(GL.GL_GREATER,integer(GL.GL_DEPTH_FUNC))
                    assertArrayEquals(intArrayOf(2,3,17,19),IntArray(4).also{GL.glGetIntegerv(GL.GL_VIEWPORT,it,0)})
                    assertArrayEquals(booleanArrayOf(false,true,false,true),BooleanArray(4).also{GL.glGetBooleanv(GL.GL_COLOR_WRITEMASK,it,0)})
                    assertFalse(BooleanArray(1).also{GL.glGetBooleanv(GL.GL_DEPTH_WRITEMASK,it,0)}[0])
                    assertArrayEquals(floatArrayOf(.2f,.8f),FloatArray(2).also{GL.glGetFloatv(GL.GL_DEPTH_RANGE,it,0)},1e-6f)
                    assertEquals(.4f,FloatArray(1).also{GL.glGetFloatv(GL.GL_DEPTH_CLEAR_VALUE,it,0)}[0],1e-6f)
                    assertTrue(GL.glIsEnabled(GL.GL_SCISSOR_TEST));assertTrue(GL.glIsEnabled(GL.GL_CULL_FACE));assertTrue(GL.glIsEnabled(GL.GL_DEPTH_TEST))
                    assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                }
            }finally {GL.glDeleteVertexArrays(1,vao,0);GL.glDeleteBuffers(1,buffer,0);GL.glDeleteRenderbuffers(1,rbo,0)}
        }
    }
    @Test fun existingMediaPipeModelOutputsReal3DOnlyWhenRequested() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val image=inst.context.assets.open("portrait.jpg").use{BitmapFactory.decodeStream(it)}!!
        val bitmap=android.graphics.Bitmap.createScaledBitmap(image,240,300,true)
        val pixels=IntArray(240*300);bitmap.getPixels(pixels,0,240,0,0,240,300)
        val rgba=ByteBuffer.allocateDirect(pixels.size*4)
        pixels.forEach{rgba.put((it ushr 16).toByte()).put((it ushr 8).toByte()).put(it.toByte()).put(255.toByte())};rgba.flip()
        if(bitmap!==image)bitmap.recycle();image.recycle()
        val frame=RgbaFrame(FrameInfo(240,300,1_000_000_000),rgba,240*4)
        MediaPipeFaceAnalyzer(inst.targetContext,output3D=true).use { analyzer ->
            assertTrue(analyzer.capabilities.contains(FaceCapability.GEOMETRY_3D))
            val face=analyzer.analyzeRgba(frame).single();val geometry=checkNotNull(face.geometry3D)
            assertEquals(478,geometry.points.size);assertEquals(frame.info.timestampNs,geometry.timestampNs)
            assertEquals(16,geometry.copyCanonicalTransform()!!.size)
            assertTrue(geometry.points.maxOf{it.z}-geometry.points.minOf{it.z}>.01f)
        }
        MediaPipeFaceAnalyzer(inst.targetContext).use { assertNull(it.analyzeRgba(frame).single().geometry3D) }
    }
    @Test fun curvedBuiltInsKeepShadingAndDepthAcrossThreeViews()=withEgl {
        withInput(info.copy(width=512,height=512)){input->
            GlSticker3DRenderer(GlBeautyRenderer()).use{renderer->
                for((name,sticker) in listOf("glasses" to BuiltInStickers3D.glasses(),"hat" to BuiltInStickers3D.topHat(),"crown" to BuiltInStickers3D.crown(),"cat" to BuiltInStickers3D.pearlCatEars(),"headphones" to BuiltInStickers3D.headphones())){
                    val images=mutableListOf<ByteArray>()
                    for((view,yaw) in listOf("left" to -.65f,"front" to 0f,"right" to .65f)){
                        val pixels=read(renderer.render(input,BeautySettings(sticker3D=sticker),face(yaw)) as TextureFrame)
                        val shades=HashSet<Int>();var covered=0
                        for(i in 0 until 512*512){val r=pixels[i*4].toInt() and 255;val g=pixels[i*4+1].toInt() and 255;val b=pixels[i*4+2].toInt() and 255
                            if(r!=32||g!=32||b!=32){covered++;shades.add((r shl 16) or (g shl 8) or b)}
                            assertEquals(173,pixels[i*4+3].toInt() and 255)
                        }
                        assertTrue("$name $view must have visible coverage",covered>1000)
                        assertTrue("$name $view must show curved shading",shades.size>80)
                        images+=pixels
                        if(InstrumentationRegistry.getArguments().getString("export3dFixture")=="true")exportFixture("showcase-$name-$view",pixels,512)
                    }
                    assertFalse(images[0].contentEquals(images[1]));assertFalse(images[1].contentEquals(images[2]))
                }
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        }
    }
    private fun withInput(frameInfo:FrameInfo=info,block:(TextureFrame)->Unit) {
        val id=IntArray(1);GL.glGenTextures(1,id,0);GL.glBindTexture(GL.GL_TEXTURE_2D,id[0])
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
        val bytes=ByteBuffer.allocateDirect(frameInfo.width*frameInfo.height*4)
        repeat(frameInfo.width*frameInfo.height){bytes.put(32).put(32).put(32).put(173.toByte())};bytes.flip()
        GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,frameInfo.width,frameInfo.height,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,bytes)
        try{block(TextureFrame(frameInfo,id[0],TextureTarget.TEXTURE_2D,identity))}finally{GL.glDeleteTextures(1,id,0)}
    }
    private fun exportFixture(name:String,rgba:ByteArray,size:Int=128) {
        val bitmap=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888)
        val pixels=IntArray(size*size){i->
            (255 shl 24) or ((rgba[i*4].toInt() and 255) shl 16) or
                ((rgba[i*4+1].toInt() and 255) shl 8) or (rgba[i*4+2].toInt() and 255)
        }
        bitmap.setPixels(pixels,0,size,0,0,size,size)
        try {java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"sticker3d-$name.png")
            .outputStream().use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}
        finally{bitmap.recycle()}
    }
    private fun read(frame:TextureFrame):ByteArray {
        val id=IntArray(1);GL.glGenFramebuffers(1,id,0)
        try {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,id[0])
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,frame.textureId,0)
            val bytes=ByteBuffer.allocateDirect(frame.info.width*frame.info.height*4)
            GL.glReadPixels(0,0,frame.info.width,frame.info.height,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,bytes)
            return ByteArray(bytes.capacity()).also{bytes.get(it)}
        }finally{GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0);GL.glDeleteFramebuffers(1,id,0)}
    }
    private fun withEgl(block:()->Unit) {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
        val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
        check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
        val context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,128,EGL14.EGL_HEIGHT,128,EGL14.EGL_NONE),0)
        check(EGL14.eglMakeCurrent(display,surface,surface,context))
        try{block()}finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display)
        }
    }
}
